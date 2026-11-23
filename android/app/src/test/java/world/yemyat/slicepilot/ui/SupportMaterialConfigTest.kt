package world.yemyat.slicepilot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportMaterialConfigTest {

    private val presets = listOf(
        "Elegoo Centauri Carbon 2 0.4 nozzle",
        "Elegoo Centauri Carbon 2 0.4+0.4 nozzle",
        "Elegoo Centauri Carbon 0.4 nozzle",
        "Elegoo Centauri Carbon 0.4C nozzle",
        "Elegoo Neptune 4 0.4 nozzle",
    )

    @Test
    fun cc2StaysOnSingleNozzleProfileForCanvasSupportMaterial() {
        assertEquals(
            "Elegoo Centauri Carbon 2 0.4 nozzle",
            resolveSupportMachineProfile(
                profile = "Elegoo Centauri Carbon 2 0.4 nozzle",
                model = "Centauri Carbon 2",
                availablePresets = presets,
            ),
        )
        // Persisted legacy "0.4+0.4" profile normalizes back to the single-nozzle profile.
        assertEquals(
            "Elegoo Centauri Carbon 2 0.4 nozzle",
            resolveSupportMachineProfile(
                profile = "Elegoo Centauri Carbon 2 0.4+0.4 nozzle",
                model = "Centauri Carbon 2",
                availablePresets = presets,
            ),
        )
    }

    @Test
    fun cc1WithCanvasUsesSingleNozzleCVariant() {
        assertEquals(
            "Elegoo Centauri Carbon 0.4C nozzle",
            resolveSupportMachineProfile(
                profile = "Elegoo Centauri Carbon 0.4 nozzle",
                model = "Centauri Carbon",
                availablePresets = presets,
            ),
        )
    }

    @Test
    fun singleExtruderWithoutCanvasVariantStaysUnchanged() {
        assertEquals(
            "Elegoo Neptune 4 0.4 nozzle",
            resolveSupportMachineProfile(
                profile = "Elegoo Neptune 4 0.4 nozzle",
                model = "Neptune 4",
                availablePresets = presets,
            ),
        )
    }

    @Test
    fun supportOverridesRespectInterfaceOnlyVsFullSupport() {
        val iface = buildSupportFilamentOverrides(interfaceOnly = true)
        assertTrue(iface.contains("support_filament=0"))
        assertTrue(iface.contains("support_interface_filament=2"))
        assertTrue(iface.contains("wipe_tower=1"))
        assertTrue(iface.contains("purge_in_prime_tower=1"))

        val full = buildSupportFilamentOverrides(interfaceOnly = false)
        assertTrue(full.contains("support_filament=2"))
        assertTrue(full.contains("support_interface_filament=2"))
        assertTrue(full.contains("wipe_tower=1"))
    }
}
