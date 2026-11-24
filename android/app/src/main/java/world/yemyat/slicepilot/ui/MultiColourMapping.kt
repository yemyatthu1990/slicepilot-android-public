package world.yemyat.slicepilot.ui

import org.json.JSONObject
import world.yemyat.slicepilot.cloud.LoadedTray
import world.yemyat.slicepilot.cloud.PrintSlot

/** A physical spool position: which Canvas/AMS ([canvasId]) and which tray in it. */
data class FilamentSlot(
    val canvasId: Int = 0,
    val trayId: Int = 0,
)

/**
 * One filament slot declared by a multi-colour 3MF project (Bambu Studio / OrcaSlicer / PrusaSlicer).
 * [index] is 0-based (G-code tool T<index>; 3MF extruder = index + 1).
 */
data class ModelFilament(
    val index: Int,
    val type: String,
    val colorHex: String,
    val preset: String = "",
    val used: Boolean = true,
)

/** Inspected filament metadata for the active model file. */
data class ModelFilamentInfo(
    val filaments: List<ModelFilament>,
) {
    val usedFilaments: List<ModelFilament>
        get() = filaments.filter { it.used }.ifEmpty { filaments }

    /**
     * True when the model actually uses more than one colour (or references an extruder > 1).
     * Filters out single-colour MakerWorld 3MFs exported from a 4-slot AMS Bambu template
     * where every triangle only uses extruder 1.
     */
    val isMultiColour: Boolean
        get() = filaments.size > 1 && (usedFilaments.size > 1 || usedFilaments.any { it.index > 0 })
}

/** Resolved mapping from one model filament (tool T[modelIndex]) to an AMS/Canvas spool & preset. */
data class ResolvedFilamentAssignment(
    val modelIndex: Int,
    val modelType: String,
    val modelColorHex: String,
    val used: Boolean,
    val slot: FilamentSlot,
    val preset: String,
    val spoolColorHex: String,
    val spoolType: String,
    val matchedTray: LoadedTray?,
)

/**
 * Pure multi-colour / AMS spool-mapping logic (tested without an Android context):
 * - Parses `NativeLib.nativeInspectModel` JSON + optional `MeshData.triExtruders` usage set
 * - Auto-maps model filaments to loaded Canvas/AMS trays by material type + RGB colour proximity
 * - Encodes/decodes persisted `canvas:tray` mappings
 * - Builds INI overrides & `PrintSlot` lists for N-colour slicing and printing
 */
object MultiColourMapping {
    const val DEFAULT_AMS_TRAY_COUNT = 4
    private const val PREF_SEPARATOR = ','
    private const val CANVAS_SEPARATOR = ':'

    private val FALLBACK_PALETTE =
        listOf("#E53935", "#1E88E5", "#43A047", "#FDD835", "#8E24AA", "#FB8C00", "#00ACC1", "#F5F5F5")

    fun parseInspectJson(
        json: String?,
        triExtruders: IntArray? = null,
    ): ModelFilamentInfo? {
        if (json.isNullOrBlank()) return null
        val root =
            try {
                JSONObject(json)
            } catch (_: Exception) {
                return null
            }
        if (root.has("error")) return null
        val arr = root.optJSONArray("filaments") ?: return null
        val count = arr.length()
        if (count == 0) return ModelFilamentInfo(emptyList())

        val usedSet: Set<Int>? =
            if (triExtruders != null && triExtruders.isNotEmpty()) {
                val s = HashSet<Int>(8)
                for (e in triExtruders) {
                    if (e in 1..count) s.add(e)
                }
                s.takeIf { it.isNotEmpty() }
            } else {
                null
            }

        val list =
            (0 until count).map { i ->
                val obj = arr.optJSONObject(i)
                val rawType = obj?.optString("type", "").orEmpty().trim()
                val rawColor = normalizeHex(obj?.optString("color", "")) ?: FALLBACK_PALETTE[i % FALLBACK_PALETTE.size]
                val rawPreset = obj?.optString("preset", "").orEmpty().trim()
                ModelFilament(
                    index = i,
                    type = rawType.ifBlank { "PLA" },
                    colorHex = rawColor,
                    preset = rawPreset,
                    used = usedSet == null || (i + 1) in usedSet,
                )
            }
        return ModelFilamentInfo(list)
    }

    /** Decodes persisted "canvas:tray,..." (or legacy "tray,...") slot assignments. */
    fun decodeSlots(raw: String?): List<FilamentSlot> =
        raw.orEmpty().split(PREF_SEPARATOR).mapNotNull { token ->
            val t = token.trim()
            if (t.isEmpty()) return@mapNotNull null
            val colon = t.indexOf(CANVAS_SEPARATOR)
            val canvas: Int
            val tray: Int
            if (colon >= 0) {
                canvas = t.substring(0, colon).trim().toIntOrNull() ?: return@mapNotNull null
                tray = t.substring(colon + 1).trim().toIntOrNull() ?: return@mapNotNull null
            } else {
                canvas = 0
                tray = t.toIntOrNull() ?: return@mapNotNull null
            }
            if (canvas < 0 || tray < 0) null else FilamentSlot(canvas, tray)
        }

    fun encodeSlots(slots: List<FilamentSlot>): String =
        slots.joinToString(PREF_SEPARATOR.toString()) { "${it.canvasId}$CANVAS_SEPARATOR${it.trayId}" }

    /** Available physical AMS/Canvas slots: reported trays if present, else 4 fallback slots S1..S4. */
    fun availableSlots(reported: List<LoadedTray>): List<FilamentSlot> {
        val slots =
            reported
                .sortedWith(compareBy({ it.canvasId }, { it.trayId }))
                .map { FilamentSlot(it.canvasId, it.trayId) }
                .distinct()
        if (slots.isNotEmpty()) return slots
        return (0 until DEFAULT_AMS_TRAY_COUNT).map { FilamentSlot(0, it) }
    }

    /** Human label for a slot ("S1".."S4", or "C1·S1" when more than one Canvas is present). */
    fun formatSlotTag(
        slot: FilamentSlot,
        multiCanvas: Boolean = false,
    ): String =
        if (multiCanvas) {
            "C${slot.canvasId + 1}·S${slot.trayId + 1}"
        } else {
            "S${slot.trayId + 1}"
        }

    /**
     * Resolves every model filament to an AMS/Canvas spool and local slicer filament preset.
     * - Honours explicit [overrides] (index -> FilamentSlot) when that slot exists in [availableSlots].
     * - Otherwise auto-matches each filament to the closest unused loaded tray by material type + colour.
     * - Falls back cleanly to S1..S4 with local type-matched presets when the printer reports no trays.
     */
    fun resolveAssignments(
        modelFilaments: List<ModelFilament>,
        reportedTrays: List<LoadedTray>,
        compatiblePresets: List<String>,
        overrides: Map<Int, FilamentSlot> = emptyMap(),
        fallbackPreset: String = "",
    ): List<ResolvedFilamentAssignment> {
        if (modelFilaments.isEmpty()) return emptyList()
        val sortedTrays = reportedTrays.sortedWith(compareBy({ it.canvasId }, { it.trayId }))
        val trayBySlot = sortedTrays.associateBy { FilamentSlot(it.canvasId, it.trayId) }
        val pool = availableSlots(sortedTrays)
        val claimedSlots = HashSet<FilamentSlot>()

        // Reserve explicitly overridden slots first so auto-matching avoids colliding with them.
        val chosenSlots = arrayOfNulls<FilamentSlot>(modelFilaments.size)
        for (mf in modelFilaments) {
            val ov = overrides[mf.index]
            if (ov != null && ov in pool) {
                chosenSlots[mf.index] = ov
                claimedSlots.add(ov)
            }
        }

        // Auto-match remaining model filaments: prioritize used filaments first so unused
        // template slots don't steal the best colour matches from active filaments.
        val remainingOrder =
            modelFilaments
                .filter { chosenSlots[it.index] == null }
                .sortedByDescending { it.used }

        for (mf in remainingOrder) {
            val picked: FilamentSlot =
                if (sortedTrays.isNotEmpty()) {
                    pickBestTraySlot(mf, sortedTrays, claimedSlots)
                } else {
                    pool[Math.floorMod(mf.index, pool.size)]
                }
            chosenSlots[mf.index] = picked
            claimedSlots.add(picked)
        }

        return modelFilaments.map { mf ->
            val slot = chosenSlots[mf.index] ?: pool[Math.floorMod(mf.index, pool.size)]
            val tray = trayBySlot[slot]
            val targetType = tray?.type?.takeIf { it.isNotBlank() } ?: mf.type
            val preset =
                matchPreset(compatiblePresets, mf.preset, targetType)
                    ?: fallbackPreset.takeIf { it.isNotBlank() }
                    ?: compatiblePresets.firstOrNull().orEmpty()
            val spoolColor = normalizeHex(tray?.colorHex) ?: mf.colorHex
            ResolvedFilamentAssignment(
                modelIndex = mf.index,
                modelType = mf.type,
                modelColorHex = mf.colorHex,
                used = mf.used,
                slot = slot,
                preset = preset,
                spoolColorHex = spoolColor,
                spoolType = targetType,
                matchedTray = tray,
            )
        }
    }

    private fun pickBestTraySlot(
        mf: ModelFilament,
        trays: List<LoadedTray>,
        claimed: Set<FilamentSlot>,
    ): FilamentSlot {
        val unclaimed = trays.filter { FilamentSlot(it.canvasId, it.trayId) !in claimed }
        val candidates = unclaimed.ifEmpty { trays }
        val best =
            candidates.minByOrNull { tray ->
                val typePenalty = if (typesCompatible(mf.type, tray.type)) 0L else 1_000_000L
                val colorDist = colorDistanceSq(mf.colorHex, tray.colorHex)
                typePenalty + colorDist
            } ?: trays.first()
        return FilamentSlot(best.canvasId, best.trayId)
    }

    internal fun typesCompatible(
        modelType: String,
        trayType: String,
    ): Boolean {
        val a = modelType.trim().uppercase()
        val b = trayType.trim().uppercase()
        if (a.isEmpty() || b.isEmpty()) return true
        if (a == b) return true
        // Treat PLA variants ("PLA+", "PLA Basic", "PLA Matte", "PLA Silk") as compatible with PLA.
        val baseA = a.substringBefore(' ').substringBefore('-').substringBefore('+')
        val baseB = b.substringBefore(' ').substringBefore('-').substringBefore('+')
        return baseA == baseB
    }

    internal fun matchPreset(
        compatible: List<String>,
        preferredPreset: String,
        type: String,
    ): String? {
        if (compatible.isEmpty()) return null
        if (preferredPreset.isNotBlank()) {
            compatible.firstOrNull { it.equals(preferredPreset, ignoreCase = true) }?.let { return it }
        }
        val cleanType = type.trim()
        if (cleanType.isBlank()) return compatible.firstOrNull()
        fun friendly(p: String) = p.removePrefix("Elegoo ").substringBefore(" @").ifBlank { p }
        compatible.firstOrNull { friendly(it).equals(cleanType, ignoreCase = true) }?.let { return it }
        compatible.firstOrNull { friendly(it).contains(cleanType, ignoreCase = true) }?.let { return it }
        val base = cleanType.substringBefore(' ').substringBefore('-').substringBefore('+')
        if (base.isNotBlank() && !base.equals(cleanType, ignoreCase = true)) {
            compatible.firstOrNull { friendly(it).equals(base, ignoreCase = true) }?.let { return it }
            compatible.firstOrNull { friendly(it).contains(base, ignoreCase = true) }?.let { return it }
        }
        return compatible.firstOrNull()
    }

    internal fun colorDistanceSq(
        hexA: String?,
        hexB: String?,
    ): Long {
        val a = parseRgb(hexA) ?: return 200_000L
        val b = parseRgb(hexB) ?: return 200_000L
        val dr = (a[0] - b[0]).toLong()
        val dg = (a[1] - b[1]).toLong()
        val db = (a[2] - b[2]).toLong()
        return dr * dr + dg * dg + db * db
    }

    fun normalizeHex(raw: String?): String? {
        val cleaned = raw?.trim()?.removePrefix("#") ?: return null
        val rgb =
            when (cleaned.length) {
                6 -> cleaned
                8 -> cleaned.substring(2)
                else -> return null
            }
        if (rgb.any { it !in '0'..'9' && it !in 'a'..'f' && it !in 'A'..'F' }) return null
        return "#${rgb.uppercase()}"
    }

    private fun parseRgb(hex: String?): IntArray? {
        val norm = normalizeHex(hex) ?: return null
        val rgb = norm.removePrefix("#")
        return intArrayOf(
            rgb.substring(0, 2).toInt(16),
            rgb.substring(2, 4).toInt(16),
            rgb.substring(4, 6).toInt(16),
        )
    }

    /** Builds the printer `slot_map` entries (`t = 0..N-1`) from the active multi-colour assignments. */
    fun toPrintSlots(assignments: List<ResolvedFilamentAssignment>): List<PrintSlot> =
        assignments.map { a ->
            PrintSlot(t = a.modelIndex, canvasId = a.slot.canvasId, trayId = a.slot.trayId)
        }

    /** Builds multi-colour INI overrides (colours, wipe tower, single-extruder multi-material flag). */
    fun buildIniOverrides(
        assignments: List<ResolvedFilamentAssignment>,
        singleNozzle: Boolean = true,
    ): String {
        if (assignments.isEmpty()) return ""
        val colours = assignments.joinToString(";") { normalizeHex(it.spoolColorHex) ?: "#26A69A" }
        return buildString {
            appendLine("filament_colour=$colours")
            appendLine("filament_multi_colour=$colours")
            if (singleNozzle && assignments.size > 1) {
                appendLine("single_extruder_multi_material=1")
            }
            if (assignments.size > 1) {
                // Use the Orca/Bambu prime-tower key and leave `purge_in_prime_tower` to the
                // printer profile (0 on CC2, which purges via M6211 into the waste chute so the
                // prime tower stays compact instead of swelling to hold full flush volumes).
                appendLine("enable_prime_tower=1")
            }
        }
    }
}
