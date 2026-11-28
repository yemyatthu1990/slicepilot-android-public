package world.yemyat.slicepilot.model

import kotlin.math.cbrt
import kotlin.math.floor
import kotlin.math.max

/**
 * Fast preview-quality mesh simplification by uniform vertex clustering:
 * vertices are snapped to a grid sized for the target triangle budget, duplicate
 * and degenerate triangles are dropped, and smooth normals are recomputed.
 * O(n) with no allocations beyond the output arrays — good enough to turn a
 * multi-million-triangle model into a snappy preview in well under a second.
 * Slicing is unaffected: it reads the original file in native code.
 */
object MeshDecimator {

    /** Preview meshes are simplified to this many triangles (phone screens need few). */
    const val MAX_PREVIEW_TRIANGLES = 400_000

    /** Simplifies a full mesh (recomputes smooth normals); returns [mesh] when small enough. */
    fun decimate(mesh: MeshData, targetTris: Int): MeshData {
        val reduced = decimateRaw(
            mesh.positions, mesh.indices,
            mesh.minX, mesh.minY, mesh.minZ,
            mesh.maxX, mesh.maxY, mesh.maxZ,
            targetTris,
        ) ?: return mesh
        val (posArr, idxArr) = reduced
        val faceNormals = faceNormals(posArr, idxArr)
        val normals = StlParser.smoothNormalsPublic(posArr, idxArr, faceNormals)
        return MeshData(
            posArr, normals, idxArr,
            mesh.minX, mesh.minY, mesh.minZ,
            mesh.maxX, mesh.maxY, mesh.maxZ,
        )
    }

    /**
     * Grid-cluster raw geometry; returns reduced (positions, indices) or null
     * when the mesh is already small. Callers compute normals afterwards, so the
     * expensive smooth-normal pass only runs on the reduced mesh.
     */
    fun decimateRaw(
        pos: FloatArray,
        indices: IntArray,
        minX: Float, minY: Float, minZ: Float,
        maxX: Float, maxY: Float, maxZ: Float,
        targetTris: Int,
    ): Pair<FloatArray, IntArray>? {
        val triCount = indices.size / 3
        if (triCount <= targetTris) return null

        val dx = max(maxX - minX, 1e-3f)
        val dy = max(maxY - minY, 1e-3f)
        val dz = max(maxZ - minZ, 1e-3f)

        // Grid cell sized so occupied cells land near the target vertex count;
        // keep every axis within the 12 bits available per axis in the cell key.
        var s = cbrt((dx.toDouble() * dy * dz) / targetTris).toFloat()
        s = max(s, max(dx, max(dy, dz)) / 4095f)
        s = max(s, 1e-6f)

        val cellToVert = HashMap<Long, Int>(targetTris * 2)
        val outPos = FloatArray(targetTris * 3 * 3)
        val outIdx = IntArray(targetTris * 3)
        val v = IntArray(3)
        var uv = 0
        var ii = 0

        var t = 0
        while (t + 2 < indices.size) {
            var ok = true
            for (k in 0 until 3) {
                val vi = indices[t + k] * 3
                if (vi + 2 >= pos.size) { ok = false; break }
                val x = pos[vi]; val y = pos[vi + 1]; val z = pos[vi + 2]
                if (x.isNaN() || y.isNaN() || z.isNaN()) { ok = false; break }
                val ix = floor((x - minX) / s).toInt().coerceIn(0, 4095)
                val iy = floor((y - minY) / s).toInt().coerceIn(0, 4095)
                val iz = floor((z - minZ) / s).toInt().coerceIn(0, 4095)
                val key = (ix.toLong() shl 24) or (iy.toLong() shl 12) or iz.toLong()
                val existing = cellToVert[key]
                if (existing != null) {
                    v[k] = existing
                } else {
                    if (uv * 3 + 2 >= outPos.size) { ok = false; break }
                    outPos[uv * 3] = minX + (ix + 0.5f) * s
                    outPos[uv * 3 + 1] = minY + (iy + 0.5f) * s
                    outPos[uv * 3 + 2] = minZ + (iz + 0.5f) * s
                    cellToVert[key] = uv
                    v[k] = uv
                    uv++
                }
            }
            if (!ok) break
            if (v[0] != v[1] && v[1] != v[2] && v[0] != v[2]) {
                outIdx[ii] = v[0]; outIdx[ii + 1] = v[1]; outIdx[ii + 2] = v[2]
                ii += 3
            }
            t += 3
        }
        if (ii == 0) return null
        return Pair(outPos.copyOf(uv * 3), outIdx.copyOf(ii))
    }

    /** Per-triangle geometric normals, ready for [StlParser.smoothNormalsPublic]. */
    fun faceNormals(posArr: FloatArray, idxArr: IntArray): FloatArray {
        val faceNormals = FloatArray(idxArr.size)
        for (tri in 0 until idxArr.size / 3) {
            val i0 = idxArr[tri * 3] * 3
            val i1 = idxArr[tri * 3 + 1] * 3
            val i2 = idxArr[tri * 3 + 2] * 3
            val ax = posArr[i1] - posArr[i0]; val ay = posArr[i1 + 1] - posArr[i0 + 1]; val az = posArr[i1 + 2] - posArr[i0 + 2]
            val bx = posArr[i2] - posArr[i0]; val by = posArr[i2 + 1] - posArr[i0 + 1]; val bz = posArr[i2 + 2] - posArr[i0 + 2]
            faceNormals[tri * 3] = ay * bz - az * by
            faceNormals[tri * 3 + 1] = az * bx - ax * bz
            faceNormals[tri * 3 + 2] = ax * by - ay * bx
        }
        return faceNormals
    }
}
