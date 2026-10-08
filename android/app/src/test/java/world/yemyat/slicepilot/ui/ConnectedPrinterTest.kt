package world.yemyat.slicepilot.ui

import world.yemyat.slicepilot.cloud.CloudPrinter
import world.yemyat.slicepilot.cloud.LanPrintManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectedPrinterTest {

    @Test
    fun testLanPrinterToConnectedPrinter() {
        val lan = LanPrintManager.DiscoveredPrinter(
            ip = "192.168.1.120",
            serial = "CC2-SN-00123",
            model = "Centauri Carbon 2",
            name = "My CC2 Printer",
        )
        val connected = lan.toConnectedPrinter()

        assertEquals("CC2-SN-00123", connected.id)
        assertEquals("My CC2 Printer", connected.name)
        assertEquals("Centauri Carbon 2", connected.model)
        assertEquals("192.168.1.120", connected.ip)
        assertEquals("CC2-SN-00123", connected.serial)
        assertTrue(connected.isLocal)
        assertTrue(connected.online)
        assertEquals(lan, connected.lanPrinter)
    }

    @Test
    fun testLanPrinterFallbackNameAndModel() {
        val lan = LanPrintManager.DiscoveredPrinter(
            ip = "192.168.1.50",
            serial = "",
            model = "",
            name = "",
        )
        val connected = lan.toConnectedPrinter()

        assertEquals("192.168.1.50", connected.id)
        assertEquals("Centauri Carbon 2", connected.name)
        assertEquals("Centauri Carbon 2", connected.model)
        assertTrue(connected.isLocal)
    }

    @Test
    fun testCloudPrinterToConnectedPrinter() {
        val cloud = CloudPrinter(
            id = "printer-cloud-99",
            name = "Living Room Neptune",
            model = "Neptune 4 Pro",
            online = true,
        )
        val connected = cloud.toConnectedPrinter()

        assertEquals("printer-cloud-99", connected.id)
        assertEquals("Living Room Neptune", connected.name)
        assertEquals("Neptune 4 Pro", connected.model)
        assertFalse(connected.isLocal)
        assertTrue(connected.online)
        assertEquals(cloud, connected.cloudPrinter)
    }

    @Test
    fun testParseJsonStringArray() {
        val json = """["Elegoo Centauri Carbon 2 0.4 nozzle", "Elegoo Neptune 4 Pro 0.4 nozzle"]"""
        val parsed = parseJsonStringArray(json)
        assertEquals(2, parsed.size)
        assertEquals("Elegoo Centauri Carbon 2 0.4 nozzle", parsed[0])
        assertEquals("Elegoo Neptune 4 Pro 0.4 nozzle", parsed[1])
    }
}
