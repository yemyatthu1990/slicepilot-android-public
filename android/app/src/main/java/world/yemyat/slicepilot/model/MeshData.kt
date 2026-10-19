package world.yemyat.slicepilot.model

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

class MeshData(
    val positions: FloatArray,
    val normals: FloatArray,
    val indices: IntArray,
    val minX: Float, val minY: Float, val minZ: Float,
    val maxX: Float, val maxY: Float, val maxZ: Float,
) {
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
    val centerZ get() = (minZ + maxZ) / 2f
    val maxRadius: Float
        get() {
            val hx = (maxX - minX) / 2f
            val hy = (maxY - minY) / 2f
            val hz = (maxZ - minZ) / 2f
            return max(1f, sqrt(hx * hx + hy * hy + hz * hz))
        }
}

object ModelParser {
    fun parse(file: File): MeshData {
        return if (isZip(file)) ThreeMFParser.parse(file) else StlParser.parse(file)
    }

    fun isZip(file: File): Boolean {
        return try {
            file.inputStream().use { ins ->
                val b = ByteArray(4)
                val n = ins.read(b)
                n == 4 && b[0] == 'P'.code.toByte() && b[1] == 'K'.code.toByte() &&
                    b[2] == 3.toByte() && b[3] == 4.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }
}

object StlParser {
    fun parse(file: File): MeshData {
        return if (isBinaryStlFile(file)) parseBinary(file) else parseAscii(file.readText(Charsets.ISO_8859_1))
    }

    private fun isBinaryStlFile(file: File): Boolean {
        val len = file.length()
        if (len < 84) return false
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(80)
            val tris = Integer.reverseBytes(raf.readInt())
            return tris > 0 && tris.toLong() * 50L + 84L == len
        }
    }

    private fun parseBinary(file: File): MeshData {
        java.io.BufferedInputStream(file.inputStream(), 1 shl 16).use { ins ->
            val head = ByteArray(84)
            readFully(ins, head)
            val triCount = leInt(head, 80)
            val positions = FloatArray(triCount * 9)
            val faceNormals = FloatArray(triCount * 3)
            var seen: LongIntMap? = LongIntMap(triCount)
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
            var p = 0
            var f = 0
            val rec = ByteArray(50)
            for (i in 0 until triCount) {
                readFully(ins, rec)
                val nx = leFloat(rec, 0); val ny = leFloat(rec, 4); val nz = leFloat(rec, 8)
                val x0 = leFloat(rec, 12); val y0 = leFloat(rec, 16); val z0 = leFloat(rec, 20)
                val x1 = leFloat(rec, 24); val y1 = leFloat(rec, 28); val z1 = leFloat(rec, 32)
                val x2 = leFloat(rec, 36); val y2 = leFloat(rec, 40); val z2 = leFloat(rec, 44)
                if (seen!!.add(triKeyLong(x0, y0, z0, x1, y1, z1, x2, y2, z2))) {
                    faceNormals[f] = nx; faceNormals[f + 1] = ny; faceNormals[f + 2] = nz; f += 3
                    positions[p] = x0; positions[p + 1] = y0; positions[p + 2] = z0
                    positions[p + 3] = x1; positions[p + 4] = y1; positions[p + 5] = z1
                    positions[p + 6] = x2; positions[p + 7] = y2; positions[p + 8] = z2
                    p += 9
                    minX = min(minX, x0); minY = min(minY, y0); minZ = min(minZ, z0)
                    maxX = max(maxX, x0); maxY = max(maxY, y0); maxZ = max(maxZ, z0)
                    minX = min(minX, x1); minY = min(minY, y1); minZ = min(minZ, z1)
                    maxX = max(maxX, x1); maxY = max(maxY, y1); maxZ = max(maxZ, z1)
                    minX = min(minX, x2); minY = min(minY, y2); minZ = min(minZ, z2)
                    maxX = max(maxX, x2); maxY = max(maxY, y2); maxZ = max(maxZ, z2)
                }
            }
            seen = null
            val fnArr = if (f == faceNormals.size) faceNormals else faceNormals.copyOf(f)
            val vertMap = LongIntMap(p / 3 * 2)
            val wpos = FloatArray(p)
            val widx = IntArray(p / 3)
            var uv = 0
            var ii = 0
            var i = 0
            while (i < p) {
                for (k in 0 until 3) {
                    val x = positions[i + k * 3]; val y = positions[i + k * 3 + 1]; val z = positions[i + k * 3 + 2]
                    val h = vertHash(x, y, z)
                    val existing = vertMap.get(h)
                    if (existing >= 0) {
                        widx[ii++] = existing
                    } else {
                        wpos[uv * 3] = x; wpos[uv * 3 + 1] = y; wpos[uv * 3 + 2] = z
                        vertMap.put(h, uv)
                        widx[ii++] = uv
                        uv++
                    }
                }
                i += 9
            }
            var posArr = if (uv * 3 == wpos.size) wpos else wpos.copyOf(uv * 3)
            var idxArr = if (ii == widx.size) widx else widx.copyOf(ii)
            var fn = fnArr
            // Simplify before smooth normals; slicing re-reads the original file natively.
            val reduced = MeshDecimator.decimateRaw(
                posArr, idxArr, minX, minY, minZ, maxX, maxY, maxZ,
                MeshDecimator.MAX_PREVIEW_TRIANGLES,
            )
            if (reduced != null) {
                posArr = reduced.first
                idxArr = reduced.second
                fn = MeshDecimator.faceNormals(posArr, idxArr)
            }
            val normals = smoothNormals(posArr, idxArr, fn)
            return MeshData(posArr, normals, idxArr, minX, minY, minZ, maxX, maxY, maxZ)
        }
    }

    private fun readFully(ins: java.io.InputStream, buf: ByteArray) {
        var off = 0
        while (off < buf.size) {
            val n = ins.read(buf, off, buf.size - off)
            if (n < 0) throw java.io.EOFException("Unexpected end of STL stream")
            off += n
        }
    }

    private fun leInt(b: ByteArray, o: Int): Int =
        (b[o].toInt() and 0xff) or ((b[o + 1].toInt() and 0xff) shl 8) or
            ((b[o + 2].toInt() and 0xff) shl 16) or ((b[o + 3].toInt() and 0xff) shl 24)

    private fun leFloat(b: ByteArray, o: Int): Float = java.lang.Float.intBitsToFloat(leInt(b, o))

    internal fun triKeyLong(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
    ): Long {
        val a = vertHash(x0, y0, z0)
        val b = vertHash(x1, y1, z1)
        val c = vertHash(x2, y2, z2)
        val s = longArrayOf(a, b, c)
        java.util.Arrays.sort(s)
        return mix64(s[0] xor mix64(s[1] xor mix64(s[2])))
    }

    private fun vertHash(x: Float, y: Float, z: Float): Long {
        var h = mix64(r(x))
        h = mix64(h + r(y))
        h = mix64(h + r(z))
        return h
    }

    private fun mix64(h: Long): Long {
        var x = h
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }

    internal fun triKey(
        x0: Float, y0: Float, z0: Float,
        x1: Float, y1: Float, z1: Float,
        x2: Float, y2: Float, z2: Float,
    ): String {
        val a = "${r(x0)},${r(y0)},${r(z0)}"
        val b = "${r(x1)},${r(y1)},${r(z1)}"
        val c = "${r(x2)},${r(y2)},${r(z2)}"
        return arrayOf(a, b, c).sorted().joinToString("|")
    }

    private fun r(v: Float): Long = Math.round((v * 1000).toDouble())

    private fun parseAscii(text: String): MeshData {
        val positions = ArrayList<Float>(4096)
        val faceNormals = ArrayList<Float>(4096)
        val seen = HashSet<String>(4096)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var nx = 0f; var ny = 0f; var nz = 1f
        val cur = ArrayList<Float>(9)
        for (rawLine in text.lineSequence()) {
            val line = rawLine.trim()
            when {
                line.startsWith("facet normal") -> {
                    val p = line.splitWhitespace(5)
                    nx = p[2]; ny = p[3]; nz = p[4]
                    cur.clear()
                }
                line.startsWith("vertex") -> {
                    val p = line.splitWhitespace(4)
                    cur.add(p[1]); cur.add(p[2]); cur.add(p[3])
                    if (cur.size == 9) {
                        if (seen.add(triKey(cur[0], cur[1], cur[2], cur[3], cur[4], cur[5], cur[6], cur[7], cur[8]))) {
                            faceNormals.add(nx); faceNormals.add(ny); faceNormals.add(nz)
                            for (k in 0 until 9) positions.add(cur[k])
                            for (k in 0 until 3) {
                                val x = cur[k * 3]; val y = cur[k * 3 + 1]; val z = cur[k * 3 + 2]
                                minX = min(minX, x); minY = min(minY, y); minZ = min(minZ, z)
                                maxX = max(maxX, x); maxY = max(maxY, y); maxZ = max(maxZ, z)
                            }
                        }
                        cur.clear()
                    }
                }
            }
        }
        val indices = IntArray(positions.size / 3) { it }
        val normals = smoothNormals(positions.toFloatArray(), indices, faceNormals.toFloatArray())
        return MeshData(positions.toFloatArray(), normals, indices, minX, minY, minZ, maxX, maxY, maxZ)
    }

    private fun String.splitWhitespace(maxParts: Int): FloatArray {
        val parts = split(" ").filter { it.isNotBlank() }
        val out = FloatArray(maxParts)
        for (i in 0 until min(maxParts, parts.size)) out[i] = parts[i].toFloatOrNull() ?: 0f
        return out
    }

    fun smoothNormalsPublic(positions: FloatArray, indices: IntArray, faceNormals: FloatArray): FloatArray =
        smoothNormals(positions, indices, faceNormals)

    private fun smoothNormals(positions: FloatArray, indices: IntArray, faceNormals: FloatArray): FloatArray {
        val vertCount = positions.size / 3
        val acc = FloatArray(vertCount * 3)
        for (t in 0 until indices.size / 3) {
            val fnx = faceNormals[t * 3]; val fny = faceNormals[t * 3 + 1]; val fnz = faceNormals[t * 3 + 2]
            val isZero = fnx * fnx + fny * fny + fnz * fnz < 1e-9f
            var nx = fnx; var ny = fny; var nz = fnz
            if (isZero) {
                val i0 = indices[t * 3] * 3; val i1 = indices[t * 3 + 1] * 3; val i2 = indices[t * 3 + 2] * 3
                val ax = positions[i1] - positions[i0]; val ay = positions[i1 + 1] - positions[i0 + 1]; val az = positions[i1 + 2] - positions[i0 + 2]
                val bx = positions[i2] - positions[i0]; val by = positions[i2 + 1] - positions[i0 + 1]; val bz = positions[i2 + 2] - positions[i0 + 2]
                nx = ay * bz - az * by; ny = az * bx - ax * bz; nz = ax * by - ay * bx
            }
            for (k in 0 until 3) {
                val vi = indices[t * 3 + k]
                acc[vi * 3] += nx; acc[vi * 3 + 1] += ny; acc[vi * 3 + 2] += nz
            }
        }
        for (v in 0 until vertCount) {
            val x = acc[v * 3]; val y = acc[v * 3 + 1]; val z = acc[v * 3 + 2]
            val len = sqrt(x * x + y * y + z * z)
            if (len > 1e-9f) {
                acc[v * 3] = x / len; acc[v * 3 + 1] = y / len; acc[v * 3 + 2] = z / len
            } else {
                acc[v * 3 + 2] = 1f
            }
        }
        return acc
    }
}

/** Open-addressing long→int map; avoids the Long/Int boxing of HashMap<Long, Int>. */
private class LongIntMap(capacityHint: Int) {
    private var keys: LongArray
    private var vals: IntArray
    private var used: BooleanArray
    private var mask: Int
    private var size = 0

    init {
        var cap = 16
        while (cap < capacityHint * 2) cap = cap shl 1
        keys = LongArray(cap)
        vals = IntArray(cap)
        used = BooleanArray(cap)
        mask = cap - 1
    }

    /** Returns the stored value, or -1 when absent. */
    fun get(key: Long): Int {
        val keys = this.keys; val used = this.used
        var i = mix(key).toInt() and mask
        while (used[i]) {
            if (keys[i] == key) return vals[i]
            i = (i + 1) and mask
        }
        return -1
    }

    fun put(key: Long, value: Int) {
        if ((size + 1) * 4 > (mask + 1) * 3) rehash()
        var i = mix(key).toInt() and mask
        while (used[i]) {
            if (keys[i] == key) { vals[i] = value; return }
            i = (i + 1) and mask
        }
        used[i] = true
        keys[i] = key
        vals[i] = value
        size++
    }

    /** Adds the key if new; true when newly added, false when already present. */
    fun add(key: Long): Boolean {
        if (get(key) >= 0) return false
        put(key, 0)
        return true
    }

    private fun rehash() {
        val oldKeys = keys; val oldVals = vals; val oldUsed = used
        val cap = (mask + 1) shl 1
        keys = LongArray(cap); vals = IntArray(cap); used = BooleanArray(cap)
        mask = cap - 1
        size = 0
        for (i in oldKeys.indices) {
            if (oldUsed[i]) put(oldKeys[i], oldVals[i])
        }
    }

    private fun mix(h0: Long): Long {
        var x = h0
        x = (x xor (x ushr 30)) * -4658895280553007687L
        x = (x xor (x ushr 27)) * -7723592293110705685L
        return x xor (x ushr 31)
    }
}
