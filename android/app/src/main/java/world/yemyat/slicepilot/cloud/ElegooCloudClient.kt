package world.yemyat.slicepilot.cloud

import android.content.Context
import world.yemyat.slicepilot.cloud.auth.CloudAuthClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import world.yemyat.slicepilot.BuildConfig

data class CloudPrinter(
    val id: String,
    val name: String,
    val model: String,
    val online: Boolean,
)

class CloudUser(val nickname: String, val email: String, val region: String)

sealed class CloudResult<out T> {
    data class Ok<T>(val value: T) : CloudResult<T>()
    data class Err(val message: String) : CloudResult<Nothing>()
}

class ElegooCloudClient(
    context: Context,
    val auth: CloudAuthClient = CloudAuthClient(context),
) {

    private val gateway: String
        get() = if (auth.region == "china")
            BuildConfig.CLOUD_GATEWAY_CHINA
        else
            BuildConfig.CLOUD_GATEWAY_GLOBAL

    private val deviceApi = "/api/v1/device-management-server"

    val isLoggedIn: Boolean get() = auth.isLoggedIn

    suspend fun validateSession(): CloudResult<CloudUser> = withContext(Dispatchers.IO) {
        if (!auth.isLoggedIn) {
            return@withContext CloudResult.Err("not logged in")
        }
        if (!auth.refreshIfNeeded()) {
            auth.logout()
            return@withContext CloudResult.Err("session expired")
        }
        when (val r = listPrinters()) {
            is CloudResult.Ok -> CloudResult.Ok(CloudUser(auth.nickname, auth.email, auth.region))
            is CloudResult.Err -> {
                android.util.Log.w("ElegooCloud", "validateSession failed: ${r.message}")
                if (auth.isAuthFailure(r.message)) {
                    auth.logout()
                    CloudResult.Err("signed out")
                } else {
                    CloudResult.Err(r.message)
                }
            }
        }
    }

    private suspend fun apiPost(path: String, body: JSONObject, authed: Boolean): JSONObject {
        return postRaw(gateway + path, body, authed)
    }

    suspend fun listPrinters(): CloudResult<List<CloudPrinter>> = withContext(Dispatchers.IO) {
        if (!auth.tokenUsable()) auth.refreshTokens()
        try {
            val json = apiPost(
                deviceApi + "/device/list/query",
                JSONObject().put("pageNum", 1).put("pageSize", 50),
                authed = true,
            )
            if (!isSuccess(json)) {
                return@withContext CloudResult.Err(json.optString("msg", "device list failed (code ${json.optInt("code")})"))
            }
            android.util.Log.i("CloudClient", "device list raw: ${json.toString().take(1500)}")
            val arr = when (val d = json.opt("data")) {
                is org.json.JSONArray -> d
                is JSONObject -> d.optJSONArray("list") ?: d.optJSONArray("records") ?: d.optJSONArray("devices")
                else -> null
            } ?: return@withContext CloudResult.Ok(emptyList())
            val printers = ArrayList<CloudPrinter>()
            for (i in 0 until arr.length()) {
                val dd = arr.getJSONObject(i)
                val uid = dd.optString("userId", "")
                if (uid.isNotBlank()) auth.userId = uid
                printers.add(
                    CloudPrinter(
                        id = firstNonBlank(dd, "serialNo", "pcode", "deviceCode", "id"),
                        name = firstNonBlank(dd, "deviceName", "name", "productName"),
                        model = firstNonBlank(dd, "productName", "deviceModel", "model"),
                        online = parseOnline(dd.opt("onlineStatus")),
                    )
                )
            }
            CloudResult.Ok(printers)
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "device list failed")
        }
    }

    data class MqttConfig(
        val host: String,
        val port: Int,
        val path: String,
        val clientId: String,
        val username: String,
        val password: String,
        val topicFilter: String,
        val extraTopicFilters: List<String> = emptyList(),
    )

    suspend fun getMqttConfig(): CloudResult<MqttConfig> = withContext(Dispatchers.IO) {
        if (!auth.tokenUsable()) auth.refreshTokens()
        try {
            val userId = auth.userId
            if (userId.isBlank()) return@withContext CloudResult.Err("mqtt: no userId")
            val clientId = "elegooapp$userId-${System.currentTimeMillis()}"
            val json = getRaw(
                gateway + deviceApi + "/mqtt-link/mqtt-client?mqttClientId=$clientId",
                authed = true,
            )
            if (!isSuccess(json)) {
                return@withContext CloudResult.Err(json.optString("msg", "mqtt config failed (code ${json.optInt("code")})"))
            }
            val d = json.optJSONObject("data")
                ?: return@withContext CloudResult.Err("mqtt: no data")
            val hostUrl = d.optString("host", "")
            if (hostUrl.isBlank()) return@withContext CloudResult.Err("mqtt: no host")
            val uri = java.net.URI(hostUrl)
            val host = uri.host ?: hostUrl.substringAfter("://").substringBefore(":")
            val port = if (uri.port > 0) uri.port else 8085
            val path = uri.path.ifBlank { "/mqtt" }
            val username = d.optString("mqttUserName", "")
            val password = d.optString("mqttPassword", "")
            if (username.isBlank() || password.isBlank()) {
                return@withContext CloudResult.Err("mqtt: missing credentials")
            }
            // `subscribeAuthorization` is a broker-side ACL *regex* applied to
            // the subscribe filter itself: `\w*` does not match `+`, so MQTT
            // wildcard filters like `+/+/user/#` are SUBACK-rejected while
            // literal `app/v1/user/#` is allowed. The optional `-suffix` group
            // covers the server-assigned (suffixed) clientId topic.
            val clientIdValue = d.optString("mqttClientId", clientId)
            val subs = mutableListOf("app/v1/$username/#")
            if (clientIdValue.isNotBlank() && clientIdValue != username) {
                subs += "app/v1/$clientIdValue/#"
            }
            android.util.Log.i(
                "CloudClient",
                "mqtt-client config: user=$username clientId=$clientIdValue subs=$subs",
            )
            CloudResult.Ok(
                MqttConfig(
                    host = host,
                    port = port,
                    path = path,
                    clientId = clientIdValue,
                    username = username,
                    password = password,
                    topicFilter = subs.first(),
                    extraTopicFilters = subs.drop(1),
                )
            )
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "mqtt config failed")
        }
    }

    /**
     * Snapshot of the last reported printer status (same report-data endpoint as filament).
     * The cloud report stores each section as `[{reportLinkKey, reportValue}, …]`; sections
     * are flattened and merged with [LanPrintMonitor.mergePrintStatus].
     */
    suspend fun getReportStatus(deviceCode: String): PrintStatus? = withContext(Dispatchers.IO) {
        if (!auth.tokenUsable()) auth.refreshTokens()
        try {
            val json = getRaw(
                gateway + deviceApi + "/device/report-data/list?deviceCode=$deviceCode",
                authed = true,
            )
            if (!isSuccess(json)) return@withContext null
            val data = json.optJSONObject("data") ?: return@withContext null
            val result = JSONObject()
            for (section in listOf(
                "machine_status", "print_status", "extruder", "heater_bed",
                "ztemperature_sensor", "fans", "led",
            )) {
                val arr = data.optJSONArray(section) ?: continue
                val obj = JSONObject()
                for (i in 0 until arr.length()) {
                    val e = arr.optJSONObject(i) ?: continue
                    val key = e.optString("reportLinkKey", "")
                    if (key.isBlank()) continue
                    var value: Any? = e.opt("reportValue")
                    // Nested sections (fans) arrive as JSON strings.
                    if (value is String && value.startsWith("{")) {
                        value = try { JSONObject(value) } catch (_: Exception) { value }
                    }
                    obj.put(key, value)
                }
                if (obj.length() > 0) result.put(section, obj)
            }
            if (result.length() == 0) return@withContext null
            LanPrintMonitor.mergePrintStatus(PrintStatus(), result)
        } catch (e: Exception) {
            android.util.Log.w("CloudClient", "getReportStatus failed: ${e.message}")
            null
        }
    }

    suspend fun getFilament(deviceCode: String): FilamentInfo? = withContext(Dispatchers.IO) {        if (!auth.tokenUsable()) auth.refreshTokens()
        try {
            val json = getRaw(
                gateway + deviceApi + "/device/report-data/list?deviceCode=$deviceCode",
                authed = true,
            )
            if (!isSuccess(json)) return@withContext null
            android.util.Log.i("CloudClient", "report-data $deviceCode: ${json.optJSONObject("data")?.toString()?.take(2500)}")
            parseFilament(json.optJSONObject("data"))
        } catch (e: Exception) {
            android.util.Log.w("CloudClient", "getFilament failed: ${e.message}")
            null
        }
    }

    private fun parseFilament(data: JSONObject?): FilamentInfo? {
        if (data == null) return null
        val mono = data.optJSONArray("mono_filament_info")
        if (mono != null) {
            val status = reportVal(mono, "status")?.toIntOrNull() ?: 0
            if (status == 1) {
                val type = reportVal(mono, "filament_type")
                val name = reportVal(mono, "filament_name")
                val color = reportVal(mono, "filament_color")
                if (!type.isNullOrBlank() || !color.isNullOrBlank()) {
                    return FilamentInfo(
                        present = true,
                        name = (type ?: name).orBlank("Filament"),
                        colorHex = normColor(color),
                        remaining = null,
                    )
                }
            }
        }
        val canvas = data.optJSONArray("canvas_info")
        if (canvas != null) {
            val activeTray = reportVal(canvas, "active_tray_id")?.toIntOrNull() ?: -1
            val canvasListStr = reportVal(canvas, "canvas_list")
            if (!canvasListStr.isNullOrBlank()) {
                try {
                    val arr = JSONArray(canvasListStr)
                    val trays = ArrayList<LoadedTray>()
                    for (ci in 0 until arr.length()) {
                        val c = arr.getJSONObject(ci)
                        if (c.optInt("connected", 0) != 1) continue
                        val canvasId = c.optInt("canvas_id", c.optInt("canvasId", ci))
                        val tl = c.optJSONArray("tray_list") ?: continue
                        for (ti in 0 until tl.length()) {
                            val t = tl.getJSONObject(ti)
                            if (t.optInt("status", 0) != 1) continue
                            trays.add(
                                LoadedTray(
                                    trayId = t.optInt("tray_id", -1),
                                    name = t.optString("filament_name", ""),
                                    type = t.optString("filament_type", ""),
                                    colorHex = normColor(t.optString("filament_color", "")),
                                    canvasId = canvasId,
                                )
                            )
                        }
                    }
                    if (trays.isNotEmpty()) {
                        val primary = trays.firstOrNull { it.trayId == activeTray } ?: trays.first()
                        return FilamentInfo(
                            present = true,
                            name = primary.type.ifBlank { primary.name }.ifBlank { "Filament" },
                            colorHex = primary.colorHex,
                            remaining = null,
                            trays = trays,
                        )
                    }
                } catch (_: Exception) {
                }
            }
        }
        return null
    }

    private fun reportVal(arr: JSONArray, linkKey: String): String? {
        for (i in 0 until arr.length()) {
            val e = arr.optJSONObject(i) ?: continue
            if (e.optString("reportLinkKey") == linkKey) return e.optString("reportValue", null)
        }
        return null
    }

    private fun String?.orBlank(fallback: String): String = if (isNullOrBlank()) fallback else this

    private fun normColor(c: String?): String? {
        if (c.isNullOrBlank()) return null
        val s = c.trim()
        return when {
            s.startsWith("#") -> s
            s.matches(Regex("^[0-9a-fA-F]{6}$")) -> "#$s"
            s.matches(Regex("^[0-9a-fA-F]{8}$")) -> "#${s.substring(2)}"
            else -> null
        }
    }

    suspend fun sendPrintTask(deviceCode: String, fileName: String): CloudResult<Boolean> = withContext(Dispatchers.IO) {
        try {
            val body = JSONObject()
                .put("deviceCode", deviceCode)
                .put("fileName", fileName)
            apiPost(deviceApi + "/device/print-task", body, authed = true)
            CloudResult.Ok(true)
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "print task failed")
        }
    }

    private fun isSuccess(json: JSONObject): Boolean {
        val code = json.optInt("code", 200)
        return code == 200 || code == 0
    }

    private fun firstNonBlank(o: JSONObject, vararg keys: String): String {
        for (k in keys) {
            val v = o.optString(k, "")
            if (v.isNotBlank()) return v
        }
        return ""
    }

    private fun parseOnline(v: Any?): Boolean {
        return when (v) {
            null, JSONObject.NULL -> false
            is Boolean -> v
            is Number -> v.toInt() == 1
            is String -> v == "1" || v.equals("online", ignoreCase = true) || v.equals("true", ignoreCase = true)
            else -> false
        }
    }

    private fun baseHeaders(authed: Boolean): Map<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        h["User-Lang"] = "en"
        h["X-Client-Request-Id"] = java.util.UUID.randomUUID().toString()
        h["User-Agent"] =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        if (authed) {
            auth.accessToken?.let { h["Authorization"] = "Bearer $it" }
        }
        auth.sessionCookie?.let { if (it.isNotBlank()) h["Cookie"] = it }
        return h
    }

    private fun applyTls(conn: HttpURLConnection) {
        if (conn is javax.net.ssl.HttpsURLConnection) {
            try {
                val ctx = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
                ctx.init(null, null, SecureRandom())
                conn.sslSocketFactory = ctx.socketFactory
            } catch (e: Exception) {
                android.util.Log.w("CloudClient", "TLS 1.2 setup failed", e)
            }
        }
    }

    private fun postRaw(url: String, body: JSONObject, authed: Boolean): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        baseHeaders(authed).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        applyTls(conn)
        try {
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        } catch (e: Exception) {
            android.util.Log.e("CloudClient", "POST write failed: $url", e)
            throw e
        }
        return readJson(conn)
    }

    private fun getRaw(url: String, authed: Boolean): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15000
        conn.readTimeout = 20000
        baseHeaders(authed).forEach { (k, v) -> conn.setRequestProperty(k, v) }
        applyTls(conn)
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let { BufferedReader(InputStreamReader(it, Charsets.UTF_8)).useLines { ls -> ls.joinToString("") } } ?: ""
        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: ${text.take(200)}")
        }
        return if (text.isNotBlank()) JSONObject(text) else JSONObject()
    }

    data class AgoraCredential(
        val userId: String,
        val rtmUserId: String,
        val rtmToken: String,
        /** RTC credentials for the cloud camera stream (same Agora project as RTM). */
        val rtcToken: String? = null,
        val rtcUserId: Int? = null,
    )

    data class OssEntrypoint(val entrypoint: String, val accessUrl: String, val objectName: String)

    /**
     * Matrix `RtcHelper.join` pre-step: POST device-rpc/direct wakes the
     * printer's cloud RTC bridge so it accepts the camera session.
     */
    suspend fun deviceRpcPrepare(serial: String): CloudResult<Unit> = withContext(Dispatchers.IO) {
        if (!auth.tokenUsable()) auth.refreshTokens()
        try {
            val json = postRaw(
                gateway + "/api/v1/device-server/device-rpc/direct",
                org.json.JSONObject().put("serialNo", serial).put("data", ""),
                authed = true,
            )
            if (isSuccess(json)) CloudResult.Ok(Unit)
            else CloudResult.Err(json.optString("msg", "device-rpc/direct failed"))
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "device-rpc/direct failed")
        }
    }

    suspend fun getAgoraCredential(): CloudResult<AgoraCredential> = withContext(Dispatchers.IO) {
        if (!auth.tokenUsable()) auth.refreshTokens()
        try {
            val json = getRaw(gateway + deviceApi + "/device/list/agora-token?source=slicer", authed = true)
            if (!isSuccess(json)) {
                return@withContext CloudResult.Err(json.optString("message", "agora token failed"))
            }
            val data = json.optJSONObject("data")
                ?: return@withContext CloudResult.Err("agora: no data")
            val token = data.optJSONObject("agoraToken")
                ?: return@withContext CloudResult.Err("agora: no agoraToken")
            val userId = token.opt("userId")?.toString()?.takeIf { it.isNotBlank() && it != "null" }
                ?: token.optString("rtmUserId", "")
            val rtmUserId = token.optString("rtmUserId", "")
            val rtmToken = token.optString("rtmToken", "")
            if (userId.isBlank() || rtmUserId.isBlank() || rtmToken.isBlank()) {
                return@withContext CloudResult.Err("agora: incomplete credential")
            }
            val rtcToken = token.optString("rtcToken", "").takeIf { it.isNotBlank() }
            val rtcUserId = token.optInt("rtcUserId", 0).takeIf { it != 0 }
            CloudResult.Ok(AgoraCredential(userId, rtmUserId, rtmToken, rtcToken, rtcUserId))
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "agora token failed")
        }
    }

    suspend fun getOssEntrypoint(fileName: String, fileMd5Base64: String): CloudResult<OssEntrypoint> =
        withContext(Dispatchers.IO) {
            if (!auth.tokenUsable()) auth.refreshTokens()
            try {
                val path = deviceApi + "/oss/biz-entrypoint?filename=" + URLEncoder.encode(fileName, "UTF-8") +
                    "&bucketAlias=iot-private&module=gcode&fileMd5=" + URLEncoder.encode(fileMd5Base64, "UTF-8")
                val json = getRaw(gateway + path, authed = true)
                if (!isSuccess(json)) {
                    return@withContext CloudResult.Err(json.optString("message", "oss entrypoint failed"))
                }
                val data = json.optJSONObject("data")
                    ?: return@withContext CloudResult.Err("oss: no data")
                val entrypoint = data.optString("entrypoint", "")
                val accessUrl = data.optString("accessUrl", "")
                if (entrypoint.isBlank() || accessUrl.isBlank()) {
                    return@withContext CloudResult.Err("oss: incomplete entrypoint")
                }
                CloudResult.Ok(OssEntrypoint(entrypoint, accessUrl, data.optString("objectName", "")))
            } catch (e: Exception) {
                CloudResult.Err(e.message ?: "oss entrypoint failed")
            }
        }

    suspend fun uploadToOss(entrypoint: String, file: File, fileMd5Base64: String): CloudResult<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val conn = URL(entrypoint).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.doOutput = true
                conn.connectTimeout = 20000
                conn.readTimeout = 180000
                conn.setRequestProperty("Content-Type", "application/octet-stream")
                conn.setRequestProperty("Content-MD5", fileMd5Base64)
                conn.setFixedLengthStreamingMode(file.length())
                file.inputStream().use { input -> conn.outputStream.use { out -> input.copyTo(out) } }
                val code = conn.responseCode
                val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                conn.disconnect()
                if (code in 200..299) CloudResult.Ok(Unit)
                else CloudResult.Err("OSS upload HTTP $code: ${body.take(200)}")
            } catch (e: Exception) {
                CloudResult.Err(e.message ?: "OSS upload failed")
            }
        }
}
