package world.yemyat.slicepilot.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveLayoutTest {
    @Test
    fun widthClassesFollowMaterial3Breakpoints() {
        assertEquals(WindowWidthClass.Compact, windowWidthClassFor(360))
        assertEquals(WindowWidthClass.Compact, windowWidthClassFor(599))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(600))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(800))
        assertEquals(WindowWidthClass.Medium, windowWidthClassFor(839))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(840))
        assertEquals(WindowWidthClass.Expanded, windowWidthClassFor(1280))
    }

    @Test
    fun compactSpecPreservesPhoneLayout() {
        val spec = feedLayoutSpecFor(412)
        assertEquals(WindowWidthClass.Compact, spec.widthClass)
        assertEquals(2, spec.columns)
        assertEquals(4, spec.previewLimitAll)
        assertEquals(8, spec.previewLimitSingle)
        assertEquals(20, spec.horizontalPaddingDp)
        assertEquals(12, spec.cardSpacingDp)
        assertFalse(spec.useNavigationRail)
        assertFalse(spec.inlineSearchAndChips)
    }

    @Test
    fun mediumSpecUsesThreeColumnsAndNavigationRail() {
        // Pixel Tablet portrait: 800dp window minus ~80dp rail = 720dp pane
        val spec = feedLayoutSpecFor(720)
        assertEquals(WindowWidthClass.Medium, spec.widthClass)
        assertEquals(3, spec.columns)
        assertEquals(6, spec.previewLimitAll)
        assertEquals(9, spec.previewLimitSingle)
        assertEquals(24, spec.horizontalPaddingDp)
        assertTrue(spec.useNavigationRail)
        assertFalse(spec.inlineSearchAndChips)
    }

    @Test
    fun expandedSpecUsesFourColumnsAndInlineSearchToolbar() {
        // Pixel Tablet landscape: 1280dp window minus ~80dp rail = 1200dp pane
        val spec = feedLayoutSpecFor(1200)
        assertEquals(WindowWidthClass.Expanded, spec.widthClass)
        assertEquals(4, spec.columns)
        assertEquals(8, spec.previewLimitAll)
        assertEquals(12, spec.previewLimitSingle)
        assertEquals(32, spec.horizontalPaddingDp)
        assertEquals(16, spec.cardSpacingDp)
        assertTrue(spec.useNavigationRail)
        assertTrue(spec.inlineSearchAndChips)
    }

    @Test
    fun navigationRailSwitchesAt600Dp() {
        assertFalse(shouldUseNavigationRail(599))
        assertTrue(shouldUseNavigationRail(600))
        assertTrue(shouldUseNavigationRail(1280))
    }

    @Test
    fun twoPaneAndLibraryHelpersFollowBreakpoints() {
        assertFalse(shouldUseTwoPane(599))
        assertFalse(shouldUseTwoPane(839))
        assertTrue(shouldUseTwoPane(840))
        assertTrue(shouldUseTwoPane(1200))

        assertEquals(1, libraryListColumnsFor(412))
        assertEquals(2, libraryListColumnsFor(600))
        assertEquals(2, libraryListColumnsFor(1200))

        assertEquals(2, photoGridColumnsFor(412))
        assertEquals(3, photoGridColumnsFor(720))
        assertEquals(4, photoGridColumnsFor(1200))
    }
}
