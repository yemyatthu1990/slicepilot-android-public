package world.yemyat.slicepilot.model

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

data class ProjectPrintSettings(
    val supportEnabled: Boolean? = null,
    val supportStyle: String? = null,
    val layerHeight: Float? = null,
    val wallLoops: Int? = null,
    val topShellLayers: Int? = null,
    val bottomShellLayers: Int? = null,
    val infillDensity: Int? = null,
    val infillPattern: String? = null,
    val adhesion: String? = null,
    val bedType: String? = null,
)

object ProjectSettingsReader {
    private const val PROJECT_CONFIG = "Metadata/project_settings.config"

    fun read(file: File): ProjectPrintSettings? {
        if (!file.name.endsWith(".3mf", ignoreCase = true)) return null
        val json = try {
            ZipFile(file).use { zip ->
                val entry = zip.getEntry(PROJECT_CONFIG) ?: return null
                zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
            }
        } catch (_: Exception) {
            return null
        }
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        fun str(vararg keys: String): String? {
            for (key in keys) {
                if (!obj.has(key)) continue
                val v = obj.optString(key, "").trim()
                if (v.isNotEmpty() && v != "null") return v
            }
            return null
        }

        fun bool(vararg keys: String): Boolean? = str(*keys)?.let {
            when (it.lowercase()) {
                "1", "true", "yes", "on" -> true
                "0", "false", "no", "off" -> false
                else -> it.toIntOrNull()?.let { n -> n != 0 }
            }
        }

        fun float(vararg keys: String): Float? = str(*keys)?.let {
            it.removeSuffix("%").removeSuffix("mm").trim().toFloatOrNull()
        }

        fun int(vararg keys: String): Int? = float(*keys)?.toInt()

        val supportType = str("support_type") ?: ""
        val supportStyleRaw = str("support_style") ?: ""
        val style = when {
            supportType.contains("tree", ignoreCase = true) ||
                supportStyleRaw.contains("organic", ignoreCase = true) ||
                supportStyleRaw.contains("tree", ignoreCase = true) -> "trees"
            supportType.isNotEmpty() || supportStyleRaw.isNotEmpty() -> "grid"
            else -> null
        }

        val adhesion = run {
            val raft = int("raft_layers")
            val brim = float("brim_width")
            val skirt = int("skirt_height")
            when {
                raft != null && raft > 0 -> "raft"
                brim != null && brim > 0f -> "brim"
                skirt != null && skirt > 0 -> "skirt"
                raft == 0 && brim == 0f && skirt == 0 -> "none"
                else -> null
            }
        }

        return ProjectPrintSettings(
            supportEnabled = bool("enable_support"),
            supportStyle = style,
            layerHeight = float("layer_height"),
            wallLoops = int("wall_loops"),
            topShellLayers = int("top_shell_layers"),
            bottomShellLayers = int("bottom_shell_layers"),
            infillDensity = int("sparse_infill_density", "infill_density"),
            infillPattern = str("sparse_infill_pattern", "infill_pattern"),
            adhesion = adhesion,
            bedType = str("curr_bed_type"),
        )
    }
}