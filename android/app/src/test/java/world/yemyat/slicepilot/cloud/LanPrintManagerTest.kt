package world.yemyat.slicepilot.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LanPrintManagerTest {

    private val mgr = LanPrintManager()

    @Test
    fun testParseCanvasStatusResponse() {
        val payload = """
        {
            "id": 2005,
            "result": {
                "active_canvas_id": 0,
                "active_tray_id": 1,
                "canvas_list": [
                    {
                        "canvas_id": 0,
                        "tray_list": [
                            {
                                "tray_id": 0,
                                "filament_type": "PLA",
                                "filament_name": "Elegoo PLA White",
                                "filament_color": "#FFFFFF",
                                "status": 1
                            },
                            {
                                "tray_id": 1,
                                "filament_type": "PETG",
                                "filament_name": "Elegoo PETG Red",
                                "filament_color": "E53935",
                                "status": 1
                            }
                        ]
                    }
                ]
            }
        }
        """.trimIndent()

        val info = mgr.parseLanFilamentMessage(payload)
        assertNotNull(info)
        assertTrue(info!!.present)
        assertEquals(2, info.trays.size)
        assertEquals("PETG", info.name) // active tray
        assertEquals("#E53935", info.colorHex)
        assertEquals("PLA", info.trays[0].type)
        assertEquals("#FFFFFF", info.trays[0].colorHex)
        assertEquals("PETG", info.trays[1].type)
        assertEquals("#E53935", info.trays[1].colorHex)
    }

    @Test
    fun testParseMonoFilamentStatusResponse() {
        val payload = """
        {
            "id": 1002,
            "result": {
                "mono_filament_info": {
                    "filament_type": "PLA-CF",
                    "filament_name": "Elegoo PLA-CF Black",
                    "filament_color": "212121"
                }
            }
        }
        """.trimIndent()

        val info = mgr.parseLanFilamentMessage(payload)
        assertNotNull(info)
        assertTrue(info!!.present)
        assertEquals("PLA-CF", info.name)
        assertEquals("#212121", info.colorHex)
        assertEquals(1, info.trays.size)
        assertEquals("PLA-CF", info.trays[0].type)
        assertEquals("#212121", info.trays[0].colorHex)
    }

    @Test
    fun testParseFlatFilamentKeys() {
        val payload = """
        {
            "filament_type": "ABS",
            "filament_color": "#1E88E5"
        }
        """.trimIndent()

        val info = mgr.parseLanFilamentMessage(payload)
        assertNotNull(info)
        assertTrue(info!!.present)
        assertEquals("ABS", info.name)
        assertEquals("#1E88E5", info.colorHex)
    }
}
