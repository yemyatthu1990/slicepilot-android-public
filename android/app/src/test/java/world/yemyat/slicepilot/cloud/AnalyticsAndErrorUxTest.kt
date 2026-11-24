package world.yemyat.slicepilot.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import world.yemyat.slicepilot.repo.modelProviderIdFor

class AnalyticsAndErrorUxTest {
    @Test
    fun friendlyLanErrorMapsTooManyClientsAndTimeouts() {
        val clientsMsg = LanPrintManager.friendlyLanError("192.168.1.50", "Printer registration failed: too many clients")
        assertTrue(clientsMsg.contains("max LAN connections"))
        assertTrue(clientsMsg.contains("ElegooSlicer"))

        val timeoutMsg =
            LanPrintManager.friendlyLanError(
                "192.168.1.50",
                "failed to connect to /192.168.1.50 (port 80) from /192.168.1.12 (port 42486) after 15000ms",
            )
        assertTrue(timeoutMsg.contains("192.168.1.50"))
        assertTrue(timeoutMsg.contains("same network"))

        val passthrough = LanPrintManager.friendlyLanError("192.168.1.50", "Printer rejected start (error 42)")
        assertEquals("Printer rejected start (error 42)", passthrough)
    }

    @Test
    fun modelProviderIdNormalizesUrlsAndSchemes() {
        assertEquals("makerworld", modelProviderIdFor("makerworld://instance/12345"))
        assertEquals("makerworld", modelProviderIdFor("https://cdn.bblmw.com/abc.3mf"))
        assertEquals("printables", modelProviderIdFor("printables://file/1/2/3"))
        assertEquals("nexprint", modelProviderIdFor("nexprint://file/9/10"))
        assertEquals("thingiverse", modelProviderIdFor("https://cdn.thingiverse.com/assets/foo.stl"))
        assertEquals("printables", modelProviderIdFor("https://example.com/file.3mf", "Printables"))
        assertEquals("unknown", modelProviderIdFor("https://example.com/file.3mf", null))
    }
}
