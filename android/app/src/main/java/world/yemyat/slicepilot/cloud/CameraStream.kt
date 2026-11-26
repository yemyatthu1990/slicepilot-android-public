package world.yemyat.slicepilot.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/**
 * Minimal MJPEG reader for LAN printer cameras (Elegoo CC2/CC1 and OpenCentauri COSMOS uStreamer):
 * serves `multipart/x-mixed-replace` or single-frame JPEG streams, scanning JPEG SOI/EOI markers
 * and emitting decoded bitmaps on [frames].
 *
 * Every stream attempt reports a structured [StreamOutcome] via `onFinished` so callers can log
 * actionable Crashlytics / analytics diagnostics (HTTP status, content type, decode counts,
 * failure reason) instead of silently swallowing playback failures.
 */
object CameraStream {

    /** Safety cap on a single JPEG frame buffer so a corrupt/non-JPEG stream cannot OOM the app. */
    private const val MAX_FRAME_BYTES = 4 * 1024 * 1024

    private fun logInfo(msg: String) {
        try { Log.i("CameraStream", msg) } catch (_: RuntimeException) {}
    }

    private fun logWarn(msg: String) {
        try { Log.w("CameraStream", msg) } catch (_: RuntimeException) {}
    }

    enum class StreamFailureReason {
        /** Normal cancellation by caller (screen closed / user toggled camera off). */
        CANCELLED,
        /** Non-2xx HTTP status (e.g. 404 wrong path, 401/403 auth, 502/503 streamer down). */
        HTTP_ERROR,
        /** HTTP 2xx, 0 frames decoded, and Content-Type is not MJPEG/JPEG (e.g. HTML or HLS/WebRTC). */
        UNEXPECTED_CONTENT_TYPE,
        /** Timed out connecting to the camera server. */
        CONNECT_TIMEOUT,
        /** Connected, but timed out waiting for bytes/frames. */
        READ_TIMEOUT,
        /** TCP connection refused (uStreamer / camera service not running on that port). */
        CONNECTION_REFUSED,
        /** Hostname could not be resolved or host unreachable on LAN. */
        HOST_UNREACHABLE,
        /** Received SOI/EOI-framed payloads, but BitmapFactory failed to decode any frame. */
        DECODE_FAILED,
        /** Server returned HTTP 200 and closed the connection before delivering any frame. */
        EOF_NO_FRAMES,
        /** At least one frame decoded, then the stream closed or errored mid-playback. */
        STREAM_DROPPED,
        /** Any other I/O error. */
        IO_ERROR,
        ;

        /** True when retrying the exact same URL immediately is unlikely to succeed. */
        val isLikelyPermanent: Boolean
            get() = this == UNEXPECTED_CONTENT_TYPE || this == DECODE_FAILED
    }

    data class StreamOutcome(
        val url: String,
        val httpCode: Int? = null,
        val contentType: String? = null,
        val framesDecoded: Int = 0,
        val decodeFailures: Int = 0,
        val bytesRead: Long = 0L,
        val durationMs: Long = 0L,
        val firstFrameMs: Long? = null,
        val reason: StreamFailureReason,
        val errorMessage: String? = null,
        val throwable: Throwable? = null,
    )

    /**
     * Strip the printer's LAN IP/hostname so analytics/Crashlytics keys capture the
     * scheme, port, and path (e.g. `http://<host>:8080/stream`) without PII or high cardinality.
     */
    fun sanitizeEndpoint(rawUrl: String): String =
        try {
            val u = URL(rawUrl)
            val portPart = if (u.port > 0) ":${u.port}" else ""
            val queryPart = if (!u.query.isNullOrBlank()) "?${u.query}" else ""
            "${u.protocol}://<host>$portPart${u.path.ifEmpty { "/" }}$queryPart"
        } catch (_: Exception) {
            "<invalid-url>"
        }

    internal fun isLikelyImageContentType(contentType: String?): Boolean {
        val ct = contentType?.lowercase().orEmpty()
        return ct.isEmpty() ||
            ct.contains("multipart/") ||
            ct.contains("image/jpeg") ||
            ct.contains("image/jpg") ||
            ct.contains("application/octet-stream")
    }

    fun frames(
        url: String,
        onFinished: (StreamOutcome) -> Unit = {},
    ): Flow<Bitmap> = flow {
        val startedAt = System.currentTimeMillis()
        var httpCode: Int? = null
        var contentType: String? = null
        var framesDecoded = 0
        var decodeFailures = 0
        var bytesRead = 0L
        var firstFrameMs: Long? = null
        var connected = false
        var finishedReported = false

        fun report(reason: StreamFailureReason, errorMessage: String? = null, throwable: Throwable? = null) {
            if (finishedReported) return
            finishedReported = true
            onFinished(
                StreamOutcome(
                    url = url,
                    httpCode = httpCode,
                    contentType = contentType,
                    framesDecoded = framesDecoded,
                    decodeFailures = decodeFailures,
                    bytesRead = bytesRead,
                    durationMs = System.currentTimeMillis() - startedAt,
                    firstFrameMs = firstFrameMs,
                    reason = reason,
                    errorMessage = errorMessage,
                    throwable = throwable,
                ),
            )
        }

        val conn = try {
            URL(url).openConnection() as HttpURLConnection
        } catch (e: Exception) {
            report(StreamFailureReason.IO_ERROR, "Invalid camera URL: ${e.message}", e)
            return@flow
        }
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        try {
            conn.connect()
            connected = true
            httpCode = conn.responseCode
            contentType = conn.contentType
            logInfo("stream connected from $url (http $httpCode, type=$contentType)")

            if (httpCode !in 200..299) {
                val errSnippet = try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(160)
                } catch (_: Exception) {
                    null
                }
                val msg = buildString {
                    append("HTTP $httpCode")
                    if (!errSnippet.isNullOrBlank()) append(": ").append(errSnippet.replace('\n', ' '))
                }
                logWarn("stream HTTP error from $url: $msg")
                report(StreamFailureReason.HTTP_ERROR, msg)
                return@flow
            }

            if (!isLikelyImageContentType(contentType)) {
                val msg = "Unsupported Content-Type: $contentType"
                logWarn("$msg ($url)")
                report(StreamFailureReason.UNEXPECTED_CONTENT_TYPE, msg)
                return@flow
            }

            val input = conn.inputStream
            val buf = ByteArrayOutputStream(256 * 1024)
            val chunk = ByteArray(64 * 1024)
            var prev = 0
            var inFrame = false
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                bytesRead += n
                for (i in 0 until n) {
                    val b = chunk[i].toInt() and 0xFF
                    if (!inFrame) {
                        if (prev == 0xFF && b == 0xD8) {
                            buf.reset()
                            buf.write(0xFF)
                            buf.write(0xD8)
                            inFrame = true
                        }
                    } else {
                        if (buf.size() >= MAX_FRAME_BYTES) {
                            inFrame = false
                            buf.reset()
                            decodeFailures++
                        } else {
                            buf.write(b)
                            if (prev == 0xFF && b == 0xD9) {
                                inFrame = false
                                val bytes = buf.toByteArray()
                                val bmp = try {
                                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                                } catch (_: Exception) {
                                    null
                                }
                                if (bmp != null) {
                                    if (firstFrameMs == null) {
                                        firstFrameMs = System.currentTimeMillis() - startedAt
                                    }
                                    framesDecoded++
                                    emit(bmp)
                                } else {
                                    decodeFailures++
                                }
                            }
                        }
                    }
                    prev = b
                }
            }
            logInfo("stream EOF (frames=$framesDecoded, decodeFailures=$decodeFailures, bytes=$bytesRead)")
            val eofReason = when {
                framesDecoded > 0 -> StreamFailureReason.STREAM_DROPPED
                decodeFailures > 0 -> StreamFailureReason.DECODE_FAILED
                !isLikelyImageContentType(contentType) -> StreamFailureReason.UNEXPECTED_CONTENT_TYPE
                else -> StreamFailureReason.EOF_NO_FRAMES
            }
            report(eofReason, "EOF after $framesDecoded frames ($bytesRead bytes)")
        } catch (e: kotlinx.coroutines.CancellationException) {
            report(StreamFailureReason.CANCELLED)
            throw e
        } catch (e: java.io.IOException) {
            // Stream ended: read timeouts, connection resets when the preview is closed
            // (the printer yanks the socket), printer rebooting, ... End the flow quietly;
            // letting these escape kills the app (uncaught in the collecting coroutine).
            logInfo("stream ended: ${e.javaClass.simpleName}: ${e.message}")
            val reason = when {
                framesDecoded > 0 -> StreamFailureReason.STREAM_DROPPED
                e is ConnectException -> StreamFailureReason.CONNECTION_REFUSED
                e is UnknownHostException || e is NoRouteToHostException -> StreamFailureReason.HOST_UNREACHABLE
                e is SocketTimeoutException ->
                    if (connected) StreamFailureReason.READ_TIMEOUT else StreamFailureReason.CONNECT_TIMEOUT
                else -> StreamFailureReason.IO_ERROR
            }
            report(reason, "${e.javaClass.simpleName}: ${e.message.orEmpty()}", e)
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)
}
