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

    private var client: Mqtt5AsyncClient? = null
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

    @Volatile var connected: Boolean = false
        private set

    fun filamentFor(deviceCode: String): FilamentInfo? = filamentMap[deviceCode]

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
        try {
            val ws = com.hivemq.client.mqtt.MqttWebSocketConfig.builder().serverPath(path).build()
            val auth = com.hivemq.client.mqtt.mqtt5.message.auth.Mqtt5SimpleAuth.builder()
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
                .addConnectedListener { connected = true; Log.i("Mqtt", "connected") }
                .addDisconnectedListener { d -> connected = false; Log.i("Mqtt", "disconnected: ${d.cause}") }
                .buildAsync()
            client = c
            c.connectWith()
                .cleanStart(true)
                .simpleAuth(auth)
                .send()
                .whenComplete { _, err ->
                    if (err != null) {
                        Log.e("Mqtt", "connect failed", err)
                    } else {
                        val allTopics = (listOf(topicFilter) + extraTopicFilters).distinct()
                        Log.i("Mqtt", "connected, subscribing $allTopics")
                        for (topic in allTopics) {
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
                }
        } catch (e: Exception) {
            Log.e("Mqtt", "start failed", e)
        }
    }

    fun stop() {
        try {
            client?.disconnectWith()?.send()
        } catch (_: Exception) {
        }
        client = null
        connected = false
    }

    private fun handle(topic: String, payload: String) {
        Log.i("Mqtt", "msg topic=$topic len=${payload.length} head=${payload.take(160)}")
        try {
            val json = JSONObject(payload)
            val deviceCode = extractDeviceCode(json) ?: return
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
        val merged = LanPrintMonitor.mergePrintStatus(printStatusMap[deviceCode] ?: PrintStatus(), node)
        Log.i("Mqtt", "status merged: state=${merged.state} progress=${merged.progress} layer=${merged.currentLayer}/${merged.totalLayer}")
        printStatusMap[deviceCode] = merged
        _printStatuses.value = HashMap(printStatusMap)
    }

    /**
     * Find the object carrying `machine_status` / `print_status`. Cloud payloads nest reports
     * as objects, JSON-string values (reportValue), arrays of {reportLinkKey, reportValue}
     * entries, and generic `data`/`result` wrappers — all are walked.
     */
    private fun findStatusNode(json: JSONObject, depth: Int): JSONObject? {
        if (depth > 6) return null
        if (json.has("machine_status") || json.has("print_status")) return flattenReportArrays(json)
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
