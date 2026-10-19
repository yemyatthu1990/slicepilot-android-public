package world.yemyat.slicepilot.model

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

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

object ThreeMFParser {

    private fun newPullParser(): XmlPullParser {
        return try {
            Xml.newPullParser()
        } catch (_: Throwable) {
            XmlPullParserFactory.newInstance().newPullParser()
        }
    }

    internal fun normalizePath(p: String): String {
        val s = p.trim().replace('\\', '/')
        val clean = if (s.startsWith("/")) s.substring(1) else s
        val parts = ArrayList<String>()
        for (seg in clean.split('/')) {
            when (seg) {
                "", "." -> {}
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.size - 1)
                else -> parts.add(seg)
            }
        }
        return parts.joinToString("/")
    }

    internal fun resolvePath(basePath: String, relPath: String): String {
        val s = relPath.trim().replace('\\', '/')
        if (s.startsWith("/")) return normalizePath(s)
        val baseDir = basePath.replace('\\', '/').substringBeforeLast('/', "")
        val combined = if (baseDir.isEmpty()) s else "$baseDir/$s"
        return normalizePath(combined)
    }

    private fun findEntry(zip: ZipFile, path: String): ZipEntry? {
        val norm = normalizePath(path)
        zip.getEntry(norm)?.let { return it }
        zip.getEntry("/$norm")?.let { return it }
        return zip.entries().asSequence().firstOrNull {
            normalizePath(it.name).equals(norm, ignoreCase = true)
        }
    }

    fun parse(file: File): MeshData {
        ZipFile(file).use { zip ->
            val rootEntry = findRootModelEntry(zip)
                ?: throw RuntimeException("No 3MF model part found in ${file.name}")
            val rootPath = normalizePath(rootEntry.name)

            val parsedModels = HashMap<String, ModelFile>()

            fun getModel(path: String): ModelFile? {
                val norm = normalizePath(path)
                parsedModels[norm]?.let { return it }
                val entry = findEntry(zip, norm) ?: return null
                return try {
                    zip.getInputStream(entry).use { ins ->
                        val mf = parseModelXml(BufferedInputStream(ins), norm)
                        parsedModels[norm] = mf
                        mf
                    }
                } catch (_: Exception) {
                    null
                }
            }

            val rootModel = getModel(rootPath)
                ?: throw RuntimeException("Failed to parse root model $rootPath")

            val positions = FloatList()
            val indices = IntList()
            var vOff = 0

            fun instantiate(modelPath: String, objectId: Int, transform: FloatArray?, depth: Int) {
                if (depth > 32) return
                val mf = getModel(modelPath) ?: return
                val obj = mf.objects[objectId] ?: return

                val base = vOff
                val verts = obj.verts
                val vertCount = verts.size / 3
                for (i in 0 until vertCount) {
                    val x = verts[i * 3]; val y = verts[i * 3 + 1]; val z = verts[i * 3 + 2]
                    if (transform != null) {
                        val nx = transform[0] * x + transform[3] * y + transform[6] * z + transform[9]
                        val ny = transform[1] * x + transform[4] * y + transform[7] * z + transform[10]
                        val nz = transform[2] * x + transform[5] * y + transform[8] * z + transform[11]
                        positions.add(nx); positions.add(ny); positions.add(nz)
                    } else {
                        positions.add(x); positions.add(y); positions.add(z)
                    }
                }
                vOff += vertCount

                val tris = obj.tris
                val triCount = tris.size / 3
                for (i in 0 until triCount) {
                    val t1 = tris[i * 3]; val t2 = tris[i * 3 + 1]; val t3 = tris[i * 3 + 2]
                    if (t1 in 0 until vertCount && t2 in 0 until vertCount && t3 in 0 until vertCount) {
                        indices.add(base + t1)
                        indices.add(base + t2)
                        indices.add(base + t3)
                    }
                }

                for (comp in obj.comps) {
                    val nextPath = if (!comp.path.isNullOrBlank()) resolvePath(modelPath, comp.path) else modelPath
                    val nextTransform = combine(transform, comp.transform)
                    instantiate(nextPath, comp.objectId, nextTransform, depth + 1)
                }
            }

            val printableItems = rootModel.buildItems.filter { it.printable }
            val buildItems = if (printableItems.isNotEmpty()) printableItems else rootModel.buildItems

            if (buildItems.isNotEmpty()) {
                for (item in buildItems) {
                    val targetPath = if (!item.path.isNullOrBlank()) resolvePath(rootPath, item.path) else rootPath
                    instantiate(targetPath, item.objectId, item.transform, 0)
                }
            } else {
                val referenced = HashSet<Pair<String, Int>>()
                for (mf in listOf(rootModel)) {
                    for (obj in mf.objects.values) {
                        for (comp in obj.comps) {
                            val cp = if (!comp.path.isNullOrBlank()) resolvePath(mf.path, comp.path) else mf.path
                            referenced.add(cp to comp.objectId)
                        }
                    }
                }
                val roots = rootModel.objects.keys.filter { (rootPath to it) !in referenced }.sorted()
                val toInstantiate = if (roots.isNotEmpty()) roots else rootModel.objects.keys.sorted()
                for (oid in toInstantiate) {
                    instantiate(rootPath, oid, null, 0)
                }
            }

            if (positions.isEmpty()) {
                val otherEntries = zip.entries().asSequence()
                    .filter { it.name.endsWith(".model", ignoreCase = true) && normalizePath(it.name) != rootPath }
                    .toList()
                for (entry in otherEntries) {
                    val mf = getModel(entry.name) ?: continue
                    for (obj in mf.objects.values) {
                        if (obj.verts.isNotEmpty()) {
                            instantiate(mf.path, obj.id, null, 0)
                        }
                    }
                }
            }

            if (positions.isEmpty()) throw RuntimeException("3MF contains no vertices in ${file.name}")

            val posArr0 = positions.toTrimmedArray()
            val idxArr0 = indices.toTrimmedArray()
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            for (i in posArr0.indices step 3) {
                val x = posArr0[i]; val y = posArr0[i + 1]; val z = posArr0[i + 2]
                if (x < minX) minX = x; if (y < minY) minY = y; if (z < minZ) minZ = z
                if (x > maxX) maxX = x; if (y > maxY) maxY = y; if (z > maxZ) maxZ = z
            }
            // Simplify before the normal pass: previews never need millions of triangles,
            // and slicing re-reads the original file in native code.
            val reduced = MeshDecimator.decimateRaw(
                posArr0, idxArr0, minX, minY, minZ, maxX, maxY, maxZ,
                MeshDecimator.MAX_PREVIEW_TRIANGLES,
            )
            val posArr = reduced?.first ?: posArr0
            val idxArr = reduced?.second ?: idxArr0
            val faceNormals = FloatArray(idxArr.size)
            val normals = StlParser.smoothNormalsPublic(posArr, idxArr, faceNormals)
            return MeshData(posArr, normals, idxArr, minX, minY, minZ, maxX, maxY, maxZ)
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

    private fun localName(p: XmlPullParser): String {
        return (p.name ?: "").substringAfterLast(':').lowercase()
    }

    private fun getAttr(p: XmlPullParser, local: String): String? {
        val count = p.attributeCount
        for (i in 0 until count) {
            val name = p.getAttributeName(i).substringAfterLast(':')
            if (name.equals(local, ignoreCase = true)) {
                return p.getAttributeValue(i)
            }
        }
        return null
    }

    private fun parseModelXml(ins: InputStream, modelPath: String): ModelFile {
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
                            unitFactor = when (unit) {
                                "micron" -> 0.001f
                                "centimeter" -> 10.0f
                                "inch" -> 25.4f
                                "meter" -> 1000.0f
                                "foot" -> 304.8f
                                else -> 1.0f
                            }
                        }
                        "build" -> inBuild = true
                        "object" -> {
                            currentId = getAttr(p, "id")?.toIntOrNull() ?: -1
                            if (currentId != -1) {
                                objects.getOrPut(currentId) { MFObject(currentId) }
                            }
                        }
                        "vertex" -> {
                            if (currentId != -1) {
                                val x = (getAttr(p, "x")?.toFloatOrNull() ?: 0f) * unitFactor
                                val y = (getAttr(p, "y")?.toFloatOrNull() ?: 0f) * unitFactor
                                val z = (getAttr(p, "z")?.toFloatOrNull() ?: 0f) * unitFactor
                                val o = objects[currentId]
                                o?.verts?.add(x); o?.verts?.add(y); o?.verts?.add(z)
                            }
                        }
                        "triangle" -> {
                            if (currentId != -1) {
                                val v1 = getAttr(p, "v1")?.toIntOrNull() ?: 0
                                val v2 = getAttr(p, "v2")?.toIntOrNull() ?: 0
                                val v3 = getAttr(p, "v3")?.toIntOrNull() ?: 0
                                val o = objects[currentId]
                                o?.tris?.add(v1); o?.tris?.add(v2); o?.tris?.add(v3)
                            }
                        }
                        "component" -> {
                            val oid = (getAttr(p, "objectid") ?: getAttr(p, "object"))?.toIntOrNull()
                            val path = getAttr(p, "path")
                            val tr = parseTransform(getAttr(p, "transform"), unitFactor)
                            if (oid != null && currentId != -1) {
                                objects[currentId]?.comps?.add(MFComponent(path, oid, tr))
                            }
                        }
                        "item" -> {
                            val oid = (getAttr(p, "objectid") ?: getAttr(p, "object"))?.toIntOrNull()
                            val path = getAttr(p, "path")
                            val tr = parseTransform(getAttr(p, "transform"), unitFactor)
                            val printableVal = getAttr(p, "printable")
                            val printable = printableVal?.let {
                                it != "0" && !it.equals("false", ignoreCase = true)
                            } ?: true
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

    private fun parseTransform(s: String?, unitFactor: Float = 1.0f): FloatArray? {
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

    internal fun combine(a: FloatArray?, b: FloatArray?): FloatArray? {
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
internal class FloatList(initialCapacity: Int = 4096) {
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
internal class IntList(initialCapacity: Int = 4096) {
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
