package world.yemyat.slicepilot.repo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScadCustomizerTest {
    private val sampleKeychainScad =
        """
        /* [Text Settings] */
        // Name printed on the keychain
        keychain_text = "Samantha";
        font_name = "Lobster"; //font
        text_color = "#FF4500"; //color
        letter_spacing = 1.00; // [0.75:0.01:1.30]

        /* [Keychain Shape] */
        style = "pill"; // [pill:Rounded Pill, rect:Rectangle, heart:Heart]
        hole_side = 0; // [0:Left, 1:Right, 2:None]
        thickness = 3.2;
        ring_at_text_end = false;
        border_points = [10, 20, 30];

        /* [Hidden] */
        ${'$'}fn = 64;
        secret_internal = "do_not_show";

        module make_keychain() {
            inside_var = "ignored";
            cube([10, 10, 2]);
        }
        """.trimIndent()

    @Test
    fun parseExtractsAllSupportedTypesAndSkipsHiddenAndModules() {
        val params = ScadCustomizer.parse(sampleKeychainScad)
        val names = params.map { it.name }

        assertEquals(
            listOf(
                "keychain_text",
                "font_name",
                "text_color",
                "letter_spacing",
                "style",
                "hole_side",
                "thickness",
                "ring_at_text_end",
                "border_points",
            ),
            names,
        )

        val text = params[0] as ScadCustomizer.Param.Text
        assertEquals("Text Settings", text.section)
        assertTrue(text.isQuoted)
        assertFalse(text.isFont)
        assertFalse(text.isColor)
        assertEquals("Samantha", ScadCustomizer.displayValue(text))

        val font = params[1] as ScadCustomizer.Param.Text
        assertTrue(font.isFont)

        val color = params[2] as ScadCustomizer.Param.Text
        assertTrue(color.isColor)
        assertFalse(color.isFont)

        // Custom font fields named *font* without //font comment are also detected as font pickers.
        val namedFontParams =
            ScadCustomizer.parse(
                """
                custom_font_name = "Pacifico";
                font_color = "#FFFFFF";
                font_size = 20;
                """.trimIndent(),
            )
        assertTrue((namedFontParams[0] as ScadCustomizer.Param.Text).isFont)
        assertFalse((namedFontParams[1] as ScadCustomizer.Param.Text).isFont)
        assertTrue((namedFontParams[1] as ScadCustomizer.Param.Text).isColor)
        assertTrue(namedFontParams[2] is ScadCustomizer.Param.Number)

        val slider = params[3] as ScadCustomizer.Param.Slider
        assertEquals(0.75, slider.min, 1e-6)
        assertEquals(1.30, slider.max, 1e-6)
        assertEquals(0.01, slider.step, 1e-6)
        assertFalse(slider.isInt)

        val style = params[4] as ScadCustomizer.Param.Choice
        assertEquals("Keychain Shape", style.section)
        assertTrue(style.isQuoted)
        assertEquals(3, style.options.size)
        assertEquals("\"pill\"", style.options[0].value)
        assertEquals("Rounded Pill", style.options[0].label)

        val holeSide = params[5] as ScadCustomizer.Param.Choice
        assertFalse(holeSide.isQuoted)
        assertEquals("1", holeSide.options[1].value)
        assertEquals("Right", holeSide.options[1].label)

        val thickness = params[6] as ScadCustomizer.Param.Number
        assertFalse(thickness.isInt)
        assertEquals("3.2", thickness.value)

        val bool = params[7] as ScadCustomizer.Param.Bool
        assertFalse(bool.on)

        assertTrue(params[8] is ScadCustomizer.Param.Raw)
    }

    @Test
    fun applyUpdatesValuesSafelyWithQuoteEscapingAndPreservesRest() {
        val params = ScadCustomizer.parse(sampleKeychainScad)
        params[0].value = ScadCustomizer.quoteScadString("Alex \"Pro\"")
        params[3].value = "1.15"
        params[4].value = "\"heart\""
        (params[7] as ScadCustomizer.Param.Bool).on = true

        val updated = ScadCustomizer.apply(sampleKeychainScad, params)

        assertTrue(updated.contains("""keychain_text = "Alex \"Pro\"";"""))
        assertTrue(updated.contains("letter_spacing = 1.15; // [0.75:0.01:1.30]"))
        assertTrue(updated.contains("""style = "heart"; // [pill:Rounded Pill, rect:Rectangle, heart:Heart]"""))
        assertTrue(updated.contains("ring_at_text_end = true;"))
        // Hidden & module code stay intact.
        assertTrue(updated.contains("""secret_internal = "do_not_show";"""))
        assertTrue(updated.contains("""inside_var = "ignored";"""))

        // Round-trip display value unescapes cleanly.
        val reparsed = ScadCustomizer.parse(updated)
        assertEquals("Alex \"Pro\"", ScadCustomizer.displayValue(reparsed[0]))
    }

    @Test
    fun labelsPreferPrecedingCommentAndFallbackToHumanizedName() {
        assertEquals("Name printed on the keychain", ScadCustomizer.label(sampleKeychainScad, "keychain_text"))
        assertEquals("Letter spacing", ScadCustomizer.label(sampleKeychainScad, "letter_spacing"))
        assertEquals("Font size", ScadCustomizer.humanizeName("fontSize"))
    }

    @Test
    fun formatSliderValueAvoidsFloatDrift() {
        assertEquals("1.15", ScadCustomizer.formatSliderValue(1.1500000000000001, 0.01, false))
        assertEquals("10", ScadCustomizer.formatSliderValue(9.8, 1.0, true))
        assertEquals("0.5", ScadCustomizer.formatSliderValue(0.5000001, 0.1, false))
    }
}
