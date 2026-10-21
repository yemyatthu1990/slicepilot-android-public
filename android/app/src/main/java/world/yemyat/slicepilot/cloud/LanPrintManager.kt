package world.yemyat.slicepilot.cloud

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import world.yemyat.slicepilot.tracking.EventNotifier
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/**
 * Local-network printing for Elegoo printers (no account required).
 *
 * Mirrors ELEGOO's open-source elegoo-link LAN flow:
 *  - UDP discovery (port 52700 for CC2).
 *  - Chunked HTTP PUT /upload (CC2) with X-Token.
 *  - Start print over the printer's LAN MQTT (method 1020).
 */
class LanPrintManager {

    data class DiscoveredPrinter(
        val ip: String,
        val serial: String,
        val model: String,
        val name: String,
        val lanMode: Boolean = false,
        /** True when the printer advertises that it requires a custom access code. */
        val needsAccessCode: Boolean = false,
    )

    suspend fun print(
        context: Context,
        gcode: File,
        target: DiscoveredPrinter? = null,
        slots: List<PrintSlot> = emptyList(),
        accessCode: String = CC2_DEFAULT_TOKEN,
        onStatus: (String) -> Unit,
    ): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            if (!gcode.exists()) return@withContext CloudResult.Err("Sliced file not found")

            val token = accessCode.ifBlank { CC2_DEFAULT_TOKEN }

            val printer = target ?: run {
                onStatus("Searching for a printer on the local network…")
                discoverAll(context, timeoutMs = 5000).firstOrNull()
            } ?: return@withContext CloudResult.Err("No supported printer found on this Wi-Fi")

            val isCentauri = printer.model.contains("Centauri", ignoreCase = true) || printer.model.contains("CC", ignoreCase = true)
            if (!isCentauri) {
                return@withContext CloudResult.Err("Local printing supports Centauri series only (found ${printer.model})")
            }
            if (isCc1Model(printer.model)) {
                return@withContext printCc1(printer, gcode, slots, onStatus)
            }

            onStatus("Uploading G-code to ${printer.name.ifBlank { printer.ip }}…")
            val uploadStartedAt = System.currentTimeMillis()
            EventNotifier.trackEvent(
                "print_upload_started",
                mapOf(
                    "transport" to "lan",
                    "printer" to printer.name,
                    "ip" to printer.ip,
                    "file" to gcode.name,
                    "size_bytes" to gcode.length(),
                ),
            )
            val up = uploadCc2(printer.ip, gcode, token)
            if (up is CloudResult.Err) {
                EventNotifier.trackError(
                    "http_upload_failed",
                    null,
                    mapOf(
                        "printer" to printer.name,
                        "ip" to printer.ip,
                        "file" to gcode.name,
                        "duration_ms" to (System.currentTimeMillis() - uploadStartedAt),
                        "message" to up.message,
                    ),
                )
                return@withContext up
            }
            EventNotifier.trackEvent(
                "print_upload_completed",
                mapOf(
                    "transport" to "lan",
                    "printer" to printer.name,
                    "ip" to printer.ip,
                    "file" to gcode.name,
                    "duration_ms" to (System.currentTimeMillis() - uploadStartedAt),
                ),
            )

            onStatus("Starting print…")
            EventNotifier.trackEvent(
                "print_mqtt_start_sent",
                mapOf(
                    "transport" to "lan",
                    "printer" to printer.name,
                    "ip" to printer.ip,
                    "file" to gcode.name,
                ),
            )
            return@withContext startPrintCc2(printer.ip, printer.serial, gcode.name, slots, token)
        }

    private val cc1Http by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private suspend fun printCc1(
        printer: DiscoveredPrinter,
        gcode: File,
        slots: List<PrintSlot>,
        onStatus: (String) -> Unit,
    ): CloudResult<Unit> {
        onStatus("Uploading G-code to ${printer.name.ifBlank { printer.ip }}…")
        val uploadStartedAt = System.currentTimeMillis()
        EventNotifier.trackEvent(
            "print_upload_started",
            mapOf(
                "transport" to "lan-cc1",
                "printer" to printer.name,
                "ip" to printer.ip,
                "file" to gcode.name,
                "size_bytes" to gcode.length(),
            ),
        )
        val up = uploadCc1(printer.ip, gcode, onStatus)
        if (up is CloudResult.Err) {
            EventNotifier.trackError(
                "http_upload_failed",
                null,
                mapOf(
                    "printer" to printer.name,
                    "ip" to printer.ip,
                    "message" to up.message,
                    "duration_ms" to (System.currentTimeMillis() - uploadStartedAt),
                ),
            )
            return up
        }
        EventNotifier.trackEvent(
            "print_upload_completed",
            mapOf(
                "transport" to "lan-cc1",
                "printer" to printer.name,
                "duration_ms" to (System.currentTimeMillis() - uploadStartedAt),
            ),
        )
        onStatus("Starting print…")
        val params = JSONObject()
            .put("Filename", "/local/${gcode.name}")
            .put("StartLayer", 0)
            .put("Calibration_switch", 0)
            .put("PrintPlatformType", 0)
            .put("Tlp_Switch", 0)
            // Canvas/AMS: map the selected spool; an empty slot_map on a
            // Canvas-equipped CC1 starts the job paused for filament confirm.
            .put("slot_map", slotMapJson(slots))
        val ok = LanPrintMonitorCC1.oneShotCommand(
            printer.ip, printer.serial, LanPrintMonitorCC1.CMD_START_PRINT, params,
        )
        return if (ok) {
            EventNotifier.trackEvent(
                "lan_cc1_print_started",
                mapOf("printer" to printer.name, "ip" to printer.ip, "file" to gcode.name),
            )
            CloudResult.Ok(Unit)
        } else {
            EventNotifier.trackError(
                "lan_cc1_start_print_failed",
                null,
                mapOf("printer" to printer.name, "ip" to printer.ip),
            )
            CloudResult.Err("The printer rejected the print command")
        }
    }

    /**
     * CC1 file upload: `POST /uploadFile/upload`, multipart form-data in 1 MiB chunks;
     * every chunk carries the whole-file MD5 (verified per chunk by the printer) and the
     * same Uuid for the transfer. Success per chunk: `{"code":"000000"}`.
     * (Framing from Elegoo's open-source elegoo-link SDK, confirmed by community probes.)
     */
    private fun uploadCc1(ip: String, gcode: File, onStatus: (String) -> Unit): CloudResult<Unit> {
        val total = gcode.length()
        if (total <= 0) return CloudResult.Err("Sliced file is empty")
        val md5 = MessageDigest.getInstance("MD5").let { d ->
            gcode.inputStream().use { input ->
                val buf = ByteArray(1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    d.update(buf, 0, n)
                }
            }
            d.digest().joinToString("") { "%02x".format(it) }
        }
        val uuid = UUID.randomUUID().toString()
        val chunkSize = 1024 * 1024
        RandomAccessFile(gcode, "r").use { raf ->
            var offset = 0L
            while (offset < total) {
                val len = minOf(chunkSize.toLong(), total - offset).toInt()
                val chunk = ByteArray(len)
                raf.seek(offset)
                raf.readFully(chunk)
                val body = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("Check", "1")
                    .addFormDataPart("S-File-MD5", md5)
                    .addFormDataPart("Offset", offset.toString())
                    .addFormDataPart("Uuid", uuid)
                    .addFormDataPart("TotalSize", total.toString())
                    .addFormDataPart(
                        "File",
                        gcode.name,
                        chunk.toRequestBody("application/octet-stream".toMediaType()),
                    )
                    .build()
                val request = Request.Builder().url("http://$ip/uploadFile/upload").post(body).build()
                try {
                    cc1Http.newCall(request).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (!resp.isSuccessful || !text.contains("000000")) {
                            return CloudResult.Err("CC1 upload failed (HTTP ${resp.code}): ${text.take(120)}")
                        }
                    }
                } catch (e: Exception) {
                    return CloudResult.Err("CC1 upload failed: ${e.message}")
                }
                offset += len
                onStatus("Uploading G-code… ${(offset * 100 / total).toInt()}%")
            }
        }
        return CloudResult.Ok(Unit)
    }

    // ---------------- Discovery ----------------

    suspend fun discoverAll(context: Context, timeoutMs: Long = 5000): List<DiscoveredPrinter> =
        withContext(Dispatchers.IO) {
            val found = LinkedHashMap<String, DiscoveredPrinter>()
            var lock: WifiManager.MulticastLock? = null
            try {
                val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
                lock = wifi?.createMulticastLock("elegoo-lan")?.apply {
                    setReferenceCounted(false)
                    try { acquire() } catch (_: Exception) {}
                }
            } catch (_: Exception) {
            }

            val socket = DatagramSocket()
            try {
                socket.broadcast = true
                socket.soTimeout = 400
                val payload = """{"id":0,"method":7000}""".toByteArray(Charsets.UTF_8)
                // CC1 answers a plain "M99999" on UDP 3000 (SDCP), not the CC2 JSON probe.
                val cc1Payload = "M99999".toByteArray(Charsets.UTF_8)
                val targets = broadcastAddresses()
                val buffer = ByteArray(8192)
                val deadline = System.currentTimeMillis() + timeoutMs
                var lastSend = 0L

                while (System.currentTimeMillis() < deadline) {
                    if (System.currentTimeMillis() - lastSend > 700) {
                        for (addr in targets) {
                            try {
                                socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName(addr), CC2_DISCOVERY_PORT))
                                socket.send(DatagramPacket(cc1Payload, cc1Payload.size, InetAddress.getByName(addr), CC1_DISCOVERY_PORT))
                            } catch (_: Exception) {
                            }
                        }
                        lastSend = System.currentTimeMillis()
                    }
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val ip = packet.address?.hostAddress ?: continue
                        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        val json = try { JSONObject(text) } catch (_: Exception) { continue }
                        // CC1 SDCP reply: {"Id", "Data":{Name, MachineName, MainboardID, …}}
                        json.optJSONObject("Data")?.let { data ->
                            val mainboard = data.optString("MainboardID", "")
                            if (mainboard.isBlank()) return@let
                            found["cc1-$mainboard"] = DiscoveredPrinter(
                                ip = ip,
                                serial = mainboard,
                                model = data.optString("MachineName", ""),
                                name = data.optString("Name", ""),
                                lanMode = true,
                                needsAccessCode = false,
                            )
                            return@let
                        }
                        val result = json.optJSONObject("result") ?: continue
                        val serial = result.optString("sn", "")
                        if (serial.isBlank()) continue
                        found[serial] = DiscoveredPrinter(
                            ip = ip,
                            serial = serial,
                            model = result.optString("machine_model", ""),
                            name = result.optString("host_name", ""),
                            lanMode = result.optInt("lan_status", 0) == 1,
                            needsAccessCode = result.optInt("token_status", 0) != 0,
                        )
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                }
            } finally {
                try { socket.close() } catch (_: Exception) {}
                try { lock?.release() } catch (_: Exception) {}
            }
            val cc1Found = found.filterKeys { it.startsWith("cc1-") }.values
            if (cc1Found.isNotEmpty()) {
                EventNotifier.trackEvent(
                    "lan_cc1_discovered",
                    mapOf(
                        "count" to cc1Found.size,
                        "models" to cc1Found.joinToString(",") { it.model }.take(100),
                    ),
                )
            }
            found.values.toList()
        }

    // ---------------- Filament Query (LAN MQTT) ----------------

    suspend fun getFilament(
        ip: String,
        serial: String,
        accessCode: String = CC2_DEFAULT_TOKEN,
        timeoutMs: Long = 6000,
    ): FilamentInfo? = withContext(Dispatchers.IO) {
        if (ip.isBlank() || serial.isBlank()) return@withContext null
        val token = accessCode.ifBlank { CC2_DEFAULT_TOKEN }
        val clientId = "1_PC_" + (1000..9999).random()
        val requestId = clientId + "_req"
        val responses = ConcurrentLinkedQueue<String>()
        val registrations = ConcurrentLinkedQueue<String>()

        var client: Mqtt3AsyncClient? = null
        try {
            val auth = Mqtt3SimpleAuth.builder()
                .username(DEFAULT_MQTT_USER)
                .password(token.toByteArray(Charsets.UTF_8))
                .build()
            val c = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(ip)
                .serverPort(1883)
                .buildAsync()
            client = c

            c.connectWith().cleanSession(true).simpleAuth(auth).send().get(2500, TimeUnit.MILLISECONDS)
            Log.i(TAG, "LAN MQTT connected to $ip:1883 as $clientId (serial=$serial)")

            c.subscribeWith().topicFilter("elegoo/$serial/$clientId/api_response")
                .callback { pub -> responses.add(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                .send().get(1500, TimeUnit.MILLISECONDS)
            c.subscribeWith().topicFilter("elegoo/$serial/$requestId/register_response")
                .callback { pub -> registrations.add(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                .send().get(1500, TimeUnit.MILLISECONDS)
            c.subscribeWith().topicFilter("elegoo/$serial/api_status")
                .callback { pub -> responses.add(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                .send().get(1500, TimeUnit.MILLISECONDS)

            val register = JSONObject()
                .put("client_id", clientId)
                .put("request_id", requestId)
                .toString()
            c.publishWith().topic("elegoo/$serial/api_register")
                .payload(register.toByteArray(Charsets.UTF_8))
                .send().get(1500, TimeUnit.MILLISECONDS)

            // Wait for the printer to acknowledge registration before issuing requests.
            val regDeadline = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < regDeadline && registrations.isEmpty()) {
                delay(100)
            }
            Log.i(TAG, "LAN register response: ${registrations.poll() ?: "(none)"}")

            // Ask for canvas status (2005) and full printer status (1002).
            for (method in listOf(2005, 1002)) {
                val req = JSONObject()
                    .put("id", method)
                    .put("method", method)
                    .put("params", JSONObject())
                    .toString()
                c.publishWith().topic("elegoo/$serial/$clientId/api_request")
                    .payload(req.toByteArray(Charsets.UTF_8))
                    .send().get(1500, TimeUnit.MILLISECONDS)
            }

            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val msg = responses.poll()
                if (msg != null) {
                    val info = parseLanFilamentMessage(msg)
                    if (info != null && (info.present || info.trays.isNotEmpty())) {
                        return@withContext info
                    }
                } else {
                    delay(100)
                }
            }
            null
        } catch (e: Exception) {
            Log.d(TAG, "LAN getFilament error for $ip ($serial): ${e.message}")
            null
        } finally {
            try { client?.disconnect() } catch (_: Exception) {}
        }
    }

    fun parseLanFilamentMessage(payload: String): FilamentInfo? {
        try {
            val root = JSONObject(payload)
            var result: JSONObject = root.optJSONObject("result") ?: root.optJSONObject("data") ?: root

            // Status deltas may wrap the real payload in a JSON string.
            val report = result.optString("reportValue", "")
            if (report.startsWith("{")) {
                result = try { JSONObject(report) } catch (_: Exception) { result }
            }

            // CC2 puts canvas/AMS data under result.canvas_info.
            val canvas: JSONObject = result.optJSONObject("canvas_info") ?: result

            fun trayFrom(obj: JSONObject, idx: Int, canvasId: Int): LoadedTray? {
                val trayId = obj.optInt("tray_id", obj.optInt("trayId", idx))
                val fType = obj.optString("filament_type", "").ifBlank { obj.optString("filamentType", "") }
                val fName = obj.optString("filament_name", "").ifBlank { obj.optString("filamentName", "") }
                val fColor = obj.optString("filament_color", "").ifBlank { obj.optString("filamentColor", "") }
                val status = obj.optInt("status", 1)
                if (status == 0 && fType.isBlank() && fName.isBlank() && fColor.isBlank()) return null
                val name = fName.ifBlank { fType }.ifBlank { "Filament ${trayId + 1}" }
                val type = fType.ifBlank { fName }.ifBlank { "PLA" }
                return LoadedTray(trayId, name, type, normalizeColorHex(fColor), canvasId)
            }

            val trays = mutableListOf<LoadedTray>()
            canvas.optJSONArray("canvas_list")?.let { canvasList ->
                for (i in 0 until canvasList.length()) {
                    val cv = canvasList.optJSONObject(i) ?: continue
                    val canvasId = cv.optInt("canvas_id", cv.optInt("canvasId", i))
                    val trayList = cv.optJSONArray("tray_list") ?: continue
                    for (j in 0 until trayList.length()) {
                        trayFrom(trayList.optJSONObject(j) ?: continue, j, canvasId)?.let { trays.add(it) }
                    }
                }
            }
            if (trays.isEmpty()) {
                canvas.optJSONArray("tray_list")?.let { trayList ->
                    for (j in 0 until trayList.length()) {
                        trayFrom(trayList.optJSONObject(j) ?: continue, j, 0)?.let { trays.add(it) }
                    }
                }
            }
            if (trays.isNotEmpty()) {
                val activeId = canvas.optInt("active_tray_id", result.optInt("active_tray_id", 0))
                val active = trays.firstOrNull { it.trayId == activeId } ?: trays.first()
                return FilamentInfo(
                    present = true,
                    name = active.type,
                    colorHex = active.colorHex,
                    trays = trays,
                )
            }

            val mono = canvas.optJSONObject("mono_filament_info")
                ?: result.optJSONObject("mono_filament_info")
                ?: canvas.optJSONArray("mono_filament_info")?.optJSONObject(0)
                ?: result.optJSONArray("mono_filament_info")?.optJSONObject(0)
            if (mono != null) {
                trayFrom(mono, 0, 0)?.let { t ->
                    return FilamentInfo(present = true, name = t.type, colorHex = t.colorHex, trays = listOf(t))
                }
            }

            val fType = result.optString("filament_type", "")
                .ifBlank { result.optString("filamentType", "") }
                .ifBlank { result.optString("material", "") }
            val fColor = result.optString("filament_color", "")
                .ifBlank { result.optString("filamentColor", "") }
                .ifBlank { result.optString("color", "") }
            if (fType.isNotBlank() || fColor.isNotBlank()) {
                val colorHex = normalizeColorHex(fColor)
                val type = fType.ifBlank { "PLA" }
                return FilamentInfo(present = true, name = type, colorHex = colorHex, trays = listOf(LoadedTray(0, type, type, colorHex)))
            }
        } catch (_: Exception) {}
        return null
    }

    private fun normalizeColorHex(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        var s = raw.trim()
        if (s.startsWith("#")) s = s.substring(1)
        return when (s.length) {
            6 -> "#$s"
            8 -> "#${s.substring(2)}"
            else -> if (s.matches(Regex("^[0-9a-fA-F]{6}$"))) "#$s" else null
        }
    }

    private fun broadcastAddresses(): List<String> {
        val out = LinkedHashSet<String>()
        out.add("255.255.255.255")
        try {
            for (iface in NetworkInterface.getNetworkInterfaces()) {
                if (!iface.isUp || iface.isLoopback) continue
                for (ia in iface.interfaceAddresses) {
                    val address = ia.address ?: continue
                    if (address is java.net.Inet4Address) {
                        val broadcast = ia.broadcast
                        if (broadcast != null) out.add(broadcast.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return out.toList()
    }

    // ---------------- CC2 upload ----------------

    private fun uploadCc2(ip: String, file: File, token: String): CloudResult<Unit> {
        val total = file.length()
        if (total <= 0) return CloudResult.Err("G-code file is empty")
        val md5 = md5Hex(file)

        try {
            RandomAccessFile(file, "r").use { raf ->
                var offset = 0L
                while (offset < total) {
                    val len = minOf(CHUNK_SIZE.toLong(), total - offset).toInt()
                    val data = ByteArray(len)
                    raf.seek(offset)
                    raf.readFully(data)

                    val conn = URL("http://$ip/upload").openConnection() as HttpURLConnection
                    conn.requestMethod = "PUT"
                    conn.doOutput = true
                    conn.connectTimeout = 15000
                    conn.readTimeout = 180000
                    conn.setRequestProperty("Content-Type", "application/octet-stream")
                    conn.setRequestProperty("Content-Range", "bytes $offset-${offset + len - 1}/$total")
                    conn.setRequestProperty("X-File-Name", file.name)
                    conn.setRequestProperty("X-File-MD5", md5)
                    conn.setRequestProperty("X-Token", token)
                    conn.setFixedLengthStreamingMode(len)
                    conn.outputStream.use { it.write(data) }
                    val code = conn.responseCode
                    val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                        ?.bufferedReader()?.use { it.readText() }.orEmpty()
                    conn.disconnect()
                    if (code == 401) {
                        return CloudResult.Err(ACCESS_CODE_REJECTED_MESSAGE)
                    }
                    if (code !in 200..299) {
                        return CloudResult.Err("Upload failed HTTP $code: ${body.take(200)}")
                    }
                    offset += len
                }
            }
            return CloudResult.Ok(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "CC2 upload failed", e)
            return CloudResult.Err(e.message ?: "upload failed")
        }
    }

    // ---------------- CC2 start print (LAN MQTT) ----------------

    private fun slotMapJson(slots: List<PrintSlot>): org.json.JSONArray = org.json.JSONArray().apply {
        slots.forEach { put(JSONObject().put("t", it.t).put("canvas_id", it.canvasId).put("tray_id", it.trayId)) }
    }

    private fun startPrintCc2(
        ip: String,
        serial: String,
        fileName: String,
        slots: List<PrintSlot>,
        token: String,
    ): CloudResult<Unit> {
        val clientId = "1_PC_" + (100000..999999).random()
        val requestId = clientId + "_req"
        val responses = ConcurrentLinkedQueue<String>()
        val registrations = ConcurrentLinkedQueue<String>()

        var client: Mqtt3AsyncClient? = null
        try {
            val auth = Mqtt3SimpleAuth.builder()
                .username(DEFAULT_MQTT_USER)
                .password(token.toByteArray(Charsets.UTF_8))
                .build()
            val c = MqttClient.builder()
                .useMqttVersion3()
                .identifier(clientId)
                .serverHost(ip)
                .serverPort(1883)
                .buildAsync()
            client = c

            c.connectWith().cleanSession(true).simpleAuth(auth).send().get(15, TimeUnit.SECONDS)

            c.subscribeWith().topicFilter("elegoo/$serial/$clientId/api_response")
                .callback { pub -> responses.add(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                .send().get(10, TimeUnit.SECONDS)
            c.subscribeWith().topicFilter("elegoo/$serial/$requestId/register_response")
                .callback { pub -> registrations.add(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                .send().get(10, TimeUnit.SECONDS)

            // Register this client with the printer.
            val register = JSONObject()
                .put("client_id", clientId)
                .put("request_id", requestId)
                .toString()
            c.publishWith().topic("elegoo/$serial/api_register")
                .payload(register.toByteArray(Charsets.UTF_8))
                .send().get(10, TimeUnit.SECONDS)

            val regDeadline = System.currentTimeMillis() + 10_000
            while (System.currentTimeMillis() < regDeadline && registrations.isEmpty()) {
                Thread.sleep(200)
            }
            val regMsg = registrations.poll()
            if (regMsg != null) {
                val reg = try { JSONObject(regMsg) } catch (_: Exception) { null }
                val err = reg?.optString("error", "") ?: ""
                if (err.isNotEmpty() && err != "ok") {
                    return CloudResult.Err("Printer registration failed: $err")
                }
            }

            val requestIdNum = (1000..9999).random()
            val params = JSONObject()
                .put("storage_media", "local")
                .put("filename", fileName)
                .put(
                    "config",
                    JSONObject()
                        .put("delay_video", false)
                        .put("printer_check", false)
                        .put("print_layout", "A")
                        .put("bedlevel_force", false)
                        .put("slot_map", slotMapJson(slots)),
                )
            val message = JSONObject()
                .put("id", requestIdNum)
                .put("method", 1020)
                .put("params", params)
                .toString()
            c.publishWith().topic("elegoo/$serial/$clientId/api_request")
                .payload(message.toByteArray(Charsets.UTF_8))
                .send().get(10, TimeUnit.SECONDS)

            val deadline = System.currentTimeMillis() + 20_000
            while (System.currentTimeMillis() < deadline) {
                val msg = responses.poll()
                if (msg == null) {
                    Thread.sleep(150)
                    continue
                }
                val json = try { JSONObject(msg) } catch (_: Exception) { continue }
                if (json.optInt("id", -1) != requestIdNum) continue
                val code = json.optJSONObject("result")?.optInt("error_code", -1) ?: -1
                return if (code == 0) CloudResult.Ok(Unit)
                else CloudResult.Err("Printer rejected start (error $code)")
            }
            return CloudResult.Err("Printer did not respond")
        } catch (e: Exception) {
            Log.e(TAG, "LAN start print failed", e)
            if (isMqttAuthFailure(e)) return CloudResult.Err(ACCESS_CODE_REJECTED_MESSAGE)
            return CloudResult.Err(e.message ?: "LAN print failed")
        } finally {
            try { client?.disconnect() } catch (_: Exception) {}
        }
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

    /** True when the printer refused the MQTT login because the access code (password) was wrong. */
    private fun isMqttAuthFailure(e: Throwable): Boolean {
        val m = generateSequence<Throwable>(e) { it.cause }
            .joinToString(" ") { it.message?.lowercase().orEmpty() }
        return listOf(
            "not authorized", "not authorised",
            "not_authorized", "not_authorised",
            "bad user name", "bad_user_name", "bad username",
        ).any { m.contains(it) }
    }

    companion object {
        const val TAG = "LanPrint"
        const val CC2_DISCOVERY_PORT = 52700
        /** Centauri Carbon 1: "Centauri Carbon" (no "2"); SDCP over WebSocket, no access code. */
        fun isCc1Model(model: String): Boolean =
            model.contains("Centauri", ignoreCase = true) && !model.contains("2")
        const val CC1_DISCOVERY_PORT = 3000
        const val CC2_DEFAULT_TOKEN = "123456"
        const val DEFAULT_MQTT_USER = "elegoo"
        const val CHUNK_SIZE = 1024 * 1024

        /** User-facing error when the printer rejects the access code (HTTP 401 / MQTT auth). */
        const val ACCESS_CODE_REJECTED_MESSAGE =
            "The printer rejected the access code. Enter the code shown on its screen."

        fun isAccessCodeRejection(message: String): Boolean =
            message.startsWith(ACCESS_CODE_REJECTED_MESSAGE)
    }
}