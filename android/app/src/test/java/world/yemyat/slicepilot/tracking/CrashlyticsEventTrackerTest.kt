package world.yemyat.slicepilot.tracking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CrashlyticsEventTrackerTest {
    @Test
    fun distinctErrorCategoriesProduceDistinctExceptionClasses() {
        val printErr = CrashlyticsEventTracker.exceptionFor("print_failed")
        val uploadErr = CrashlyticsEventTracker.exceptionFor("http_upload_failed")
        val loginErr = CrashlyticsEventTracker.exceptionFor("cloud_login_failed")
        val controlErr = CrashlyticsEventTracker.exceptionFor("print_control_failed")
        val pairErr = CrashlyticsEventTracker.exceptionFor("printer_pair_failed")
        val sliceErr = CrashlyticsEventTracker.exceptionFor("slice_failed")
        val lanErr = CrashlyticsEventTracker.exceptionFor("lan_cc1_start_print_failed")
        val camErr = CrashlyticsEventTracker.exceptionFor("camera_start_failed")
        val dlErr = CrashlyticsEventTracker.exceptionFor("model_download_failed")
        val otherErr = CrashlyticsEventTracker.exceptionFor("unknown_error")

        val classes =
            listOf(printErr, uploadErr, loginErr, controlErr, pairErr, sliceErr, lanErr, camErr, dlErr, otherErr)
                .map { it.javaClass }
        assertEquals(10, classes.toSet().size)
        assertEquals("print_failed", printErr.message)
        assertNotEquals(printErr.javaClass, loginErr.javaClass)
    }
}
