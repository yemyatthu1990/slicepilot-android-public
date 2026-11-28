package world.yemyat.slicepilot.cloud

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class LoadedTray(
    val trayId: Int,
    val name: String,
    val type: String,
    val colorHex: String?,
    val canvasId: Int = 0,
)

data class PrintSlot(
    val t: Int,
    val canvasId: Int,
    val trayId: Int,
)

data class FilamentInfo(
    val present: Boolean = false,
    val name: String = "",
    val colorHex: String? = null,
    val remaining: Int? = null,
    val trays: List<LoadedTray> = emptyList(),
)

class MqttManager {

    private val lock = Any()
    private var client: Mqtt5AsyncClient? = null
    private var desiredTopics: List<String> = emptyList()
    private val filamentMap = ConcurrentHashMap<String, FilamentInfo>()
    private val _filaments = MutableStateFlow<Map<String, FilamentInfo>>(emptyMap())
    val filaments: StateFlow<Map<String, FilamentInfo>> = _filaments.asStateFlow()

    /**
     * Live print statuses parsed from the cloud status stream. The cloud relays the same
     * CC2 schema (`machine_status` / `print_status` / `reportValue`) as the LAN MQTT protocol,
     * so [LanPrintMonitor.mergePrintStatus] merges full and delta messages per device.
     */
    private val printStatusMap = ConcurrentHashMap<String, PrintStatus>()
    private val _printStatuses = MutableStateFlow<Map<String, PrintStatus>>(emptyMap())
    val printStatuses: StateFlow<Map<String, PrintStatus>> = _printStatuses.asStateFlow()

    /** Live online/offline events from `.../device/onoffline` (`onlineStatus: 0|1`). */
    private val onlineStatusMap = ConcurrentHashMap<String, Boolean>()
    private val _onlineStatuses = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val onlineStatuses: StateFlow<Map<String, Boolean>> = _onlineStatuses.asStateFlow()

    @Volatile var connected: Boolean = false
        private set

    fun filamentFor(deviceCode: String): FilamentInfo? = filamentMap[deviceCode]

    /**
     * Seed or refresh the per-device baseline from a full REST report snapshot so
     * incremental MQTT deltas (which omit `total_layer`, `filename`, targets, etc.)
     * never clobber the full print status.
     */
    fun seedPrintStatus(deviceCode: String, snapshot: PrintStatus) {
        if (deviceCode.isBlank()) return
        printStatusMap.compute(deviceCode) { _, existing ->
            if (existing == null || !existing.live) {
                snapshot
            } else {
                mergeSnapshotWithLive(snapshot = snapshot, live = existing)
            }
        }
        _printStatuses.value = HashMap(printStatusMap)
    }

    fun start(
        host: String,
        port: Int,
        path: String,
        clientId: String,
        username: String,
        password: String,
        topicFilter: String,
        extraTopicFilters: List<String> = emptyList(),
    ) {
        val allTopics = (listOf(topicFilter) + extraTopicFilters).distinct()
        val oldClient: Mqtt5AsyncClient?
        val newClient: Mqtt5AsyncClient
        val auth: com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth
        try {
            val ws = com.hivemq.client.mqtt.MqttWebSocketConfig.builder().serverPath(path).build()
            auth = com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth.builder()
                .username(username)
                .password(password.toByteArray(Charsets.UTF_8))
                .build()
            val c = MqttClient.builder()
                .useMqttVersion5()
                .identifier(clientId)
                .serverHost(host)
                .serverPort(port)
                .webSocketConfig(ws)
                .sslWithDefaultConfig()
                .automaticReconnectWithDefaultConfig()
                .addConnectedListener { ctx ->
                    val current = synchronized(lock) { client }
                    if (ctx.clientConfig.clientIdentifier.orElse(null)?.toString() != clientId || current == null) {
                        return@addConnectedListener
                    }
                    connected = true
                    Log.i("Mqtt", "connected ($clientId), subscribing $allTopics")
                    subscribeAll(current, allTopics)
                }
                .addDisconnectedListener { d ->
                    val isCurrent = synchronized(lock) {
                        client?.config?.clientIdentifier?.orElse(null)?.toString() == clientId
                    }
                    if (isCurrent) {
                        connected = false
                    }
                    Log.i("Mqtt", "disconnected ($clientId): ${d.cause}")
                }
                .buildAsync()
            synchronized(lock) {
                oldClient = client
                client = c
                desiredTopics = allTopics
                connected = false
            }
            newClient = c
        } catch (e: Exception) {
            Log.e("Mqtt", "start failed", e)
            return
        }

        // Always tear down any prior client so automaticReconnect never leaves zombie connections.
        if (oldClient != null) {
            try {
                oldClient.disconnectWith().send()
            } catch (_: Exception) {
            }
        }

        try {
            newClient.connectWith()
                .cleanStart(true)
                .keepAlive(60)
                .simpleAuth(auth)
                .send()
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.e("Mqtt", "connect failed ($clientId)", err)
                    }
                }
        } catch (e: Exception) {
            Log.e("Mqtt", "connect send failed", e)
        }
    }

    private fun subscribeAll(c: Mqtt5AsyncClient, topics: List<String>) {
        for (topic in topics) {
            c.subscribeWith()
                .topicFilter(topic)
                .callback { publish ->
                    val payload = publish.payloadAsBytes.toString(Charsets.UTF_8)
                    Log.i("Mqtt", "MSG ${publish.topic} => ${payload.take(800)}")
                    handle(publish.topic.toString(), payload)
                }
                .send()
                .whenComplete { _, serr ->
                    if (serr != null) Log.e("Mqtt", "subscribe failed ($topic)", serr)
                    else Log.i("Mqtt", "subscribed $topic")
                }
        }
    }

    fun stop() {
        val old = synchronized(lock) {
            val c = client
            client = null
            desiredTopics = emptyList()
            connected = false
            c
        }
        try {
            old?.disconnectWith()?.send()
        } catch (_: Exception) {
        }
    }

    private fun handle(topic: String, payload: String) {
        Log.i("Mqtt", "msg topic=$topic len=${payload.length} head=${payload.take(160)}")
        try {
            val json = JSONObject(payload)
            val deviceCode = extractDeviceCode(json) ?: return
            if (json.has("onlineStatus")) {
                val isOnline =
                    when (val v = json.opt("onlineStatus")) {
                        is Boolean -> v
                        is Number -> v.toInt() == 1
                        is String -> v == "1" || v.equals("online", ignoreCase = true) || v.equals("true", ignoreCase = true)
                        else -> false
                    }
                onlineStatusMap[deviceCode] = isOnline
                _onlineStatuses.value = HashMap(onlineStatusMap)
            }
            extractPrintStatus(deviceCode, json)
            val info = extractFilament(json)
            if (info != null) {
                filamentMap[deviceCode] = info
                _filaments.value = HashMap(filamentMap)
            }
        } catch (e: Exception) {
            Log.w("Mqtt", "parse error: ${e.message}")
        }
    }

    /** Merge any CC2 status node carried by a cloud MQTT message into the per-device print status. */
    private fun extractPrintStatus(deviceCode: String, json: JSONObject) {
        val node = findStatusNode(json, 0) ?: return
        val merged = printStatusMap.compute(deviceCode) { _, existing ->
            LanPrintMonitor.mergePrintStatus(existing ?: PrintStatus(), node)
        } ?: return
        Log.i("Mqtt", "status merged: state=${merged.state} progress=${merged.progress} layer=${merged.currentLayer}/${merged.totalLayer}")
        _printStatuses.value = HashMap(printStatusMap)
    }

    /**
     * Find the object carrying CC2 status fields (`machine_status`, `print_status`, `extruder`,
     * `heater_bed`, `ztemperature_sensor`, `fans`, `led`, `gcode_move_inf`). Cloud payloads nest
     * reports as objects, JSON-string values (`reportValue`), arrays of `{reportLinkKey, reportValue}`
     * entries, and generic `data`/`result` wrappers — all are walked.
     */
    private fun findStatusNode(json: JSONObject, depth: Int): JSONObject? {
        if (depth > 6) return null
        if (STATUS_NODE_KEYS.any { json.has(it) }) return flattenReportArrays(json)
        val keys = json.keys()
        while (keys.hasNext()) {
            when (val v = json.get(keys.next())) {
                is JSONObject -> findStatusNode(v, depth + 1)?.let { return it }
                is JSONArray -> {
                    for (i in 0 until v.length()) {
                        val item = v.optJSONObject(i) ?: continue
                        findStatusNode(item, depth + 1)?.let { return it }
                    }
                }
                is String -> if (v.startsWith("{")) {
                    val inner = try { JSONObject(v) } catch (_: Exception) { null }
                    inner?.let { findStatusNode(it, depth + 1) }?.let { return it }
                }
                else -> {}
            }
        }
        return null
    }

    companion object {
        private val STATUS_NODE_KEYS = setOf(
            "machine_status",
            "print_status",
            "extruder",
            "heater_bed",
            "ztemperature_sensor",
            "chamber",
            "fans",
            "led",
            "gcode_move_inf",
            "gcode_move",
        )

        /**
         * Combine a full REST snapshot with live MQTT delta state: REST carries fields deltas omit
         * (`totalLayer`, `filename`, targets, fans, `lightOn`), while live MQTT may be a few seconds
         * ahead on `elapsedSec`, `currentLayer`, or `progress` for the same in-progress job.
         */
        internal fun mergeSnapshotWithLive(snapshot: PrintStatus, live: PrintStatus): PrintStatus {
            val sameActiveJob =
                snapshot.state.isInProgress &&
                    live.state.isInProgress &&
                    (snapshot.filename.isBlank() || live.filename.isBlank() || snapshot.filename == live.filename)
            return snapshot.copy(
                filename = snapshot.filename.ifBlank { if (sameActiveJob) live.filename else "" },
                totalLayer = if (snapshot.totalLayer > 0) snapshot.totalLayer else if (sameActiveJob) live.totalLayer else 0,
                currentLayer = if (sameActiveJob) maxOf(snapshot.currentLayer, live.currentLayer) else snapshot.currentLayer,
                progress = if (sameActiveJob) maxOf(snapshot.progress, live.progress) else snapshot.progress,
                elapsedSec = if (sameActiveJob) maxOf(snapshot.elapsedSec, live.elapsedSec) else snapshot.elapsedSec,
                lightOn = snapshot.lightOn ?: live.lightOn,
                live = true,
            )
        }
    }

    /**
     * Convert cloud report arrays ([{reportLinkKey, reportValue}, …]) into the nested
     * object shape the LAN parser expects, e.g. print_status: [{reportLinkKey:"progress",…}]
     * becomes print_status: {progress: …}.
     */
    private fun flattenReportArrays(json: JSONObject): JSONObject {
        val out = JSONObject()
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val v = json.get(key)
            if (v is JSONArray) {
                val obj = JSONObject()
                var flattened = true
                for (i in 0 until v.length()) {
                    val e = v.optJSONObject(i)
                    if (e == null || !e.has("reportLinkKey")) {
                        flattened = false
                        break
                    }
                    // Nested payloads (fans, led) arrive as JSON strings; the LAN parser
                    // expects objects.
                    val value = e.opt("reportValue")
                    obj.put(
                        e.getString("reportLinkKey"),
                        if (value is String && value.startsWith("{")) {
                            try { JSONObject(value) } catch (_: Exception) { value }
                        } else {
                            value
                        },
                    )
                }
                if (flattened && obj.length() > 0) {
                    out.put(key, obj)
                    continue
                }
            }
            out.put(key, v)
        }
        return out
    }

    private fun extractDeviceCode(json: JSONObject): String? {
        for (k in listOf("deviceCode", "serialNo", "sn", "deviceId", "device_id")) {
            val v = json.optString(k, "")
            if (v.isNotBlank()) return v
        }
        val d = json.optJSONObject("data")
        if (d != null) {
            for (k in listOf("deviceCode", "serialNo", "sn", "deviceId")) {
                val v = d.optString(k, "")
                if (v.isNotBlank()) return v
            }
        }
        return null
    }

    private fun extractFilament(json: JSONObject): FilamentInfo? {
        val name = firstString(json, "filamentType", "resinType", "materialName", "material", "filament", "resin")
        val color = firstString(json, "filamentColor", "resinColor", "color", "colour")
        val remaining = firstInt(json, "remaining", "remain", "remainAmount", "level", "remainingPercent", "resinRemaining", "filamentRemaining")
        if (name == null && color == null && remaining == null) return null
        return FilamentInfo(
            present = true,
            name = name ?: "",
            colorHex = normalizeColor(color),
            remaining = remaining?.coerceIn(0, 100),
        )
    }

    private fun normalizeColor(c: String?): String? {
        if (c.isNullOrBlank()) return null
        val s = c.trim()
        return when {
            s.startsWith("#") -> s
            s.matches(Regex("^[0-9a-fA-F]{6}$")) -> "#$s"
            s.matches(Regex("^[0-9a-fA-F]{8}$")) -> "#${s.substring(2)}"
            else -> null
        }
    }

    private fun firstString(root: JSONObject, vararg keys: String): String? {
        for (k in keys) {
            val v = root.optString(k, "")
            if (v.isNotBlank()) return v
        }
        val it = root.keys()
        while (it.hasNext()) {
            val key = it.next()
            val v = root.get(key)
            if (v is JSONObject) {
                val r = firstString(v, *keys)
                if (r != null) return r
            }
        }
        return null
    }

    private fun firstInt(root: JSONObject, vararg keys: String): Int? {
        for (k in keys) {
            if (root.has(k) && !root.isNull(k)) {
                val v = root.get(k)
                when (v) {
                    is Number -> return v.toInt()
                    is String -> v.toIntOrNull()?.let { return it }
                }
            }
        }
        val it = root.keys()
        while (it.hasNext()) {
            val key = it.next()
            val v = root.get(key)
            if (v is JSONObject) {
                val r = firstInt(v, *keys)
                if (r != null) return r
            }
        }
        return null
    }
}
