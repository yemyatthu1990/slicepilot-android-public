package world.yemyat.slicepilot.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import world.yemyat.slicepilot.ui.Screen
import world.yemyat.slicepilot.ui.analyticsName

class TrackingBootstrapTest {
    @Test
    fun analyticsDisabledForDebugAndTestLab() {
        assertTrue(TrackingBootstrap.shouldEnableAnalytics(isDebug = false, isTestLab = false))
        assertFalse(TrackingBootstrap.shouldEnableAnalytics(isDebug = true, isTestLab = false))
        assertFalse(TrackingBootstrap.shouldEnableAnalytics(isDebug = false, isTestLab = true))
        assertFalse(TrackingBootstrap.shouldEnableAnalytics(isDebug = true, isTestLab = true))
    }

    @Test
    fun testLabSettingDetectionIsCaseInsensitive() {
        assertTrue(TrackingBootstrap.isTestLabDevice("true"))
        assertTrue(TrackingBootstrap.isTestLabDevice("TRUE"))
        assertFalse(TrackingBootstrap.isTestLabDevice("false"))
        assertFalse(TrackingBootstrap.isTestLabDevice(null))
        assertFalse(TrackingBootstrap.isTestLabDevice(""))
    }

    @Test
    fun everyScreenHasDistinctStableAnalyticsName() {
        val screens =
            listOf(
                Screen.Home,
                Screen.Library,
                Screen.Printer,
                Screen.PrintSetup,
                Screen.Slice,
                Screen.Slicing,
                Screen.Preview,
                Screen.Printing,
                Screen.DeviceStatus,
                Screen.CameraFull,
            )
        val names = screens.map { it.analyticsName }
        assertTrue(names.all { it.isNotBlank() })
        assertEquals(screens.size, names.toSet().size)
    }

    @Test
    fun trackScreenEmitsStandardScreenViewEvent() {
        val captured = mutableListOf<TrackingEvent>()
        val recorder =
            object : EventTracker {
                override val id: String = "test-screen-recorder"

                override fun trackEvent(event: TrackingEvent) {
                    captured.add(event)
                }

                override fun trackError(error: TrackingError) = Unit
            }
        EventNotifier.registerTracker(recorder)
        try {
            EventNotifier.trackScreen(Screen.PrintSetup.analyticsName)
            assertEquals(1, captured.size)
            assertEquals("screen_view", captured[0].name)
            assertEquals("PrintSetup", captured[0].properties["screen_name"])
            assertEquals("PrintSetup", captured[0].properties["screen_class"])
        } finally {
            EventNotifier.unregisterTracker(recorder.id)
        }
    }
}
