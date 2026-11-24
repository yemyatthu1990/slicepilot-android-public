package world.yemyat.slicepilot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import world.yemyat.slicepilot.cloud.LoadedTray
import world.yemyat.slicepilot.cloud.PrintSlot

class MultiColourMappingTest {
    @Test
    fun `parseInspectJson detects genuine multi-colour 3MF`() {
        val json =
            """{"multi":true,"filaments":[
                {"type":"PLA","color":"#FF0000","preset":"Bambu PLA Basic"},
                {"type":"PLA","color":"#0000FF","preset":"Bambu PLA Basic"}
            ]}"""
        val info = MultiColourMapping.parseInspectJson(json, intArrayOf(1, 1, 2, 2))
        assertNotNull(info)
        assertTrue(info!!.isMultiColour)
        assertEquals(2, info.filaments.size)
        assertEquals("#FF0000", info.filaments[0].colorHex)
        assertEquals("#0000FF", info.filaments[1].colorHex)
        assertTrue(info.filaments[0].used)
        assertTrue(info.filaments[1].used)
    }

    @Test
    fun `parseInspectJson ignores unused template slots from 4-slot AMS Bambu export`() {
        val json =
            """{"multi":true,"filaments":[
                {"type":"PLA","color":"#FFFFFF"},
                {"type":"PLA","color":"#000000"},
                {"type":"PLA","color":"#FF0000"},
                {"type":"PLA","color":"#00FF00"}
            ]}"""
        // Every triangle uses extruder 1: this is a single-colour model exported with 4 AMS defaults.
        val info = MultiColourMapping.parseInspectJson(json, intArrayOf(1, 1, 1, 1))
        assertNotNull(info)
        assertFalse(info!!.isMultiColour)
        assertEquals(1, info.usedFilaments.size)
    }

    @Test
    fun `parseInspectJson treats single non-primary extruder as multi-colour`() {
        val json =
            """{"multi":true,"filaments":[
                {"type":"PETG","color":"#FFFFFF"},
                {"type":"PETG","color":"#FF8800"}
            ]}"""
        val info = MultiColourMapping.parseInspectJson(json, intArrayOf(2, 2, 2))
        assertNotNull(info)
        assertTrue(info!!.isMultiColour)
        assertFalse(info.filaments[0].used)
        assertTrue(info.filaments[1].used)
    }

    @Test
    fun `parseInspectJson returns null on native error`() {
        assertNull(MultiColourMapping.parseInspectJson("""{"error":"cannot open"}"""))
        assertNull(MultiColourMapping.parseInspectJson(""))
    }

    @Test
    fun `autoMatch maps model colours to closest AMS trays by material and colour`() {
        val model =
            listOf(
                ModelFilament(0, "PLA", "#1122EE", used = true), // blue PLA
                ModelFilament(1, "PLA", "#EE1111", used = true), // red PLA
                ModelFilament(2, "PETG", "#F8F8F8", used = true), // white PETG
            )
        val trays =
            listOf(
                LoadedTray(trayId = 0, name = "Red PLA", type = "PLA", colorHex = "#E53935", canvasId = 0),
                LoadedTray(trayId = 1, name = "White PETG", type = "PETG", colorHex = "#FAFAFA", canvasId = 0),
                LoadedTray(trayId = 2, name = "Blue PLA", type = "PLA", colorHex = "#1E88E5", canvasId = 0),
                LoadedTray(trayId = 3, name = "Black PLA", type = "PLA", colorHex = "#212121", canvasId = 0),
            )
        val presets = listOf("Elegoo PLA @ECC2", "Elegoo PETG @ECC2")

        val assigned = MultiColourMapping.resolveAssignments(model, trays, presets)
        assertEquals(3, assigned.size)
        // Blue PLA -> tray 2
        assertEquals(FilamentSlot(0, 2), assigned[0].slot)
        assertEquals("Elegoo PLA @ECC2", assigned[0].preset)
        // Red PLA -> tray 0
        assertEquals(FilamentSlot(0, 0), assigned[1].slot)
        // White PETG -> tray 1
        assertEquals(FilamentSlot(0, 1), assigned[2].slot)
        assertEquals("Elegoo PETG @ECC2", assigned[2].preset)
    }

    @Test
    fun `manual overrides take precedence over auto-matching`() {
        val model =
            listOf(
                ModelFilament(0, "PLA", "#FF0000"),
                ModelFilament(1, "PLA", "#0000FF"),
            )
        val trays =
            listOf(
                LoadedTray(0, "Red", "PLA", "#FF0000", 0),
                LoadedTray(1, "Blue", "PLA", "#0000FF", 0),
                LoadedTray(2, "Green", "PLA", "#00FF00", 0),
            )
        val assigned =
            MultiColourMapping.resolveAssignments(
                modelFilaments = model,
                reportedTrays = trays,
                compatiblePresets = listOf("Elegoo PLA @ECC2"),
                overrides = mapOf(0 to FilamentSlot(0, 2)),
            )
        assertEquals(FilamentSlot(0, 2), assigned[0].slot)
        assertEquals("#00FF00", assigned[0].spoolColorHex)
        assertEquals(FilamentSlot(0, 1), assigned[1].slot)
    }

    @Test
    fun `five-colour model wraps safely across 4 fallback AMS slots`() {
        val model = (0 until 5).map { i -> ModelFilament(i, "PLA", "#112233") }
        val assigned =
            MultiColourMapping.resolveAssignments(
                modelFilaments = model,
                reportedTrays = emptyList(),
                compatiblePresets = listOf("Elegoo PLA @ECC2"),
            )
        assertEquals(5, assigned.size)
        assertEquals(listOf(0, 1, 2, 3, 0), assigned.map { it.slot.trayId })
        assertEquals(
            listOf(
                PrintSlot(0, 0, 0),
                PrintSlot(1, 0, 1),
                PrintSlot(2, 0, 2),
                PrintSlot(3, 0, 3),
                PrintSlot(4, 0, 0),
            ),
            MultiColourMapping.toPrintSlots(assigned),
        )
    }

    @Test
    fun `encode and decode preserve canvas and tray and accept legacy format`() {
        val slots = listOf(FilamentSlot(0, 2), FilamentSlot(1, 0))
        val encoded = MultiColourMapping.encodeSlots(slots)
        assertEquals("0:2,1:0", encoded)
        assertEquals(slots, MultiColourMapping.decodeSlots(encoded))
        assertEquals(listOf(FilamentSlot(0, 1), FilamentSlot(0, 3)), MultiColourMapping.decodeSlots("1,3"))
    }

    @Test
    fun `buildIniOverrides emits semicolon colours, single-extruder multi-material, and wipe tower`() {
        val assignments =
            listOf(
                ResolvedFilamentAssignment(0, "PLA", "#FF0000", true, FilamentSlot(0, 0), "Elegoo PLA @ECC2", "#E53935", "PLA", null),
                ResolvedFilamentAssignment(1, "PLA", "#0000FF", true, FilamentSlot(0, 1), "Elegoo PLA @ECC2", "#1E88E5", "PLA", null),
            )
        val ini = MultiColourMapping.buildIniOverrides(assignments, singleNozzle = true)
        assertTrue(ini.contains("filament_colour=#E53935;#1E88E5"))
        assertTrue(ini.contains("filament_multi_colour=#E53935;#1E88E5"))
        assertTrue(ini.contains("single_extruder_multi_material=1"))
        assertTrue(ini.contains("enable_prime_tower=1"))
        assertFalse(ini.contains("purge_in_prime_tower=1"))
    }
}
