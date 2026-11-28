package world.yemyat.slicepilot.cloud

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import world.yemyat.slicepilot.tracking.EventNotifier
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Live print monitor and control backend for OpenCentauri COSMOS and generic
 * Klipper/Moonraker printers over Moonraker's JSON-RPC WebSocket (`/websocket`).
 *
 * Verified against OpenCentauri/cosmos:
 *  - Moonraker listens on port 80 (standard Klipper setups typically use 7125).
 *  - `[exclude_object]` is enabled in Klipper, while Moonraker sets
 *    `enable_object_processing: False` (so the slicer embeds `EXCLUDE_OBJECT_*` tags).
 *  - Peripherals (`enclosed.cfg`): `[fan]`, `[fan_generic aux_fan]`,
 *    `[fan_generic case_fan]`, `[led case]` (white_pin), `[temperature_sensor chamber]`.
 *  - Camera: `uStreamer` MJPEG at `:8080/stream` (also advertised by `server.webcams.list`).
 */
class LanPrintMonitorMoonraker : PrintMonitor {

    /** Discovered Klipper object names for peripherals on the connected printer. */
    data class HardwareMapping(
        val auxFanObject: String? = "fan_generic aux_fan",
        val boxFanObject: String? = "fan_generic case_fan",
        val lightObject: String? = "led case",
        val chamberSensorObject: String? = "temperature_sensor chamber",
        val hasExcludeObject: Boolean = true,
        val lightIsRgbOnly: Boolean = false,
    )

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _status = MutableStateFlow<PrintStatus?>(null)
    override val status: StateFlow<PrintStatus?> = _status.asStateFlow()

    private val reqSeq = AtomicInteger(1)
    private val pendingRpc = ConcurrentHashMap<Int, String>()

    private var ws: WebSocket? = null
    private var reconnectJob: Job? = null
    @Volatile private var running = false
    @Volatile private var configuredPort: Int = DEFAULT_PORT
    @Volatile private var currentHost: String = ""
    @Volatile private var currentPort: Int = DEFAULT_PORT
    @Volatile private var currentApiKey: String = ""
    @Volatile private var hw: HardwareMapping = HardwareMapping()
    @Volatile private var resolvedCameraUrl: String? = null
    @Volatile private var webcamService: String = ""
    @Volatile private var webcamSource: String = "default_fallback"
    @Volatile private var webcamCount: Int = -1
    @Volatile private var metadataFilename: String = ""
    @Volatile private var metadataLayers: Int = 0
    @Volatile private var metadataDurationSec: Int = 0

    override val supportsExcludeObject: Boolean get() = hw.hasExcludeObject
    override val cameraNeedsArm: Boolean get() = false
    override val cameraWarmupMs: Long get() = 0L

    fun configurePort(port: Int) {
        configuredPort = if (port in 1..65535) port else DEFAULT_PORT
    }

    override fun cameraStreamUrl(ip: String): String {
        resolvedCameraUrl?.let { return it }
        val (host, _) = parseHostAndPort(ip)
        val cleanHost = host.ifBlank { currentHost }
        return "http://$cleanHost:8080/stream"
    }

    override fun cameraDiagnostics(): Map<String, Any> =
        buildMap {
            put("webcam_source", webcamSource)
            if (webcamService.isNotBlank()) put("webcam_service", webcamService)
            if (webcamCount >= 0) put("webcam_count", webcamCount)
            put("moonraker_port", currentPort)
        }

    override suspend fun start(ip: String, serial: String, accessCode: String): Boolean =
        withContext(Dispatchers.IO) {
            val (parsedHost, parsedPort) = parseHostAndPort(ip)
            if (parsedHost.isBlank()) return@withContext false
            running = true
            currentHost = parsedHost
            currentPort = parsedPort ?: configuredPort
            currentApiKey = accessCode.trim()
            resolvedCameraUrl = null
            metadataFilename = ""
            metadataLayers = 0
            metadataDurationSec = 0
            pendingRpc.clear()

            connectOnce(currentHost, currentPort, currentApiKey)
        }

    private suspend fun connectOnce(host: String, port: Int, apiKey: String): Boolean {
        val opened = CompletableDeferred<Boolean>()
        var connected = false
        val wsUrl = "ws://$host:$port/websocket"
        val reqBuilder = Request.Builder().url(wsUrl)
        if (apiKey.isNotBlank()) {
            reqBuilder.header("X-Api-Key", apiKey)
        }
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {}

        ws = http.newWebSocket(
            reqBuilder.build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "Moonraker ws connected to $host:$port")
                    connected = true
                    EventNotifier.trackEvent(
                        "lan_moonraker_monitor_connected",
                        mapOf("ip" to host, "port" to port),
                    )
                    opened.complete(true)
                    sendRpc("printer.objects.list", JSONObject(), "objects_list")
                    sendRpc("server.webcams.list", JSONObject(), "webcams")
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    if (running && connected) {
                        connected = false
                        scheduleReconnect()
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.complete(false)
                    if (running) Log.d(TAG, "Moonraker ws failure ($host:$port): ${t.message}")
                    if (running && connected) {
                        connected = false
                        EventNotifier.trackError("lan_moonraker_ws_lost", t, mapOf("ip" to host, "port" to port))
                        scheduleReconnect()
                    }
                }
            },
        )
        val ok = withTimeoutOrNull(8_000) { opened.await() } ?: false
        if (!ok) {
            EventNotifier.trackError(
                "lan_moonraker_monitor_connect_failed",
                null,
                mapOf("ip" to host, "port" to port),
            )
            try { ws?.close(1000, null) } catch (_: Exception) {}
            ws = null
        }
        return ok
    }

    private fun scheduleReconnect() {
        if (!running || reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            val backoffs = longArrayOf(2000, 4000, 8000, 12000)
            var attempt = 0
            while (isActive && running) {
                delay(backoffs[minOf(attempt, backoffs.lastIndex)])
                if (!running) break
                val ok = connectOnce(currentHost, currentPort, currentApiKey)
                if (ok) break
                attempt++
            }
        }
    }

    private fun sendRpc(method: String, params: JSONObject? = null, tag: String? = null): Int {
        val socket = ws ?: return -1
        val id = reqSeq.getAndIncrement()
        if (tag != null) pendingRpc[id] = tag
        val msg = JSONObject()
            .put("jsonrpc", "2.0")
            .put("method", method)
            .put("id", id)
        if (params != null && params.length() > 0) {
            msg.put("params", params)
        }
        try {
            socket.send(msg.toString())
        } catch (e: Exception) {
            pendingRpc.remove(id)
            Log.d(TAG, "Moonraker send $method failed: ${e.message}")
        }
        return id
    }

    private fun handleFrame(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }

        // Server-initiated notification
        val method = json.optString("method", "")
        if (method.isNotEmpty()) {
            when (method) {
                "notify_status_update" -> {
                    val delta = json.optJSONArray("params")?.optJSONObject(0) ?: return
                    applyStatusDelta(delta)
                }

                "notify_klippy_ready" -> {
                    sendRpc("printer.objects.list", JSONObject(), "objects_list")
                }

                "notify_klippy_shutdown", "notify_klippy_disconnected" -> {
                    _status.value?.let { cur ->
                        if (cur.state.isInProgress) {
                            _status.value = cur.copy(state = PrintJobState.ERROR)
                        }
                    }
                }
            }
            return
        }

        // Response to one of our RPC requests
        val id = json.optInt("id", -1)
        val tag = if (id >= 0) pendingRpc.remove(id) else null
        val result = json.optJSONObject("result") ?: return

        when (tag) {
            "objects_list" -> {
                val arr = result.optJSONArray("objects")
                val names = mutableListOf<String>()
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        arr.optString(i, "").takeIf { it.isNotBlank() }?.let { names.add(it) }
                    }
                }
                hw = discoverHardwareMapping(names)
                subscribeObjects(hw)
            }

            "subscribe", "exclude_query" -> {
                result.optJSONObject("status")?.let { applyStatusDelta(it) }
            }

            "webcams" -> {
                val cams = result.optJSONArray("webcams")
                val picked = selectPreferredWebcam(cams)
                webcamCount = cams?.length() ?: 0
                if (picked != null) {
                    val (rawUrl, service) = picked
                    resolvedCameraUrl = resolveWebcamUrl(currentHost, currentPort, rawUrl)
                    webcamService = service
                    webcamSource = "moonraker_list"
                    EventNotifier.trackEvent(
                        "moonraker_webcam_discovered",
                        mapOf(
                            "count" to webcamCount,
                            "service" to service,
                            "mjpeg_compatible" to isMjpegService(service),
                            "endpoint" to CameraStream.sanitizeEndpoint(resolvedCameraUrl.orEmpty()),
                        ),
                    )
                } else {
                    webcamService = ""
                    webcamSource = if (webcamCount == 0) "none_configured" else "disabled_or_missing_url"
                    EventNotifier.trackEvent(
                        "moonraker_webcam_discovered",
                        mapOf("count" to webcamCount, "source" to webcamSource),
                    )
                }
            }

            "metadata" -> {
                val layers = result.optInt("layer_count", 0)
                val est = result.optDouble("estimated_time", 0.0).toInt()
                if (layers > 0) metadataLayers = layers
                if (est > 0) metadataDurationSec = est
                // Re-apply metadata to current status so totalLayer / remainingSec update immediately.
                _status.value?.let { cur ->
                    val updatedLayers = if (cur.totalLayer <= 0 && metadataLayers > 0) metadataLayers else cur.totalLayer
                    val updatedRemaining =
                        cur.remainingSec ?: computeRemainingSec(cur.progress, cur.elapsedSec, metadataDurationSec)
                    if (updatedLayers != cur.totalLayer || updatedRemaining != cur.remainingSec) {
                        _status.value = cur.copy(totalLayer = updatedLayers, remainingSec = updatedRemaining)
                    }
                }
            }
        }
    }

    private fun subscribeObjects(mapping: HardwareMapping) {
        val objects = JSONObject()
            .put("print_stats", JSONObject.NULL)
            .put("virtual_sdcard", JSONObject.NULL)
            .put("display_status", JSONObject.NULL)
            .put("extruder", JSONArray(listOf("temperature", "target")))
            .put("heater_bed", JSONArray(listOf("temperature", "target")))
            .put("fan", JSONArray(listOf("speed")))
            .put("gcode_move", JSONArray(listOf("speed_factor")))
        if (mapping.hasExcludeObject) {
            objects.put("exclude_object", JSONObject.NULL)
        }
        mapping.auxFanObject?.let { objects.put(it, JSONArray(listOf("speed"))) }
        mapping.boxFanObject?.let { objects.put(it, JSONArray(listOf("speed"))) }
        mapping.chamberSensorObject?.let { objects.put(it, JSONArray(listOf("temperature"))) }
        mapping.lightObject?.let { light ->
            val fields = if (light.startsWith("led ")) listOf("color_data") else listOf("value")
            objects.put(light, JSONArray(fields))
        }
        sendRpc("printer.objects.subscribe", JSONObject().put("objects", objects), "subscribe")
    }

    private fun applyStatusDelta(delta: JSONObject) {
        // Detect RGB-only LEDs from color_data so setLight sends the right channels.
        hw.lightObject?.takeIf { it.startsWith("led ") }?.let { ledKey ->
            delta.optJSONObject(ledKey)?.optJSONArray("color_data")?.optJSONArray(0)?.let { pixel ->
                if (pixel.length() >= 4) {
                    val rgbSum = pixel.optDouble(0, 0.0) + pixel.optDouble(1, 0.0) + pixel.optDouble(2, 0.0)
                    val white = pixel.optDouble(3, 0.0)
                    if (rgbSum > 0.01 && white <= 0.01 && !hw.lightIsRgbOnly) {
                        hw = hw.copy(lightIsRgbOnly = true)
                    }
                }
            }
        }

        val next = mergeMoonrakerStatus(
            base = _status.value ?: PrintStatus(),
            statusObj = delta,
            hw = hw,
            metadataLayers = metadataLayers,
            metadataDurationSec = metadataDurationSec,
        )
        _status.value = next

        val fn = next.filename
        if (fn.isNotBlank() && fn != metadataFilename) {
            metadataFilename = fn
            metadataLayers = 0
            metadataDurationSec = 0
            sendRpc("server.files.metadata", JSONObject().put("filename", fn), "metadata")
        } else if (fn.isBlank() && metadataFilename.isNotEmpty()) {
            metadataFilename = ""
            metadataLayers = 0
            metadataDurationSec = 0
        }
    }

    override fun pausePrint() {
        _status.value?.let { _status.value = it.copy(state = PrintJobState.PAUSING) }
        sendRpc("printer.print.pause")
    }

    override fun resumePrint() {
        _status.value?.let { _status.value = it.copy(state = PrintJobState.RESUMING) }
        sendRpc("printer.print.resume")
    }

    override fun stopPrint() {
        _status.value?.let { _status.value = it.copy(state = PrintJobState.STOPPING) }
        sendRpc("printer.print.cancel")
    }

    override fun setTemperature(extruder: Int?, bed: Int?) {
        val lines = mutableListOf<String>()
        if (extruder != null) lines.add("M104 S${extruder.coerceAtLeast(0)}")
        if (bed != null) lines.add("M140 S${bed.coerceAtLeast(0)}")
        if (lines.isEmpty()) return
        sendGcode(lines.joinToString("\n"))
    }

    override fun setFanSpeed(target: String, speed: Int) {
        val clamped = speed.coerceIn(0, 255)
        val gcode = when (target) {
            "fan" -> "M106 S$clamped"
            "aux_fan" -> {
                val fanName = hw.auxFanObject?.removePrefix("fan_generic ") ?: return
                "SET_FAN_SPEED FAN=$fanName SPEED=${String.format(Locale.US, "%.2f", clamped / 255.0)}"
            }

            "box_fan" -> {
                val fanName = hw.boxFanObject?.removePrefix("fan_generic ") ?: return
                "SET_FAN_SPEED FAN=$fanName SPEED=${String.format(Locale.US, "%.2f", clamped / 255.0)}"
            }

            else -> return
        }
        sendGcode(gcode)
    }

    override fun setLight(on: Boolean) {
        val light = hw.lightObject ?: return
        val v = if (on) 1 else 0
        val gcode = when {
            light.startsWith("led ") -> {
                val name = light.removePrefix("led ")
                if (hw.lightIsRgbOnly) {
                    "SET_LED LED=$name RED=$v GREEN=$v BLUE=$v"
                } else {
                    "SET_LED LED=$name WHITE=$v"
                }
            }

            light.startsWith("output_pin ") -> {
                val name = light.removePrefix("output_pin ")
                "SET_PIN PIN=$name VALUE=$v"
            }

            else -> return
        }
        sendGcode(gcode)
    }

    override fun setCamera(enable: Boolean) {
        // uStreamer runs continuously; nothing to arm over RPC.
    }

    override fun excludeObject(name: String) {
        val gcode = buildExcludeObjectGcode(name) ?: return
        EventNotifier.trackEvent("moonraker_exclude_object", mapOf("object" to name))
        sendGcode(gcode)
        // Follow up with a targeted query so UI updates even if Moonraker coalesces deltas.
        scope.launch {
            delay(350)
            if (running) {
                sendRpc(
                    "printer.objects.query",
                    JSONObject().put("objects", JSONObject().put("exclude_object", JSONObject.NULL)),
                    "exclude_query",
                )
            }
        }
    }

    private fun sendGcode(script: String) {
        sendRpc("printer.gcode.script", JSONObject().put("script", script))
    }

    override fun shutdown() {
        running = false
        reconnectJob?.cancel()
        reconnectJob = null
        pendingRpc.clear()
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        _status.value = null
    }

    companion object {
        const val TAG = "MoonrakerMonitor"
        const val DEFAULT_PORT = 80
        const val STANDARD_MOONRAKER_PORT = 7125

        /**
         * Parse a user-entered host/IP string such as `"192.168.1.42"`, `"192.168.1.42:7125"`,
         * `"http://cosmos.local:80/"` into `(host, explicitPort?)`.
         */
        fun parseHostAndPort(raw: String): Pair<String, Int?> {
            var s = raw.trim()
            if (s.isEmpty()) return "" to null
            s = s.removePrefix("http://").removePrefix("https://").removePrefix("ws://").removePrefix("wss://")
            val slash = s.indexOf('/')
            if (slash >= 0) s = s.substring(0, slash)
            val colon = s.lastIndexOf(':')
            if (colon > 0 && s.indexOf(':') == colon) {
                val host = s.substring(0, colon).trim()
                val port = s.substring(colon + 1).trim().toIntOrNull()?.takeIf { it in 1..65535 }
                return host to port
            }
            return s.trim() to null
        }

        /** True when a Moonraker webcam `service` value serves MJPEG frames [CameraStream] can decode. */
        fun isMjpegService(service: String): Boolean {
            val s = service.lowercase()
            return s.isBlank() || s.contains("mjpeg") || s.contains("uv4l") || s.contains("ipstream")
        }

        /**
         * Pick the best enabled webcam from Moonraker's `server.webcams.list` array,
         * preferring MJPEG-compatible streams over WebRTC/HLS when multiple are configured.
         * Returns `(rawStreamUrl, service)` or null if none are enabled with a stream URL.
         */
        fun selectPreferredWebcam(cams: JSONArray?): Pair<String, String>? {
            if (cams == null) return null
            var fallback: Pair<String, String>? = null
            for (i in 0 until cams.length()) {
                val cam = cams.optJSONObject(i) ?: continue
                if (!cam.optBoolean("enabled", true)) continue
                val raw = cam.optString("stream_url", "").trim()
                if (raw.isBlank()) continue
                val service = cam.optString("service", "").trim()
                if (isMjpegService(service)) return raw to service
                if (fallback == null) fallback = raw to service
            }
            return fallback
        }

        /** Resolve a Moonraker webcam `stream_url` (which COSMOS sets to `:8080/stream`) to a full HTTP URL. */
        fun resolveWebcamUrl(host: String, moonrakerPort: Int, rawUrl: String?): String {
            val clean = rawUrl?.trim().orEmpty()
            if (clean.isEmpty()) return "http://$host:8080/stream"
            if (clean.startsWith("http://") || clean.startsWith("https://")) return clean
            if (clean.startsWith(":")) return "http://$host$clean"
            if (clean.startsWith("/")) {
                val portPart = if (moonrakerPort == 80) "" else ":$moonrakerPort"
                return "http://$host$portPart$clean"
            }
            return "http://$host/$clean"
        }

        /** Build a safe Klipper `EXCLUDE_OBJECT NAME=...` command, rejecting control characters. */
        fun buildExcludeObjectGcode(name: String): String? {
            val trimmed = name.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.any { it == '\n' || it == '\r' || it == '"' || it == ';' }) return null
            return if (trimmed.any { it.isWhitespace() }) {
                "EXCLUDE_OBJECT NAME=\"$trimmed\""
            } else {
                "EXCLUDE_OBJECT NAME=$trimmed"
            }
        }

        /**
         * Inspect Klipper's `printer.objects.list` output and pick the best matching
         * peripheral objects (COSMOS defaults first, then common Klipper conventions).
         */
        fun discoverHardwareMapping(objects: List<String>): HardwareMapping {
            val set = objects.toSet()
            val auxFan =
                when {
                    "fan_generic aux_fan" in set -> "fan_generic aux_fan"
                    else -> objects.firstOrNull { it.startsWith("fan_generic ") && it.contains("aux", ignoreCase = true) }
                }
            val boxFan =
                listOf(
                    "fan_generic case_fan",
                    "fan_generic box_fan",
                    "fan_generic exhaust_fan",
                    "fan_generic chamber_fan",
                ).firstOrNull { it in set }
                    ?: objects.firstOrNull {
                        it.startsWith("fan_generic ") && it != auxFan &&
                            (it.contains("case", true) || it.contains("box", true) ||
                                it.contains("exhaust", true) || it.contains("chamber", true))
                    }
            val light =
                listOf(
                    "led case",
                    "led caselight",
                    "output_pin caselight",
                    "output_pin case_light",
                    "output_pin led_pin",
                    "neopixel caselight",
                ).firstOrNull { it in set }
                    ?: objects.firstOrNull {
                        (it.startsWith("led ") || it.startsWith("output_pin ")) &&
                            (it.contains("light", true) || it.contains("led", true) || it.contains("case", true))
                    }
            val chamber =
                listOf(
                    "temperature_sensor chamber",
                    "temperature_fan chamber",
                    "temperature_sensor box",
                    "temperature_sensor enclosure",
                ).firstOrNull { it in set }
                    ?: objects.firstOrNull {
                        (it.startsWith("temperature_sensor ") || it.startsWith("temperature_fan ")) &&
                            (it.contains("chamber", true) || it.contains("box", true) || it.contains("enclosure", true))
                    }
            return HardwareMapping(
                auxFanObject = auxFan,
                boxFanObject = boxFan,
                lightObject = light,
                chamberSensorObject = chamber,
                hasExcludeObject = "exclude_object" in set,
            )
        }

        /** Map Klipper `print_stats.state` to [PrintJobState], preserving transition latching. */
        fun mapKlipperState(state: String?, previous: PrintJobState): PrintJobState {
            val raw = when (state?.lowercase()) {
                "printing" -> PrintJobState.PRINTING
                "paused" -> PrintJobState.PAUSED
                "complete" -> PrintJobState.COMPLETED
                "cancelled" -> PrintJobState.STOPPED
                "error" -> PrintJobState.ERROR
                "standby" -> PrintJobState.IDLE
                else -> return previous
            }
            return LanPrintMonitor.latchState(raw, previous)
        }

        /** Parse Klipper's `exclude_object` status object (handling partial delta updates). */
        fun parseExcludeObject(
            obj: JSONObject,
            baseObjects: List<PrintObject>,
            baseExcluded: Set<String>,
            baseCurrent: String?,
        ): Triple<List<PrintObject>, Set<String>, String?> {
            val objects =
                if (obj.has("objects") && !obj.isNull("objects")) {
                    val arr = obj.optJSONArray("objects")
                    val list = mutableListOf<PrintObject>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i) ?: continue
                            val name = item.optString("name", "").trim()
                            if (name.isEmpty()) continue
                            val centerArr = item.optJSONArray("center")
                            val center =
                                if (centerArr != null && centerArr.length() >= 2) {
                                    centerArr.optDouble(0).toFloat() to centerArr.optDouble(1).toFloat()
                                } else {
                                    null
                                }
                            val polyArr = item.optJSONArray("polygon")
                            val poly = mutableListOf<Pair<Float, Float>>()
                            if (polyArr != null) {
                                for (j in 0 until polyArr.length()) {
                                    val pt = polyArr.optJSONArray(j) ?: continue
                                    if (pt.length() >= 2) {
                                        poly.add(pt.optDouble(0).toFloat() to pt.optDouble(1).toFloat())
                                    }
                                }
                            }
                            list.add(PrintObject(name = name, center = center, polygon = poly))
                        }
                    }
                    list
                } else {
                    baseObjects
                }

            val excluded =
                if (obj.has("excluded_objects") && !obj.isNull("excluded_objects")) {
                    val arr = obj.optJSONArray("excluded_objects")
                    val set = LinkedHashSet<String>()
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            arr.optString(i, "").takeIf { it.isNotBlank() }?.let { set.add(it) }
                        }
                    }
                    set
                } else {
                    baseExcluded
                }

            val current =
                if (obj.has("current_object")) {
                    if (obj.isNull("current_object")) {
                        null
                    } else {
                        obj.optString("current_object", "").takeIf { it.isNotBlank() }
                    }
                } else {
                    baseCurrent
                }

            return Triple(objects, excluded, current)
        }

        private fun computeRemainingSec(progress: Int, elapsedSec: Int, metadataDurationSec: Int): Int? {
            val fromMeta = if (metadataDurationSec > 0) (metadataDurationSec - elapsedSec).coerceAtLeast(0) else null
            val fromRate =
                if (progress >= 5 && progress < 100 && elapsedSec > 0) {
                    ((elapsedSec.toLong() * (100 - progress)) / progress).toInt()
                } else {
                    null
                }
            return when {
                fromMeta != null && fromRate != null -> maxOf(fromMeta, fromRate)
                fromRate != null -> fromRate
                fromMeta != null -> fromMeta
                progress >= 100 -> 0
                else -> null
            }
        }

        /** Merge a Moonraker full status or `notify_status_update` delta into [base]. */
        fun mergeMoonrakerStatus(
            base: PrintStatus,
            statusObj: JSONObject,
            hw: HardwareMapping = HardwareMapping(),
            metadataLayers: Int = 0,
            metadataDurationSec: Int = 0,
        ): PrintStatus {
            var state = base.state
            var filename = base.filename
            var progress = base.progress
            var currentLayer = base.currentLayer
            var totalLayer = if (base.totalLayer > 0) base.totalLayer else metadataLayers
            var elapsedSec = base.elapsedSec
            var nozzleTemp = base.nozzleTemp
            var nozzleTarget = base.nozzleTarget
            var bedTemp = base.bedTemp
            var bedTarget = base.bedTarget
            var speedMode = base.speedMode
            var chamberTemp = base.chamberTemp
            var partFan = base.partFanSpeed
            var auxFan = base.auxFanSpeed
            var boxFan = base.boxFanSpeed
            var lightOn = base.lightOn
            var objects = base.objects
            var excludedObjects = base.excludedObjects
            var currentObject = base.currentObject

            statusObj.optJSONObject("print_stats")?.let { ps ->
                if (ps.has("state") && !ps.isNull("state")) {
                    state = mapKlipperState(ps.optString("state"), state)
                }
                if (ps.has("filename") && !ps.isNull("filename")) {
                    filename = ps.optString("filename", "")
                }
                if (ps.has("print_duration") && !ps.isNull("print_duration")) {
                    elapsedSec = ps.optDouble("print_duration", elapsedSec.toDouble()).toInt().coerceAtLeast(0)
                }
                ps.optJSONObject("info")?.let { info ->
                    if (info.has("current_layer") && !info.isNull("current_layer")) {
                        currentLayer = info.optInt("current_layer", currentLayer).coerceAtLeast(0)
                    }
                    if (info.has("total_layer") && !info.isNull("total_layer")) {
                        val tl = info.optInt("total_layer", 0)
                        if (tl > 0) totalLayer = tl
                    }
                }
            }

            statusObj.optJSONObject("virtual_sdcard")?.let { sd ->
                if (sd.has("progress") && !sd.isNull("progress")) {
                    val p = sd.optDouble("progress", -1.0)
                    if (p in 0.0..1.0) progress = (p * 100.0).toInt().coerceIn(0, 100)
                }
            }
            statusObj.optJSONObject("display_status")?.let { ds ->
                if (progress == 0 && ds.has("progress") && !ds.isNull("progress")) {
                    val p = ds.optDouble("progress", -1.0)
                    if (p in 0.0..1.0) progress = (p * 100.0).toInt().coerceIn(0, 100)
                }
            }

            statusObj.optJSONObject("extruder")?.let { ex ->
                if (ex.has("temperature") && !ex.isNull("temperature")) {
                    nozzleTemp = ex.optDouble("temperature", nozzleTemp.toDouble()).toFloat()
                }
                if (ex.has("target") && !ex.isNull("target")) {
                    nozzleTarget = ex.optDouble("target", nozzleTarget.toDouble()).toFloat()
                }
            }
            statusObj.optJSONObject("heater_bed")?.let { bed ->
                if (bed.has("temperature") && !bed.isNull("temperature")) {
                    bedTemp = bed.optDouble("temperature", bedTemp.toDouble()).toFloat()
                }
                if (bed.has("target") && !bed.isNull("target")) {
                    bedTarget = bed.optDouble("target", bedTarget.toDouble()).toFloat()
                }
            }

            statusObj.optJSONObject("fan")?.let { f ->
                if (f.has("speed") && !f.isNull("speed")) {
                    partFan = (f.optDouble("speed", 0.0).coerceIn(0.0, 1.0) * 255.0).toInt()
                }
            }
            hw.auxFanObject?.let { key ->
                statusObj.optJSONObject(key)?.let { f ->
                    if (f.has("speed") && !f.isNull("speed")) {
                        auxFan = (f.optDouble("speed", 0.0).coerceIn(0.0, 1.0) * 255.0).toInt()
                    }
                }
            }
            hw.boxFanObject?.let { key ->
                statusObj.optJSONObject(key)?.let { f ->
                    if (f.has("speed") && !f.isNull("speed")) {
                        boxFan = (f.optDouble("speed", 0.0).coerceIn(0.0, 1.0) * 255.0).toInt()
                    }
                }
            }
            hw.chamberSensorObject?.let { key ->
                statusObj.optJSONObject(key)?.let { sensor ->
                    if (sensor.has("temperature") && !sensor.isNull("temperature")) {
                        chamberTemp = sensor.optDouble("temperature", chamberTemp.toDouble()).toFloat()
                    }
                }
            }
            hw.lightObject?.let { key ->
                statusObj.optJSONObject(key)?.let { l ->
                    if (key.startsWith("led ")) {
                        val pixel = l.optJSONArray("color_data")?.optJSONArray(0)
                        if (pixel != null) {
                            var lit = false
                            for (i in 0 until pixel.length()) {
                                if (pixel.optDouble(i, 0.0) > 0.01) {
                                    lit = true
                                    break
                                }
                            }
                            lightOn = lit
                        }
                    } else if (l.has("value") && !l.isNull("value")) {
                        lightOn = l.optDouble("value", 0.0) > 0.01
                    }
                }
            }

            statusObj.optJSONObject("gcode_move")?.let { gm ->
                if (gm.has("speed_factor") && !gm.isNull("speed_factor")) {
                    val sf = gm.optDouble("speed_factor", 1.0)
                    speedMode = when {
                        sf < 0.75 -> 0
                        sf < 1.25 -> 1
                        sf < 1.75 -> 2
                        else -> 3
                    }
                }
            }

            statusObj.optJSONObject("exclude_object")?.let { eo ->
                val (objs, excl, curr) = parseExcludeObject(eo, objects, excludedObjects, currentObject)
                objects = objs
                excludedObjects = excl
                currentObject = curr
            }

            if (state == PrintJobState.IDLE) {
                progress = 0
                currentLayer = 0
                elapsedSec = 0
            }

            val remaining =
                if (state.isInProgress) {
                    computeRemainingSec(progress, elapsedSec, metadataDurationSec)
                } else if (state == PrintJobState.COMPLETED) {
                    0
                } else {
                    null
                }

            return base.copy(
                state = state,
                filename = filename,
                progress = progress,
                currentLayer = currentLayer,
                totalLayer = totalLayer,
                elapsedSec = elapsedSec,
                remainingSec = remaining,
                nozzleTemp = nozzleTemp,
                nozzleTarget = nozzleTarget,
                bedTemp = bedTemp,
                bedTarget = bedTarget,
                speedMode = speedMode,
                chamberTemp = chamberTemp,
                partFanSpeed = partFan,
                auxFanSpeed = auxFan,
                boxFanSpeed = boxFan,
                lightOn = lightOn,
                objects = objects,
                excludedObjects = excludedObjects,
                currentObject = currentObject,
                live = true,
            )
        }
    }
}
