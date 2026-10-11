package world.yemyat.slicepilot.preview

import world.yemyat.slicepilot.model.GcodePart
import world.yemyat.slicepilot.model.GcodePreview

class MeshGeometry(
    val vertices: FloatArray,
    val indices: IntArray,
)

class PlaneGeometry(
    val vertices: FloatArray,
    val minX: Float,
    val minY: Float,
    val maxX: Float,
    val maxY: Float,
)

class GcodePartGeometry(
    val part: GcodePart,
    val vertices: FloatArray,
    val cumulativeVertices: IntArray,
    val layerCount: Int,
    val color: FloatArray,
)

object PreviewGeometry {

    val DEFAULT_MODEL_COLOR = floatArrayOf(0.42f, 0.55f, 0.72f)
    val DEFAULT_PLATE_COLOR = floatArrayOf(0.13f, 0.14f, 0.17f)
    val REVEAL_COLOR = floatArrayOf(0.24f, 0.51f, 0.85f)
    val GCODE_DEFAULT_COLOR = floatArrayOf(0.20f, 0.45f, 0.80f)
    val BACKGROUND_COLOR = floatArrayOf(0.93f, 0.94f, 0.96f)

    fun interleave(positions: FloatArray, normals: FloatArray): FloatArray {
        val count = positions.size / 3
        val out = FloatArray(count * 6)
        val hasNormals = normals.size == positions.size
        var i = 0
        while (i < count) {
            val p = i * 3
            val o = i * 6
            out[o] = positions[p]
            out[o + 1] = positions[p + 1]
            out[o + 2] = positions[p + 2]
            if (hasNormals) {
                out[o + 3] = normals[p]
                out[o + 4] = normals[p + 1]
                out[o + 5] = normals[p + 2]
            } else {
                out[o + 5] = 1f
            }
            i++
        }
        return out
    }

    fun plate(cx: Float, cy: Float, z: Float, halfX: Float, halfY: Float): PlaneGeometry = PlaneGeometry(
        vertices = plateVertices(cx, cy, z, halfX, halfY),
        minX = cx - halfX,
        minY = cy - halfY,
        maxX = cx + halfX,
        maxY = cy + halfY,
    )

    fun plateVertices(cx: Float, cy: Float, z: Float, halfX: Float, halfY: Float): FloatArray = floatArrayOf(
        cx - halfX, cy - halfY, z, 0f, 0f, 1f,
        cx + halfX, cy - halfY, z, 0f, 0f, 1f,
        cx + halfX, cy + halfY, z, 0f, 0f, 1f,
        cx - halfX, cy - halfY, z, 0f, 0f, 1f,
        cx + halfX, cy + halfY, z, 0f, 0f, 1f,
        cx - halfX, cy + halfY, z, 0f, 0f, 1f,
    )

    fun gcodeParts(preview: GcodePreview, defaultColor: FloatArray): List<GcodePartGeometry> {
        val layerCount = preview.layers.size
        val byType = LinkedHashMap<GcodePart, Int>()
        val countsByType = HashMap<GcodePart, IntArray>()
        for (layer in preview.layers) {
            for (pg in layer.parts) {
                if (pg.vertices.isEmpty()) continue
                byType[pg.part] = (byType[pg.part] ?: 0) + pg.vertices.size
                countsByType.getOrPut(pg.part) { IntArray(layerCount + 1) }
            }
        }
        val posArrays = HashMap<GcodePart, FloatArray>()
        for ((part, size) in byType) {
            posArrays[part] = FloatArray(size)
        }
        val writeOffsets = HashMap<GcodePart, Int>()
        for (layer in preview.layers) {
            for (pg in layer.parts) {
                if (pg.vertices.isEmpty()) continue
                val pos = posArrays.getValue(pg.part)
                val o = writeOffsets[pg.part] ?: 0
                System.arraycopy(pg.vertices, 0, pos, o, pg.vertices.size)
                writeOffsets[pg.part] = o + pg.vertices.size
                val layerIndex = (layer.index + 1).coerceIn(0, layerCount)
                countsByType.getValue(pg.part)[layerIndex] = pg.vertices.size / 3
            }
        }
        val out = ArrayList<GcodePartGeometry>(byType.size)
        for ((part, _) in byType) {
            val cumulative = countsByType.getValue(part)
            for (i in 1..layerCount) cumulative[i] += cumulative[i - 1]
            if (cumulative[layerCount] <= 0) continue
            out.add(
                GcodePartGeometry(
                    part = part,
                    vertices = posArrays.getValue(part),
                    cumulativeVertices = cumulative,
                    layerCount = layerCount,
                    color = colorFor(part, defaultColor),
                )
            )
        }
        return out
    }

    private fun colorFor(part: GcodePart, defaultColor: FloatArray): FloatArray = when (part) {
        GcodePart.SUPPORT -> floatArrayOf(0.18f, 0.72f, 0.35f)
        GcodePart.SUPPORT_INTERFACE -> floatArrayOf(0.95f, 0.55f, 0.15f)
        GcodePart.TRAVEL -> floatArrayOf(0.75f, 0.75f, 0.78f)
        else -> defaultColor
    }

    fun parseColor(hex: String?): FloatArray? {
        if (hex.isNullOrBlank()) return null
        val first = hex.split('|').firstOrNull { it.isNotBlank() }?.trim()?.removePrefix("#") ?: return null
        val full = when (first.length) {
            6 -> first
            8 -> first.substring(2)
            else -> return null
        }
        return try {
            val r = Integer.parseInt(full.substring(0, 2), 16) / 255f
            val g = Integer.parseInt(full.substring(2, 4), 16) / 255f
            val b = Integer.parseInt(full.substring(4, 6), 16) / 255f
            floatArrayOf(r, g, b)
        } catch (_: Exception) {
            null
        }
    }
}