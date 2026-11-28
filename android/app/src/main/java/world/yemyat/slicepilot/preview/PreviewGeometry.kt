package world.yemyat.slicepilot.preview

import world.yemyat.slicepilot.model.GcodePart
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.GcodePreviewParser

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
    val tool: Int = 0,
)

object PreviewGeometry {
    val DEFAULT_MODEL_COLOR = floatArrayOf(0.42f, 0.55f, 0.72f)
    val DEFAULT_PLATE_COLOR = floatArrayOf(0.13f, 0.14f, 0.17f)

    /**
     * The brand gold (ui/theme/LanternGold, #E8B84B) - the exact colour the Pieces tiles draw
     * their selected border with, so the tile and the piece on the plate are visibly the same
     * thing. Naming a piece is not enough on a file of eight identical clips: the user has to
     * see which one on the plate is theirs. Gold also stays readable on every filament colour
     * and on both themes (unlike the material blue, which disappears on a blue PLA).
     */
    val SELECTED_PART_COLOR = floatArrayOf(0.910f, 0.722f, 0.294f)
    val REVEAL_COLOR = floatArrayOf(0.24f, 0.51f, 0.85f)
    val GCODE_DEFAULT_COLOR = floatArrayOf(0.20f, 0.45f, 0.80f)
    val BACKGROUND_COLOR = floatArrayOf(0.93f, 0.94f, 0.96f)

    fun interleave(
        positions: FloatArray,
        normals: FloatArray,
    ): FloatArray {
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

    fun plate(
        cx: Float,
        cy: Float,
        z: Float,
        halfX: Float,
        halfY: Float,
    ): PlaneGeometry =
        PlaneGeometry(
            vertices = plateVertices(cx, cy, z, halfX, halfY),
            minX = cx - halfX,
            minY = cy - halfY,
            maxX = cx + halfX,
            maxY = cy + halfY,
        )

    fun plateVertices(
        cx: Float,
        cy: Float,
        z: Float,
        halfX: Float,
        halfY: Float,
    ): FloatArray =
        floatArrayOf(
            cx - halfX,
            cy - halfY,
            z,
            0f,
            0f,
            1f,
            cx + halfX,
            cy - halfY,
            z,
            0f,
            0f,
            1f,
            cx + halfX,
            cy + halfY,
            z,
            0f,
            0f,
            1f,
            cx - halfX,
            cy - halfY,
            z,
            0f,
            0f,
            1f,
            cx + halfX,
            cy + halfY,
            z,
            0f,
            0f,
            1f,
            cx - halfX,
            cy + halfY,
            z,
            0f,
            0f,
            1f,
        )

    fun gcodeParts(
        preview: GcodePreview,
        defaultColor: FloatArray,
    ): List<GcodePartGeometry> {
        val layerCount = preview.layers.size
        // Group by (feature, tool) so multi-colour G-code layers render each extruder
        // in its own filament colour instead of collapsing all tools into one colour.
        val byKey = LinkedHashMap<Pair<GcodePart, Int>, Int>()
        val countsByKey = HashMap<Pair<GcodePart, Int>, IntArray>()
        for (layer in preview.layers) {
            for (pg in layer.parts) {
                if (pg.vertices.isEmpty()) continue
                val key = pg.part to pg.tool
                byKey[key] = (byKey[key] ?: 0) + pg.vertices.size
                countsByKey.getOrPut(key) { IntArray(layerCount + 1) }
            }
        }
        val posArrays = HashMap<Pair<GcodePart, Int>, FloatArray>()
        for ((key, size) in byKey) {
            posArrays[key] = FloatArray(size)
        }
        val writeOffsets = HashMap<Pair<GcodePart, Int>, Int>()
        for (layer in preview.layers) {
            for (pg in layer.parts) {
                if (pg.vertices.isEmpty()) continue
                val key = pg.part to pg.tool
                val pos = posArrays.getValue(key)
                val o = writeOffsets[key] ?: 0
                System.arraycopy(pg.vertices, 0, pos, o, pg.vertices.size)
                writeOffsets[key] = o + pg.vertices.size
                val layerIndex = (layer.index + 1).coerceIn(0, layerCount)
                countsByKey.getValue(key)[layerIndex] += pg.vertices.size / 3
            }
        }
        val out = ArrayList<GcodePartGeometry>(byKey.size)
        for ((key, _) in byKey) {
            val cumulative = countsByKey.getValue(key)
            for (i in 1..layerCount) cumulative[i] += cumulative[i - 1]
            if (cumulative[layerCount] <= 0) continue
            out.add(
                GcodePartGeometry(
                    part = key.first,
                    vertices = posArrays.getValue(key),
                    cumulativeVertices = cumulative,
                    layerCount = layerCount,
                    color = colorFor(key.first, key.second, preview, defaultColor),
                    tool = key.second,
                ),
            )
        }
        return out
    }

    private fun colorFor(
        part: GcodePart,
        tool: Int,
        preview: GcodePreview,
        defaultColor: FloatArray,
    ): FloatArray =
        when (part) {
            GcodePart.SUPPORT -> floatArrayOf(0.18f, 0.72f, 0.35f)
            GcodePart.SUPPORT_INTERFACE -> floatArrayOf(0.95f, 0.55f, 0.15f)
            GcodePart.TRAVEL -> floatArrayOf(0.75f, 0.75f, 0.78f)
            else -> GcodePreviewParser.parseColorHex(preview.filamentColors.getOrNull(tool)) ?: defaultColor
        }

    fun parseColor(hex: String?): FloatArray? {
        if (hex.isNullOrBlank()) return null
        val first =
            hex
                .split(';', '|', ',')
                .firstOrNull { it.isNotBlank() }
                ?.trim()
                ?.removePrefix("#") ?: return null
        return GcodePreviewParser.parseColorHex(first)
    }
}
