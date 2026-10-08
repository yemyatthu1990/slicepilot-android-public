package world.yemyat.slicepilot.model

import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class GcodePart { PERIMETER, INFILL, TOP_SHELL, BOTTOM_SHELL, SUPPORT, SUPPORT_INTERFACE, TRAVEL, OTHER }

class PartGeometry(val part: GcodePart, val vertices: FloatArray, val normals: FloatArray)

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
                    line.startsWith("estimated printing time") -> est = parseTimeLabel(line.substringAfter('=').trim())
                    line.startsWith("filament used [mm]") -> mm = line.substringAfter('=').trim().toFloatOrNull()
                    line.startsWith("total filament used [g]") -> grams = line.substringAfter('=').trim().toFloatOrNull()
                    line.startsWith("filament used [g]") && grams == null -> grams = line.substringAfter('=').trim().toFloatOrNull()
                    line.startsWith("filament_type") -> ftype = line.substringAfter('=').trim()
                    line.startsWith("filament_colour") -> fcolor = line.substringAfter('=').trim().ifBlank { null }
                    line.startsWith("nozzle_diameter") -> nozzle = line.substringAfter('=').trim().toFloatOrNull()
                }
                if (est != null && mm != null && grams != null && layers != null && ftype != null && fcolor != null && nozzle != null) break
            }
        }
        return GcodeStats(est, mm, grams, layers, ftype, fcolor, nozzle)
    }

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
        val layerVerts = HashMap<GcodePart, ArrayList<Float>>()
        val layerNormals = HashMap<GcodePart, ArrayList<Float>>()
        val layers = ArrayList<GcodeLayer>()
        var x = 0f; var y = 0f; var z = 0f; var e = 0f
        var width = defaultWidth
        var part = GcodePart.PERIMETER
        var layerIndex = 0
        var thickness = 0.2f
        var relativeExtrusion = false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        var maxZ = 0f
        var read = 0L

        fun flushLayer() {
            if (layerVerts.isNotEmpty()) {
                val parts = layerVerts.filter { it.value.isNotEmpty() }
                    .map { PartGeometry(it.key, it.value.toFloatArray(), layerNormals[it.key]?.toFloatArray() ?: FloatArray(0)) }
                if (parts.isNotEmpty()) {
                    layers.add(GcodeLayer(layerIndex, z, parts))
                    layerIndex++
                }
                layerVerts.values.forEach { it.clear() }
                layerNormals.values.forEach { it.clear() }
            }
        }

        file.bufferedReader().useLines { lines ->
            for (raw in lines) {
                read += raw.length + 1
                if (raw.isEmpty()) continue
                if (raw[0] == ';') {
                    val upper = raw.uppercase()
                    if (thickness == 0.2f && "LAYER_HEIGHT" in upper && "=" in raw) {
                        val v = raw.substringAfter('=').trim().toFloatOrNull()
                        if (v != null && v > 0f && v < 1f) thickness = v
                    }
                    when {
                        "LAYER_CHANGE" in upper || upper.startsWith(";LAYER ") || upper.startsWith(";LAYER_COUNT") -> {
                            if ("LAYER_CHANGE" in upper || upper.startsWith(";LAYER ")) flushLayer()
                        }
                        upper.startsWith(";TYPE:") -> {
                            val t = raw.substring(6).trim().lowercase()
                            part = when {
                                "support" in t && "interface" in t -> GcodePart.SUPPORT_INTERFACE
                                "support" in t -> GcodePart.SUPPORT
                                "top" in t && "surface" in t -> GcodePart.TOP_SHELL
                                "bottom" in t && "surface" in t -> GcodePart.BOTTOM_SHELL
                                "infill" in t || "solid infill" == t -> GcodePart.INFILL
                                "perimeter" in t || "external perimeter" == t || "wall" in t -> GcodePart.PERIMETER
                                else -> GcodePart.OTHER
                            }
                        }
                    }
                    continue
                }
                val cmd = raw.substringBefore(';').trim()
                if (cmd.isEmpty()) continue
                val op = cmd.substringBefore(' ').uppercase()
                if (op == "M82") { relativeExtrusion = false; continue }
                if (op == "M83") { relativeExtrusion = true; continue }
                if (op == "G92") {
                    for (tok in cmd.split(' ').drop(1)) {
                        if (tok.length < 2) continue
                        val v = tok.substring(1).toFloatOrNull() ?: continue
                        if (tok[0].uppercaseChar() == 'E') e = v
                    }
                    continue
                }
                if (op != "G1" && op != "G0") continue
                var nx = x; var ny = y; var nz = z; var ne = e
                var hasE = false
                for (tok in cmd.split(' ').drop(1)) {
                    if (tok.length < 2) continue
                    val v = tok.substring(1).toFloatOrNull() ?: continue
                    when (tok[0].uppercaseChar()) {
                        'X' -> nx = v
                        'Y' -> ny = v
                        'Z' -> nz = v
                        'E' -> { ne = v; hasE = true }
                    }
                }
                val extruding = hasE && if (relativeExtrusion) ne > 1e-6f else ne > e + 1e-6f
                if (op == "G1" && extruding) {
                    val dx = nx - x; val dy = ny - y
                    val len = sqrt(dx * dx + dy * dy)
                    val verts = layerVerts.getOrPut(part) { ArrayList() }
                    val norms = layerNormals.getOrPut(part) { ArrayList() }
                    if (len > 1e-4f && verts.size / 18 < MAX_SEGMENTS_PER_LAYER) {
                        val ux = dx / len; val uy = dy / len
                        val px = -uy * width * 0.5f; val py = ux * width * 0.5f
                        val zz = nz
                        val zb = zz - thickness
                        val spx = -uy; val spy = ux
                        val snx = uy; val sny = -ux
                        // top face (normal +z)
                        verts.add(x + px); verts.add(y + py); verts.add(zz)
                        verts.add(x - px); verts.add(y - py); verts.add(zz)
                        verts.add(nx + px); verts.add(ny + py); verts.add(zz)
                        verts.add(x - px); verts.add(y - py); verts.add(zz)
                        verts.add(nx - px); verts.add(ny - py); verts.add(zz)
                        verts.add(nx + px); verts.add(ny + py); verts.add(zz)
                        for (i in 0 until 6) { norms.add(0f); norms.add(0f); norms.add(1f) }
                        // +p side
                        verts.add(x + px); verts.add(y + py); verts.add(zz)
                        verts.add(nx + px); verts.add(ny + py); verts.add(zz)
                        verts.add(nx + px); verts.add(ny + py); verts.add(zb)
                        verts.add(x + px); verts.add(y + py); verts.add(zz)
                        verts.add(nx + px); verts.add(ny + py); verts.add(zb)
                        verts.add(x + px); verts.add(y + py); verts.add(zb)
                        for (i in 0 until 6) { norms.add(spx); norms.add(spy); norms.add(0f) }
                        // -p side
                        verts.add(x - px); verts.add(y - py); verts.add(zz)
                        verts.add(nx - px); verts.add(ny - py); verts.add(zb)
                        verts.add(nx - px); verts.add(ny - py); verts.add(zz)
                        verts.add(x - px); verts.add(y - py); verts.add(zz)
                        verts.add(x - px); verts.add(y - py); verts.add(zb)
                        verts.add(nx - px); verts.add(ny - py); verts.add(zb)
                        for (i in 0 until 6) { norms.add(snx); norms.add(sny); norms.add(0f) }
                        minX = min(minX, min(x, nx) - width); maxX = max(maxX, max(x, nx) + width)
                        minY = min(minY, min(y, ny) - width); maxY = max(maxY, max(y, ny) + width)
                        maxZ = max(maxZ, zz)
                    }
                }
                x = nx; y = ny; z = nz
                e = if (relativeExtrusion) 0f else ne
                if (read % 262144L < (raw.length + 1).toLong()) progress?.invoke(read.toFloat() / total)
            }
        }
        flushLayer()
        if (minX > maxX) { minX = 0f; maxX = 1f; minY = 0f; maxY = 1f }
        return GcodePreview(layers, minX, minY, maxX, maxY, maxZ)
    }

    fun colorFor(part: GcodePart): FloatArray = when (part) {
        GcodePart.PERIMETER -> floatArrayOf(0.16f, 0.45f, 0.85f)
        GcodePart.INFILL -> floatArrayOf(0.85f, 0.55f, 0.20f)
        GcodePart.TOP_SHELL -> floatArrayOf(0.20f, 0.65f, 0.45f)
        GcodePart.BOTTOM_SHELL -> floatArrayOf(0.55f, 0.35f, 0.75f)
        GcodePart.SUPPORT, GcodePart.SUPPORT_INTERFACE -> floatArrayOf(0.55f, 0.58f, 0.62f)
        else -> floatArrayOf(0.75f, 0.75f, 0.78f)
    }
}
