package world.yemyat.slicepilot.cloud

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt3.Mqtt3AsyncClient
import com.hivemq.client.mqtt.mqtt3.message.auth.Mqtt3SimpleAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Live print job state, mapped from the CC2 machine/print status codes. */
enum class PrintJobState {
    CONNECTING,
    IDLE,
    PRINTING,
    PAUSING,
    PAUSED,
    RESUMING,
    STOPPING,
    STOPPED,
    COMPLETED,
    ERROR,
    ;

    val isTerminal: Boolean get() = this == STOPPED || this == COMPLETED || this == ERROR

    /** True when a user pause is active or in progress. */
    val isPaused: Boolean get() = this == PAUSED || this == PAUSING
}

/** A snapshot of a running print, merged from the CC2 status stream (method 1002 / 6000 pushes). */
data class PrintStatus(
    val state: PrintJobState = PrintJobState.CONNECTING,
    val filename: String = "",
    val progress: Int = 0,
    val currentLayer: Int = 0,
    val totalLayer: Int = 0,
    val elapsedSec: Int = 0,
    val remainingSec: Int? = null,
    val nozzleTemp: Float = 0f,
    val nozzleTarget: Float = 0f,
    val bedTemp: Float = 0f,
    val bedTarget: Float = 0f,
    /** CC2 speed mode: 0=Silent 50%, 1=Balanced 100%, 2=Sport 150%, 3=Ludicrous 200%. */
    val speedMode: Int = 1,
    /** Enclosure (chamber) temperature from `ztemperature_sensor` (°C); 0 until seen. */
    val chamberTemp: Float = 0f,
    /** Fan PWM 0-255 from `fans.<name>.speed`; -1 = never reported. */
    val partFanSpeed: Int = -1,
    val auxFanSpeed: Int = -1,
    val boxFanSpeed: Int = -1,
    /** Case light from `led.status`; null = never reported. */
    val lightOn: Boolean? = null,
    /** True once the printer has sent at least one real status message. */
    val live: Boolean = false,
) {
    val speedPercent: Int get() = when (speedMode) {
        0 -> 50
        2 -> 150
        3 -> 200
        else -> 100
    }
}

/**
 * Live print monitoring for Elegoo printers over the LAN MQTT (Centauri Carbon 2 protocol):
 *  - connect + register (same handshake as [LanPrintManager]),
 *  - subscribe to `elegoo/<sn>/api_status` delta pushes (method 6000)
 *    and periodic full statuses (method 1002),
 *  - JSON PING heartbeat every 10 s (the printer drops silent clients),
 *  - pause (1021) / resume (1023) / stop (1022) print control.
 */
/**
 * Live-monitor + control backend for a LAN printer. [LanPrintMonitor] speaks the CC2
 * MQTT protocol, [LanPrintMonitorCC1] the CC1 SDCP WebSocket; the UI drives either.
 */
interface PrintMonitor {
    val status: kotlinx.coroutines.flow.StateFlow<PrintStatus?>
    suspend fun start(ip: String, serial: String, accessCode: String): Boolean
    fun pausePrint()
    fun resumePrint()
    fun stopPrint()
    fun setTemperature(extruder: Int?, bed: Int?)
    fun setFanSpeed(target: String, speed: Int)
    fun setLight(on: Boolean)
    fun setCamera(enable: Boolean)
    fun shutdown()
    /** CC2 needs method 1042 to wake its camera; the CC1 streams unconditionally. */
    val cameraNeedsArm: Boolean get() = true
    /** Settle time after arming the camera (CC2 pipeline lag; CC1: none). */
    val cameraWarmupMs: Long get() = 2500L
    /** MJPEG stream endpoint for [CameraStream]. */
    fun cameraStreamUrl(ip: String): String = "http://$ip:8080/?action=stream"
    /**
     * One-shot: if the print lands in PAUSED shortly after starting (CC1
     * firmware with a Canvas starts paused for filament confirmation), send
     * a single RESUME. Armed only by fresh print starts.
     */
    fun armAutoResume() {}
}

class LanPrintMonitor : PrintMonitor {

    private val scope = CoroutineScope(Dispatchers.IO)
    private var client: Mqtt3AsyncClient? = null
    private var heartbeatJob: Job? = null
    private var pollJob: Job? = null

    private var clientId = ""
    private var requestId = ""
    private var serial = ""

    private val _status = MutableStateFlow<PrintStatus?>(null)
override     val status: StateFlow<PrintStatus?> = _status.asStateFlow()

    @Volatile var running: Boolean = false
        private set

override     suspend fun start(ip: String, serialNumber: String, accessCode: String): Boolean =
        withContext(Dispatchers.IO) {
            shutdown()
            if (ip.isBlank() || serialNumber.isBlank()) return@withContext false
            serial = serialNumber
            val token = accessCode.ifBlank { LanPrintManager.CC2_DEFAULT_TOKEN }
            clientId = "1_PC_" + (100000..999999).random()
            requestId = clientId + "_req"
            _status.value = PrintStatus(state = PrintJobState.CONNECTING)

            val registrations = ConcurrentLinkedQueue<String>()
            try {
                val auth = Mqtt3SimpleAuth.builder()
                    .username(LanPrintManager.DEFAULT_MQTT_USER)
                    .password(token.toByteArray(Charsets.UTF_8))
                    .build()
                val c = MqttClient.builder()
                    .useMqttVersion3()
                    .identifier(clientId)
                    .serverHost(ip)
                    .serverPort(1883)
                    .addDisconnectedListener { d ->
                        Log.d(TAG, "monitor disconnected: ${d.cause}")
                        updateStatus { it.copy(live = false) }
                    }
                    .buildAsync()
                client = c

                c.connectWith().cleanSession(true).keepAlive(30).simpleAuth(auth)
                    .send().get(10, TimeUnit.SECONDS)

                c.subscribeWith().topicFilter("elegoo/$serial/$clientId/api_response")
                    .callback { pub -> handlePayload(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                    .send().get(5, TimeUnit.SECONDS)
                c.subscribeWith().topicFilter("elegoo/$serial/$requestId/register_response")
                    .callback { pub -> registrations.add(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                    .send().get(5, TimeUnit.SECONDS)
                c.subscribeWith().topicFilter("elegoo/$serial/api_status")
                    .callback { pub -> handlePayload(String(pub.payloadAsBytes, Charsets.UTF_8)) }
                    .send().get(5, TimeUnit.SECONDS)

                publish("elegoo/$serial/api_register",
                    JSONObject()
                        .put("client_id", clientId)
                        .put("request_id", requestId)
                        .toString())

                val regDeadline = System.currentTimeMillis() + 5000
                while (System.currentTimeMillis() < regDeadline && registrations.isEmpty()) {
                    delay(100)
                }
                val regMsg = registrations.poll()
                if (regMsg != null) {
                    val err = try { JSONObject(regMsg).optString("error", "ok") } catch (_: Exception) { "ok" }
                    if (err != "ok") {
                        Log.w(TAG, "monitor registration failed: $err")
                        shutdown()
                        return@withContext false
                    }
                }

                running = true
                Log.i(TAG, "print monitor live on $ip (sn=$serial)")
                requestStatus()

                heartbeatJob = scope.launch {
                    while (isActive) {
                        delay(10_000)
                        publish("elegoo/$serial/$clientId/api_request", """{"type":"PING"}""")
                    }
                }
                // Safety net: deltas can be missed; a full status every 30 s re-syncs the view.
                pollJob = scope.launch {
                    while (isActive) {
                        delay(30_000)
                        requestStatus()
                    }
                }
                true
            } catch (e: Exception) {
                Log.d(TAG, "monitor start failed for $ip: ${e.message}")
                shutdown()
                false
            }
        }

override     fun pausePrint() {
        sendControl(1021) { it.copy(state = PrintJobState.PAUSING) }
        resendUntilConfirmed(1021) { it == PrintJobState.PAUSED }
    }

override     fun resumePrint() {
        sendControl(1023) { it.copy(state = PrintJobState.RESUMING) }
        resendUntilConfirmed(1023) { it == PrintJobState.PRINTING }
    }

override     fun stopPrint() {
        sendControl(1022) { it.copy(state = PrintJobState.STOPPING) }
        resendUntilConfirmed(1022) { it == PrintJobState.STOPPED || it == PrintJobState.COMPLETED }
    }

    private fun sendControl(method: Int, optimistic: (PrintStatus) -> PrintStatus) {
        updateStatus(optimistic)
        publishControl(method)
    }

    private fun publishControl(method: Int) {
        val c = client ?: return
        val msg = JSONObject()
            .put("id", (1000..9999).random())
            .put("method", method)
            .put("params", JSONObject())
            .toString()
        scope.launch {
            publish("elegoo/$serial/$clientId/api_request", msg)
        }
    }

    /**
     * Method 1028 SET_TEMPERATURE: set nozzle/bed targets (params `{"extruder": n,
     * "heater_bed": n}`, verified against Matrix `setTargetTemp`).
     */
override     fun setTemperature(extruder: Int?, bed: Int?) {
        val params = JSONObject()
        if (extruder != null) params.put("extruder", extruder)
        if (bed != null) params.put("heater_bed", bed)
        publishMethod(1028, params)
    }

    /**
     * Method 1030 SET_FAN_SPEED: fan PWM 0-255 for `fan` (part cooling), `aux_fan`
     * (chamber circulation) or `box_fan` (enclosure exhaust); Matrix `fanControl`.
     */
override     fun setFanSpeed(target: String, speed: Int) {
        publishMethod(1030, JSONObject().put(target, speed.coerceIn(0, 255)))
    }

    /**
     * Method 1029 SET_LIGHT: case light on/off (params `{"power": 1|0}`, verified
     * against Matrix `lightSwitch`).
     */
override     fun setLight(on: Boolean) {
        publishMethod(1029, JSONObject().put("power", if (on) 1 else 0))
    }

    /** Method 1042 VIDEO_STREAM: enable/disable the camera stream served on port 8080. */
override     fun setCamera(enable: Boolean) {
        publishMethod(1042, JSONObject().put("enable", enable))
    }

    private fun publishMethod(method: Int, params: JSONObject) {
        val c = client ?: return
        val msg = JSONObject()
            .put("id", (1000..9999).random())
            .put("method", method)
            .put("params", params)
            .toString()
        scope.launch {
            publish("elegoo/$serial/$clientId/api_request", msg)
        }
    }

    private var controlRetryJob: Job? = null

    /**
     * The printer's MQTT commands are fire-and-forget; a publish can be swallowed while the
     * printer is busy (observed: stop ignored, UI flipped back to Printing). Resend the
     * command until the status stream confirms it, so one tap is enough.
     */
    private fun resendUntilConfirmed(method: Int, confirm: (PrintJobState) -> Boolean) {
        controlRetryJob?.cancel()
        controlRetryJob = scope.launch {
            repeat(2) {
                val confirmed = withTimeoutOrNull(6_000) {
                    status.first { it != null && confirm(it.state) }
                    true
                } ?: false
                if (confirmed) return@launch
                Log.i(TAG, "control method $method not confirmed, resending")
                publishControl(method)
            }
        }
    }

    private fun requestStatus() {
        val c = client ?: return
        val msg = JSONObject()
            .put("id", METHOD_GET_STATUS)
            .put("method", METHOD_GET_STATUS)
            .put("params", JSONObject())
            .toString()
        scope.launch {
            publish("elegoo/$serial/$clientId/api_request", msg)
        }
    }

override     fun shutdown() {
        running = false
        heartbeatJob?.cancel()
        pollJob?.cancel()
        heartbeatJob = null
        pollJob = null
        try { client?.disconnect() } catch (_: Exception) {}
        client = null
        _status.value = null
    }

    private fun publish(topic: String, payload: String) {
        val c = client ?: return
        try {
            c.publishWith().topic(topic)
                .payload(payload.toByteArray(Charsets.UTF_8))
                .send().get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.d(TAG, "monitor publish to $topic failed: ${e.message}")
        }
    }

    /** Handles both api_response (method 1002 replies) and api_status pushes (method 6000). */
    private fun handlePayload(payload: String) {
        val json = try { JSONObject(payload) } catch (_: Exception) { return }
        when (json.optInt("method", -1)) {
            METHOD_GET_STATUS, METHOD_STATUS_PUSH ->
                json.optJSONObject("result")?.let { applyResult(it) }
        }
    }

    private fun applyResult(result: JSONObject) {
        val merged = mergePrintStatus(_status.value ?: PrintStatus(), result)
        _status.value = merged
    }

    private inline fun updateStatus(transform: (PrintStatus) -> PrintStatus) {
        _status.value?.let { _status.value = transform(it) }
    }

    companion object {
        const val TAG = "PrintMonitor"
        const val METHOD_GET_STATUS = 1002
        const val METHOD_STATUS_PUSH = 6000

        /**
         * Merge a full (method 1002) or delta (method 6000) status [result] into [base].
         * Fields absent from the delta keep their previous values, so deltas apply incrementally.
         */
        fun mergePrintStatus(base: PrintStatus, result: JSONObject): PrintStatus {
            var json = result
            // Status deltas may wrap the real payload in a JSON string.
            val report = json.optString("reportValue", "")
            if (report.startsWith("{")) {
                json = try { JSONObject(report) } catch (_: Exception) { json }
            }

            var s = base
            val ps = json.optJSONObject("print_status")
            if (ps != null) {
                if (ps.has("filename")) s = s.copy(filename = ps.optString("filename", s.filename))
                if (ps.has("current_layer")) s = s.copy(currentLayer = ps.optInt("current_layer", s.currentLayer))
                if (ps.has("total_layer")) s = s.copy(totalLayer = ps.optInt("total_layer", s.totalLayer))
                if (ps.has("print_duration")) s = s.copy(elapsedSec = ps.optDouble("print_duration", s.elapsedSec.toDouble()).toInt())
                if (ps.has("remaining_time_sec")) {
                    s = s.copy(remainingSec = ps.optInt("remaining_time_sec", 0).coerceAtLeast(0))
                } else if (ps.has("total_duration") && ps.has("print_duration")) {
                    val rem = ps.optInt("total_duration", 0) - ps.optDouble("print_duration", 0.0).toInt()
                    s = s.copy(remainingSec = rem.coerceAtLeast(0))
                }
                if (ps.has("progress")) s = s.copy(progress = ps.optInt("progress", s.progress).coerceIn(0, 100))
                val stateStr = ps.optString("state", "")
                if (stateStr.isNotBlank()) {
                    mapJobState(stateStr.lowercase(), null, null, s.state)?.let { s = s.copy(state = it) }
                }
            }
            val ms = json.optJSONObject("machine_status")
            if (ms != null) {
                if (ms.has("progress")) s = s.copy(progress = ms.optInt("progress", s.progress).coerceIn(0, 100))
                val machine = if (ms.has("status")) ms.optInt("status", -1) else null
                val sub = if (ms.has("sub_status")) ms.optInt("sub_status", -1) else null
                if (machine != null || sub != null) {
                    mapJobState(null, machine, sub, s.state)?.let { s = s.copy(state = it) }
                }
            }

            json.optJSONObject("extruder")?.let { ex ->
                if (ex.has("temperature")) s = s.copy(nozzleTemp = ex.optDouble("temperature", s.nozzleTemp.toDouble()).toFloat())
                if (ex.has("target")) s = s.copy(nozzleTarget = ex.optDouble("target", s.nozzleTarget.toDouble()).toFloat())
            }
            json.optJSONObject("heater_bed")?.let { bed ->
                if (bed.has("temperature")) s = s.copy(bedTemp = bed.optDouble("temperature", s.bedTemp.toDouble()).toFloat())
                if (bed.has("target")) s = s.copy(bedTarget = bed.optDouble("target", s.bedTarget.toDouble()).toFloat())
            }
            // "gcode_move_inf" on stock firmware, "gcode_move" on some variants.
            (json.optJSONObject("gcode_move_inf") ?: json.optJSONObject("gcode_move"))?.let { gm ->
                if (gm.has("speed_mode")) s = s.copy(speedMode = gm.optInt("speed_mode", s.speedMode).coerceIn(0, 3))
            }
            // Enclosure temperature; the field is `ztemperature_sensor` on stock firmware.
            (json.optJSONObject("ztemperature_sensor") ?: json.optJSONObject("chamber"))?.let { cz ->
                if (cz.has("temperature")) {
                    s = s.copy(chamberTemp = cz.optDouble("temperature", s.chamberTemp.toDouble()).toFloat())
                }
            }
            json.optJSONObject("fans")?.let { fans ->
                fans.optJSONObject("fan")?.let {
                    if (it.has("speed")) s = s.copy(partFanSpeed = it.optInt("speed", s.partFanSpeed))
                }
                fans.optJSONObject("aux_fan")?.let {
                    if (it.has("speed")) s = s.copy(auxFanSpeed = it.optInt("speed", s.auxFanSpeed))
                }
                fans.optJSONObject("box_fan")?.let {
                    if (it.has("speed")) s = s.copy(boxFanSpeed = it.optInt("speed", s.boxFanSpeed))
                }
            }
            json.optJSONObject("led")?.let { led ->
                if (led.has("status")) s = s.copy(lightOn = led.optInt("status") != 0)
            }

            // The CC2 estimate (`total_duration - print_duration`, i.e. the slicer's linear
            // time model) ignores heat-up waits, color changes and speed adjustments and is
            // consistently too short. Blend it with an estimate from actual elapsed time and
            // observed progress, keeping the longer of the two: it self-corrects as the print
            // proceeds and only the printer knows the endgame (tower, purge, cooling moves).
            return blendRemainingEstimate(s.copy(live = true))
        }

        private fun blendRemainingEstimate(s: PrintStatus): PrintStatus {
            val reported = s.remainingSec ?: return s
            val progress = s.progress
            if (progress !in 5..95 || reported <= 0 || s.elapsedSec <= 0) return s
            val observedPerPercent = s.elapsedSec.toLong() / progress
            val observed = observedPerPercent * (100 - progress)
            val blended = maxOf(reported.toLong(), observed).coerceAtMost(48 * 3600L)
            return s.copy(remainingSec = blended.toInt())
        }

        /**
         * Map CC2 status codes to a [PrintJobState].
         * `print_status.state` strings win over machine/sub-status codes;
         * returns null when nothing recognizable is present (keep previous state).
         */
        fun mapJobState(
            stateStr: String?,
            machineStatus: Int?,
            subStatus: Int?,
            previous: PrintJobState,
        ): PrintJobState? {
            stateStr?.let { s ->
                val mapped = when (s) {
                    "printing" -> PrintJobState.PRINTING
                    "paused" -> PrintJobState.PAUSED
                    "complete" -> PrintJobState.COMPLETED
                    "cancelled" -> PrintJobState.STOPPED
                    "error" -> PrintJobState.ERROR
                    "standby" -> PrintJobState.IDLE // printer back at its main screen
                    else -> null
                }
                return mapped?.let { latchState(it, previous) }
            }
            subStatus?.let { sub ->
                val mapped = when (sub) {
                    2075, 2402, 2802, 2902, 1045, 1096, 1405, 1906 -> PrintJobState.PRINTING // printing, resume/auto-level done, preheating
                    2401 -> PrintJobState.RESUMING
                    2501 -> PrintJobState.PAUSING
                    2502, 2505 -> PrintJobState.PAUSED
                    2503 -> PrintJobState.STOPPING
                    // The CC2 keeps the last sub_status when idling: stopped + machine
                    // idle (1/26) means "back at the main screen", not an active stop.
                    2504 -> if (machineStatus == 1 || machineStatus == 26) PrintJobState.IDLE
                    else PrintJobState.STOPPED
                    2077 -> PrintJobState.COMPLETED
                    else -> null
                }
                if (mapped != null) return latchState(mapped, previous)
            }
            machineStatus?.let { m ->
                val mapped = when (m) {
                    2, 15 -> PrintJobState.PRINTING // printing, power-loss recovery
                    14 -> PrintJobState.ERROR // emergency stop
                    1, 26 -> PrintJobState.IDLE // idle at the main screen (26 = manual-ready,
                    // the same idle gate Matrix uses for fan control)
                    else -> null
                }
                return mapped?.let { latchState(it, previous) }
            }
            return null
        }

        /**
         * Prevent the status stream from resurrecting a finished job: the CC2 keeps streaming
         * `printing` status for seconds after a stop command (it flushes queued moves), and the
         * 30 s full-status resync can deliver stale `printing` after the job already stopped.
         * Without latching, the UI flickers STOPPED→PRINTING→STOPPED.
         */
        internal fun latchState(mapped: PrintJobState, previous: PrintJobState): PrintJobState = when {
            // Explicit idle releases a latched terminal state (printer is back at its
            // main screen) — terminal states would otherwise show "Print stopped" forever.
            mapped == PrintJobState.IDLE -> PrintJobState.IDLE
            // Terminal states latch: late messages for the finished job keep the terminal state.
            previous.isTerminal && mapped != previous -> previous
            // While stopping, the printer legitimately keeps printing briefly — don't regress.
            previous == PrintJobState.STOPPING && mapped == PrintJobState.PRINTING -> previous
            else -> mapped
        }
    }
}
