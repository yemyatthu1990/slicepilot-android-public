package world.yemyat.slicepilot.model

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlin.math.max
import kotlin.math.min

internal class MFComponent(
    val path: String?,
    val objectId: Int,
    val transform: FloatArray?,
)

internal class MFObject(
    val id: Int,
) {
    /** Flat xyz triples; primitive lists avoid per-vertex FloatArray/IntArray boxing. */
    val verts = FloatList()
    val tris = IntList()
    val comps = ArrayList<MFComponent>()
    var name: String? = null
}

internal class MFItem(
    val path: String?,
    val objectId: Int,
    val transform: FloatArray?,
    val printable: Boolean,
)

internal class ModelFile(
    val path: String,
    val unitFactor: Float,
    val objects: HashMap<Int, MFObject>,
    val buildItems: ArrayList<MFItem>,
)

/** One place the build puts a mesh: which file, which object, and the placement matrix. */
internal class PartSpec(
    val modelPath: String,
    val objectId: Int,
    val transform: FloatArray?,
    val name: String,
)

object ThreeMFParser {
    private fun newPullParser(): XmlPullParser =
        try {
            Xml.newPullParser()
        } catch (_: Throwable) {
            XmlPullParserFactory.newInstance().newPullParser()
        }

    internal fun normalizePath(p: String): String {
        val s = p.trim().replace('\\', '/')
        val clean = if (s.startsWith("/")) s.substring(1) else s
        val parts = ArrayList<String>()
        for (seg in clean.split('/')) {
            when (seg) {
                "", "." -> {}

                ".." -> {
                    if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                }

                else -> {
                    parts.add(seg)
                }
            }
        }
        return parts.joinToString("/")
    }

    internal fun resolvePath(
        basePath: String,
        relPath: String,
    ): String {
        val s = relPath.trim().replace('\\', '/')
        if (s.startsWith("/")) return normalizePath(s)
        val baseDir = basePath.replace('\\', '/').substringBeforeLast('/', "")
        val combined = if (baseDir.isEmpty()) s else "$baseDir/$s"
        return normalizePath(combined)
    }

    private fun findEntry(
        zip: ZipFile,
        path: String,
    ): ZipEntry? {
        val norm = normalizePath(path)
        zip.getEntry(norm)?.let { return it }
        zip.getEntry("/$norm")?.let { return it }
        return zip.entries().asSequence().firstOrNull {
            normalizePath(it.name).equals(norm, ignoreCase = true)
        }
    }

    /** Parse [path] once per file; every part of a model resolves through this cache. */
    private fun modelAt(
        zip: ZipFile,
        cache: MutableMap<String, ModelFile>,
        path: String,
    ): ModelFile? {
        val norm = normalizePath(path)
        cache[norm]?.let { return it }
        val entry = findEntry(zip, norm) ?: return null
        return try {
            zip.getInputStream(entry).use { ins ->
                parseModelXml(BufferedInputStream(ins), norm).also { cache[norm] = it }
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Instantiate [objectId] of [modelPath] — plus everything it references through
     * `<component>`, with the matrices multiplied along the way — into [positions]/[indices].
     */
    private fun instantiate(
        cache: Map<String, ModelFile>,
        modelPath: String,
        objectId: Int,
        transform: FloatArray?,
        depth: Int,
        positions: FloatList,
        indices: IntList,
    ) {
        if (depth > 32) return
        val obj = cache[modelPath]?.objects?.get(objectId) ?: return

        val base = positions.size / 3
        val verts = obj.verts
        val vertCount = verts.size / 3
        for (i in 0 until vertCount) {
            val x = verts[i * 3]
            val y = verts[i * 3 + 1]
            val z = verts[i * 3 + 2]
            if (transform != null) {
                val nx = transform[0] * x + transform[3] * y + transform[6] * z + transform[9]
                val ny = transform[1] * x + transform[4] * y + transform[7] * z + transform[10]
                val nz = transform[2] * x + transform[5] * y + transform[8] * z + transform[11]
                positions.add(nx)
                positions.add(ny)
                positions.add(nz)
            } else {
                positions.add(x)
                positions.add(y)
                positions.add(z)
            }
        }
        val tris = obj.tris
        val triCount = tris.size / 3
        for (i in 0 until triCount) {
            val t1 = tris[i * 3]
            val t2 = tris[i * 3 + 1]
            val t3 = tris[i * 3 + 2]
            if (t1 in 0 until vertCount && t2 in 0 until vertCount && t3 in 0 until vertCount) {
                indices.add(base + t1)
                indices.add(base + t2)
                indices.add(base + t3)
            }
        }
        for (comp in obj.comps) {
            val nextPath = if (!comp.path.isNullOrBlank()) resolvePath(modelPath, comp.path) else modelPath
            instantiate(cache, nextPath, comp.objectId, combine(transform, comp.transform), depth + 1, positions, indices)
        }
    }

    /**
     * Name a part the way the user reads the file: the object's own `name` when the author
     * set one, the name of the geometry it references when the object is only a shell
     * (Bambu Studio keeps the mesh in `3D/Objects/object_N.model`), otherwise "Part N".
     */
    private fun partName(
        cache: Map<String, ModelFile>,
        spec: PartSpec,
        index: Int,
    ): String {
        val fallback = "Part ${index + 1}"
        val stack = ArrayDeque<Pair<String, Int>>()
        stack.addLast(spec.modelPath to spec.objectId)
        while (stack.isNotEmpty()) {
            val (path, oid) = stack.removeFirst()
            val obj = cache[path]?.objects?.get(oid) ?: continue
            val name = obj.name?.trim()
            if (!name.isNullOrEmpty()) return name
            // A shell object with exactly one component is a reference; follow it.
            if (obj.comps.size == 1) {
                val comp = obj.comps.first()
                stack.addLast(
                    (if (!comp.path.isNullOrBlank()) resolvePath(path, comp.path) else path) to comp.objectId,
                )
            }
        }
        return fallback
    }

    /** Every place the build puts a mesh, in file order. */
    private fun collectParts(
        zip: ZipFile,
        cache: MutableMap<String, ModelFile>,
        rootPath: String,
        rootModel: ModelFile,
    ): List<PartSpec> {
        val specs = ArrayList<PartSpec>()
        val items = rootModel.buildItems.filter { it.printable }.ifEmpty { rootModel.buildItems }
        for ((i, item) in items.withIndex()) {
            val path = if (!item.path.isNullOrBlank()) resolvePath(rootPath, item.path) else rootPath
            val spec = PartSpec(path, item.objectId, item.transform, partName(cache, PartSpec(path, item.objectId, item.transform, ""), i))
            if (path in cache) specs.add(spec)
        }
        if (specs.isEmpty()) {
            // No usable build section: fall back to the objects the root declares, then to
            // every other .model part in the package, so a bare object library still shows
            // up instead of throwing "no vertices".
            val referenced = HashSet<Pair<String, Int>>()
            for (obj in rootModel.objects.values) {
                for (comp in obj.comps) {
                    referenced.add(
                        (if (!comp.path.isNullOrBlank()) resolvePath(rootPath, comp.path) else rootPath) to comp.objectId,
                    )
                }
            }
            val roots =
                rootModel.objects.keys
                    .filter { (rootPath to it) !in referenced }
                    .sorted()
            val toInstantiate = roots.ifEmpty { rootModel.objects.keys.sorted() }
            for ((i, oid) in toInstantiate.withIndex()) {
                specs.add(PartSpec(rootPath, oid, null, partName(cache, PartSpec(rootPath, oid, null, ""), i)))
            }
            if (specs.isEmpty()) {
                zip
                    .entries()
                    .asSequence()
                    .filter { it.name.endsWith(".model", ignoreCase = true) && normalizePath(it.name) != rootPath }
                    .toList()
                    .forEach { entry ->
                        val mf = modelAt(zip, cache, entry.name) ?: return@forEach
                        for (obj in mf.objects.values) {
                            if (obj.verts.isNotEmpty()) {
                                specs.add(PartSpec(mf.path, obj.id, null, obj.name?.takeIf { it.isNotBlank() } ?: "Part ${specs.size + 1}"))
                            }
                        }
                    }
            }
        }
        return specs
    }

    /** Per-part geometry plus the bounding box the placement was authored with. */
    private class RawPart(
        val positions: FloatList,
        val indices: IntList,
        val minX: Float,
        val minY: Float,
        val minZ: Float,
        val maxX: Float,
        val maxY: Float,
        val maxZ: Float,
    )

    private fun rawPart(
        positions: FloatList,
        indices: IntList,
    ): RawPart {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var maxZ = -Float.MAX_VALUE
        for (i in 0 until positions.size step 3) {
            val x = positions[i]
            val y = positions[i + 1]
            val z = positions[i + 2]
            if (x < minX) minX = x
            if (y < minY) minY = y
            if (z < minZ) minZ = z
            if (x > maxX) maxX = x
            if (y > maxY) maxY = y
            if (z > maxZ) maxZ = z
        }
        return RawPart(positions, indices, minX, minY, minZ, maxX, maxY, maxZ)
    }

    fun parse(file: File): MeshData {
        ZipFile(file).use { zip ->
            val rootEntry =
                findRootModelEntry(zip)
                    ?: throw RuntimeException("No 3MF model part found in ${file.name}")
            val rootPath = normalizePath(rootEntry.name)
            val cache = HashMap<String, ModelFile>()
            val rootModel =
                modelAt(zip, cache, rootPath)
                    ?: throw RuntimeException("Failed to parse root model $rootPath")
            val specs = collectParts(zip, cache, rootPath, rootModel)
            if (specs.isEmpty()) throw RuntimeException("3MF contains no vertices in ${file.name}")

            // One mesh per part, each simplified to its own share of the preview budget and
            // then concatenated. The single shared mesh stays (Home thumbnails, the sliced
            // preview) and the part ranges let the renderer and the slicer address parts
            // individually — which is what makes "print just this one" possible at all.
            val rawParts =
                specs.mapNotNull { spec ->
                    val pos = FloatList()
                    val idx = IntList()
                    instantiate(cache, spec.modelPath, spec.objectId, spec.transform, 0, pos, idx)
                    if (pos.isEmpty()) null else rawPart(pos, idx)
                }
            if (rawParts.isEmpty()) throw RuntimeException("3MF contains no vertices in ${file.name}")

            val totalTris = rawParts.sumOf { it.indices.size / 3 }
            val budget = MeshDecimator.MAX_PREVIEW_TRIANGLES
            val positions = FloatList()
            val normals = FloatList()
            val indices = IntList()
            val parts = ArrayList<MeshPart>()
            var gMinX = Float.MAX_VALUE
            var gMinY = Float.MAX_VALUE
            var gMinZ = Float.MAX_VALUE
            var gMaxX = -Float.MAX_VALUE
            var gMaxY = -Float.MAX_VALUE
            var gMaxZ = -Float.MAX_VALUE

            for ((i, raw) in rawParts.withIndex()) {
                val posArr = raw.positions.toTrimmedArray()
                val idxArr = raw.indices.toTrimmedArray()
                val tris = idxArr.size / 3
                val share =
                    if (rawParts.size == 1) {
                        budget
                    } else {
                        max(1, (tris.toDouble() * budget / max(1, totalTris)).toInt())
                    }
                val reduced =
                    MeshDecimator.decimateRaw(
                        posArr,
                        idxArr,
                        raw.minX,
                        raw.minY,
                        raw.minZ,
                        raw.maxX,
                        raw.maxY,
                        raw.maxZ,
                        share,
                    )
                val p = reduced?.first ?: posArr
                val ix = reduced?.second ?: idxArr
                val n = StlParser.smoothNormalsPublic(p, ix, MeshDecimator.faceNormals(p, ix))

                val vertexOffset = positions.size / 3
                val indexOffset = indices.size
                for (k in p.indices step 3) {
                    positions.add(p[k])
                    positions.add(p[k + 1])
                    positions.add(p[k + 2])
                }
                for (k in n.indices step 3) {
                    normals.add(n[k])
                    normals.add(n[k + 1])
                    normals.add(n[k + 2])
                }
                for (k in ix.indices) indices.add(vertexOffset + ix[k])
                parts.add(
                    MeshPart(
                        name = rawParts[i].let { specs[i].name },
                        vertexOffset = vertexOffset,
                        vertexCount = p.size / 3,
                        indexOffset = indexOffset,
                        indexCount = ix.size,
                        minX = raw.minX,
                        minY = raw.minY,
                        minZ = raw.minZ,
                        maxX = raw.maxX,
                        maxY = raw.maxY,
                        maxZ = raw.maxZ,
                    ),
                )
                gMinX = min(gMinX, raw.minX)
                gMaxX = max(gMaxX, raw.maxX)
                gMinY = min(gMinY, raw.minY)
                gMaxY = max(gMaxY, raw.maxY)
                gMinZ = min(gMinZ, raw.minZ)
                gMaxZ = max(gMaxZ, raw.maxZ)
            }

            if (positions.isEmpty()) throw RuntimeException("3MF contains no vertices in ${file.name}")
            return MeshData(
                positions.toTrimmedArray(),
                normals.toTrimmedArray(),
                indices.toTrimmedArray(),
                gMinX,
                gMinY,
                gMinZ,
                gMaxX,
                gMaxY,
                gMaxZ,
                parts,
            )
        }
    }

    private fun findRootModelEntry(zip: ZipFile): ZipEntry? {
        findEntry(zip, "_rels/.rels")?.let { rels ->
            try {
                zip.getInputStream(rels).use { ins ->
                    val p = newPullParser()
                    p.setInput(ins, null)
                    var t = p.eventType
                    while (t != XmlPullParser.END_DOCUMENT) {
                        if (t == XmlPullParser.START_TAG && localName(p) == "relationship") {
                            val type = getAttr(p, "type") ?: ""
                            if (type.contains("3dmodel", ignoreCase = true)) {
                                val target = getAttr(p, "target") ?: ""
                                if (target.isNotBlank()) {
                                    val resolved = normalizePath(target)
                                    findEntry(zip, resolved)?.let { return it }
                                }
                            }
                        }
                        t = p.next()
                    }
                }
            } catch (_: Exception) {
            }
        }
        findEntry(zip, "3D/3dmodel.model")?.let { return it }
        return zip.entries().asSequence().firstOrNull {
            it.name.endsWith(".model", ignoreCase = true)
        }
    }

    private fun localName(p: XmlPullParser): String = (p.name ?: "").substringAfterLast(':').lowercase()

    private fun getAttr(
        p: XmlPullParser,
        local: String,
    ): String? {
        val count = p.attributeCount
        for (i in 0 until count) {
            val name = p.getAttributeName(i).substringAfterLast(':')
            if (name.equals(local, ignoreCase = true)) {
                return p.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseModelXml(
        ins: InputStream,
        modelPath: String,
    ): ModelFile {
        val p = newPullParser()
        p.setInput(ins, null)
        val objects = HashMap<Int, MFObject>()
        val buildItems = ArrayList<MFItem>()
        var inBuild = false
        var currentId = -1
        var unitFactor = 1.0f

        var t = p.eventType
        while (t != XmlPullParser.END_DOCUMENT) {
            when (t) {
                XmlPullParser.START_TAG -> {
                    when (localName(p)) {
                        "model" -> {
                            val unit = getAttr(p, "unit")?.lowercase()
                            unitFactor =
                                when (unit) {
                                    "micron" -> 0.001f
                                    "centimeter" -> 10.0f
                                    "inch" -> 25.4f
                                    "meter" -> 1000.0f
                                    "foot" -> 304.8f
                                    else -> 1.0f
                                }
                        }

                        "build" -> {
                            inBuild = true
                        }

                        "object" -> {
                            currentId = getAttr(p, "id")?.toIntOrNull() ?: -1
                            if (currentId != -1) {
                                objects.getOrPut(currentId) { MFObject(currentId) }.apply {
                                    // The first non-blank name wins: an object declared in the
                                    // root file keeps its name over the one it references.
                                    getAttr(p, "name")?.trim()?.takeIf { it.isNotEmpty() }?.let {
                                        if (name.isNullOrBlank()) name = it
                                    }
                                }
                            }
                        }

                        "vertex" -> {
                            if (currentId != -1) {
                                val x = (getAttr(p, "x")?.toFloatOrNull() ?: 0f) * unitFactor
                                val y = (getAttr(p, "y")?.toFloatOrNull() ?: 0f) * unitFactor
                                val z = (getAttr(p, "z")?.toFloatOrNull() ?: 0f) * unitFactor
                                val o = objects[currentId]
                                o?.verts?.add(x)
                                o?.verts?.add(y)
                                o?.verts?.add(z)
                            }
                        }

                        "triangle" -> {
                            if (currentId != -1) {
                                val v1 = getAttr(p, "v1")?.toIntOrNull() ?: 0
                                val v2 = getAttr(p, "v2")?.toIntOrNull() ?: 0
                                val v3 = getAttr(p, "v3")?.toIntOrNull() ?: 0
                                val o = objects[currentId]
                                o?.tris?.add(v1)
                                o?.tris?.add(v2)
                                o?.tris?.add(v3)
                            }
                        }

                        "component" -> {
                            val oid = (getAttr(p, "objectid") ?: getAttr(p, "object"))?.toIntOrNull()
                            val path = getAttr(p, "path") ?: getAttr(p, "p:path")
                            val tr = parseTransform(getAttr(p, "transform"), unitFactor)
                            if (oid != null && currentId != -1) {
                                objects[currentId]?.comps?.add(MFComponent(path, oid, tr))
                            }
                        }

                        "item" -> {
                            val oid = (getAttr(p, "objectid") ?: getAttr(p, "object"))?.toIntOrNull()
                            val path = getAttr(p, "path") ?: getAttr(p, "p:path")
                            val tr = parseTransform(getAttr(p, "transform"), unitFactor)
                            val printableVal = getAttr(p, "printable")
                            val printable =
                                printableVal?.let { it != "0" && !it.equals("false", ignoreCase = true) }
                                    ?: true
                            if (oid != null) {
                                if (inBuild) {
                                    buildItems.add(MFItem(path, oid, tr, printable))
                                } else if (currentId != -1) {
                                    objects[currentId]?.comps?.add(MFComponent(path, oid, tr))
                                }
                            }
                        }
                    }
                }

                XmlPullParser.END_TAG -> {
                    when (localName(p)) {
                        "build" -> inBuild = false
                        "object" -> currentId = -1
                    }
                }
            }
            t = p.next()
        }
        return ModelFile(modelPath, unitFactor, objects, buildItems)
    }

    private fun parseTransform(
        s: String?,
        unitFactor: Float = 1.0f,
    ): FloatArray? {
        if (s.isNullOrBlank()) return null
        val parts = s.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull() }
        if (parts.size < 12) return null
        val r = FloatArray(12) { parts[it] }
        if (unitFactor != 1.0f) {
            r[9] *= unitFactor
            r[10] *= unitFactor
            r[11] *= unitFactor
        }
        return r
    }

    internal fun combine(
        a: FloatArray?,
        b: FloatArray?,
    ): FloatArray? {
        if (a == null) return b
        if (b == null) return a
        val c = FloatArray(12)

        c[0] = a[0] * b[0] + a[3] * b[1] + a[6] * b[2]
        c[1] = a[1] * b[0] + a[4] * b[1] + a[7] * b[2]
        c[2] = a[2] * b[0] + a[5] * b[1] + a[8] * b[2]

        c[3] = a[0] * b[3] + a[3] * b[4] + a[6] * b[5]
        c[4] = a[1] * b[3] + a[4] * b[4] + a[7] * b[5]
        c[5] = a[2] * b[3] + a[5] * b[4] + a[8] * b[5]

        c[6] = a[0] * b[6] + a[3] * b[7] + a[6] * b[8]
        c[7] = a[1] * b[6] + a[4] * b[7] + a[7] * b[8]
        c[8] = a[2] * b[6] + a[5] * b[7] + a[8] * b[8]

        c[9] = a[0] * b[9] + a[3] * b[10] + a[6] * b[11] + a[9]
        c[10] = a[1] * b[9] + a[4] * b[10] + a[7] * b[11] + a[10]
        c[11] = a[2] * b[9] + a[5] * b[10] + a[8] * b[11] + a[11]

        return c
    }
}

/** Growable primitive float array; avoids Float boxing of ArrayList<Float>. */
internal class FloatList(
    initialCapacity: Int = 4096,
) {
    private var data = FloatArray(initialCapacity)
    var size = 0
        private set

    operator fun get(i: Int): Float = data[i]

    fun add(value: Float) {
        if (size == data.size) grow(1)
        data[size++] = value
    }

    fun isEmpty() = size == 0

    fun isNotEmpty() = size > 0

    fun toTrimmedArray(): FloatArray = data.copyOf(size)

    private fun grow(extra: Int) {
        var capacity = data.size + (data.size shr 1)
        if (capacity < size + extra) capacity = size + extra
        data = data.copyOf(capacity)
    }
}

/** Growable primitive int array; avoids Int boxing of ArrayList<Int>. */
internal class IntList(
    initialCapacity: Int = 4096,
) {
    private var data = IntArray(initialCapacity)
    var size = 0
        private set

    operator fun get(i: Int): Int = data[i]

    fun add(value: Int) {
        if (size == data.size) grow(1)
        data[size++] = value
    }

    fun isEmpty() = size == 0

    fun toTrimmedArray(): IntArray = data.copyOf(size)

    private fun grow(extra: Int) {
        var capacity = data.size + (data.size shr 1)
        if (capacity < size + extra) capacity = size + extra
        data = data.copyOf(capacity)
    }
}
