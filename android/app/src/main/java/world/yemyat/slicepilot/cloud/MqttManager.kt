package world.yemyat.slicepilot.cloud

import android.util.Log
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

data class LoadedTray(
    val trayId: Int,
    val name: String,
    val type: String,
    val colorHex: String?,
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

    @Volatile var connected: Boolean = false
        private set

    fun filamentFor(deviceCode: String): FilamentInfo? = filamentMap[deviceCode]

    fun start(host: String, port: Int, path: String, clientId: String, username: String, password: String, topicFilter: String) {
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
                        Log.i("Mqtt", "connected, subscribing $topicFilter")
                        c.subscribeWith()
                            .topicFilter(topicFilter)
                            .callback { publish ->
                                val payload = publish.payloadAsBytes.toString(Charsets.UTF_8)
                                Log.i("Mqtt", "MSG ${publish.topic} => ${payload.take(800)}")
                                handle(publish.topic.toString(), payload)
                            }
                            .send()
                            .whenComplete { _, serr ->
                                if (serr != null) Log.e("Mqtt", "subscribe failed", serr)
                                else Log.i("Mqtt", "subscribed")
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
        try {
            val json = JSONObject(payload)
            val deviceCode = extractDeviceCode(json) ?: return
            val info = extractFilament(json)
            if (info != null) {
                filamentMap[deviceCode] = info
                _filaments.value = HashMap(filamentMap)
            }
        } catch (e: Exception) {
            Log.w("Mqtt", "parse error: ${e.message}")
        }
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
