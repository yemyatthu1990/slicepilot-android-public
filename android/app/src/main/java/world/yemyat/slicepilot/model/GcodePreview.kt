package world.yemyat.slicepilot.model

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

enum class GcodePart { PERIMETER, INFILL, TOP_SHELL, BOTTOM_SHELL, SUPPORT, SUPPORT_INTERFACE, TRAVEL, OTHER }

class PartGeometry(
    val part: GcodePart,
    val vertices: FloatArray,
    val tool: Int = 0,
)

class GcodeLayer(
    val index: Int,
    val z: Float,
    val parts: List<PartGeometry>,
)

class GcodePreview(
    val layers: List<GcodeLayer>,
    val minX: Float, val minY: Float,
    val maxX: Float, val maxY: Float,
    val maxZ: Float,
    /**
     * Per-tool filament colours (`#RRGGBB`, index = tool index T0..TN) parsed from
     * `; filament_colour = ...` in the G-code header/footer.
     */
    val filamentColors: List<String> = emptyList(),
) {
    val centerX get() = (minX + maxX) / 2f
    val centerY get() = (minY + maxY) / 2f
    val centerZ get() = maxZ / 2f
    val maxRadius: Float
        get() {
            val hx = (maxX - minX) / 2f
            val hy = (maxY - minY) / 2f
            val hz = maxZ / 2f
            return max(1f, sqrt(hx * hx + hy * hy + hz * hz))
        }
}

class GcodeStats(
    val estimatedSeconds: Long?,
    val filamentMm: Float?,
    val filamentGrams: Float?,
    val layerCount: Int?,
    val filamentType: String?,
    val filamentColor: String?,
    val nozzleDiameter: Float?,
) {
    val estimatedTimeLabel: String?
        get() {
            val s = estimatedSeconds ?: return null
            val h = s / 3600
            val m = (s % 3600) / 60
            val sec = s % 60
            return if (h > 0) "${h}h ${m}m ${sec}s" else if (m > 0) "${m}m ${sec}s" else "${sec}s"
        }
}

object GcodePreviewParser {
    private const val MAX_SEGMENTS_PER_LAYER = 60000

    // Target (~32 MB) and hard (~48 MB) caps on preview geometry across all layers.
    // Large G-code files adapt their level of detail (dropping hidden infill / inner walls,
    // coalescing sub-millimetre sculpted segments, and only striding layers on extreme files)
    // proportionally to file size and parse rate so 100% of the model's height always renders
    // without OOMing or cutting off the upper half.
    private const val TARGET_FLOATS = 8L * 1024 * 1024
    private const val MAX_HARD_FLOATS = 12L * 1024 * 1024

    fun parseStats(file: File): GcodeStats {
        var est: Long? = null
        var mm: Float? = null
        var grams: Float? = null
        var layers: Int? = null
        var ftype: String? = null
        var fcolor: String? = null
        var nozzle: Float? = null
        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                if (raw.isEmpty() || raw[0] != ';') {
                    if (est != null && mm != null && grams != null && layers != null && ftype != null && fcolor != null && nozzle != null) break
                    continue
                }
                val line = raw.trimStart(';', ' ')
                when {
                    line.startsWith("LAYER_COUNT:") -> layers = line.substringAfter(':').trim().toIntOrNull()
                    line.startsWith("total layer number:") && layers == null -> layers = line.substringAfter(':').trim().toIntOrNull()
                    line.startsWith("estimated printing time") -> est = parseTimeLabel(line.substringAfter('=').trim())
                    line.startsWith("model printing time:") && est == null -> {
                        val totalPart = line.substringAfter("total estimated time:", line.substringAfter("model printing time:")).substringBefore(';').trim()
                        est = parseTimeLabel(totalPart)
                    }
                    line.startsWith("filament used [mm]") -> mm = parseFloatSum(line.substringAfter('=').trim())
                    line.startsWith("total filament used [g]") -> grams = parseFloatSum(line.substringAfter('=').trim())
                    line.startsWith("filament used [g]") && grams == null -> grams = parseFloatSum(line.substringAfter('=').trim())
                    line.startsWith("filament_type") -> ftype = line.substringAfter('=').trim()
                    line.startsWith("filament_colour") -> fcolor = line.substringAfter('=').trim().ifBlank { null }
                    line.startsWith("nozzle_diameter") -> nozzle = line.substringAfter('=').trim().substringBefore(',').substringBefore(';').trim().toFloatOrNull()
                }
                if (est != null && mm != null && grams != null && layers != null && ftype != null && fcolor != null && nozzle != null) break
            }
        }
        return GcodeStats(est, mm, grams, layers, ftype, fcolor, nozzle)
    }

    private fun parseFloatSum(raw: String): Float? {
        val vals =
            raw
                .split(',', ';', '|')
                .mapNotNull { it.trim().toFloatOrNull() }
        return if (vals.isNotEmpty()) vals.sum() else null
    }

    internal fun parseColorList(raw: String): List<String> =
        raw
            .split(';', '|', ',')
            .map { it.trim().trim('"') }
            .filter { it.isNotEmpty() }

    private fun parseTimeLabel(s: String): Long? {
        var total = 0L
        var num = StringBuilder()
        for (c in s) {
            if (c.isDigit()) num.append(c)
            else if (num.isNotEmpty()) {
                val v = num.toString().toLongOrNull() ?: return null
                when (c) {
                    'h' -> total += v * 3600
                    'm' -> total += v * 60
                    's' -> total += v
                    else -> return null
                }
                num = StringBuilder()
            }
        }
        return if (total > 0) total else null
    }

    fun parse(file: File, defaultWidth: Float = 0.45f, progress: ((Float) -> Unit)? = null): GcodePreview {
        val total = if (file.exists()) file.length().coerceAtLeast(1) else 1
        val layerVerts = HashMap<Pair<GcodePart, Int>, FloatBuf>()
        val layers = ArrayList<GcodeLayer>()
        var x = 0f; var y = 0f; var z = 0f; var e = 0f
        var layerZ = 0f
        var width = defaultWidth
        var part = GcodePart.PERIMETER
        var isOuterWall = true
        var tool = 0
        var filamentColors = emptyList<String>()
        var layerIndex = 0
        var rawLayerIndex = 0
        var sawExtrusionInLayer = false
        var thickness = 0.2f
        var relativePositioning = false
        var relativeExtrusion = false
        var seenLayerMarker = false
        var inCustomFeature = false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var maxZ = 0f
        var read = 0L
        var totalFloats = 0L
        var bufferedFloats = 0L

        // Estimate initial LOD from file size (~32 bytes per G1 extrusion line -> 54 floats).
        val estFloats = total * 54L / 32L
        var lodLevel = initialLodLevel(estFloats)
        val warmupBytes = max(512L * 1024L, total / 12L)
        val lodCheckStep = max(256L * 1024L, total / 16L)
        var nextLodCheckByte = warmupBytes

        // Polyline coalescing state for LOD >= 1 (merges tiny sculpted segments along a wall).
        var hasAnchor = false
        var anchorX = 0f; var anchorY = 0f; var anchorZ = 0f
        var anchorAccumLen = 0f

        fun currentMinSegLen(): Float {
            val base =
                when (lodLevel) {
                    0 -> 0f
                    1 -> 0.35f
                    2 -> 0.6f
                    3 -> 1.0f
                    4 -> 1.6f
                    else -> 2.5f
                }
            // Tree/organic supports have many tiny circular arcs; slightly coarser
            // coalescing at higher LOD keeps all supports visible without blowing the
            // geometry budget.
            return if (part == GcodePart.SUPPORT && lodLevel >= 3) base * 1.35f else base
        }

        fun currentLayerStride(): Int =
            when {
                lodLevel <= 3 -> 1
                lodLevel == 4 -> 2
                else -> 4
            }

        fun keepCurrentFeature(): Boolean {
            if (inCustomFeature && lodLevel >= 1) return false
            return when (lodLevel) {
                0 -> true
                1 -> part != GcodePart.INFILL
                // Always keep exterior-visible features — including supports, support
                // interfaces, brims, and prime towers — at every LOD level so large
                // models never lose their supports in the final G-code preview.
                else -> (part == GcodePart.PERIMETER && isOuterWall) ||
                    part == GcodePart.TOP_SHELL || part == GcodePart.BOTTOM_SHELL ||
                    part == GcodePart.SUPPORT || part == GcodePart.SUPPORT_INTERFACE ||
                    part == GcodePart.OTHER
            }
        }

        fun emitSegment(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float) {
            if (totalFloats + bufferedFloats + 54L > MAX_HARD_FLOATS) return
            val dx = x1 - x0; val dy = y1 - y0
            val len = sqrt(dx * dx + dy * dy)
            if (len <= 1e-4f) return
            layerZ = z1
            val verts = layerVerts.getOrPut(part to tool) { FloatBuf() }
            if (verts.size / 54 >= MAX_SEGMENTS_PER_LAYER) return
            val ux = dx / len; val uy = dy / len
            val px = -uy * width * 0.5f; val py = ux * width * 0.5f
            val wallHeight = thickness * currentLayerStride()
            val zb0 = z0 - wallHeight
            val zb1 = z1 - wallHeight
            // top face (normal +z)
            verts.add(x0 + px); verts.add(y0 + py); verts.add(z0)
            verts.add(x0 - px); verts.add(y0 - py); verts.add(z0)
            verts.add(x1 + px); verts.add(y1 + py); verts.add(z1)
            verts.add(x0 - px); verts.add(y0 - py); verts.add(z0)
            verts.add(x1 - px); verts.add(y1 - py); verts.add(z1)
            verts.add(x1 + px); verts.add(y1 + py); verts.add(z1)

            // +p side
            verts.add(x0 + px); verts.add(y0 + py); verts.add(z0)
            verts.add(x1 + px); verts.add(y1 + py); verts.add(z1)
            verts.add(x1 + px); verts.add(y1 + py); verts.add(zb1)
            verts.add(x0 + px); verts.add(y0 + py); verts.add(z0)
            verts.add(x1 + px); verts.add(y1 + py); verts.add(zb1)
            verts.add(x0 + px); verts.add(y0 + py); verts.add(zb0)

            // -p side
            verts.add(x0 - px); verts.add(y0 - py); verts.add(z0)
            verts.add(x1 - px); verts.add(y1 - py); verts.add(zb1)
            verts.add(x1 - px); verts.add(y1 - py); verts.add(z1)
            verts.add(x0 - px); verts.add(y0 - py); verts.add(z0)
            verts.add(x0 - px); verts.add(y0 - py); verts.add(zb0)
            verts.add(x1 - px); verts.add(y1 - py); verts.add(zb1)

            bufferedFloats += 54L
            minX = min(minX, min(x0, x1) - width); maxX = max(maxX, max(x0, x1) + width)
            minY = min(minY, min(y0, y1) - width); maxY = max(maxY, max(y0, y1) + width)
            maxZ = max(maxZ, max(z0, z1))
        }

        fun flushAnchor() {
            if (!hasAnchor) return
            val segZ0 = if (abs(z - anchorZ) <= thickness * 2f) anchorZ else z
            emitSegment(anchorX, anchorY, segZ0, x, y, z)
            hasAnchor = false
            anchorAccumLen = 0f
        }

        fun clearBufferedLayer() {
            hasAnchor = false
            anchorAccumLen = 0f
            for (buf in layerVerts.values) {
                if (buf.size > 0) buf.takeArray()
            }
            bufferedFloats = 0L
            sawExtrusionInLayer = false
        }

        fun flushLayer() {
            flushAnchor()
            val hadExtrusion = sawExtrusionInLayer
            sawExtrusionInLayer = false
            var hasData = false
            for (buf in layerVerts.values) {
                if (buf.size > 0) {
                    hasData = true
                    break
                }
            }
            if (hasData) {
                val parts = ArrayList<PartGeometry>(layerVerts.size)
                for ((key, verts) in layerVerts) {
                    if (verts.size == 0) continue
                    val arr = verts.takeArray()
                    totalFloats += arr.size
                    parts.add(PartGeometry(key.first, arr, key.second))
                }
                bufferedFloats = 0L
                if (parts.isNotEmpty()) {
                    layers.add(GcodeLayer(layerIndex, layerZ, parts))
                    layerIndex++
                }
            }
            if (hadExtrusion) {
                rawLayerIndex++
                if (totalFloats >= MAX_HARD_FLOATS * 92L / 100L && lodLevel < 5) {
                    lodLevel = 5
                } else if (totalFloats >= MAX_HARD_FLOATS * 80L / 100L && lodLevel < 4) {
                    lodLevel = 4
                } else if (read >= nextLodCheckByte && totalFloats > 0L && read > 0L) {
                    val projected = totalFloats.toDouble() * total.toDouble() / read.toDouble()
                    val allowBump =
                        when {
                            lodLevel < 3 -> projected > TARGET_FLOATS * 1.25
                            lodLevel == 3 -> totalFloats > TARGET_FLOATS * 55L / 100L && projected > TARGET_FLOATS * 1.35
                            lodLevel == 4 -> totalFloats > TARGET_FLOATS * 85L / 100L && projected > MAX_HARD_FLOATS * 0.95
                            else -> false
                        }
                    if (allowBump) {
                        lodLevel++
                    }
                    nextLodCheckByte = read + lodCheckStep
                }
            }
        }

        fun emitArc(
            x0: Float, y0: Float, z0: Float,
            x1: Float, y1: Float, z1: Float,
            arcI: Float, arcJ: Float,
            clockwise: Boolean,
        ) {
            val cx = x0 + arcI
            val cy = y0 + arcJ
            val r = hypot((x0 - cx).toDouble(), (y0 - cy).toDouble())
            if (r < 1e-4) {
                emitSegment(x0, y0, z0, x1, y1, z1)
                return
            }
            val a0 = atan2((y0 - cy).toDouble(), (x0 - cx).toDouble())
            val a1 = atan2((y1 - cy).toDouble(), (x1 - cx).toDouble())
            val fullCircle = abs(x1 - x0) < 1e-4f && abs(y1 - y0) < 1e-4f
            var sweep =
                if (fullCircle) {
                    if (clockwise) -2.0 * PI else 2.0 * PI
                } else {
                    var s = a1 - a0
                    if (clockwise) {
                        if (s > 0.0) s -= 2.0 * PI
                        if (abs(s) < 1e-6) s = -2.0 * PI
                    } else {
                        if (s < 0.0) s += 2.0 * PI
                        if (abs(s) < 1e-6) s = 2.0 * PI
                    }
                    s
                }
            val arcLen = abs(sweep) * r
            val chordTarget = max(0.5, currentMinSegLen().toDouble())
            val steps = ceil(max(abs(sweep) / (PI / 12.0), arcLen / chordTarget)).toInt().coerceIn(2, 64)
            var px = x0; var py = y0; var pz = z0
            for (s in 1..steps) {
                val t = s.toDouble() / steps
                val ang = a0 + sweep * t
                val nx = if (s == steps) x1 else (cx + r * cos(ang)).toFloat()
                val ny = if (s == steps) y1 else (cy + r * sin(ang)).toFloat()
                val nz = if (s == steps) z1 else z0 + (z1 - z0) * t.toFloat()
                emitSegment(px, py, pz, nx, ny, nz)
                px = nx; py = ny; pz = nz
            }
        }

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                read += raw.length + 1
                if (raw.isEmpty()) continue
                if (raw[0] == ';') {
                    val trimmed = raw.trimStart(';', ' ', '\t')
                    val trimmedUpper = trimmed.uppercase()
                    when {
                        trimmedUpper.startsWith("HEIGHT:") -> {
                            val v = trimmed.substringAfter(':').trim().toFloatOrNull()
                            if (v != null && v in 0.02f..2.0f) thickness = v
                        }
                        trimmedUpper.startsWith("LAYER_HEIGHT:") -> {
                            val v = trimmed.substringAfter(':').trim().toFloatOrNull()
                            if (v != null && v in 0.02f..2.0f) thickness = v
                        }
                        trimmedUpper.startsWith("LAYER_HEIGHT") && "=" in trimmed -> {
                            val v = trimmed.substringAfter('=').trim().toFloatOrNull()
                            if (v != null && v in 0.02f..2.0f) thickness = v
                        }
                        trimmedUpper.startsWith("WIDTH:") -> {
                            val v = trimmed.substringAfter(':').trim().toFloatOrNull()
                            if (v != null && v in 0.05f..3.0f) width = v
                        }
                        trimmedUpper.startsWith("LINE_WIDTH:") -> {
                            val v = trimmed.substringAfter(':').trim().toFloatOrNull()
                            if (v != null && v in 0.05f..3.0f) width = v
                        }
                    }
                    if ((trimmedUpper.startsWith("FILAMENT_COLOUR") || trimmedUpper.startsWith("FILAMENT_COLOR")) && "=" in trimmed) {
                        val parsed = parseColorList(trimmed.substringAfter('='))
                        if (parsed.isNotEmpty()) filamentColors = parsed
                    }
                    when {
                        isLayerChangeComment(trimmedUpper) -> {
                            if (!seenLayerMarker && layers.isEmpty() && isPreLayerZeroPurge(trimmedUpper, inCustomFeature)) {
                                clearBufferedLayer()
                                minX = Float.MAX_VALUE; minY = Float.MAX_VALUE
                                maxX = -Float.MAX_VALUE; maxY = -Float.MAX_VALUE
                                maxZ = 0f
                            } else {
                                flushLayer()
                            }
                            seenLayerMarker = true
                            inCustomFeature = false
                        }
                        trimmedUpper.startsWith("TYPE:") || trimmedUpper.startsWith("FEATURE:") -> {
                            flushAnchor()
                            val t = trimmed.substringAfter(':').trim().lowercase()
                            inCustomFeature = (t == "custom")
                            part = classifyFeature(t)
                            isOuterWall = part == GcodePart.PERIMETER && "inner" !in t
                        }
                    }
                    continue
                }
                val cmd = raw.substringBefore(';').trim()
                if (cmd.isEmpty()) continue
                val tokens = cmd.split(' ', '\t')
                val op = tokens[0].uppercase()
                if (op.length > 1 && op[0] == 'T' && op.substring(1).all { it.isDigit() }) {
                    flushAnchor()
                    tool = op.substring(1).toInt()
                    continue
                }
                if (op == "G90") { relativePositioning = false; continue }
                if (op == "G91") { relativePositioning = true; continue }
                if (op == "M82") { relativeExtrusion = false; continue }
                if (op == "M83") { relativeExtrusion = true; continue }
                if (op == "G92") {
                    flushAnchor()
                    for (i in 1 until tokens.size) {
                        val tok = tokens[i]
                        if (tok.length < 2) continue
                        val v = tok.substring(1).toFloatOrNull() ?: continue
                        when (tok[0].uppercaseChar()) {
                            'X' -> x = v
                            'Y' -> y = v
                            'Z' -> z = v
                            'E' -> e = v
                        }
                    }
                    continue
                }
                val isLinear = op == "G1" || op == "G0"
                val isArc = op == "G2" || op == "G3"
                if (!isLinear && !isArc) continue
                var nx = x; var ny = y; var nz = z; var ne = e
                var arcI = 0f; var arcJ = 0f
                var hasE = false; var hasIJ = false
                for (i in 1 until tokens.size) {
                    val tok = tokens[i]
                    if (tok.length < 2) continue
                    val v = tok.substring(1).toFloatOrNull() ?: continue
                    when (tok[0].uppercaseChar()) {
                        'X' -> nx = if (relativePositioning) x + v else v
                        'Y' -> ny = if (relativePositioning) y + v else v
                        'Z' -> nz = if (relativePositioning) z + v else v
                        'E' -> { ne = v; hasE = true }
                        'I' -> { arcI = v; hasIJ = true }
                        'J' -> { arcJ = v; hasIJ = true }
                    }
                }
                val extruding = hasE && if (relativeExtrusion) ne > 1e-6f else ne > e + 1e-6f
                if (extruding) sawExtrusionInLayer = true
                val stride = currentLayerStride()
                val keepMove =
                    extruding &&
                        (totalFloats + bufferedFloats + 54L <= MAX_HARD_FLOATS) &&
                        (rawLayerIndex % stride == 0) &&
                        keepCurrentFeature()
                if (keepMove) {
                    if (op == "G1") {
                        val minLen = currentMinSegLen()
                        if (minLen <= 0f) {
                            val segZ0 = if (abs(nz - z) <= thickness * 2f) z else nz
                            emitSegment(x, y, segZ0, nx, ny, nz)
                        } else {
                            if (!hasAnchor) {
                                anchorX = x; anchorY = y; anchorZ = z
                                anchorAccumLen = 0f
                                hasAnchor = true
                            }
                            anchorAccumLen += sqrt((nx - x) * (nx - x) + (ny - y) * (ny - y))
                            val dxA = nx - anchorX; val dyA = ny - anchorY
                            if (dxA * dxA + dyA * dyA >= minLen * minLen || anchorAccumLen >= minLen * 1.5f) {
                                val segZ0 = if (abs(nz - anchorZ) <= thickness * 2f) anchorZ else nz
                                emitSegment(anchorX, anchorY, segZ0, nx, ny, nz)
                                anchorX = nx; anchorY = ny; anchorZ = nz
                                anchorAccumLen = 0f
                            }
                        }
                    } else if (isArc) {
                        flushAnchor()
                        val segZ0 = if (abs(nz - z) <= thickness * 2f) z else nz
                        if (hasIJ) {
                            emitArc(x, y, segZ0, nx, ny, nz, arcI, arcJ, clockwise = op == "G2")
                        } else {
                            emitSegment(x, y, segZ0, nx, ny, nz)
                        }
                    }
                } else {
                    flushAnchor()
                }
                x = nx; y = ny; z = nz
                e = if (relativeExtrusion) 0f else ne
                if (read % 262144L < (raw.length + 1).toLong()) progress?.invoke(read.toFloat() / total)
            }
        }
        flushLayer()
        if (minX > maxX) { minX = 0f; maxX = 1f; minY = 0f; maxY = 1f }
        return GcodePreview(layers, minX, minY, maxX, maxY, maxZ, filamentColors)
    }

    internal fun initialLodLevel(estFloats: Long): Int =
        when {
            estFloats <= TARGET_FLOATS -> 0
            estFloats <= TARGET_FLOATS * 2L -> 1
            estFloats <= TARGET_FLOATS * 4L -> 2
            estFloats <= TARGET_FLOATS * 10L -> 3
            else -> 4
        }

    private fun isLayerChangeComment(trimmedUpper: String): Boolean {
        if ("LAYER_CHANGE" in trimmedUpper || "CHANGE_LAYER" in trimmedUpper) return true
        if (trimmedUpper.startsWith("LAYER:") || trimmedUpper.startsWith("LAYER ")) {
            val rest = trimmedUpper.substring(6).trim()
            return rest.isNotEmpty() && (rest[0].isDigit() || rest[0] == '-')
        }
        return false
    }

    private fun isPreLayerZeroPurge(trimmedUpper: String, inCustomFeature: Boolean): Boolean {
        if (inCustomFeature) return true
        if (trimmedUpper.startsWith("LAYER:") || trimmedUpper.startsWith("LAYER ")) {
            return trimmedUpper.substring(6).trim() == "0"
        }
        return false
    }

    private fun classifyFeature(t: String): GcodePart =
        when {
            "support" in t && "interface" in t -> GcodePart.SUPPORT_INTERFACE
            "support" in t -> GcodePart.SUPPORT
            "top" in t && ("surface" in t || "shell" in t) -> GcodePart.TOP_SHELL
            "bottom" in t && ("surface" in t || "shell" in t) -> GcodePart.BOTTOM_SHELL
            "infill" in t || "bridge" in t -> GcodePart.INFILL
            "perimeter" in t || "wall" in t -> GcodePart.PERIMETER
            else -> GcodePart.OTHER
        }

    private class FloatBuf(private val initialCapacity: Int = 4096) {
        private var data = FloatArray(initialCapacity)
        var size = 0
            private set

        fun add(value: Float) {
            ensureCapacity(1)
            data[size++] = value
        }

        fun addTriple(x: Float, y: Float, z: Float, count: Int) {
            ensureCapacity(count * 3)
            var i = 0
            while (i < count) {
                data[size++] = x
                data[size++] = y
                data[size++] = z
                i++
            }
        }

        /**
         * Returns the buffered values and resets the buffer. If the internal
         * array is an exact fit it is adopted (no copy), keeping the memory
         * peak of a layer flush at one array instead of two.
         */
        fun takeArray(): FloatArray {
            val out = if (size == data.size) data else data.copyOf(size)
            data = FloatArray(initialCapacity)
            size = 0
            return out
        }

        private fun ensureCapacity(extra: Int) {
            val required = size + extra
            if (required <= data.size) return
            var newCapacity = data.size * 2
            if (newCapacity < required) newCapacity = required
            data = data.copyOf(newCapacity)
        }
    }

    fun colorFor(
        part: GcodePart,
        tool: Int = 0,
        filamentColors: List<String> = emptyList(),
    ): FloatArray =
        when (part) {
            GcodePart.SUPPORT -> floatArrayOf(0.18f, 0.72f, 0.35f)
            GcodePart.SUPPORT_INTERFACE -> floatArrayOf(0.95f, 0.55f, 0.15f)
            GcodePart.TRAVEL -> floatArrayOf(0.75f, 0.75f, 0.78f)
            else -> parseColorHex(filamentColors.getOrNull(tool)) ?: palette(part)
        }

    private fun palette(part: GcodePart): FloatArray =
        when (part) {
            GcodePart.PERIMETER -> floatArrayOf(0.16f, 0.45f, 0.85f)
            GcodePart.INFILL -> floatArrayOf(0.85f, 0.55f, 0.20f)
            GcodePart.TOP_SHELL -> floatArrayOf(0.20f, 0.65f, 0.45f)
            GcodePart.BOTTOM_SHELL -> floatArrayOf(0.55f, 0.35f, 0.75f)
            GcodePart.SUPPORT, GcodePart.SUPPORT_INTERFACE -> floatArrayOf(0.55f, 0.58f, 0.62f)
            else -> floatArrayOf(0.75f, 0.75f, 0.78f)
        }

    fun parseColorHex(hex: String?): FloatArray? {
        val value = hex?.trim()?.removePrefix("#") ?: return null
        val rgb =
            when (value.length) {
                6 -> value
                8 -> value.substring(2)
                else -> return null
            }
        return try {
            floatArrayOf(
                Integer.parseInt(rgb.substring(0, 2), 16) / 255f,
                Integer.parseInt(rgb.substring(2, 4), 16) / 255f,
                Integer.parseInt(rgb.substring(4, 6), 16) / 255f,
            )
        } catch (_: NumberFormatException) {
            null
        }
    }
}
