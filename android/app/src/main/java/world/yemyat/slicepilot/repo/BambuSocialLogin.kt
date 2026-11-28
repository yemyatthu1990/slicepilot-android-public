package world.yemyat.slicepilot.repo

import org.json.JSONException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bambu Lab / MakerWorld third-party OAuth (Google, Apple, Facebook) support.
 *
 * Bambu's sign-in page (`https://bambulab.com/en/sign-in`) refuses Google OAuth inside an embedded
 * Android WebView (`403 disallowed_useragent`). When loaded with Bambu Studio's slicer UA token
 * (`BBL-Slicer/v…`) and `window.wx.postMessage` bridge, tapping Google (or Apple/Facebook) asks
 * the host for a local loopback address (`get_localhost_url`), hands back a browser URL
 * (`thirdparty_login`), and after Google completes redirects that browser tab to:
 *
 * ```text
 * http://localhost:<port>/?ticket=<T>&redirect_url=…
 * ```
 *
 * We catch `<T>` on a short-lived `127.0.0.1` [ServerSocket], 302 the Custom Tab straight back into
 * SlicePilot (`world.yemyat.slicepilot://bambu/callback`), and exchange `<T>` via
 * `POST https://api.bambulab.com/v1/user-service/user/ticket/<T>` for a normal Bambu bearer token.
 */
object BambuSocialLogin {
    /** Bambu Lab's global sign-in page (supports Google, Apple, Facebook, and email). */
    const val SIGN_IN_URL = "https://bambulab.com/en/sign-in"

    /** Provider identifier accepted by Bambu's third-party OAuth start endpoint. */
    const val PROVIDER_GOOGLE = "google"

    /** Default loopback port Bambu Studio uses for third-party OAuth callbacks. */
    const val DEFAULT_LOOPBACK_PORT = 13618

    /** Host segment of the deep link that returns Chrome Custom Tabs back to SlicePilot. */
    const val APP_CALLBACK_HOST = "bambu"

    /** Deep link the loopback server redirects Chrome Custom Tabs to once the callback lands. */
    const val APP_CALLBACK_URL = "world.yemyat.slicepilot://$APP_CALLBACK_HOST/callback"

    /**
     * UA fragment Bambu's sign-in page checks before enabling `get_localhost_url` /
     * `thirdparty_login` / `user_ticket_login` over the `window.wx` bridge.
     */
    const val SLICER_USER_AGENT_TOKEN = "BBL-Slicer/v02.08.01.55 (light) BBL-Language/en"

    private const val TPLOGIN_URL = "https://bambulab.com/api/v1/user-service/user/tplogin"
    private const val AUTH_CALLBACK_URL = "https://bambulab.com/api/auth/callback"
    private const val CONSENT_BODY =
        """{"version":1,"scene":"register_or_login","formList":[{"formId":"AccountTerms","op":"Opt-in"},{"formId":"PrivacyPolicy","op":"Opt-in"}]}"""
    private const val TICKET_API_PREFIX = "https://api.bambulab.com/v1/user-service/user/ticket/"
    private const val PROFILE_API_URL = "https://api.bambulab.com/v1/user-service/my/profile"
    private const val USER_AGENT = "BBL-Slicer/v02.08.01.55"

    /**
     * Builds Bambu Lab's direct third-party OAuth URL (identical to the URL `bambulab.com/en/sign-in`
     * passes to Bambu Studio's `thirdparty_login` bridge) so a single tap on "Sign in with Google"
     * opens Chrome Custom Tabs straight into Google's account picker with no WebView in between.
     */
    fun thirdPartySignInUrl(
        loopbackBaseUrl: String,
        provider: String = PROVIDER_GOOGLE,
        locale: String = "en",
    ): String {
        val cleanProvider = provider.trim().lowercase()
        val encodedRedirect = URLEncoder.encode(loopbackBaseUrl, "UTF-8")
        val callback =
            "$AUTH_CALLBACK_URL?openBy=studio&from=studio&source=$cleanProvider" +
                "&locale=$locale&redirect_url=$encodedRedirect&slicerLoginType=ticket"
        return "$TPLOGIN_URL?source=$cleanProvider&from=slicer" +
            "&callback=${URLEncoder.encode(callback, "UTF-8")}" +
            "&consentBody=${URLEncoder.encode(CONSENT_BODY, "UTF-8")}"
    }

    data class Session(
        val accessToken: String,
        val refreshToken: String,
        val uid: String,
    )

    sealed class CallbackResult {
        data class Ticket(
            val ticket: String,
        ) : CallbackResult()

        data class DirectToken(
            val accessToken: String,
            val refreshToken: String,
        ) : CallbackResult()

        data class Error(
            val message: String,
        ) : CallbackResult()
    }

    /** True when [url] is the return-to-app deep link emitted by [BambuLoopbackServer]. */
    fun isAppCallback(url: String): Boolean {
        val scheme = url.substringBefore("://", "")
        if (scheme != "world.yemyat.slicepilot") return false
        val host = url.substringAfter("://", "").substringBefore('/').substringBefore('?')
        return host.equals(APP_CALLBACK_HOST, ignoreCase = true)
    }

    /**
     * Parses the HTTP request target from a loopback callback (e.g.
     * `/?ticket=AM2PQ2&redirect_url=https%3A%2F%2Fbambulab.com%2Fen%2Fsign-in%2Fstudio-callback`).
     * Returns null for non-callback browser probes such as `/favicon.ico`.
     */
    fun parseCallbackPath(requestTarget: String): CallbackResult? {
        val path = requestTarget.substringBefore('?')
        if (path.endsWith("/favicon.ico", ignoreCase = true)) return null
        val query = requestTarget.substringAfter('?', "")
        if (query.isBlank()) return null

        val params = parseQuery(query)
        params["ticket"]?.takeIf { it.isNotBlank() }?.let {
            return CallbackResult.Ticket(it)
        }
        val access =
            params["access_token"]?.takeIf { it.isNotBlank() }
                ?: params["accessToken"]?.takeIf { it.isNotBlank() }
        if (access != null) {
            val refresh =
                params["refresh_token"]
                    ?: params["refreshToken"]
                    ?: ""
            return CallbackResult.DirectToken(access, refresh)
        }
        val err =
            params["error_description"]?.takeIf { it.isNotBlank() }
                ?: params["error"]?.takeIf { it.isNotBlank() }
        if (err != null) {
            return CallbackResult.Error(err)
        }
        return null
    }

    /**
     * Rewrites Bambu's default port ([DEFAULT_LOOPBACK_PORT]) inside a third-party OAuth URL when
     * our loopback server had to bind an ephemeral port instead.
     */
    fun rewriteLoopbackUrl(
        url: String,
        port: Int,
    ): String {
        if (port <= 0 || port == DEFAULT_LOOPBACK_PORT) return url
        val oldPort = DEFAULT_LOOPBACK_PORT.toString()
        val newPort = port.toString()
        return url
            .replace("http://localhost:$oldPort", "http://localhost:$newPort")
            .replace("http://127.0.0.1:$oldPort", "http://127.0.0.1:$newPort")
            .replace("http%3A%2F%2Flocalhost%3A$oldPort", "http%3A%2F%2Flocalhost%3A$newPort")
            .replace("http%3A%2F%2F127.0.0.1%3A$oldPort", "http%3A%2F%2F127.0.0.1%3A$newPort")
            .replace("http%3a%2f%2flocalhost%3a$oldPort", "http%3a%2f%2flocalhost%3a$newPort")
            .replace("http%3a%2f%2f127.0.0.1%3a$oldPort", "http%3a%2f%2f127.0.0.1%3a$newPort")
    }

    /**
     * Exchanges a single-use Bambu OAuth ticket for an access/refresh token pair and resolves the
     * user's `uidStr` from `/v1/user-service/my/profile`.
     */
    fun exchangeTicket(ticket: String): Session {
        val clean = ticket.trim()
        if (clean.isBlank()) throw IOException("Bambu sign-in returned an empty ticket.")
        val encoded = URLEncoder.encode(clean, "UTF-8")
        val conn = URL("$TICKET_API_PREFIX$encoded").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("x-bbl-language", "en-US")
        conn.setRequestProperty("x-bbl-region-global", "true")
        val payload = JSONObject().put("ticket", clean).toString()
        val text =
            try {
                conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
        val json =
            try {
                JSONObject(text)
            } catch (_: JSONException) {
                throw IOException("Bambu sign-in returned an unexpected response.")
            }
        val access = json.optString("accessToken", "")
        if (access.isBlank()) {
            val err =
                json
                    .optString("error", "")
                    .ifBlank { json.optString("message", "") }
                    .ifBlank { "Bambu sign-in could not complete. Please try again." }
            throw IOException(err)
        }
        val refresh = json.optString("refreshToken", "")
        val uid =
            json
                .optString("uidStr", "")
                .ifBlank { json.optString("uid", "") }
                .ifBlank { fetchProfileUid(access) }
        return Session(accessToken = access, refreshToken = refresh, uid = uid)
    }

    /** Best-effort lookup of the user's `uidStr` using a fresh Bambu bearer token. */
    fun fetchProfileUid(accessToken: String): String {
        if (accessToken.isBlank()) return ""
        return runCatching {
            val conn = URL(PROFILE_API_URL).openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $accessToken")
            try {
                if (conn.responseCode !in 200..299) return@runCatching ""
                val text = conn.inputStream.bufferedReader().use { it.readText() }
                val json = JSONObject(text)
                json.optString("uidStr", "").ifBlank { json.optString("uid", "") }
            } finally {
                conn.disconnect()
            }
        }.getOrDefault("")
    }

    private fun parseQuery(query: String): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        for (pair in query.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val rawKey = if (idx >= 0) pair.substring(0, idx) else pair
            val rawVal = if (idx >= 0) pair.substring(idx + 1) else ""
            val key = runCatching { URLDecoder.decode(rawKey, "UTF-8") }.getOrDefault(rawKey)
            val value = runCatching { URLDecoder.decode(rawVal, "UTF-8") }.getOrDefault(rawVal)
            if (key.isNotBlank() && key !in out) out[key] = value
        }
        return out
    }
}

/**
 * Short-lived `127.0.0.1` HTTP server that receives Bambu Lab's third-party OAuth loopback
 * redirect from Chrome Custom Tabs, bounces the tab back into SlicePilot via
 * [BambuSocialLogin.APP_CALLBACK_URL], and hands the captured ticket/token to [onResult].
 */
class BambuLoopbackServer(
    preferredPort: Int = BambuSocialLogin.DEFAULT_LOOPBACK_PORT,
    private val appReturnUrl: String = BambuSocialLogin.APP_CALLBACK_URL,
    private val onResult: (BambuSocialLogin.CallbackResult) -> Unit,
) : AutoCloseable {
    private val socket: ServerSocket = openLoopbackSocket(preferredPort)
    private val closed = AtomicBoolean(false)
    private val delivered = AtomicBoolean(false)

    val port: Int = socket.localPort
    val baseUrl: String = "http://localhost:$port"

    private val worker =
        Thread(
            { acceptLoop() },
            "bambu-oauth-loopback-$port",
        ).apply {
            isDaemon = true
            start()
        }

    private fun acceptLoop() {
        while (!closed.get()) {
            val client =
                try {
                    socket.accept()
                } catch (_: SocketException) {
                    break
                } catch (_: IOException) {
                    break
                }
            try {
                client.soTimeout = 5_000
                val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
                val requestLine = reader.readLine() ?: continue
                // Drain HTTP headers before writing the response so Chrome doesn't reset the socket.
                while (true) {
                    val header = reader.readLine() ?: break
                    if (header.isEmpty()) break
                }
                val parts = requestLine.split(' ')
                val method = parts.getOrNull(0).orEmpty()
                val target = parts.getOrNull(1).orEmpty()
                if (method.equals("OPTIONS", ignoreCase = true)) {
                    writeNoContent(client.getOutputStream())
                    continue
                }
                val parsed = BambuSocialLogin.parseCallbackPath(target)
                if (parsed == null) {
                    writeNoContent(client.getOutputStream())
                    continue
                }
                val status = if (parsed is BambuSocialLogin.CallbackResult.Error) "error" else "ok"
                val redirect = "$appReturnUrl?status=$status"
                writeRedirect(client.getOutputStream(), redirect)
                if (delivered.compareAndSet(false, true)) {
                    onResult(parsed)
                }
                close()
                break
            } catch (_: IOException) {
                // Ignore transient client errors and keep listening until a real callback lands.
            } finally {
                runCatching { client.close() }
            }
        }
    }

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            runCatching { socket.close() }
        }
    }

    private fun writeNoContent(out: java.io.OutputStream) {
        val resp = "HTTP/1.1 204 No Content\r\nConnection: close\r\n\r\n"
        out.write(resp.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun writeRedirect(
        out: java.io.OutputStream,
        location: String,
    ) {
        val html =
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta http-equiv=\"refresh\" content=\"0;url=$location\">" +
                "<title>Returning to SlicePilot</title></head>" +
                "<body style=\"font-family:sans-serif;padding:24px;text-align:center\">" +
                "<h3>Sign-in complete</h3>" +
                "<p><a href=\"$location\">Return to SlicePilot</a></p>" +
                "<script>window.location.replace(${JSONObject.quote(location)});</script>" +
                "</body></html>"
        val bodyBytes = html.toByteArray(Charsets.UTF_8)
        val headers =
            "HTTP/1.1 302 Found\r\n" +
                "Location: $location\r\n" +
                "Content-Type: text/html; charset=utf-8\r\n" +
                "Content-Length: ${bodyBytes.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(bodyBytes)
        out.flush()
    }

    companion object {
        private fun openLoopbackSocket(preferredPort: Int): ServerSocket {
            val loopback = InetAddress.getByName("127.0.0.1")
            if (preferredPort > 0) {
                runCatching { return ServerSocket(preferredPort, 4, loopback) }
            }
            return ServerSocket(0, 4, loopback)
        }
    }
}
