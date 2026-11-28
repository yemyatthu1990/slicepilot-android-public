package world.yemyat.slicepilot.cloud

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class LanPrintMonitorMoonrakerTest {

    private val cosmosObjects = listOf(
        "print_stats",
        "virtual_sdcard",
        "display_status",
        "extruder",
        "heater_bed",
        "fan",
        "fan_generic aux_fan",
        "fan_generic case_fan",
        "led case",
        "temperature_sensor chamber",
        "gcode_move",
        "exclude_object",
        "canvas_filament_sensor",
        "servo case_servo",
    )

    @Test
    fun discoverHardwareMappingMatchesCosmosDefaults() {
        val hw = LanPrintMonitorMoonraker.discoverHardwareMapping(cosmosObjects)
        assertEquals("fan_generic aux_fan", hw.auxFanObject)
        assertEquals("fan_generic case_fan", hw.boxFanObject)
        assertEquals("led case", hw.lightObject)
        assertEquals("temperature_sensor chamber", hw.chamberSensorObject)
        assertTrue(hw.hasExcludeObject)
    }

    @Test
    fun discoverHardwareMappingMatchesGenericKlipper() {
        val hw = LanPrintMonitorMoonraker.discoverHardwareMapping(
            listOf(
                "extruder",
                "heater_bed",
                "fan",
                "fan_generic exhaust_fan",
                "output_pin caselight",
                "temperature_sensor enclosure",
            ),
        )
        assertNull(hw.auxFanObject)
        assertEquals("fan_generic exhaust_fan", hw.boxFanObject)
        assertEquals("output_pin caselight", hw.lightObject)
        assertEquals("temperature_sensor enclosure", hw.chamberSensorObject)
        assertFalse(hw.hasExcludeObject)
    }

    @Test
    fun parseFullCosmosStatusWithExcludeObjects() {
        val hw = LanPrintMonitorMoonraker.discoverHardwareMapping(cosmosObjects)
        val payload = JSONObject(
            """
            {
                "print_stats": {
                    "state": "printing",
                    "filename": "multi_part.gcode",
                    "print_duration": 1200.5,
                    "info": { "current_layer": 42, "total_layer": 200 }
                },
                "virtual_sdcard": { "progress": 0.25 },
                "extruder": { "temperature": 219.8, "target": 220.0 },
                "heater_bed": { "temperature": 60.1, "target": 60.0 },
                "fan": { "speed": 1.0 },
                "fan_generic aux_fan": { "speed": 0.5 },
                "fan_generic case_fan": { "speed": 0.2 },
                "led case": { "color_data": [[0.0, 0.0, 0.0, 1.0]] },
                "temperature_sensor chamber": { "temperature": 36.4 },
                "gcode_move": { "speed_factor": 1.5 },
                "exclude_object": {
                    "objects": [
                        {
                            "name": "BRACKET_LEFT",
                            "center": [80.5, 110.0],
                            "polygon": [[60.0, 90.0], [101.0, 90.0], [101.0, 130.0], [60.0, 130.0]]
                        },
                        {
                            "name": "BRACKET_RIGHT",
                            "center": [170.0, 110.0],
                            "polygon": [[150.0, 90.0], [190.0, 90.0], [190.0, 130.0], [150.0, 130.0]]
                        }
                    ],
                    "excluded_objects": [],
                    "current_object": "BRACKET_LEFT"
                }
            }
            """.trimIndent(),
        )

        val st = LanPrintMonitorMoonraker.mergeMoonrakerStatus(
            base = PrintStatus(),
            statusObj = payload,
            hw = hw,
            metadataDurationSec = 4800,
        )

        assertEquals(PrintJobState.PRINTING, st.state)
        assertEquals("multi_part.gcode", st.filename)
        assertEquals(25, st.progress)
        assertEquals(42, st.currentLayer)
        assertEquals(200, st.totalLayer)
        assertEquals(1200, st.elapsedSec)
        assertEquals(3600, st.remainingSec)
        assertEquals(219.8f, st.nozzleTemp, 0.01f)
        assertEquals(220f, st.nozzleTarget, 0.01f)
        assertEquals(60.1f, st.bedTemp, 0.01f)
        assertEquals(60f, st.bedTarget, 0.01f)
        assertEquals(255, st.partFanSpeed)
        assertEquals(127, st.auxFanSpeed)
        assertEquals(51, st.boxFanSpeed)
        assertEquals(true, st.lightOn)
        assertEquals(36.4f, st.chamberTemp, 0.01f)
        assertEquals(2, st.speedMode)
        assertEquals(2, st.objects.size)
        assertEquals("BRACKET_LEFT", st.objects[0].name)
        assertEquals(4, st.objects[0].polygon.size)
        assertEquals("BRACKET_LEFT", st.currentObject)
        assertTrue(st.excludedObjects.isEmpty())
        assertTrue(st.canExcludeObjects)
        assertTrue(st.live)
    }

    @Test
    fun partialExcludeObjectDeltaPreservesObjectList() {
        val hw = LanPrintMonitorMoonraker.discoverHardwareMapping(cosmosObjects)
        val initial = LanPrintMonitorMoonraker.mergeMoonrakerStatus(
            base = PrintStatus(),
            statusObj = JSONObject(
                """
                {
                    "print_stats": { "state": "printing", "filename": "plate.gcode" },
                    "exclude_object": {
                        "objects": [
                            { "name": "PART_A", "center": [50, 50] },
                            { "name": "PART_B", "center": [150, 50] }
                        ],
                        "excluded_objects": [],
                        "current_object": "PART_A"
                    }
                }
                """.trimIndent(),
            ),
            hw = hw,
        )

        // Moonraker delta after EXCLUDE_OBJECT NAME=PART_A only sends changed fields.
        val delta = JSONObject(
            """
            {
                "exclude_object": {
                    "excluded_objects": ["PART_A"],
                    "current_object": null
                }
            }
            """.trimIndent(),
        )
        val updated = LanPrintMonitorMoonraker.mergeMoonrakerStatus(initial, delta, hw)

        assertEquals(2, updated.objects.size)
        assertEquals(setOf("PART_A"), updated.excludedObjects)
        assertNull(updated.currentObject)
        assertTrue(updated.canExcludeObjects)
    }

    @Test
    fun parseHostAndPortHandlesCommonFormats() {
        assertEquals("192.168.1.42" to null, LanPrintMonitorMoonraker.parseHostAndPort("192.168.1.42"))
        assertEquals("192.168.1.42" to 7125, LanPrintMonitorMoonraker.parseHostAndPort("192.168.1.42:7125"))
        assertEquals("cosmos.local" to 80, LanPrintMonitorMoonraker.parseHostAndPort("http://cosmos.local:80/"))
        assertEquals("cosmos.local" to null, LanPrintMonitorMoonraker.parseHostAndPort("  https://cosmos.local/index.html "))
        assertEquals("" to null, LanPrintMonitorMoonraker.parseHostAndPort("   "))
    }

    @Test
    fun resolveWebcamUrlHandlesCosmosRelativePortSyntax() {
        assertEquals(
            "http://192.168.1.42:8080/stream",
            LanPrintMonitorMoonraker.resolveWebcamUrl("192.168.1.42", 80, ":8080/stream"),
        )
        assertEquals(
            "http://192.168.1.42/webcam/?action=stream",
            LanPrintMonitorMoonraker.resolveWebcamUrl("192.168.1.42", 80, "/webcam/?action=stream"),
        )
        assertEquals(
            "http://192.168.1.42:7125/webcam/?action=stream",
            LanPrintMonitorMoonraker.resolveWebcamUrl("192.168.1.42", 7125, "/webcam/?action=stream"),
        )
        assertEquals(
            "http://10.0.0.5:8081/stream",
            LanPrintMonitorMoonraker.resolveWebcamUrl("192.168.1.42", 80, "http://10.0.0.5:8081/stream"),
        )
    }

    @Test
    fun buildExcludeObjectGcodeSanitizesAndQuotes() {
        assertEquals(
            "EXCLUDE_OBJECT NAME=PART_1",
            LanPrintMonitorMoonraker.buildExcludeObjectGcode("PART_1"),
        )
        assertEquals(
            "EXCLUDE_OBJECT NAME=\"Left Bracket\"",
            LanPrintMonitorMoonraker.buildExcludeObjectGcode("Left Bracket"),
        )
        assertNull(LanPrintMonitorMoonraker.buildExcludeObjectGcode(""))
        assertNull(LanPrintMonitorMoonraker.buildExcludeObjectGcode("PART_1\nG28"))
        assertNull(LanPrintMonitorMoonraker.buildExcludeObjectGcode("PART_1;M112"))
    }

    @Test
    fun buildMoonrakerPrinterIdentifiesCosmosCc2AndGenericKlipper() {
        val cc2 = LanPrintManager.buildMoonrakerPrinter(
            host = "192.168.1.50",
            port = 80,
            serverInfoResult = JSONObject("""{"klippy_state":"ready","components":["swu_deploy","history"]}"""),
            printerInfoResult = JSONObject("""{"hostname":"cosmos-cc2"}"""),
            objectsListResult = JSONObject("""{"objects":["exclude_object","servo case_servo"]}"""),
        )
        assertEquals(PrinterProtocol.MOONRAKER, cc2.protocol)
        assertEquals(80, cc2.port)
        assertEquals("Centauri Carbon 2 (COSMOS)", cc2.model)
        assertEquals("cosmos-cc2", cc2.name)
        assertTrue(cc2.lanMode)

        val generic = LanPrintManager.buildMoonrakerPrinter(
            host = "192.168.1.60",
            port = 7125,
            serverInfoResult = JSONObject("""{"klippy_state":"ready","components":["history"]}"""),
            printerInfoResult = JSONObject("""{"hostname":"voron24"}"""),
            objectsListResult = JSONObject("""{"objects":["exclude_object","fan"]}"""),
        )
        assertEquals("Klipper (Moonraker)", generic.model)
        assertEquals("voron24", generic.name)
        assertEquals(7125, generic.port)
    }

    @Test
    fun printerProtocolFromStringMigratesLegacyPrefs() {
        assertEquals(PrinterProtocol.MOONRAKER, PrinterProtocol.fromString("MOONRAKER", "Klipper"))
        assertEquals(PrinterProtocol.CC1_SDCP, PrinterProtocol.fromString(null, "Centauri Carbon"))
        assertEquals(PrinterProtocol.CC2_MQTT, PrinterProtocol.fromString(null, "Centauri Carbon 2"))
    }

    /** Minimal pure-Java HTTP/1.1 loopback server (no jdk.httpserver module required). */
    private class TinyHttpServer(
        private val handler: (method: String, path: String, headers: Map<String, String>, body: ByteArray) -> Pair<Int, String>,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (running.get()) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        client.use { s ->
                            val input = BufferedInputStream(s.getInputStream())
                            val hdrBuf = ByteArrayOutputStream()
                            var match = 0
                            while (true) {
                                val b = input.read()
                                if (b < 0) return@use
                                hdrBuf.write(b)
                                match = when {
                                    match == 0 && b == '\r'.code -> 1
                                    match == 1 && b == '\n'.code -> 2
                                    match == 2 && b == '\r'.code -> 3
                                    match == 3 && b == '\n'.code -> 4
                                    b == '\r'.code -> 1
                                    else -> 0
                                }
                                if (match == 4) break
                            }
                            val lines = hdrBuf.toString(StandardCharsets.UTF_8.name()).split("\r\n")
                            val first = lines.firstOrNull().orEmpty().split(" ")
                            if (first.size < 2) return@use
                            val method = first[0]
                            val path = first[1].substringBefore("?")
                            val headers = LinkedHashMap<String, String>()
                            for (line in lines.drop(1)) {
                                val colon = line.indexOf(':')
                                if (colon > 0) {
                                    headers[line.substring(0, colon).trim().lowercase()] =
                                        line.substring(colon + 1).trim()
                                }
                            }
                            val len = headers["content-length"]?.toIntOrNull() ?: 0
                            val body = ByteArray(len)
                            var read = 0
                            while (read < len) {
                                val n = input.read(body, read, len - read)
                                if (n < 0) break
                                read += n
                            }
                            val (code, respBody) = handler(method, path, headers, body)
                            val bytes = respBody.toByteArray(StandardCharsets.UTF_8)
                            val out = s.getOutputStream()
                            out.write(
                                (
                                    "HTTP/1.1 $code OK\r\n" +
                                        "Content-Type: application/json\r\n" +
                                        "Content-Length: ${bytes.size}\r\n" +
                                        "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.UTF_8),
                            )
                            out.write(bytes)
                            out.flush()
                        }
                    }
                }
            }
        }

        override fun close() {
            running.set(false)
            try { socket.close() } catch (_: Exception) {}
        }
    }

    @Test
    fun probeMoonrakerHitsRealHttpEndpointsAndDetectsCosmos() = runBlocking {
        TinyHttpServer { _, path, _, _ ->
            when (path) {
                "/server/info" -> 200 to """{"result":{"klippy_state":"ready","moonraker_version":"v0.11.0","components":["swu_deploy","history"]}}"""
                "/printer/info" -> 200 to """{"result":{"hostname":"cosmos-cc2","state":"ready"}}"""
                "/printer/objects/list" -> 200 to """{"result":{"objects":["exclude_object","fan_generic aux_fan","servo case_servo"]}}"""
                else -> 404 to "{}"
            }
        }.use { server ->
            val mgr = LanPrintManager()
            val res = mgr.probeMoonraker("127.0.0.1:${server.port}")
            assertTrue("Expected Ok, got $res", res is CloudResult.Ok)
            val printer = (res as CloudResult.Ok).value
            assertEquals("127.0.0.1", printer.ip)
            assertEquals(server.port, printer.port)
            assertEquals(PrinterProtocol.MOONRAKER, printer.protocol)
            assertEquals("Centauri Carbon 2 (COSMOS)", printer.model)
            assertEquals("cosmos-cc2", printer.name)
        }
    }

    @Test
    fun probeMoonrakerReportsAuthRejectionOn401() = runBlocking {
        TinyHttpServer { _, path, headers, _ ->
            if (path == "/server/info" && headers["x-api-key"] == "secret-key") {
                200 to """{"result":{"klippy_state":"ready","components":[]}}"""
            } else if (path == "/server/info") {
                401 to """{"error":"Unauthorized"}"""
            } else {
                200 to """{"result":{}}"""
            }
        }.use { server ->
            val mgr = LanPrintManager()
            val denied = mgr.probeMoonraker("127.0.0.1:${server.port}")
            assertTrue(denied is CloudResult.Err)
            assertTrue(LanPrintManager.isAccessCodeRejection((denied as CloudResult.Err).message))

            val allowed = mgr.probeMoonraker("127.0.0.1:${server.port}", apiKey = "secret-key")
            assertTrue("Expected Ok with API key, got $allowed", allowed is CloudResult.Ok)
        }
    }

    @Test
    fun moonrakerMultipartUploadSendsRootPrintAndFile() = runBlocking {
        var capturedBody = ""
        var capturedApiKey: String? = null
        TinyHttpServer { _, path, headers, body ->
            if (path == "/server/files/upload") {
                capturedApiKey = headers["x-api-key"]
                capturedBody = body.toString(StandardCharsets.UTF_8)
                201 to """{"item":{"path":"test_part.gcode"},"print_started":true}"""
            } else {
                404 to "{}"
            }
        }.use { server ->
            val tmp = File.createTempFile("test_part", ".gcode")
            tmp.writeText("EXCLUDE_OBJECT_DEFINE NAME=PART_1\nG28\n")
            try {
                val target = LanPrintManager.DiscoveredPrinter(
                    ip = "127.0.0.1",
                    serial = "moonraker:127.0.0.1:${server.port}",
                    model = "Centauri Carbon 2 (COSMOS)",
                    name = "cosmos-cc2",
                    lanMode = true,
                    protocol = PrinterProtocol.MOONRAKER,
                    port = server.port,
                )
                val res = LanPrintManager().uploadAndPrintMoonrakerForTest(target, tmp, "my-key")
                assertTrue("Expected Ok, got $res", res is CloudResult.Ok)
                assertEquals("my-key", capturedApiKey)
                assertTrue("root field missing in:\n$capturedBody", capturedBody.contains("name=\"root\"") && capturedBody.contains("\r\n\r\ngcodes"))
                assertTrue("print field missing in:\n$capturedBody", capturedBody.contains("name=\"print\"") && capturedBody.contains("\r\n\r\ntrue"))
                assertTrue("file payload missing", capturedBody.contains("EXCLUDE_OBJECT_DEFINE NAME=PART_1"))
            } finally {
                tmp.delete()
            }
        }
    }
}
