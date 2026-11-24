package world.yemyat.slicepilot.repo

import android.content.SharedPreferences
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * MakerWorld parametric ("MakerLab" PMM) customization, mirroring the flow the
 * makerworld.com web UI and Bambu Handy use:
 *
 * 1. `GET design-service/design/<designId>/model?key=<unikey>&modelType=scad...`
 *    signs the design's source file for open-source designs; protected designs
 *    return an empty URL and are accessed via the in-app WebView (`MakerWorldWebViewScreen`).
 * 2. For protected designs, saves edited values to the user's make via
 *    `PUT creation-executor-service/my/scad/history` (using the make resolved from
 *    `GET creation-service/scad/gethistoryid?uniqueKey=...`).
 * 3. `POST creation-executor-service/scad/modelv2` renders the customized model
 *    and polls `GET modelv2?id=` until `status == "success"`.
 * 4. Downloads the generated mesh (STL, OBJ+MTL converted to binary STL, or 3MF)
 *    and unwraps MakerWorld's 80-byte `"MW 1.0 ..."` container header when present.
 */
class MakerworldCustomizer(
    private val prefs: SharedPreferences,
    private val cacheDir: File,
) {
    private val token: String get() = prefs.getString("bambu_token", "").orEmpty()

    /** Active Bambu access token for MakerWorld cookie/bridge SSO (empty when signed out). */
    val accessToken: String get() = token

    /** True when the app already has a Bambu/MakerWorld sign-in. */
    val signedIn: Boolean get() = token.isNotBlank()

    /**
     * Payload for MakerWorld's in-app token bridge. When its web app detects the
     * Bambu app shell (user agent `BBL-App/<n>`) it asks the host for the user
     * token (`request_user_token_from_app`) and exchanges it for its own web
     * session, so a user who signed in once in SlicePilot never signs in again.
     */
    fun appBridgePayload(): String {
        var uid = prefs.getString("bambu_uid", "").orEmpty()
        if (uid.isBlank() && token.isNotBlank()) {
            uid =
                runCatching {
                    JSONObject(authed("GET", "https://api.bambulab.com/v1/user-service/my/profile", null))
                        .optString("uidStr", "")
                }.getOrDefault("")
            if (uid.isNotBlank()) prefs.edit().putString("bambu_uid", uid).apply()
        }
        return JSONObject()
            .put("token", token)
            .put("accessToken", token)
            .put("refreshToken", prefs.getString("bambu_refresh_token", "").orEmpty())
            .put("expiresIn", 7200)
            .put("refreshExpiresIn", 2592000)
            .put("uidStr", uid)
            .put("uid", uid.toLongOrNull() ?: 0L)
            .toString()
    }

    class Job(
        val id: String,
        val objUrl: String?,
        val stlUrl: String? = null,
        val mtlUrl: String? = null,
    )

    /** Thrown with user-facing error messages. */
    class CustomizeError(
        message: String,
    ) : IOException(message)

    private fun authed(
        method: String,
        url: String,
        body: String?,
        accept: String = "application/json",
    ): String {
        if (token.isBlank()) {
            throw CustomizeError("Sign in with your Bambu Lab account to customize this model.")
        }
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", accept)
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("x-bbl-client-name", "MakerWorld")
        conn.setRequestProperty("x-bbl-client-type", "web")
        conn.setRequestProperty("x-bbl-app-source", "makerworld")
        conn.setRequestProperty("x-bbl-language", "en-US")
        conn.setRequestProperty("x-bbl-region-global", "true")
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        return try {
            val code = conn.responseCode
            when (code) {
                401, 403 -> {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "$method $url -> HTTP $code: ${errBody.take(300)}")
                    throw CustomizeError(
                        "MakerWorld refused the request (HTTP $code). Sign in again with your Bambu Lab account.",
                    )
                }

                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }

                else -> {
                    val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    Log.w(TAG, "$method $url -> HTTP $code: ${errBody.take(300)}")
                    throw CustomizeError("MakerWorld returned HTTP $code.")
                }
            }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Signs the design's source file URL — the endpoint makerworld.com's
     * "Customize" button and Bambu Handy both use. Returns null for protected designs.
     */
    private fun signModelFile(info: ParametricInfo): String? {
        val q =
            "key=${info.unikey}" +
                "&modelFileName=" + java.net.URLEncoder.encode(info.modelFileName, "UTF-8") +
                "&modelType=scad" +
                "&type=preview" +
                "&modelName=" + java.net.URLEncoder.encode(info.modelName, "UTF-8") +
                "&dirName=" + java.net.URLEncoder.encode(info.dirName, "UTF-8")
        val body =
            authed(
                "GET",
                "$SITE/api/v1/design-service/design/${info.designId}/model?$q",
                null,
            )
        val url = JSONObject(body).optString("url", "")
        Log.i(TAG, "signModelFile -> ${if (url.isBlank()) "(empty: protected)" else url.take(120)}")
        return url.takeIf { it.startsWith("http") }
    }

    /**
     * Loads the model's OpenSCAD source: cached harvested source first, then signed source file,
     * then authenticated design detail / saved history, then SSR page-scrape fallback.
     */
    fun loadScad(info: ParametricInfo): String? {
        if (info.modelType != "scad") return null
        cachedScad(info)?.let {
            Log.i(TAG, "using cached harvested source (${it.length} chars)")
            return it
        }
        if (info.modelUrl.startsWith("http")) {
            runCatching {
                val text = get(info.modelUrl, html = false)
                if (looksLikeScad(text)) return text
            }
        }
        runCatching {
            signModelFile(info)?.let { signed ->
                val text = get(signed, html = false)
                if (looksLikeScad(text)) return text
                Log.w(
                    TAG,
                    "signed source is not scad text (len=${text.length}, " +
                        "head=${text.take(24).replace('\n', ' ')})",
                )
            }
        }.onFailure { Log.w(TAG, "signModelFile failed: ${it.message}") }
        runCatching {
            val dj = authed("GET", "$SITE/api/v1/design-service/design/${info.designId}", null)
            val files = JSONObject(dj).optJSONObject("designExtension")?.optJSONArray("model_files")
            if (files != null) {
                for (i in 0 until files.length()) {
                    val f = files.getJSONObject(i)
                    val mu = f.optString("modelUrl", "")
                    val matchesKey = info.unikey.isBlank() || f.optString("unikey") == info.unikey
                    if (f.optString("modelType") == "scad" && matchesKey && mu.startsWith("http")) {
                        val text = get(mu, html = false)
                        if (looksLikeScad(text)) return text
                    }
                }
            }
        }.onFailure { Log.w(TAG, "mw design detail failed: ${it.message}") }
        runCatching {
            val makeId = makeIdFor(info)
            if (makeId != null) {
                val h = authed("GET", "$GATEWAY/creation-executor-service/my/scad/history?makeId=$makeId", null)
                findScad(JSONObject(h))?.let { return it }
                val mk = authed("GET", "$GATEWAY/creation-service/my/make/$makeId", null)
                findScad(JSONObject(mk))?.let { return it }
            }
        }.onFailure { Log.w(TAG, "history path failed: ${it.message}") }
        return fetchScadSource(info.designId, info.unikey, info.modelName)
    }

    /**
     * True when [text] is plausibly OpenSCAD source: HTML pages (Cloudflare
     * interstitial) and MakerWorld's packed "MW 1.0" preview meshes are not.
     */
    private fun looksLikeScad(text: String?): Boolean {
        if (text == null || text.length < 40) return false
        val head = text.take(64)
        if (head.startsWith("MW 1.0")) return false
        if (head.trimStart().startsWith("<")) return false
        return text.contains("=") && text.contains(";")
    }

    /** Recursively finds the first long JSON string that looks like OpenSCAD code. */
    private fun findScad(v: Any): String? =
        when (v) {
            is JSONObject -> v.keys().asSequence().mapNotNull { findScad(v[it]) }.firstOrNull()
            is JSONArray -> (0 until v.length()).mapNotNull { findScad(v[it]) }.firstOrNull()
            is String -> if (looksLikeScad(v) && v.length > 100) v else null
            else -> null
        }

    private fun get(
        url: String,
        html: Boolean,
    ): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", if (html) "text/html" else "*/*")
        cfCookie?.let { conn.setRequestProperty("Cookie", it) }
        if (token.isNotBlank() && url.startsWith(SITE)) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        return try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "GET $url -> HTTP $code")
                throw IOException("HTTP $code")
            }
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    @Volatile
    private var cfCookie: String? = prefs.getString("mw_web_cookies", "")?.takeIf { it.isNotBlank() }

    /** Cookies collected by the in-app WebView (Cloudflare clearance + MakerWorld web session). */
    fun setWebCookies(cookie: String) {
        if (cookie.isBlank()) return
        cfCookie = cookie
        prefs.edit().putString("mw_web_cookies", cookie).apply()
    }

    private fun scadCacheKey(info: ParametricInfo) = "mw_scad__${info.designId}_${info.unikey}"

    fun cachedScad(info: ParametricInfo): String? =
        prefs.getString(scadCacheKey(info), null)?.takeIf { it.isNotBlank() }

    fun cacheScad(
        info: ParametricInfo,
        text: String,
    ) {
        prefs.edit().putString(scadCacheKey(info), text).apply()
        Log.i(TAG, "cached source for ${info.designId}: ${text.length} chars")
    }

    fun cachePayload(
        info: ParametricInfo,
        body: String,
    ) {
        prefs.edit().putString("mw_pmm_payload__${info.designId}_${info.unikey}", body).apply()
        Log.i(TAG, "cached MakerWorld payload for ${info.designId}: ${body.length} bytes")
    }

    fun cachedPayload(info: ParametricInfo): String? =
        prefs.getString("mw_pmm_payload__${info.designId}_${info.unikey}", null)?.takeIf { it.isNotBlank() }

    /** Visits makerworld.com once to pass the Cloudflare check and collect cookies. */
    private fun refreshCfSession() {
        val conn = URL("$SITE/en").openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "text/html")
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "cf visit HTTP $code")
                return
            }
            val keep = LinkedHashMap<String, String>()
            conn.headerFields["Set-Cookie"]?.forEach { sc ->
                val name = sc.substringBefore(';').substringBefore('=').trim()
                if (name.isNotBlank() && !keep.containsKey(name)) keep[name] = sc.substringBefore(';')
            }
            cfCookie = keep.values.joinToString("; ").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    /** Fetches the PMM editor page (server-rendered) with CF session cookies. */
    fun fetchScadSource(
        designId: String,
        unikey: String,
        modelName: String,
    ): String? {
        val url =
            "$SITE/en/makerlab/parametricModelMaker?designId=$designId" +
                "&modelName=" + java.net.URLEncoder.encode(modelName, "UTF-8") +
                "&protected=true&unikey=$unikey"
        repeat(2) { attempt ->
            if (cfCookie == null) refreshCfSession()
            val html =
                try {
                    get(url, html = true)
                } catch (e: IOException) {
                    Log.w(TAG, "editor page attempt $attempt failed: ${e.message}")
                    cfCookie = null
                    return@repeat
                }
            extractScad(html)?.let { return it }
            cfCookie = null
        }
        return null
    }

    private fun extractScad(html: String): String? {
        val lit =
            Regex(""""scadContent":"((?:\\.|[^"\\])*)"""").find(html)
                ?: Regex(""""scad":"((?:\\.|[^"\\])*)"""").find(html)
                ?: return null
        return try {
            org.json.JSONTokener("\"${lit.groupValues[1]}\"").nextValue() as String
        } catch (_: Exception) {
            null
        }
    }

    /**
     * The make (MakerWorld's per-file customization record) the renderer serves.
     * The design's existing make (`scad/gethistoryid`) must come first; creating a
     * new make via `createfromdesign` is only a fallback when none exists yet.
     */
    private fun makeIdFor(info: ParametricInfo): String? =
        runCatching {
            JSONObject(
                authed("GET", "$GATEWAY/creation-service/scad/gethistoryid?uniqueKey=${info.unikey}", null),
            ).idOr("makeId")
        }.getOrNull()
            ?: runCatching {
                val made =
                    JSONObject(
                        authed(
                            "POST",
                            "$GATEWAY/creation-service/my/createfromdesign/${info.designId}",
                            JSONObject().put("uniqueKey", info.unikey).put("category", 6).toString(),
                        ),
                    )
                made.idOr("makeId") ?: made.idOr("id")
            }.getOrNull()

    private fun JSONObject.idOr(name: String): String? =
        optString(name, "").ifBlank { optString("id", "") }.ifBlank { null }

    /**
     * Saves the edited values onto a make via `PUT creation-executor-service/my/scad/history`
     * (POST returns 405). `history` is a JSON-encoded string containing the array of saved entries.
     */
    private fun saveCustomization(
        makeId: String,
        paramsJson: String,
    ) {
        val entry =
            JSONObject()
                .put("isCollected", false)
                .put("name", SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
                .put("time", System.currentTimeMillis())
                .put("params", paramsJson)
        val stored =
            runCatching {
                JSONObject(
                    authed("GET", "$GATEWAY/creation-executor-service/my/scad/history?makeId=$makeId", null),
                ).optString("history", "")
            }.getOrNull().orEmpty()
        val entries =
            if (stored.isBlank()) JSONArray() else runCatching { JSONArray(stored) }.getOrElse { JSONArray() }
        val merged =
            JSONArray().put(entry).apply {
                for (i in 0 until minOf(entries.length(), MAX_SAVED_VERSIONS)) put(entries.get(i))
            }
        val makeIdValue: Any = makeId.toLongOrNull() ?: makeId
        val res =
            runCatching {
                authed(
                    "PUT",
                    "$GATEWAY/creation-executor-service/my/scad/history",
                    JSONObject().put("makeId", makeIdValue).put("history", merged.toString()).toString(),
                )
            }
        Log.i(TAG, "save customization ($makeId) -> ${res.getOrNull()?.take(80) ?: res.exceptionOrNull()?.message}")
    }

    /** POSTs the edited OpenSCAD source for rendering and polls to completion. */
    fun render(
        scad: String,
        info: ParametricInfo,
        onStatus: (String) -> Unit,
    ): Job {
        val designId = info.designId
        val unikey = info.unikey
        val isProtected = info.isProtected
        runCatching {
            File(File(cacheDir, "downloads"), "scad_debug.scad").apply { parentFile?.mkdirs() }.writeText(scad)
        }
        val makeId = makeIdFor(info)
        Log.i(TAG, "make for ${unikey.take(8)}: ${makeId ?: "unknown"}")
        if (isProtected && makeId != null) {
            saveCustomization(makeId, paramsJsonFromScad(scad))
        }

        // MakerWorld keys its render cache by source hash and can answer a cache hit
        // with an empty mesh URL, so append a harmless timestamp comment.
        val freshScad = "$scad\n// generated ${System.currentTimeMillis()}\n"
        val encodedCode =
            android.util.Base64.encodeToString(
                freshScad.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
        val baseFlags =
            "--backend=manifold\n--enable=fast-csg\n--enable=textmetrics\n--enable=roof\n--colorscheme=AllWhite"
        val protectedFlags =
            buildProtectedOpenScadParams(
                baseFlags = baseFlags,
                scad = scad,
            )
        val encodedProtectedParams =
            android.util.Base64.encodeToString(
                protectedFlags.toByteArray(Charsets.UTF_8),
                android.util.Base64.NO_WRAP,
            )
        val harvested = if (isProtected) cachedPayload(info) else null
        val body =
            if (harvested != null) {
                JSONObject(harvested)
                    .put("code", encodedCode)
                    .put("params", encodedProtectedParams)
            } else if (isProtected) {
                JSONObject()
                    .put("code", encodedCode)
                    .put("params", encodedProtectedParams)
                    .put("type", "obj")
                    .put("color", "#ffffff")
                    .put("designId", designId.toLongOrNull() ?: designId)
                    .put("isClosedSource", true)
                    .put("uniqueKey", unikey)
            } else {
                // Request OBJ + MTL so OpenSCAD color(...) parts stay separated into
                // per-colour materials that SlicePilot packs into a multi-colour 3MF for AMS.
                val openFlags =
                    android.util.Base64.encodeToString(
                        baseFlags.toByteArray(Charsets.UTF_8),
                        android.util.Base64.NO_WRAP,
                    )
                JSONObject()
                    .put("code", encodedCode)
                    .put("params", openFlags)
                    .put("designId", designId.toLongOrNull() ?: designId)
                    .put("key", unikey)
                    .put("isProtected", false)
                    .put("isFusion", false)
                    .put("type", "obj")
                    .put("color", "#ffffff")
                    .put("devProductName", MAKERWORLD_PRINTER)
                    .put("nozzle", MAKERWORLD_NOZZLE)
            }
        makeId?.let { body.put("makeId", it.toLongOrNull() ?: it) }

        val first = JSONObject(authed("POST", "$GATEWAY/creation-executor-service/scad/modelv2", body.toString()))
        Log.i(TAG, "modelv2 POST -> $first")
        val id = first.optString("id", "")
        var last = first
        if (id.isBlank()) throw CustomizeError("MakerWorld did not accept the model for rendering.")
        var obj = first.optString("objUrl", "").takeIf { it.isNotBlank() }
        var stl = first.optString("stlUrl", "").takeIf { it.isNotBlank() } ?: first.firstStlUrl()
        var status = first.optString("status", "waiting")
        var tries = 0
        while (status !in setOf("success", "failed", "error") && tries < 150) {
            Thread.sleep(2000)
            val j = JSONObject(authed("GET", "$GATEWAY/creation-executor-service/scad/modelv2?id=$id", null))
            status = j.optString("status", "waiting")
            last = j
            if (tries % 5 == 0) Log.i(TAG, "modelv2 poll $tries: ${j.toString().take(300)}")
            obj = j.optString("objUrl", "").takeIf { it.isNotBlank() } ?: obj
            stl = (j.optString("stlUrl", "").takeIf { it.isNotBlank() } ?: j.firstStlUrl()) ?: stl
            tries++
            onStatus(
                when (status) {
                    "waiting" -> "Queued at MakerWorld…"
                    else -> "Generating model…"
                },
            )
        }
        if (status != "success") {
            val why = last.optString("errMsg", "").ifBlank { status }
            Log.w(TAG, "modelv2 failed: status=$status id=$id response=${last.toString().take(400)}")
            val friendly =
                if (why.contains("top level object is empty", true)) {
                    "MakerWorld produced an empty model — with these values nothing is left to " +
                        "print (e.g. text set to empty, a size of 0, or everything unchecked)."
                } else {
                    "MakerWorld failed to generate the model ($why)."
                }
            throw CustomizeError(friendly)
        }
        val mtl = last.optString("mtlUrl", "").takeIf { it.startsWith("http") }
        Log.i(TAG, "modelv2 ok: stl=${stl?.take(90)} obj=${obj?.take(90)} mtl=${mtl?.take(60)}")
        return Job(id, obj, stl, mtl)
    }

    /** Downloads the generated mesh of a finished [job] and converts/unwraps it for SlicePilot. */
    fun downloadMesh(
        job: Job,
        progressName: String,
    ): File {
        // Prefer OBJ + MTL when available so multi-colour OpenSCAD models become multi-colour 3MFs.
        val url =
            if (job.objUrl != null && job.mtlUrl != null) {
                job.objUrl
            } else {
                job.objUrl ?: job.stlUrl ?: throw CustomizeError("MakerWorld returned no model link.")
            }
        val raw = downloadBytes(url)
        val mtlText =
            job.mtlUrl?.let { mtl ->
                runCatching { downloadBytes(mtl).toString(Charsets.UTF_8) }.getOrNull()
            }
        return writeNormalizedMesh(raw, progressName, cacheDir, mtlText)
    }

    /**
     * Downloads a customized mesh directly from [url] (used for both [downloadMesh]
     * and WebView-intercepted MakerWorld downloads / `modelv2` results).
     */
    fun downloadFromUrl(
        url: String,
        suggestedName: String,
    ): File {
        val raw = downloadBytes(url)
        return writeNormalizedMesh(raw, suggestedName, cacheDir, mtlText = null)
    }

    private fun downloadBytes(url: String): ByteArray {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 180_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        cfCookie?.let { conn.setRequestProperty("Cookie", it) }
        if (token.isNotBlank() && (url.startsWith(SITE) || url.contains("bambulab.com"))) {
            conn.setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw CustomizeError("Downloading the model failed (HTTP $code).")
            return conn.inputStream.use { it.readBytes() }
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private const val TAG = "MakerworldCustom"

        /**
         * MakerWorld machine used for generation requests. MakerWorld only accepts its
         * own machine names; SlicePilot takes the resulting mesh and slices locally
         * with the user's own printer profile.
         */
        private const val MAKERWORLD_PRINTER = "Bambu Lab A1 mini"
        private const val MAKERWORLD_NOZZLE = 0.4
        private const val SITE = "https://makerworld.com"
        private const val GATEWAY = "https://api.bambulab.com/v1"
        private const val MAX_SAVED_VERSIONS = 20
        private const val USER_AGENT =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        /** MakerWorld returns `stlUrl` on older responses and a `stlUrls` array on newer ones. */
        internal fun JSONObject.firstStlUrl(): String? =
            optJSONArray("stlUrls")?.let { arr ->
                (0 until arr.length()).map { arr.optString(it, "") }.firstOrNull { it.startsWith("http") }
            }

        /** Parses a `modelv2` JSON response captured from the WebView into a [Job] if ready. */
        internal fun parseJobFromJson(jsonText: String): Job? {
            val j = runCatching { JSONObject(jsonText) }.getOrNull() ?: return null
            if (j.optString("status") != "success") return null
            val stl = j.optString("stlUrl", "").takeIf { it.startsWith("http") } ?: j.firstStlUrl()
            val obj = j.optString("objUrl", "").takeIf { it.startsWith("http") }
            if (stl == null && obj == null) return null
            val mtl = j.optString("mtlUrl", "").takeIf { it.startsWith("http") }
            return Job(
                id = j.optString("id", "web"),
                objUrl = obj,
                stlUrl = stl,
                mtlUrl = mtl,
            )
        }

        /**
         * Builds the newline-delimited OpenSCAD CLI arguments (`params` field in `modelv2`)
         * for closed-source designs, passing every edited customizer parameter via `-D`.
         */
        internal fun buildProtectedOpenScadParams(
            baseFlags: String,
            scad: String,
        ): String {
            val sb = StringBuilder(baseFlags.trimEnd())
            for (p in ScadCustomizer.parse(scad)) {
                if (p is ScadCustomizer.Param.Raw) continue
                sb.append("\n-D")
                sb.append('\n')
                sb.append(p.name)
                sb.append('=')
                sb.append(p.value)
            }
            return sb.toString()
        }

        /**
         * The values inside [scad] in the shape a MakerWorld make stores them:
         * `name -> value` with strings unquoted and numbers/booleans as written.
         */
        internal fun paramsJsonFromScad(scad: String): String {
            val obj = JSONObject()
            for (p in ScadCustomizer.parse(scad)) {
                if (p is ScadCustomizer.Param.Raw) continue
                obj.put(p.name, ScadCustomizer.displayValue(p))
            }
            return obj.toString()
        }

        /**
         * Normalizes raw downloaded bytes into a SlicePilot-loadable `.3mf` or `.stl` file:
         * - ZIP magic (`PK\x03\x04`) -> saved directly as `.3mf` (supports multi-colour AMS!)
         * - MakerWorld `MW 1.0` envelope -> unwrapped cleanly
         * - Wavefront OBJ with 2+ `usemtl` materials -> packed into a multi-colour Bambu `.3mf`
         *   with per-material assembly parts + `filament_colour` so AMS/Canvas works out of the box!
         * - Wavefront OBJ with 1 material -> converted to binary `.stl`
         * - ASCII/binary STL -> saved as `.stl`
         */
        internal fun writeNormalizedMesh(
            raw: ByteArray,
            suggestedName: String,
            cacheDir: File,
            mtlText: String? = null,
        ): File {
            val isZip =
                raw.size >= 4 &&
                    raw[0] == 0x50.toByte() &&
                    raw[1] == 0x4B.toByte() &&
                    raw[2] == 0x03.toByte() &&
                    raw[3] == 0x04.toByte()

            val baseName =
                suggestedName
                    .substringBeforeLast('.')
                    .replace(Regex("""[^\w.-]+"""), "_")
                    .ifBlank { "custom_model" }

            val payload: ByteArray
            val ext: String
            if (isZip) {
                payload = raw
                ext = "3mf"
            } else {
                val bytes = unwrapMakerWorldContainer(raw)
                val head = String(bytes, 0, minOf(bytes.size, 4096), Charsets.UTF_8)
                val isObj = head.contains("mtllib") || Regex("(?m)^\\s*v\\s+[0-9.-]").containsMatchIn(head)
                if (isObj) {
                    val objText = String(bytes, Charsets.UTF_8)
                    val multi3mf = objToMultiColour3mfBytes(objText, mtlText, baseName)
                    if (multi3mf != null) {
                        payload = multi3mf
                        ext = "3mf"
                    } else {
                        payload =
                            objToStlBytes(objText)
                                ?: throw CustomizeError("MakerWorld's mesh could not be converted to STL.")
                        ext = "stl"
                    }
                } else {
                    payload = bytes
                    ext = "stl"
                }
            }

            return File(File(cacheDir, "downloads"), "$baseName.$ext").apply {
                parentFile?.mkdirs()
                writeBytes(payload)
            }
        }

        private val HEX_COLOR_RE = Regex("""^#[0-9a-fA-F]{6}$""")

        /** Parses an OpenSCAD `.mtl` file into `materialName -> #RRGGBB` hex colours. */
        internal fun parseMtlColors(mtl: String?): Map<String, String> {
            if (mtl.isNullOrBlank()) return emptyMap()
            val out = LinkedHashMap<String, String>()
            val ws = Regex("\\s+")
            var current: String? = null
            for (rawLine in mtl.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("newmtl ", ignoreCase = true)) {
                    current = line.substring(7).trim()
                } else if (current != null && line.startsWith("Kd ", ignoreCase = true)) {
                    val parts = line.substring(3).trim().split(ws)
                    if (parts.size >= 3) {
                        val r = ((parts[0].toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * 255f).roundToInt()
                        val g = ((parts[1].toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * 255f).roundToInt()
                        val b = ((parts[2].toFloatOrNull() ?: 0f).coerceIn(0f, 1f) * 255f).roundToInt()
                        out[current] = String.format(Locale.US, "#%02X%02X%02X", r, g, b)
                    }
                }
            }
            return out
        }

        /**
         * Converts a multi-material Wavefront OBJ (+ optional MTL) into a Bambu/Orca-compatible
         * multi-colour `.3mf` assembly where each `usemtl` colour group is a `<component>` volume
         * assigned to its own 1-based extruder in `Metadata/model_settings.config`, with its exact
         * colour in `Metadata/project_settings.config` (`filament_colour`).
         *
         * Returns `null` when the OBJ has fewer than 2 distinct non-empty materials (caller falls
         * back to compact binary STL).
         */
        internal fun objToMultiColour3mfBytes(
            obj: String,
            mtl: String?,
            modelName: String = "Custom Model",
        ): ByteArray? {
            val verts = ArrayList<FloatArray>(8192)
            val groups = LinkedHashMap<String, ArrayList<IntArray>>()
            val ws = Regex("\\s+")
            var currentMat = "default"

            for (rawLine in obj.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("usemtl ", ignoreCase = true)) {
                    currentMat = line.substring(7).trim().ifBlank { "default" }
                } else if (line.startsWith("v ") || line.startsWith("v\t")) {
                    val p = line.substring(1).trim().split(ws)
                    if (p.size < 3) return null
                    val x = p[0].toFloatOrNull() ?: return null
                    val y = p[1].toFloatOrNull() ?: return null
                    val z = p[2].toFloatOrNull() ?: return null
                    verts.add(floatArrayOf(x, y, z))
                } else if (line.startsWith("f ") || line.startsWith("f\t")) {
                    val tokens = line.substring(1).trim().split(ws)
                    if (tokens.size < 3) return null
                    val idx = IntArray(tokens.size)
                    for (i in tokens.indices) {
                        val rawIdx = tokens[i].substringBefore('/').toIntOrNull() ?: return null
                        val resolved = if (rawIdx < 0) verts.size + rawIdx + 1 else rawIdx
                        if (resolved < 1 || resolved > verts.size) return null
                        idx[i] = resolved
                    }
                    val list = groups.getOrPut(currentMat) { ArrayList(4096) }
                    for (i in 1 until idx.size - 1) {
                        list.add(intArrayOf(idx[0], idx[i], idx[i + 1]))
                    }
                }
            }

            val nonEmpty = groups.entries.filter { it.value.isNotEmpty() }
            if (verts.isEmpty() || nonEmpty.size < 2) return null

            val mtlColors = parseMtlColors(mtl)
            val fallbackPalette = listOf("#F5F5F5", "#E53935", "#1E88E5", "#43A047", "#FDD835", "#8E24AA")
            val partColors =
                nonEmpty.mapIndexed { i, (mat, _) ->
                    val resolved =
                        mtlColors[mat]
                            ?: mat.takeIf { HEX_COLOR_RE.matches(it) }?.uppercase(Locale.US)
                            ?: fallbackPalette[i % fallbackPalette.size]
                    resolved
                }

            val safeName = xmlEscape(modelName.ifBlank { "Custom Model" })
            val assemblyId = nonEmpty.size + 1

            val modelXml = StringBuilder(64 * 1024)
            modelXml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            modelXml.append(
                "<model unit=\"millimeter\" xml:lang=\"en-US\" " +
                    "xmlns=\"http://schemas.microsoft.com/3dmanufacturing/core/2015/02\" " +
                    "xmlns:BambuStudio=\"http://schemas.bambulab.com/package/2021\" " +
                    "xmlns:p=\"http://schemas.microsoft.com/3dmanufacturing/production/2015/06\">\n",
            )
            modelXml.append(" <metadata name=\"Application\">BambuStudio-01.09.03.50</metadata>\n")
            modelXml.append(" <resources>\n")

            nonEmpty.forEachIndexed { idx, (mat, tris) ->
                val objId = idx + 1
                val partLabel = xmlEscape("Color $objId ($mat)")
                // Re-index vertices per colour group so each object mesh stays compact and valid.
                val remap = HashMap<Int, Int>(tris.size)
                val localVerts = ArrayList<FloatArray>(tris.size)
                val localTris = ArrayList<IntArray>(tris.size)
                for (t in tris) {
                    val a = remap.getOrPut(t[0]) { localVerts.size.also { localVerts.add(verts[t[0] - 1]) } }
                    val b = remap.getOrPut(t[1]) { localVerts.size.also { localVerts.add(verts[t[1] - 1]) } }
                    val c = remap.getOrPut(t[2]) { localVerts.size.also { localVerts.add(verts[t[2] - 1]) } }
                    localTris.add(intArrayOf(a, b, c))
                }

                modelXml.append("  <object id=\"").append(objId).append("\" type=\"model\" name=\"").append(partLabel).append("\">\n")
                modelXml.append("   <mesh>\n    <vertices>\n")
                for (v in localVerts) {
                    modelXml.append("     <vertex x=\"").append(v[0]).append("\" y=\"").append(v[1]).append("\" z=\"").append(v[2]).append("\"/>\n")
                }
                modelXml.append("    </vertices>\n    <triangles>\n")
                for (t in localTris) {
                    modelXml.append("     <triangle v1=\"").append(t[0]).append("\" v2=\"").append(t[1]).append("\" v3=\"").append(t[2]).append("\"/>\n")
                }
                modelXml.append("    </triangles>\n   </mesh>\n  </object>\n")
            }

            // Single composite assembly keeps base plate and raised text locked at exact relative Z.
            modelXml.append("  <object id=\"").append(assemblyId).append("\" type=\"model\" name=\"").append(safeName).append("\">\n")
            modelXml.append("   <components>\n")
            for (i in 1..nonEmpty.size) {
                modelXml.append("    <component objectid=\"").append(i).append("\" transform=\"1 0 0 0 1 0 0 0 1 0 0 0\"/>\n")
            }
            modelXml.append("   </components>\n  </object>\n")
            modelXml.append(" </resources>\n <build>\n")
            modelXml.append("  <item objectid=\"").append(assemblyId).append("\" printable=\"1\"/>\n")
            modelXml.append(" </build>\n</model>\n")

            val modelSettings = StringBuilder()
            modelSettings.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<config>\n")
            modelSettings.append("  <object id=\"").append(assemblyId).append("\">\n")
            modelSettings.append("    <metadata key=\"name\" value=\"").append(safeName).append("\"/>\n")
            modelSettings.append("    <metadata key=\"extruder\" value=\"1\"/>\n")
            nonEmpty.forEachIndexed { idx, (mat, _) ->
                val partId = idx + 1
                modelSettings.append("    <part id=\"").append(partId).append("\" subtype=\"normal_part\">\n")
                modelSettings.append("      <metadata key=\"name\" value=\"").append(xmlEscape("Color $partId ($mat)")).append("\"/>\n")
                modelSettings.append("      <metadata key=\"extruder\" value=\"").append(partId).append("\"/>\n")
                modelSettings.append("    </part>\n")
            }
            modelSettings.append("  </object>\n</config>\n")

            val projectSettings =
                JSONObject()
                    .put("filament_colour", JSONArray(partColors))
                    .put("filament_type", JSONArray(List(partColors.size) { "PLA" }))
                    .put("filament_settings_id", JSONArray(List(partColors.size) { "Generic PLA" }))
                    .toString(2)

            val contentTypes =
                """<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
 <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
 <Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/>
 <Default Extension="config" ContentType="text/plain"/>
</Types>
"""
            val rels =
                """<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
 <Relationship Target="/3D/3dmodel.model" Id="rel-1" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/>
</Relationships>
"""

            val baos = ByteArrayOutputStream(64 * 1024)
            ZipOutputStream(baos).use { zos ->
                fun put(path: String, text: String) {
                    zos.putNextEntry(ZipEntry(path))
                    zos.write(text.toByteArray(Charsets.UTF_8))
                    zos.closeEntry()
                }
                put("[Content_Types].xml", contentTypes)
                put("_rels/.rels", rels)
                put("3D/3dmodel.model", modelXml.toString())
                put("Metadata/model_settings.config", modelSettings.toString())
                put("Metadata/project_settings.config", projectSettings)
            }
            runCatching {
                Log.i(TAG, "packed multi-colour 3MF: ${nonEmpty.size} colours (${partColors.joinToString()})")
            }
            return baos.toByteArray()
        }

        private fun xmlEscape(s: String): String =
            s
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;")

        /**
         * OBJ → binary STL. MakerWorld's protected designs come back as OBJ + MTL, and
         * SlicePilot slices STL/3MF, so faces are fan-triangulated and normals computed.
         */
        internal fun objToStlBytes(obj: String): ByteArray? {
            val verts = ArrayList<FloatArray>(8192)
            val faces = ArrayList<IntArray>(8192)
            val ws = Regex("\\s+")
            for (rawLine in obj.lineSequence()) {
                val line = rawLine.trim()
                if (line.startsWith("v ") || line.startsWith("v\t")) {
                    val p = line.substring(1).trim().split(ws)
                    if (p.size < 3) return null
                    val x = p[0].toFloatOrNull() ?: return null
                    val y = p[1].toFloatOrNull() ?: return null
                    val z = p[2].toFloatOrNull() ?: return null
                    verts.add(floatArrayOf(x, y, z))
                } else if (line.startsWith("f ") || line.startsWith("f\t")) {
                    val tokens = line.substring(1).trim().split(ws)
                    if (tokens.size < 3) return null
                    val idx = IntArray(tokens.size)
                    for (i in tokens.indices) {
                        val rawIdx = tokens[i].substringBefore('/').toIntOrNull() ?: return null
                        val resolved = if (rawIdx < 0) verts.size + rawIdx + 1 else rawIdx
                        if (resolved < 1 || resolved > verts.size) return null
                        idx[i] = resolved
                    }
                    for (i in 1 until idx.size - 1) {
                        faces.add(intArrayOf(idx[0], idx[i], idx[i + 1]))
                    }
                }
            }
            if (verts.isEmpty() || faces.isEmpty()) return null
            val buf =
                ByteBuffer
                    .allocate(84 + faces.size * 50)
                    .order(ByteOrder.LITTLE_ENDIAN)
            buf.put(ByteArray(80))
            buf.putInt(faces.size)
            for (f in faces) {
                val v0 = verts[f[0] - 1]
                val v1 = verts[f[1] - 1]
                val v2 = verts[f[2] - 1]
                val ux = v1[0] - v0[0]
                val uy = v1[1] - v0[1]
                val uz = v1[2] - v0[2]
                val wx = v2[0] - v0[0]
                val wy = v2[1] - v0[1]
                val wz = v2[2] - v0[2]
                var nx = uy * wz - uz * wy
                var ny = uz * wx - ux * wz
                var nz = ux * wy - uy * wx
                val len = sqrt(nx * nx + ny * ny + nz * nz)
                if (len > 1e-6f) {
                    nx /= len
                    ny /= len
                    nz /= len
                }
                buf.putFloat(nx).putFloat(ny).putFloat(nz)
                buf.putFloat(v0[0]).putFloat(v0[1]).putFloat(v0[2])
                buf.putFloat(v1[0]).putFloat(v1[1]).putFloat(v1[2])
                buf.putFloat(v2[0]).putFloat(v2[1]).putFloat(v2[2])
                buf.putShort(0)
            }
            return buf.array()
        }

        /**
         * MakerWorld serves some generated files with an 80-byte `MW 1.0 …` header.
         * - If bytes 80..83 hold a binary STL triangle count matching `84 + count * 50`,
         *   the file is already a valid binary STL (its 80-byte header just starts with `MW `).
         * - Otherwise it is an ASCII STL or OBJ preceded/overwritten by an 80-byte `MW ` block;
         *   strip the 80 bytes and restore any overwritten ASCII STL opening header lines.
         */
        internal fun unwrapMakerWorldContainer(bytes: ByteArray): ByteArray {
            if (bytes.size < 84 || !bytes.decodeToString(0, 3).startsWith("MW ")) return bytes
            val triCount =
                ByteBuffer
                    .wrap(bytes, 80, 4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .int
                    .toLong() and 0xFFFFFFFFL
            if (triCount > 0 && 84L + triCount * 50L == bytes.size.toLong()) {
                return bytes
            }
            val body = bytes.copyOfRange(80, bytes.size)
            val trimmed = body.toString(Charsets.UTF_8).trimStart()
            return when {
                trimmed.startsWith("outer loop") -> {
                    ("solid Model\nfacet normal 0 0 0\n" + trimmed).toByteArray(Charsets.UTF_8)
                }

                trimmed.startsWith("vertex ") -> {
                    ("solid Model\nfacet normal 0 0 0\nouter loop\n" + trimmed).toByteArray(Charsets.UTF_8)
                }

                trimmed.startsWith("facet ") -> {
                    ("solid Model\n" + trimmed).toByteArray(Charsets.UTF_8)
                }

                else -> {
                    body
                }
            }
        }
    }
}
