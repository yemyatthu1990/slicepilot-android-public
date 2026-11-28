package world.yemyat.slicepilot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdhesionOverridesTest {

    private fun parseIni(ini: String): Map<String, String> =
        ini
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate { line ->
                val idx = line.indexOf('=')
                line.substring(0, idx) to line.substring(idx + 1)
            }

    @Test
    fun noneDisablesAutoBrimSkirtAndRaft() {
        val overrides = parseIni(buildAdhesionOverrides(adhesion = "none", brimWidth = 8))
        assertEquals("no_brim", overrides["brim_type"])
        assertEquals("0", overrides["brim_width"])
        assertEquals("0", overrides["raft_layers"])
        assertEquals("0", overrides["skirt_loops"])
        assertEquals("0", overrides["skirt_height"])
    }

    @Test
    fun skirtDisablesAutoBrimAndEnablesSkirtLoops() {
        val overrides = parseIni(buildAdhesionOverrides(adhesion = "skirt", brimWidth = 5))
        assertEquals("no_brim", overrides["brim_type"])
        assertEquals("0", overrides["brim_width"])
        assertEquals("0", overrides["raft_layers"])
        assertEquals("2", overrides["skirt_loops"])
        assertEquals("2", overrides["skirt_height"])
    }

    @Test
    fun brimSetsOuterOnlyAndWidth() {
        val defaultWidth = parseIni(buildAdhesionOverrides(adhesion = "brim", brimWidth = null))
        assertEquals("outer_only", defaultWidth["brim_type"])
        assertEquals("5", defaultWidth["brim_width"])
        assertEquals("0", defaultWidth["raft_layers"])
        assertEquals("0", defaultWidth["skirt_loops"])
        assertEquals("0", defaultWidth["skirt_height"])

        val customWidth = parseIni(buildAdhesionOverrides(adhesion = "brim", brimWidth = 8))
        assertEquals("outer_only", customWidth["brim_type"])
        assertEquals("8", customWidth["brim_width"])
    }

    @Test
    fun raftDisablesAutoBrimAndSkirt() {
        val overrides = parseIni(buildAdhesionOverrides(adhesion = "raft", brimWidth = 5))
        assertEquals("no_brim", overrides["brim_type"])
        assertEquals("0", overrides["brim_width"])
        assertEquals("2", overrides["raft_layers"])
        assertEquals("0", overrides["skirt_loops"])
        assertEquals("0", overrides["skirt_height"])
    }

    @Test
    fun autoLeavesPresetAndProjectDefaultsUntouched() {
        assertTrue(buildAdhesionOverrides(adhesion = "auto", brimWidth = 5).isEmpty())
    }
}
