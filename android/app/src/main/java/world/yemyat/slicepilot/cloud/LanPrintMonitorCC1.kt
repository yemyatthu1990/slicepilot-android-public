package world.yemyat.slicepilot.cloud

import android.util.Log
import world.yemyat.slicepilot.tracking.EventNotifier
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

/**
 * Live print monitor for the original Centauri Carbon (CC1). The CC1 speaks SDCP v3:
 * a WebSocket at `ws://ip:3030/websocket` with `{Id, Topic, Data:{Cmd, Data, …}}`
 * envelopes — no MQTT, no access code. Protocol ground truth: Elegoo's open-source
 * elegoo-link SDK (`elegoo_fdm_cc_*`) and community wire captures (pycentauri).
 *
 * Quirks mirrored here:
 *  - Cmd 512 status pushes are subscribed, but pushes can be permanently dead on some
 *    firmware states, so a Cmd 0 one-shot poll runs alongside (pycentauri's approach).
 *  - Unknown Cmd codes crash the printer's `app` daemon and kill the print, so only
 *    firmware-confirmed codes are ever sent (0, 128–131, 403, 512).
 */
class LanPrintMonitorCC1 : PrintMonitor {

    private val http = OkHttpClient.Builder()
        .pingInterval(20, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _status = MutableStateFlow<PrintStatus?>(null)
    override val status: StateFlow<PrintStatus?> = _status.asStateFlow()

    private var ws: WebSocket? = null
    private var pollJob: Job? = null
    private var controlRetryJob: Job? = null
    @Volatile private var running = false
    private var mainboardId = ""

    // Canvas/AMS filament (Cmd 324 GET_CANVAS_STATUS; the SDK enables it for
    // firmware > 1.1 — older firmware must never be probed with it, unknown
    // Cmd codes crash the `app` daemon).
    private val _filament = MutableStateFlow<FilamentInfo?>(null)
    val filament: StateFlow<FilamentInfo?> = _filament.asStateFlow()
    @Volatile private var canvasSupported = false

    // One-shot auto-resume for the two CC1 "press to continue" pauses:
    //  - print start with a Canvas: starts paused for filament confirmation
    //    (progress 0, within 60 s of a fresh start);
    //  - print end: paused to confirm filament removal (progress ≥ 99).
    // A user pause mid-print (progress in between) is never touched.
    @Volatile private var autoResumeStart = false
    @Volatile private var autoResumeEnd = false
    @Volatile private var autoResumeDeadline = 0L

    override fun armAutoResume() {
        autoResumeStart = true
        autoResumeEnd = true
        autoResumeDeadline = System.currentTimeMillis() + 60_000
    }

    override suspend fun start(ip: String, serial: String, accessCode: String): Boolean =
        withContext(Dispatchers.IO) {
            running = true
            mainboardId = serial
            val opened = CompletableDeferred<Boolean>()
            var connected = false
            val request = Request.Builder().url("ws://$ip:$CC1_WS_PORT/websocket").build()
            ws = http.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i(TAG, "CC1 ws connected to $ip")
                    connected = true
                    EventNotifier.trackEvent("lan_cc1_monitor_connected", mapOf("ip" to ip))
                    opened.complete(true)
                    // Subscribe to status pushes and keep one-shot polls as a fallback:
                    // pushes go silent on some firmware states (idle, paused, …).
                    sendCmd(CMD_SUBSCRIBE, JSONObject().put("TimePeriod", 1000))
                    // Force an Attributes push (paused/errored states never auto-push):
                    // its FirmwareVersion gates the Canvas query (Cmd 324 needs FW > 1.1).
                    sendCmd(CMD_GET_ATTRIBUTES, JSONObject())
                    pollJob?.cancel()
                    pollJob = scope.launch {
                        var tick = 0
                        while (isActive) {
                            sendCmd(CMD_GET_STATUS, JSONObject())
                            // Canvas/filament rarely changes; refresh every ~30 s.
                            if (canvasSupported && tick % 10 == 0) queryCanvas()
                            tick++
                            delay(3000)
                        }
                    }
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    handleFrame(text)
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    webSocket.close(1000, null)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!opened.isCompleted) opened.complete(false)
                    if (running) Log.d(TAG, "CC1 ws failure: ${t.message}")
                    // A lost connection after a successful connect: mid-session drop
                    // (firmware daemon crash / Wi-Fi) — worth reporting.
                    if (running && connected) {
                        connected = false
                        EventNotifier.trackError("lan_cc1_ws_lost", t, mapOf("ip" to ip))
                    }
                }
            })
            val ok = withTimeoutOrNull(8_000) { opened.await() } ?: false
            if (!ok) {
                EventNotifier.trackError(
                    "lan_cc1_monitor_connect_failed",
                    null,
                    mapOf("ip" to ip),
                )
                shutdown()
            }
            ok
        }

    private fun handleFrame(text: String) {
        val json = try { JSONObject(text) } catch (_: Exception) { return }
        val topic = json.optString("Topic", "")
        val data = json.optJSONObject("Data")
        if (topic.contains("/attributes/")) {
            val fw = data?.optJSONObject("Attributes")?.optString("FirmwareVersion", "").orEmpty()
            // SDK gate (elegoo-link): Canvas/filament mapping exists for FW > 1.1.x.
            val ver = Regex("""(\d+)\.(\d+)""").find(fw)
            if (ver != null) {
                val major = ver.groupValues[1].toIntOrNull() ?: 0
                val minor = ver.groupValues[2].toIntOrNull() ?: 0
                if (major > 1 || (major == 1 && minor > 1)) {
                    canvasSupported = true
                    queryCanvas()
                }
            }
            return
        }
        if (topic.contains("/response/")) {
            if (data?.optInt("Cmd", -1) == CMD_GET_CANVAS) handleCanvasResponse(data)
            return
        }
        if (!topic.contains("/status/")) return
        val status = data?.optJSONObject("Status") ?: return
        _status.value = mapCc1Status(_status.value ?: PrintStatus(), status)
        val st = _status.value
        if (st?.state == PrintJobState.PAUSED) {
            if (autoResumeStart && st.progress <= 1 && System.currentTimeMillis() < autoResumeDeadline) {
                autoResumeStart = false
                autoResumeEnd = false
                Log.i(TAG, "CC1 start-confirm pause at progress ${st.progress}%; auto-resuming (Cmd 131)")
                sendCmd(CMD_RESUME, JSONObject())
            } else if (autoResumeEnd && st.progress >= 99) {
                autoResumeEnd = false
                Log.i(TAG, "CC1 end-confirm pause at progress ${st.progress}%; auto-resuming (Cmd 131)")
                sendCmd(CMD_RESUME, JSONObject())
            }
        }
    }

    /** Cmd 324 GET_CANVAS_STATUS: Canvas/AMS filament trays (firmware > 1.1). */
    private fun queryCanvas() {
        if (!canvasSupported) return
        sendCmd(CMD_GET_CANVAS, JSONObject())
    }

    private fun handleCanvasResponse(data: JSONObject) {
        parseCanvasPayload(data)?.let { info ->
            Log.i(TAG, "canvas: ${info.trays.size} trays, active=${info.name.ifBlank { "none" }}")
            _filament.value = info
        }
    }

    /** Send one SDCP command envelope (fire-and-forget, like the CC2 publishes). */
    private fun sendCmd(cmd: Int, data: JSONObject) {
        val socket = ws ?: return
        val msg = JSONObject()
            .put("Id", mainboardId)
            .put("Topic", "sdcp/request/$mainboardId")
            .put(
                "Data",
                JSONObject()
                    .put("Cmd", cmd)
                    .put("Data", data)
                    .put("RequestID", requestId())
                    .put("MainboardID", mainboardId)
                    .put("TimeStamp", System.currentTimeMillis())
                    .put("From", 1),
            )
            .toString()
        try {
            socket.send(msg)
        } catch (e: Exception) {
            Log.d(TAG, "CC1 send Cmd $cmd failed: ${e.message}")
        }
    }

    private fun sendControl(cmd: Int, optimistic: (PrintStatus) -> PrintStatus) {
        _status.value?.let { _status.value = optimistic(it) }
        sendCmd(cmd, JSONObject())
    }

    /** Resend the command until the status stream confirms it (mirrors the CC2 monitor). */
    private fun resendUntilConfirmed(cmd: Int, confirm: (PrintJobState) -> Boolean) {
        controlRetryJob?.cancel()
        controlRetryJob = scope.launch {
            repeat(2) {
                val confirmed = withTimeoutOrNull(6_000) {
                    status.first { it != null && confirm(it.state) }
                    true
                } ?: false
                if (confirmed) return@launch
                Log.i(TAG, "CC1 Cmd $cmd not confirmed, resending")
                sendCmd(cmd, JSONObject())
            }
        }
    }

    override fun pausePrint() {
        sendControl(CMD_PAUSE) { it.copy(state = PrintJobState.PAUSING) }
        resendUntilConfirmed(CMD_PAUSE) { it == PrintJobState.PAUSED }
    }

    override fun resumePrint() {
        sendControl(CMD_RESUME) { it.copy(state = PrintJobState.RESUMING) }
        resendUntilConfirmed(CMD_RESUME) { it == PrintJobState.PRINTING }
    }

    override fun stopPrint() {
        sendControl(CMD_STOP) { it.copy(state = PrintJobState.STOPPING) }
        resendUntilConfirmed(CMD_STOP) { it == PrintJobState.STOPPED || it == PrintJobState.COMPLETED }
    }

    /** Cmd 403 with TempTarget* keys (fan/temp variant, confirmed working on CC firmware). */
    override fun setTemperature(extruder: Int?, bed: Int?) {
        val params = JSONObject()
        if (extruder != null) params.put("TempTargetNozzle", extruder)
        if (bed != null) params.put("TempTargetHotbed", bed)
        sendCmd(CMD_CHANGE_PARAMS, params)
    }

    /**
     * Cmd 403 TargetFanSpeed. The CC2 API names fans `fan`/`aux_fan`/`box_fan` on a
     * 0-255 PWM scale; the CC1 names them ModelFan/AuxiliaryFan/BoxFan on 0-100 %.
     */
    override fun setFanSpeed(target: String, speed: Int) {
        val cc1Key = when (target) {
            "fan" -> "ModelFan"
            "aux_fan" -> "AuxiliaryFan"
            "box_fan" -> "BoxFan"
            else -> return
        }
        val params = JSONObject().put(
            "TargetFanSpeed",
            JSONObject().put(cc1Key, speed * 100 / 255),
        )
        sendCmd(CMD_CHANGE_PARAMS, params)
    }

    /** Cmd 403 LightStatus.SecondLight — the CC1 case light (verified by community probing). */
    override fun setLight(on: Boolean) {
        sendCmd(
            CMD_CHANGE_PARAMS,
            JSONObject().put("LightStatus", JSONObject().put("SecondLight", if (on) 1 else 0)),
        )
    }

    /** The CC1 camera streams MJPEG continuously on :3031 — nothing to enable. */
    override fun setCamera(enable: Boolean) {}

    override val cameraNeedsArm: Boolean get() = false
    override val cameraWarmupMs: Long get() = 0L
    override fun cameraStreamUrl(ip: String): String = "http://$ip:3031/video"

    override fun shutdown() {
        running = false
        autoResumeStart = false
        autoResumeEnd = false
        pollJob?.cancel()
        controlRetryJob?.cancel()
        pollJob = null
        controlRetryJob = null
        try { ws?.close(1000, null) } catch (_: Exception) {}
        ws = null
        _status.value = null
    }

    companion object {
        const val TAG = "Cc1Monitor"
        const val CC1_WS_PORT = 3030
        const val CMD_GET_STATUS = 0
        const val CMD_GET_ATTRIBUTES = 1
        const val CMD_GET_CANVAS = 324
        const val CMD_START_PRINT = 128
        const val CMD_PAUSE = 129
        const val CMD_STOP = 130
        const val CMD_RESUME = 131
        const val CMD_CHANGE_PARAMS = 403
        const val CMD_SUBSCRIBE = 512

        private fun requestId(): String =
            java.util.UUID.randomUUID().toString().replace("-", "").take(16)

        /**
         * Open a short-lived SDCP WebSocket, send one command and wait for its Ack
         * (used for START_PRINT after upload, where no monitor is running yet).
         */
        suspend fun oneShotCommand(
            ip: String,
            mainboard: String,
            cmd: Int,
            data: JSONObject,
            timeoutMs: Long = 12_000,
        ): Boolean = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).build()
            val ack = CompletableDeferred<Boolean>()
            val listener = object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    val msg = JSONObject()
                        .put("Id", mainboard)
                        .put("Topic", "sdcp/request/$mainboard")
                        .put(
                            "Data",
                            JSONObject()
                                .put("Cmd", cmd)
                                .put("Data", data)
                                .put("RequestID", requestId())
                                .put("MainboardID", mainboard)
                                .put("TimeStamp", System.currentTimeMillis())
                                .put("From", 1),
                        )
                    webSocket.send(msg.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val json = try { JSONObject(text) } catch (_: Exception) { return }
                    if (!json.optString("Topic").contains("/response/")) return
                    val inner = json.optJSONObject("Data")?.optJSONObject("Data") ?: return
                    if (inner.has("Ack") && !ack.isCompleted) ack.complete(inner.optInt("Ack") == 0)
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    if (!ack.isCompleted) ack.complete(false)
                }
            }
            val webSocket = client.newWebSocket(
                Request.Builder().url("ws://$ip:$CC1_WS_PORT/websocket").build(),
                listener,
            )
            val ok = withTimeoutOrNull(timeoutMs) { ack.await() } ?: false
            try { webSocket.close(1000, null) } catch (_: Exception) {}
            ok
        }

        /**
         * Cmd 324 response: same canvas schema as the CC2's method 2005
         * (`canvas_list[].tray_list[]` with filament_name/type/color, status 1 = loaded).
         */
        internal fun parseCanvasPayload(data: JSONObject): FilamentInfo? {
            val payload = data.optJSONObject("Data") ?: return null
            if (payload.optInt("Ack", 0) != 0) return null
            val canvas = payload.optJSONObject("canvas_info") ?: payload
            val canvasList = canvas.optJSONArray("canvas_list") ?: return null
            val trays = mutableListOf<LoadedTray>()
            var active: LoadedTray? = null
            for (i in 0 until canvasList.length()) {
                val cv = canvasList.optJSONObject(i) ?: continue
                val canvasId = cv.optInt("canvas_id", i)
                val trayList = cv.optJSONArray("tray_list") ?: continue
                for (j in 0 until trayList.length()) {
                    val t = trayList.optJSONObject(j) ?: continue
                    val type = t.optString("filament_type").ifBlank { t.optString("filament_name") }
                    val name = t.optString("filament_name").ifBlank { type }.ifBlank { "Filament ${j + 1}" }
                    var color = t.optString("filament_color")
                    if (color.isNotBlank() && !color.startsWith("#")) color = "#$color"
                    val tray = LoadedTray(
                        trayId = t.optInt("tray_id", j),
                        name = name,
                        type = type.ifBlank { name },
                        colorHex = color.takeIf { it.isNotBlank() },
                        canvasId = canvasId,
                    )
                    trays += tray
                    if (t.optInt("status", 0) == 1) active = tray
                }
            }
            val activeTrayId = canvas.optInt("active_tray_id", -1)
            if (active == null && activeTrayId >= 0) {
                active = trays.firstOrNull { it.trayId == activeTrayId }
            }
            if (trays.isEmpty()) return null
            return FilamentInfo(
                present = active != null,
                name = active?.name.orEmpty(),
                colorHex = active?.colorHex,
                trays = trays,
            )
        }

        /**
         * One-shot Canvas fetch for screens with no live monitor (home card):
         * connect, Cmd 1 for the firmware gate, then Cmd 324 when supported.
         * Never sends 324 blind — unknown Cmd codes crash the CC1 `app` daemon.
         */
        suspend fun oneShotCanvas(
            ip: String,
            mainboard: String,
            timeoutMs: Long = 10_000,
        ): FilamentInfo? = withContext(Dispatchers.IO) {
            val client = OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).build()
            val done = CompletableDeferred<FilamentInfo?>()
            var webSocket: WebSocket? = null
            fun sendCmd(cmd: Int) {
                val msg = JSONObject()
                    .put("Id", mainboard)
                    .put("Topic", "sdcp/request/$mainboard")
                    .put(
                        "Data",
                        JSONObject()
                            .put("Cmd", cmd)
                            .put("Data", JSONObject())
                            .put("RequestID", requestId())
                            .put("MainboardID", mainboard)
                            .put("TimeStamp", System.currentTimeMillis())
                            .put("From", 1),
                    )
                webSocket?.send(msg.toString())
            }
            webSocket = client.newWebSocket(
                Request.Builder().url("ws://$ip:$CC1_WS_PORT/websocket").build(),
                object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        sendCmd(CMD_GET_ATTRIBUTES)
                    }

                    override fun onMessage(webSocket: WebSocket, text: String) {
                        val json = try { JSONObject(text) } catch (_: Exception) { return }
                        val topic = json.optString("Topic")
                        if (topic.contains("/attributes/")) {
                            val fw = json.optJSONObject("Data")
                                ?.optJSONObject("Attributes")?.optString("FirmwareVersion", "").orEmpty()
                            val ver = Regex("""(\d+)\.(\d+)""").find(fw)
                            val major = ver?.groupValues?.get(1)?.toIntOrNull() ?: 0
                            val minor = ver?.groupValues?.get(2)?.toIntOrNull() ?: 0
                            if (major > 1 || (major == 1 && minor > 1)) sendCmd(CMD_GET_CANVAS)
                            else if (!done.isCompleted) done.complete(null)
                        } else if (topic.contains("/response/")) {
                            val data = json.optJSONObject("Data") ?: return
                            if (data.optInt("Cmd", -1) == CMD_GET_CANVAS && !done.isCompleted) {
                                done.complete(parseCanvasPayload(data))
                            }
                        }
                    }

                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                        if (!done.isCompleted) done.complete(null)
                    }
                },
            )
            val info = withTimeoutOrNull(timeoutMs) { done.await() }
            try { webSocket?.close(1000, null) } catch (_: Exception) {}
            info
        }

        /**
         * Map a CC1 `PrintInfo.Status` code (SDCP, elegoo-link adapter lines 33-62)
         * to [PrintJobState]. Prep codes (preheat, leveling, resonance) read as
         * PRINTING; latching prevents stop/complete flicker from late frames.
         */
        fun mapCc1PrintState(code: Int, previous: PrintJobState): PrintJobState? {
            val mapped = when (code) {
                0 -> PrintJobState.IDLE
                1 -> PrintJobState.PRINTING // homing at print start
                5 -> PrintJobState.PAUSING
                6 -> PrintJobState.PAUSED
                7 -> PrintJobState.STOPPING
                8 -> PrintJobState.STOPPED
                9 -> PrintJobState.COMPLETED
                10, 11 -> PrintJobState.PRINTING // file/printer checking
                12 -> PrintJobState.RESUMING
                13, 18 -> PrintJobState.PRINTING // printing / print start
                14 -> PrintJobState.ERROR
                15, 16, 17, 19, 20, 21, 22 -> PrintJobState.PRINTING // leveling/preheat/…
                else -> null
            }
            return mapped?.let { LanPrintMonitor.latchState(it, previous) }
        }

        /** Extract (target, actual) from scalar or the old pair-form `[target, actual]`. */
        private fun tempPair(status: JSONObject, actualKey: String, targetKey: String): Pair<Double, Double> {
            return when (val v = status.opt(actualKey)) {
                is JSONArray -> Pair(
                    v.optDouble(0, 0.0),
                    v.optDouble(1, 0.0),
                )
                is Number -> Pair(
                    status.optDouble(targetKey, v.toDouble()),
                    v.toDouble(),
                )
                else -> return Pair(
                    status.optDouble(targetKey, Double.NaN),
                    Double.NaN,
                )
            }
        }

        /** Merge a CC1 `Data.Status` object into the shared [PrintStatus] model. */
        fun mapCc1Status(base: PrintStatus, status: JSONObject): PrintStatus {
            var s = base
            fun temp(actualKey: String, targetKey: String, set: (PrintStatus, Float, Float) -> PrintStatus) {
                if (!status.has(actualKey) && !status.has(targetKey)) return
                val (target, actual) = tempPair(status, actualKey, targetKey)
                if (!actual.isNaN()) s = set(s, actual.toFloat(), if (target.isNaN()) 0f else target.toFloat())
            }
            temp("TempOfNozzle", "TempTargetNozzle") { st, a, t -> st.copy(nozzleTemp = a, nozzleTarget = t) }
            temp("TempOfHotbed", "TempTargetHotbed") { st, a, t -> st.copy(bedTemp = a, bedTarget = t) }
            if (status.has("TempOfBox")) {
                s = s.copy(chamberTemp = status.optDouble("TempOfBox", s.chamberTemp.toDouble()).toFloat())
            }
            status.optJSONObject("CurrentFanSpeed")?.let { fans ->
                if (fans.has("ModelFan")) s = s.copy(partFanSpeed = fans.optInt("ModelFan") * 255 / 100)
                if (fans.has("AuxiliaryFan")) s = s.copy(auxFanSpeed = fans.optInt("AuxiliaryFan") * 255 / 100)
                if (fans.has("BoxFan")) s = s.copy(boxFanSpeed = fans.optInt("BoxFan") * 255 / 100)
            }
            status.optJSONObject("LightStatus")?.let { light ->
                if (light.has("SecondLight")) s = s.copy(lightOn = light.optInt("SecondLight") != 0)
            }
            status.optJSONObject("PrintInfo")?.let { info ->
                if (info.has("Filename")) s = s.copy(filename = info.optString("Filename", s.filename))
                if (info.has("CurrentLayer")) s = s.copy(currentLayer = info.optInt("CurrentLayer", s.currentLayer))
                if (info.has("TotalLayer")) s = s.copy(totalLayer = info.optInt("TotalLayer", s.totalLayer))
                if (info.has("CurrentTicks")) s = s.copy(elapsedSec = info.optInt("CurrentTicks", s.elapsedSec))
                if (info.has("Progress")) s = s.copy(progress = info.optInt("Progress", s.progress).coerceIn(0, 100))
                if (info.has("PrintSpeedPct")) {
                    val pct = info.optInt("PrintSpeedPct", 100)
                    val mode = when {
                        pct <= 80 -> 0
                        pct <= 100 -> 1
                        pct <= 110 -> 2
                        else -> 3
                    }
                    s = s.copy(speedMode = mode)
                }
                if (info.has("Status")) {
                    mapCc1PrintState(info.optInt("Status", -1), s.state)?.let { s = s.copy(state = it) }
                }
                // The CC1 estimates remaining time from its own linear model; blend with
                // observed rate like the CC2 path (the estimate ignores pauses).
                if (info.has("TotalTicks") && info.has("CurrentTicks")) {
                    val rem = info.optInt("TotalTicks", 0) - info.optInt("CurrentTicks", 0)
                    s = s.copy(remainingSec = rem.coerceAtLeast(0))
                }
            }
            s.remainingSec?.let { rem ->
                val progress = s.progress
                if (progress in 5..95 && rem > 0 && s.elapsedSec > 0) {
                    val observed = s.elapsedSec.toLong() / progress * (100 - progress)
                    s = s.copy(remainingSec = maxOf(rem.toLong(), observed).coerceAtMost(48 * 3600L).toInt())
                }
            }
            return s.copy(live = true)
        }
    }
}
