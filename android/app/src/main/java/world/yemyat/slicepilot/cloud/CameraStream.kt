package world.yemyat.slicepilot.cloud

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Minimal MJPEG reader for the CC2 camera: the stock firmware serves
 * `multipart/x-mixed-replace` on port 8080 for *any* request (no auth). Frames are found by
 * scanning JPEG SOI/EOI markers and emitted as bitmaps on [frames]. The printer allows a
 * single video connection, so callers must stop collecting (and send method 1042
 * `{"enable": false}`) when the preview closes.
 */
object CameraStream {

    fun frames(url: String): Flow<Bitmap> = flow {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 15000
        try {
            conn.connect()
            Log.i("CameraStream", "stream connected from $url (http ${conn.responseCode})")
            val input = conn.inputStream
            val buf = ByteArrayOutputStream(256 * 1024)
            val chunk = ByteArray(64 * 1024)
            var prev = 0
            var inFrame = false
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
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
                        buf.write(b)
                        if (prev == 0xFF && b == 0xD9) {
                            inFrame = false
                            val bytes = buf.toByteArray()
                            try {
                                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { emit(it) }
                            } catch (_: Exception) {
                            }
                        }
                    }
                    prev = b
                }
            }
            Log.i("CameraStream", "stream EOF (printer closed the connection)")
        } catch (e: java.io.IOException) {
            // Stream ended: read timeouts, connection resets when the preview is closed
            // (the printer yanks the socket), printer rebooting, ... End the flow quietly;
            // letting these escape kills the app (uncaught in the collecting coroutine).
            Log.i("CameraStream", "stream ended: ${e.message}")
        } finally {
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }.flowOn(Dispatchers.IO)
}
