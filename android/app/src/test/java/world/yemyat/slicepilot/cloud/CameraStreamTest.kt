package world.yemyat.slicepilot.cloud

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import world.yemyat.slicepilot.tracking.CrashlyticsEventTracker
import java.io.BufferedInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

class CameraStreamTest {

    private class StubCameraServer(
        private val statusCode: Int,
        private val contentType: String,
        private val body: ByteArray,
    ) : AutoCloseable {
        private val socket = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        private val running = AtomicBoolean(true)
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (running.get()) {
                    val client = try { socket.accept() } catch (_: Exception) { break }
                    thread(isDaemon = true) {
                        client.use { s ->
                            val input = BufferedInputStream(s.getInputStream())
                            // Drain request headers
                            var match = 0
                            while (match < 4) {
                                val b = input.read()
                                if (b < 0) return@use
                                match = when {
                                    match == 0 && b == '\r'.code -> 1
                                    match == 1 && b == '\n'.code -> 2
                                    match == 2 && b == '\r'.code -> 3
                                    match == 3 && b == '\n'.code -> 4
                                    b == '\r'.code -> 1
                                    else -> 0
                                }
                            }
                            val header = (
                                "HTTP/1.1 $statusCode Status\r\n" +
                                    "Content-Type: $contentType\r\n" +
                                    "Content-Length: ${body.size}\r\n" +
                                    "Connection: close\r\n\r\n"
                                ).toByteArray(StandardCharsets.UTF_8)
                            val out = s.getOutputStream()
                            out.write(header)
                            out.write(body)
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
    fun http404ReportsHttpErrorOutcome() = runBlocking {
        StubCameraServer(
            statusCode = 404,
            contentType = "text/plain",
            body = "Not Found".toByteArray(StandardCharsets.UTF_8),
        ).use { server ->
            var outcome: CameraStream.StreamOutcome? = null
            CameraStream.frames("http://127.0.0.1:${server.port}/stream") { outcome = it }.collect()
            assertNotNull(outcome)
            assertEquals(CameraStream.StreamFailureReason.HTTP_ERROR, outcome!!.reason)
            assertEquals(404, outcome!!.httpCode)
            assertEquals(0, outcome!!.framesDecoded)
            assertTrue(outcome!!.errorMessage.orEmpty().contains("HTTP 404"))
        }
    }

    @Test
    fun htmlContentTypeReportsUnexpectedContentType() = runBlocking {
        StubCameraServer(
            statusCode = 200,
            contentType = "text/html; charset=utf-8",
            body = "<html><body>Fluidd</body></html>".toByteArray(StandardCharsets.UTF_8),
        ).use { server ->
            var outcome: CameraStream.StreamOutcome? = null
            CameraStream.frames("http://127.0.0.1:${server.port}/stream") { outcome = it }.collect()
            assertNotNull(outcome)
            assertEquals(CameraStream.StreamFailureReason.UNEXPECTED_CONTENT_TYPE, outcome!!.reason)
            assertEquals(200, outcome!!.httpCode)
            assertTrue(outcome!!.contentType.orEmpty().startsWith("text/html"))
            assertTrue(outcome!!.reason.isLikelyPermanent)
        }
    }

    @Test
    fun emptyMjpegStreamReportsEofNoFrames() = runBlocking {
        StubCameraServer(
            statusCode = 200,
            contentType = "multipart/x-mixed-replace; boundary=frame",
            body = ByteArray(0),
        ).use { server ->
            var outcome: CameraStream.StreamOutcome? = null
            CameraStream.frames("http://127.0.0.1:${server.port}/stream") { outcome = it }.collect()
            assertNotNull(outcome)
            assertEquals(CameraStream.StreamFailureReason.EOF_NO_FRAMES, outcome!!.reason)
            assertEquals(200, outcome!!.httpCode)
            assertEquals(0, outcome!!.framesDecoded)
        }
    }

    @Test
    fun closedPortReportsConnectionRefused() = runBlocking {
        val unusedPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { it.localPort }
        var outcome: CameraStream.StreamOutcome? = null
        CameraStream.frames("http://127.0.0.1:$unusedPort/stream") { outcome = it }.collect()
        assertNotNull(outcome)
        assertEquals(CameraStream.StreamFailureReason.CONNECTION_REFUSED, outcome!!.reason)
        assertNotNull(outcome!!.throwable)
    }

    @Test
    fun sanitizeEndpointRedactsHostButKeepsPortAndPath() {
        assertEquals(
            "http://<host>:8080/stream",
            CameraStream.sanitizeEndpoint("http://192.168.1.42:8080/stream"),
        )
        assertEquals(
            "http://<host>:8080/?action=stream",
            CameraStream.sanitizeEndpoint("http://cosmos.local:8080/?action=stream"),
        )
        assertEquals(
            "http://<host>/webcam/?action=stream",
            CameraStream.sanitizeEndpoint("http://10.0.0.9/webcam/?action=stream"),
        )
    }

    @Test
    fun selectPreferredWebcamPrefersMjpegOverWebRtc() {
        val cams = JSONArray(
            """
            [
                {"name":"disabled","enabled":false,"service":"mjpegstreamer","stream_url":":8081/stream"},
                {"name":"webrtc","enabled":true,"service":"webrtc-camerastreamer","stream_url":":8080/webrtc"},
                {"name":"mjpeg","enabled":true,"service":"mjpegstreamer-adaptive","stream_url":":8080/stream"}
            ]
            """.trimIndent(),
        )
        val picked = LanPrintMonitorMoonraker.selectPreferredWebcam(cams)
        assertEquals(":8080/stream" to "mjpegstreamer-adaptive", picked)

        val webrtcOnly = JSONArray(
            """[{"name":"webrtc","enabled":true,"service":"webrtc-camerastreamer","stream_url":":8080/webrtc"}]""",
        )
        val fallback = LanPrintMonitorMoonraker.selectPreferredWebcam(webrtcOnly)
        assertEquals(":8080/webrtc" to "webrtc-camerastreamer", fallback)
        assertFalse(LanPrintMonitorMoonraker.isMjpegService(fallback!!.second))
    }

    @Test
    fun crashlyticsGroupsCameraStreamAndMoonrakerErrors() {
        assertEquals(
            "CameraStreamFailedException",
            CrashlyticsEventTracker.exceptionFor("camera_stream_failed").javaClass.simpleName,
        )
        assertEquals(
            "CameraStreamFailedException",
            CrashlyticsEventTracker.exceptionFor("camera_stream_dropped").javaClass.simpleName,
        )
        assertEquals(
            "LanPrintFailedException",
            CrashlyticsEventTracker.exceptionFor("lan_moonraker_monitor_connect_failed").javaClass.simpleName,
        )
        assertEquals(
            "LanPrintFailedException",
            CrashlyticsEventTracker.exceptionFor("lan_moonraker_ws_lost").javaClass.simpleName,
        )
    }
}
