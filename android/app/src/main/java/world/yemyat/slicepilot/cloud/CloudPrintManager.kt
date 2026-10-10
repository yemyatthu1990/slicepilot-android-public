package world.yemyat.slicepilot.cloud

import android.util.Base64
import android.util.Log
import io.agora.rtm.ErrorInfo
import io.agora.rtm.MessageEvent
import io.agora.rtm.PublishOptions
import io.agora.rtm.ResultCallback
import io.agora.rtm.RtmClient
import io.agora.rtm.RtmConfig
import io.agora.rtm.RtmConstants
import io.agora.rtm.RtmEventListener
import io.agora.rtm.SubscribeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Cloud printing via Elegoo's matrix cloud:
 *  1. OSS upload of the sliced G-code (HTTP).
 *  2. Agora RTM commands to the printer (download file + start print).
 *
 * Mirrors ELEGOO's open-source elegoo-link cloud flow.
 */
class CloudPrintManager(private val cloud: ElegooCloudClient) {

    private class PendingRequest {
        val latch = CountDownLatch(1)
        @Volatile var ok = false
        @Volatile var error = ""
    }

    private data class Res(val ok: Boolean, val message: String)

    private var client: RtmClient? = null
    private var currentRtmUser: String? = null
    private val pending = ConcurrentHashMap<Int, PendingRequest>()
    private val requestCounter = AtomicInteger(1000)

    @Volatile private var downloadTaskId: String? = null
    @Volatile private var downloadStatus = 0
    @Volatile private var downloadLatch: CountDownLatch? = null

    private val listener = object : RtmEventListener {
        override fun onMessageEvent(event: MessageEvent) {
            val content = when (val data = event.message?.data) {
                is String -> data
                is ByteArray -> String(data, Charsets.UTF_8)
                null -> return
                else -> data.toString()
            }
            handleMessage(content)
        }
    }

    private fun handleMessage(content: String) {
        val json = try {
            JSONObject(content)
        } catch (_: Exception) {
            return
        }
        val method = json.optInt("method", -1)
        if (method == 6006) {
            handleDownloadStatus(json)
            return
        }
        val id = json.optInt("id", -1)
        if (id < 0) return
        val p = pending[id] ?: return
        val result = json.optJSONObject("result")
        val code = result?.optInt("error_code", -1) ?: -1
        p.ok = code == 0
        if (!p.ok) {
            p.error = result?.optString("error_msg", "").orEmpty()
            if (p.error.isBlank()) p.error = json.optString("message", "printer error code $code")
        }
        p.latch.countDown()
    }

    private fun handleDownloadStatus(json: JSONObject) {
        val result = json.optJSONObject("result") ?: return
        val taskId = result.optString("taskID", "")
        if (taskId.isBlank() || taskId != downloadTaskId) return
        val status = result.optInt("status", 0)
        if (status == 1 || status == 2 || status == 3) {
            downloadStatus = status
            downloadLatch?.countDown()
        }
    }

    suspend fun print(
        printer: CloudPrinter,
        gcode: File,
        slot: PrintSlot? = null,
        onStatus: (String) -> Unit,
    ): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, "print start: printer=${printer.name} file=${gcode.name} size=${gcode.length()}")
                if (!gcode.exists()) return@withContext CloudResult.Err("Sliced file not found")
                onStatus("Requesting cloud credentials…")
                val cred = when (val r = cloud.getAgoraCredential()) {
                    is CloudResult.Ok -> r.value
                    is CloudResult.Err -> return@withContext CloudResult.Err(r.message)
                }
                Log.i(TAG, "Cloud credential acquired for user ${cred.rtmUserId}")
                ensureLoggedIn(cred)
        
                val md5Hex = md5Hex(gcode)
                val md5B64 = md5Base64(gcode)
                val ext = gcode.extension.ifBlank { "gcode" }.lowercase()
                val serial = printer.id
                val serverName = "${cred.userId.takeLast(6)}_${serial.takeLast(6)}_${md5Hex}.$ext"
                val originalName = gcode.name

                onStatus("Uploading G-code…")
                val oss = when (val r = cloud.getOssEntrypoint(serverName, md5B64)) {
                    is CloudResult.Ok -> r.value
                    is CloudResult.Err -> return@withContext CloudResult.Err(r.message)
                }
                when (val r = cloud.uploadToOss(oss.entrypoint, gcode, md5B64)) {
                    is CloudResult.Ok -> {}
                    is CloudResult.Err -> return@withContext CloudResult.Err(r.message)
                }

                val channel = cred.userId + serial
                client?.let { subscribeChannel(it, channel) }
                onStatus("Sending to printer…")

                downloadTaskId = serial
                downloadStatus = 0
                val latch = CountDownLatch(1)
                downloadLatch = latch

                val dl = send(
                    channel, 1057,
                    JSONObject()
                        .put("filename", originalName)
                        .put("url", oss.accessUrl)
                        .put("md5", md5Hex)
                        .put("taskID", serial),
                    20_000,
                )
                Log.i(TAG, "download(1057) result ok=${dl.ok} msg=${dl.message}")
                if (!dl.ok) return@withContext CloudResult.Err("Download request failed: ${dl.message}")

                onStatus("Printer downloading…")
                val completed = latch.await(180, TimeUnit.SECONDS)
                if (!completed) return@withContext CloudResult.Err("Printer download timed out")
                if (downloadStatus != 1) return@withContext CloudResult.Err("Printer download failed (status $downloadStatus)")

                onStatus("Starting print…")
                val start = send(
                    channel, 1020,
                    JSONObject()
                        .put("storage_media", "local")
                        .put("filename", originalName)
                        .put(
                            "config",
                            JSONObject()
                                .put("delay_video", false)
                                .put("printer_check", false)
                                .put("print_layout", "A")
                                .put("bedlevel_force", false)
                                .put("slot_map", slotMapJson(slot)),
                        ),
                    20_000,
                )
                Log.i(TAG, "start(1020) result ok=${start.ok} msg=${start.message}")
                if (!start.ok) return@withContext CloudResult.Err("Start print failed: ${start.message}")
                CloudResult.Ok(Unit)
            } catch (e: Exception) {
                Log.e(TAG, "print failed", e)
                CloudResult.Err(e.message ?: "print failed")
            }
        }

    private fun slotMapJson(slot: PrintSlot?): JSONArray {
        if (slot == null) return JSONArray()
        return JSONArray().put(
            JSONObject()
                .put("t", slot.t)
                .put("canvas_id", slot.canvasId)
                .put("tray_id", slot.trayId),
        )
    }

    private fun send(channel: String, method: Int, params: JSONObject, timeoutMs: Long): Res {
        val c = client ?: return Res(false, "RTM not connected")
        val id = requestCounter.incrementAndGet()
        val pendingRequest = PendingRequest()
        pending[id] = pendingRequest
        val payload = JSONObject()
            .put("id", id)
            .put("method", method)
            .put("params", params)
            .toString()

        val published = CountDownLatch(1)
        var publishError: String? = null
        val options = PublishOptions().apply {
            customType = "PlainText"
            setChannelType(RtmConstants.RtmChannelType.USER)
        }
        try {
            c.publish(channel, payload, options, object : ResultCallback<Void> {
                override fun onSuccess(responseInfo: Void?) { published.countDown() }
                override fun onFailure(errorInfo: ErrorInfo) {
                    publishError = errorInfo.toString()
                    published.countDown()
                }
            })
        } catch (e: Exception) {
            pending.remove(id)
            return Res(false, e.message ?: "publish failed")
        }
        if (!published.await(10, TimeUnit.SECONDS)) {
            pending.remove(id)
            return Res(false, "publish timed out")
        }
        publishError?.let {
            pending.remove(id)
            return Res(false, it)
        }
        val got = try {
            pendingRequest.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            false
        }
        pending.remove(id)
        return if (!got) Res(false, "printer response timed out") else Res(pendingRequest.ok, pendingRequest.error)
    }

    private suspend fun ensureLoggedIn(cred: ElegooCloudClient.AgoraCredential) {
        val existing = client
        if (existing != null && currentRtmUser == cred.rtmUserId) {
            val latch = CountDownLatch(1)
            existing.renewToken(cred.rtmToken, object : ResultCallback<Void> {
                override fun onSuccess(responseInfo: Void?) { latch.countDown() }
                override fun onFailure(errorInfo: ErrorInfo) { latch.countDown() }
            })
            latch.await(10, TimeUnit.SECONDS)
            return
        }

        existing?.let {
            try {
                val l = CountDownLatch(1)
                it.logout(object : ResultCallback<Void> {
                    override fun onSuccess(responseInfo: Void?) { l.countDown() }
                    override fun onFailure(errorInfo: ErrorInfo) { l.countDown() }
                })
                l.await(5, TimeUnit.SECONDS)
            } catch (_: Exception) {
            }
        }
        try {
            RtmClient.release()
        } catch (_: Exception) {
        }
        client = null
        currentRtmUser = null

        val config = RtmConfig.Builder(AGORA_APP_ID, cred.rtmUserId)
            .eventListener(listener)
            .build()
        val c = RtmClient.create(config)

        val loginLatch = CountDownLatch(1)
        var loginError: String? = null
        c.login(cred.rtmToken, object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) { loginLatch.countDown() }
            override fun onFailure(errorInfo: ErrorInfo) {
                loginError = errorInfo.toString()
                loginLatch.countDown()
            }
        })
        if (!loginLatch.await(15, TimeUnit.SECONDS)) throw RuntimeException("RTM login timed out")
        loginError?.let { throw RuntimeException("RTM login failed: $it") }

        client = c
        currentRtmUser = cred.rtmUserId

        subscribeChannel(c, cred.userId)
    }

    private suspend fun subscribeChannel(c: RtmClient, channel: String) {
        val latch = CountDownLatch(1)
        var err: String? = null
        val options = SubscribeOptions().apply { setWithMessage(true) }
        c.subscribe(channel, options, object : ResultCallback<Void> {
            override fun onSuccess(responseInfo: Void?) { latch.countDown() }
            override fun onFailure(errorInfo: ErrorInfo) { err = errorInfo.toString(); latch.countDown() }
        })
        latch.await(10, TimeUnit.SECONDS)
        if (err != null) Log.w(TAG, "subscribe $channel failed: $err")
    }

    fun release() {
        try {
            client?.logout(object : ResultCallback<Void> {
                override fun onSuccess(responseInfo: Void?) {}
                override fun onFailure(errorInfo: ErrorInfo) {}
            })
        } catch (_: Exception) {
        }
        try {
            RtmClient.release()
        } catch (_: Exception) {
        }
        client = null
        currentRtmUser = null
    }

    private fun md5Hex(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        val sb = StringBuilder()
        for (b in md.digest()) sb.append(String.format("%02x", b))
        return sb.toString()
    }

    private fun md5Base64(file: File): String {
        val md = MessageDigest.getInstance("MD5")
        file.inputStream().use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                md.update(buffer, 0, n)
            }
        }
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    private companion object {
        const val AGORA_APP_ID = "d035320941e34cd5bc4ec106eff05580"
        const val TAG = "CloudPrint"
    }
}