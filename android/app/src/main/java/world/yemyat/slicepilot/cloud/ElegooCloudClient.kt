package world.yemyat.slicepilot.cloud

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
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
import java.security.KeyFactory
import java.security.PublicKey
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

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

class ElegooCloudClient(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("elegoo_cloud", Context.MODE_PRIVATE)

    var region: String
        get() = prefs.getString("region", "global") ?: "global"
        set(v) = prefs.edit().putString("region", v).apply()

    private val gateway: String
        get() = if (region == "china")
            "https://matrix.elegoo.com.cn"
        else
            "https://matrix.elegoo.com"

    private val accountApi = "/api/v1/account-center-server"
    private val deviceApi = "/api/v1/device-management-server"
    private val clientId = "Graft"

    private companion object {
        const val FALLBACK_PUBLIC_KEY =
            "-----BEGIN PUBLIC KEY-----\n" +
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAlHGEjbiq1HvWfRUGchLz\n" +
            "RLjrc6aSPYooXrjPxZIOwf1EbEx16B3cxnjdJnZyUu0gNe55yTzkJMMmjoHnHuow\n" +
            "Yo1mppXUAV1mLCLKWb4nQzGvjNDM4XjqG1LIv24uxA2E+1dVwlsrfg7vMKPEOZMp\n" +
            "9B7MQwfPMA62rl6Htsu7QrNu64csXqWGgdLL3sqN4XyQoSVQoJI506hq8iaxkjyz\n" +
            "uJKcrzIG01cKJDsxQtJsOpLx3IgOzfArFkCndG7JlQgh15CN4OGL5YnaAeAjUvLc\n" +
            "jbesmzEFjqJeMOxDOIWXDZJV40wqzHBMm43OZiKO70q4L99k6lbGEXlygMiCj/loo\n" +
            "QIDAQAB\n" +
            "-----END PUBLIC KEY-----"
    }

    val loginPageUrl: String
        get() = if (region == "china")
            "https://np-intl-accounts.elegoo.com.cn/account/slicer-login"
        else
            "https://np-intl-accounts.elegoo.com/account/slicer-login"

    val isLoggedIn: Boolean get() = !accessToken.isNullOrBlank()

    var sessionCookie: String?
        get() = prefs.getString("session_cookie", null)
        set(v) = prefs.edit().putString("session_cookie", v).apply()

    var accessToken: String?
        get() = prefs.getString("access_token", null)
        private set(v) = prefs.edit().putString("access_token", v).apply()

    var refreshToken: String?
        get() = prefs.getString("refresh_token", null)
        private set(v) = prefs.edit().putString("refresh_token", v).apply()

    private var accessTokenExpire: Long
        get() = prefs.getLong("access_token_expire", 0L)
        set(v) = prefs.edit().putLong("access_token_expire", v).apply()

    var nickname: String
        get() = prefs.getString("nickname", "") ?: ""
        private set(v) = prefs.edit().putString("nickname", v).apply()

    var email: String
        get() = prefs.getString("email", "") ?: ""
        private set(v) = prefs.edit().putString("email", v).apply()

    fun saveSession(token: String, refresh: String?, nick: String, mail: String) {
        accessToken = token
        refreshToken = refresh
        nickname = nick
        email = mail
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    private suspend fun apiPost(path: String, body: JSONObject, authed: Boolean): JSONObject {
        return postRaw(gateway + path, body, authed)
    }

    fun captureWebViewCookie(): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.flush()
            val domains = listOf(
                "https://np-intl-accounts.elegoo.com",
                "https://account.elegoo.com",
                "https://www.nexprint.com",
            )
            val merged = LinkedHashMap<String, String>()
            for (d in domains) {
                val c = cm.getCookie(d) ?: continue
                for (part in c.split(";")) {
                    val p = part.trim()
                    val name = p.substringBefore("=")
                    if (name.isNotEmpty()) merged[name] = p
                }
            }
            merged.values.joinToString("; ")
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun login(emailAddr: String, password: String): CloudResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val pubKeyPem = fetchPublicKeyOrFallback()
            val publicKey = parseRsaPublicKey(pubKeyPem)

            val rnd = SecureRandom()
            val keyBytes = ByteArray(32).also { rnd.nextBytes(it) }  // AES-256
            val ivBytes = ByteArray(16).also { rnd.nextBytes(it) }

            val aes = Cipher.getInstance("AES/CBC/PKCS5Padding")
            aes.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(ivBytes))
            val encEmail = Base64.encodeToString(
                aes.doFinal(emailAddr.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP,
            )
            val encPwd = Base64.encodeToString(
                aes.doFinal(password.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP,
            )

            val rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding")
            rsa.init(Cipher.ENCRYPT_MODE, publicKey)
            val aesKeyB64 = Base64.encodeToString(rsa.doFinal(keyBytes), Base64.NO_WRAP)
            val aesIvB64 = Base64.encodeToString(rsa.doFinal(ivBytes), Base64.NO_WRAP)

            val body = JSONObject()
                .put("clientId", clientId)
                .put("email", emailAddr)
                .put("encryptEmail", encEmail)
                .put("password", encPwd)
                .put("aesIv", aesIvB64)
                .put("aesKey", aesKeyB64)
                .put("reEncode", true)

            val resp = apiPost(
                accountApi + "/account-auth/login/email/password",
                body,
                authed = false,
            )
            if (!isSuccess(resp)) {
                return@withContext CloudResult.Err(resp.optString("msg", "login failed (code ${resp.optInt("code")})"))
            }
            val data = resp.optJSONObject("data")
                ?: return@withContext CloudResult.Err("login: no data in response")
            val token = data.optString("accessToken", data.optString("token", ""))
            if (token.isBlank()) return@withContext CloudResult.Err("login: no accessToken")
            accessToken = token
            refreshToken = data.optString("refreshToken", "").ifBlank { null }
            accessTokenExpire = data.optLong("accessTokenExpireTime", 0L)
            email = emailAddr
            nickname = data.optString("nickname", data.optString("userName", emailAddr.substringBefore('@')))
            CloudResult.Ok(Unit)
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "login failed")
        }
    }

    private suspend fun fetchPublicKeyOrFallback(): String {
        return try {
            val keyJson = apiPost(
                accountApi + "/account-auth/key",
                JSONObject().put("clientId", clientId),
                authed = false,
            )
            if (isSuccess(keyJson)) {
                extractPublicKey(keyJson)?.let { return it }
            }
            FALLBACK_PUBLIC_KEY
        } catch (e: Exception) {
            FALLBACK_PUBLIC_KEY
        }
    }

    suspend fun refreshTokens(): Boolean = withContext(Dispatchers.IO) {
        val rt = refreshToken ?: return@withContext false
        try {
            val resp = apiPost(
                accountApi + "/account-auth/token/refresh",
                JSONObject().put("refreshToken", rt).put("clientId", clientId),
                authed = false,
            )
            if (!isSuccess(resp)) return@withContext false
            val data = resp.optJSONObject("data") ?: return@withContext false
            val token = data.optString("accessToken", "")
            if (token.isBlank()) return@withContext false
            accessToken = token
            data.optString("refreshToken").takeIf { it.isNotBlank() }?.let { refreshToken = it }
            accessTokenExpire = data.optLong("accessTokenExpireTime", 0L)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun tokenUsable(): Boolean =
        accessTokenExpire == 0L || System.currentTimeMillis() < accessTokenExpire - 60_000L

    suspend fun checkSession(): CloudResult<CloudUser> = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) {
            return@withContext CloudResult.Err("not logged in")
        }
        if (!tokenUsable() && !refreshTokens()) {
            return@withContext CloudResult.Err("session expired")
        }
        CloudResult.Ok(CloudUser(nickname, email, region))
    }

    suspend fun validateSession(): CloudResult<CloudUser> = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) {
            return@withContext CloudResult.Err("not logged in")
        }
        if (!tokenUsable() && !refreshTokens()) {
            logout()
            return@withContext CloudResult.Err("session expired")
        }
        when (val r = listPrinters()) {
            is CloudResult.Ok -> CloudResult.Ok(CloudUser(nickname, email, region))
            is CloudResult.Err -> {
                if (isAuthFailure(r.message)) {
                    logout()
                    CloudResult.Err("signed out")
                } else {
                    CloudResult.Err(r.message)
                }
            }
        }
    }

    fun isAuthFailure(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("http 401") ||
            m.contains("http 403") ||
            m.contains("code 401") ||
            m.contains("code 403") ||
            m.contains("unauthorized") ||
            m.contains("session expired") ||
            m.contains("token invalid") ||
            m.contains("invalid token") ||
            m.contains("not logged in")
    }

    suspend fun listPrinters(): CloudResult<List<CloudPrinter>> = withContext(Dispatchers.IO) {
        if (!tokenUsable()) refreshTokens()
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
                if (uid.isNotBlank()) prefs.edit().putString("user_id", uid).apply()
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
    )

    suspend fun getMqttConfig(): CloudResult<MqttConfig> = withContext(Dispatchers.IO) {
        if (!tokenUsable()) refreshTokens()
        try {
            val userId = prefs.getString("user_id", "") ?: ""
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
            CloudResult.Ok(
                MqttConfig(
                    host = host,
                    port = port,
                    path = path,
                    clientId = d.optString("mqttClientId", clientId),
                    username = username,
                    password = password,
                    topicFilter = "app/v1/$username/#",
                )
            )
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "mqtt config failed")
        }
    }

    suspend fun getFilament(deviceCode: String): FilamentInfo? = withContext(Dispatchers.IO) {
        if (!tokenUsable()) refreshTokens()
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

    private fun extractPublicKey(json: JSONObject): String? {
        return when (val d = json.opt("data")) {
            is JSONObject -> {
                for (k in listOf("publicKey", "key", "rsaPublicKey", "public_key")) {
                    val v = d.optString(k, "")
                    if (v.isNotBlank()) return v
                }
                null
            }
            is String -> d.takeIf { it.isNotBlank() }
            else -> null
        }
    }

    private fun parseRsaPublicKey(pem: String): PublicKey {
        val body = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace("-----BEGIN RSA PUBLIC KEY-----", "")
            .replace("\\s".toRegex(), "")
        val der = Base64.decode(body, Base64.DEFAULT)
        return try {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(der))
        } catch (e: Exception) {
            try {
                KeyFactory.getInstance("RSA").generatePublic(spkiFromPkcs1(der))
            } catch (e2: Exception) {
                KeyFactory.getInstance("RSA").generatePublic(PKCS8EncodedKeySpec(der))
            }
        }
    }

    private fun spkiFromPkcs1(pkcs1: ByteArray): X509EncodedKeySpec {
        val algorithmId = byteArrayOf(
            0x30, 0x0d,
            0x06, 0x09, 0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x01,
            0x05, 0x00,
        )
        val bitString = derWrap(0x03, byteArrayOf(0x00) + pkcs1)
        val seqBody = algorithmId + bitString
        return X509EncodedKeySpec(derWrap(0x30, seqBody))
    }

    private fun derWrap(tag: Int, body: ByteArray): ByteArray {
        val len = body.size
        val lenBytes: ByteArray = when {
            len < 128 -> byteArrayOf(len.toByte())
            len < 256 -> byteArrayOf(0x81.toByte(), len.toByte())
            len < 65536 -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
            else -> byteArrayOf(0x83.toByte(), (len shr 16).toByte(), (len shr 8).toByte(), len.toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + body
    }

    private fun baseHeaders(authed: Boolean): Map<String, String> {
        val h = HashMap<String, String>()
        h["Accept"] = "application/json"
        h["User-Lang"] = "en"
        h["X-Client-Request-Id"] = java.util.UUID.randomUUID().toString()
        h["User-Agent"] =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
        if (authed) {
            accessToken?.let { h["Authorization"] = "Bearer $it" }
        }
        sessionCookie?.let { if (it.isNotBlank()) h["Cookie"] = it }
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

    data class AgoraCredential(val userId: String, val rtmUserId: String, val rtmToken: String)

    data class OssEntrypoint(val entrypoint: String, val accessUrl: String, val objectName: String)

    suspend fun getAgoraCredential(): CloudResult<AgoraCredential> = withContext(Dispatchers.IO) {
        if (!tokenUsable()) refreshTokens()
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
            CloudResult.Ok(AgoraCredential(userId, rtmUserId, rtmToken))
        } catch (e: Exception) {
            CloudResult.Err(e.message ?: "agora token failed")
        }
    }

    suspend fun getOssEntrypoint(fileName: String, fileMd5Base64: String): CloudResult<OssEntrypoint> =
        withContext(Dispatchers.IO) {
            if (!tokenUsable()) refreshTokens()
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
