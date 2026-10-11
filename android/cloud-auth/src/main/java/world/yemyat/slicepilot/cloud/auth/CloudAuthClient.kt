package world.yemyat.slicepilot.cloud.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.SecureRandom
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class CloudAuthClient(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("elegoo_cloud", Context.MODE_PRIVATE)

    var region: String
        get() = prefs.getString("region", "global") ?: "global"
        set(v) = prefs.edit().putString("region", v).apply()

    private val gateway: String
        get() = if (region == "china")
            BuildConfig.CLOUD_GATEWAY_CHINA
        else
            BuildConfig.CLOUD_GATEWAY_GLOBAL

    private val accountApi = "/api/v1/account-center-server"
    private val clientId = BuildConfig.CLOUD_CLIENT_ID

    private companion object {
        val FALLBACK_PUBLIC_KEY = BuildConfig.CLOUD_RSA_PUBLIC_KEY
    }

    val loginPageUrl: String
        get() = if (region == "china")
            BuildConfig.CLOUD_LOGIN_URL_CHINA
        else
            BuildConfig.CLOUD_LOGIN_URL_GLOBAL

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

    var userId: String
        get() = prefs.getString("user_id", "") ?: ""
        set(v) = prefs.edit().putString("user_id", v).apply()

    var nickname: String
        get() = prefs.getString("nickname", "") ?: ""
        private set(v) = prefs.edit().putString("nickname", v).apply()

    var email: String
        get() = prefs.getString("email", "") ?: ""
        private set(v) = prefs.edit().putString("email", v).apply()

    private var accessTokenExpire: Long
        get() = prefs.getLong("access_token_expire", 0L)
        set(v) = prefs.edit().putLong("access_token_expire", v).apply()

    fun saveSession(token: String, refresh: String?, nick: String, mail: String) {
        accessToken = token
        refreshToken = refresh
        nickname = nick
        email = mail
    }

    fun logout() {
        prefs.edit().clear().apply()
    }

    fun tokenUsable(): Boolean =
        accessTokenExpire == 0L || System.currentTimeMillis() < accessTokenExpire - 60_000L

    suspend fun refreshIfNeeded(): Boolean = tokenUsable() || refreshTokens()

    suspend fun login(emailAddr: String, password: String): CloudAuthResult<Unit> = withContext(Dispatchers.IO) {
        try {
            val pubKeyPem = fetchPublicKeyOrFallback()
            val publicKey = parseRsaPublicKey(pubKeyPem)

            val rnd = SecureRandom()
            val keyBytes = ByteArray(32).also { rnd.nextBytes(it) }
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
                return@withContext CloudAuthResult.Err(
                    resp.optString("msg", "login failed (code ${resp.optInt("code")})"),
                )
            }
            val data = resp.optJSONObject("data")
                ?: return@withContext CloudAuthResult.Err("login: no data in response")
            val token = data.optString("accessToken", data.optString("token", ""))
            if (token.isBlank()) return@withContext CloudAuthResult.Err("login: no accessToken")
            accessToken = token
            refreshToken = data.optString("refreshToken", "").ifBlank { null }
            accessTokenExpire = data.optLong("accessTokenExpireTime", 0L)
            email = emailAddr
            nickname = data.optString("nickname", data.optString("userName", emailAddr.substringBefore('@')))
            CloudAuthResult.Ok(Unit)
        } catch (e: Exception) {
            CloudAuthResult.Err(e.message ?: "login failed")
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

    suspend fun checkSession(): CloudAuthResult<CloudAuthUser> = withContext(Dispatchers.IO) {
        if (accessToken.isNullOrBlank()) {
            return@withContext CloudAuthResult.Err("not logged in")
        }
        if (!tokenUsable() && !refreshTokens()) {
            return@withContext CloudAuthResult.Err("session expired")
        }
        CloudAuthResult.Ok(CloudAuthUser(nickname, email, region))
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

    fun captureWebViewCookie(): String {
        return try {
            val cm = android.webkit.CookieManager.getInstance()
            cm.flush()
            val domains = BuildConfig.CLOUD_COOKIE_DOMAINS.split(",")
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

    private suspend fun apiPost(path: String, body: JSONObject, authed: Boolean): JSONObject {
        return postRaw(gateway + path, body, authed)
    }

    private fun isSuccess(json: JSONObject): Boolean {
        val code = json.optInt("code", 200)
        return code == 200 || code == 0
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

    private fun parseRsaPublicKey(pem: String): java.security.PublicKey {
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
                android.util.Log.w("CloudAuthClient", "TLS 1.2 setup failed", e)
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
            android.util.Log.e("CloudAuthClient", "POST write failed: $url", e)
            throw e
        }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.let {
            BufferedReader(InputStreamReader(it, Charsets.UTF_8)).useLines { ls -> ls.joinToString("") }
        } ?: ""
        if (code !in 200..299) {
            throw RuntimeException("HTTP $code: ${text.take(200)}")
        }
        return if (text.isNotBlank()) JSONObject(text) else JSONObject()
    }
}
