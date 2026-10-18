package world.yemyat.slicepilot.repo

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import world.yemyat.slicepilot.tracking.EventNotifier
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** A model as returned by a repository search. */
data class ModelListing(
    val source: String,
    val id: String,
    val name: String,
    val author: String?,
    val thumbnailUrl: String?,
    val downloads: Int?,
)

/** A downloadable file (STL / 3MF / ZIP) belonging to a listing. */
data class ModelFile(
    val name: String,
    val url: String,
    val sizeBytes: Long?,
)

/** Full detail of a model, shown when a search result is tapped. */
data class ModelDetail(
    val listing: ModelListing,
    val description: String,
    val images: List<String>,
    val likes: Int?,
    val published: String?,
    val files: List<ModelFile>,
)

data class BrowseState(
    val source: ModelSource = ModelSource.PRINTABLES,
    val query: String = "",
    val results: List<ModelListing> = emptyList(),
    val searching: Boolean = false,
    val error: String? = null,
    val detail: ModelDetail? = null,
    val loadingDetail: Boolean = false,
    val downloadingFile: String? = null,
    val downloadProgress: Int = 0,
)

enum class ModelSource(val id: String, val label: String) {
    PRINTABLES("printables", "Printables"),
    THINGIVERSE("thingiverse", "Thingiverse"),
    NEXPRINT("nexprint", "Nexprint");

    companion object {
        fun from(id: String): ModelSource = entries.firstOrNull { it.id == id } ?: PRINTABLES
    }
}

/**
 * Searches model repositories (Printables, Thingiverse) through their web APIs
 * and downloads STL/3MF files into the app cache, feeding the existing
 * "open model" pipeline.
 */
class ModelBrowser(
    private val prefs: SharedPreferences,
    private val cacheDir: File,
    private val cloudTokenProvider: () -> String? = { null },
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    // Results cache keyed by "source|query", so re-visiting a tab is instant.
    private val resultsCache = HashMap<String, List<ModelListing>>()
    private var searchGeneration = 0

    val thingiverseToken: String
        get() = prefs.getString("thingiverse_token", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THINGIVERSE_TOKEN

    fun setThingiverseToken(token: String) {
        prefs.edit().putString("thingiverse_token", token.trim()).apply()
        _state.value = _state.value.copy(error = null)
    }

    fun setSource(source: ModelSource) {
        if (source == _state.value.source) return
        // Abandon any in-flight search so it can't populate the new tab.
        searchGeneration++
        _state.value = _state.value.copy(source = source, results = emptyList(), searching = false, error = null)
        if (_state.value.query.isNotBlank()) search()
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
    }

    fun search() {
        val st = _state.value
        val q = st.query.trim()
        if (q.isEmpty() || st.searching) return
        if (st.source == ModelSource.THINGIVERSE && thingiverseToken.isBlank()) {
            _state.value = st.copy(error = "Add your Thingiverse API token below to search.")
            return
        }
        val cacheKey = "${st.source.id}|$q"
        resultsCache[cacheKey]?.let { cached ->
            _state.value = st.copy(searching = false, results = cached, error = null)
            return
        }
        val generation = ++searchGeneration
        _state.value = st.copy(searching = true, error = null, results = emptyList())
        EventNotifier.trackEvent("model_search", mapOf("source" to st.source.id, "query" to q))
        scope.launch {
            try {
                val results = withContext(Dispatchers.IO) {
                    when (st.source) {
                        ModelSource.PRINTABLES -> PrintablesApi.search(q)
                        ModelSource.THINGIVERSE -> ThingiverseApi.search(q, thingiverseToken)
                        ModelSource.NEXPRINT -> NexprintApi.search(q)
                    }
                }
                if (generation == searchGeneration) {
                    resultsCache[cacheKey] = results
                    _state.value = _state.value.copy(searching = false, results = results)
                }
            } catch (e: Exception) {
                if (generation == searchGeneration) {
                    _state.value = _state.value.copy(searching = false, error = readable(e))
                }
            }
        }
    }

    fun openDetail(listing: ModelListing) {
        _state.value = _state.value.copy(detail = null, loadingDetail = true, error = null)
        EventNotifier.trackEvent("model_detail_opened", mapOf("source" to listing.source, "name" to listing.name))
        scope.launch {
            try {
                val detail = withContext(Dispatchers.IO) {
                    when (listing.source) {
                        ModelSource.PRINTABLES.id -> PrintablesApi.detail(listing)
                        ModelSource.NEXPRINT.id -> NexprintApi.detail(listing)
                        else -> ThingiverseApi.detail(listing, thingiverseToken)
                    }
                }
                _state.value = _state.value.copy(detail = detail, loadingDetail = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingDetail = false, error = readable(e))
            }
        }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null)
    }

    fun download(file: ModelFile, onDone: (String) -> Unit) {
        if (_state.value.downloadingFile != null) return
        _state.value = _state.value.copy(downloadingFile = file.name, downloadProgress = 0, error = null)
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    downloadToCache(file)
                }
                EventNotifier.trackEvent(
                    "model_downloaded",
                    mapOf("source" to file.url, "file" to file.name),
                )
                _state.value = _state.value.copy(downloadingFile = null, downloadProgress = 100)
                onDone(path)
            } catch (e: Exception) {
                _state.value = _state.value.copy(downloadingFile = null, downloadProgress = 0, error = readable(e))
            }
        }
    }

    /** Resolves printables:// file references to fresh https download URLs at click time. */
    private fun resolveUrl(file: ModelFile): String {
        if (file.url.startsWith("printables://")) {
            val parts = file.url.removePrefix("printables://file/").split("/")
            if (parts.size < 3) throw IOException("Bad Printables file reference.")
            return PrintablesApi.downloadLink(parts[0], parts[1], parts[2])
        }
        if (file.url.startsWith("nexprint://")) {
            val parts = file.url.removePrefix("nexprint://file/").split("/")
            if (parts.size < 2) throw IOException("Bad Nexprint file reference.")
            return NexprintApi.presignedDownloadUrl(parts[0], parts[1], cloudTokenProvider())
        }
        return file.url
    }

    private fun downloadToCache(file: ModelFile): String {
        val dir = File(cacheDir, "downloads").apply { mkdirs() }
        val safeName = file.name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
        val target = File(dir, safeName)
        val url = resolveUrl(file)
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            if (conn.responseCode !in 200..299) {
                throw IOException("HTTP ${conn.responseCode} downloading ${file.name}")
            }
            val total = conn.contentLength.toLong()
            conn.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = ((done * 100) / total).toInt()
                            if (pct != _state.value.downloadProgress) {
                                _state.value = _state.value.copy(downloadProgress = pct)
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        return target.absolutePath
    }

    fun dispose() {
        scope.cancel()
    }

    private fun readable(e: Exception): String = when {
        e is java.net.SocketTimeoutException -> "The site took too long to respond."
        e is java.net.UnknownHostException -> "No network connection."
        e is IOException -> e.message ?: "Network request failed."
        else -> e.message ?: e.javaClass.simpleName
    }
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) SlicePilot/1.0 AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

// Built-in Thingiverse app token (thingiverse.com/developers); user-entered tokens override it.
private const val DEFAULT_THINGIVERSE_TOKEN = "375d3773d07dc8a6c02a958782b16893"

private val MODEL_EXTENSIONS = listOf(".stl", ".3mf", ".zip")

private fun isModelFile(name: String): Boolean {
    val lower = name.lowercase()
    return MODEL_EXTENSIONS.firstOrNull { lower.endsWith(it) } != null
}

private fun extOf(name: String): String {
    val lower = name.lowercase()
    return MODEL_EXTENSIONS.firstOrNull { lower.endsWith(it) } ?: ".stl"
}

private fun getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject {    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
    conn.instanceFollowRedirects = true
    conn.setRequestProperty("User-Agent", USER_AGENT)
    conn.setRequestProperty("Accept", "application/json")
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    try {
        val code = conn.responseCode
        if (code == 401 || code == 403) {
            throw IOException("Access denied (HTTP $code). Check the API token.")
        }
        if (code == 429) throw IOException("Rate limited by the site. Try again in a minute.")
        if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
        return JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
        conn.disconnect()
    }
}

private fun postJson(url: String, body: JSONObject, token: String? = null): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.requestMethod = "POST"
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
    conn.doOutput = true
    conn.setRequestProperty("User-Agent", USER_AGENT)
    conn.setRequestProperty("Accept", "application/json")
    conn.setRequestProperty("Content-Type", "application/json")
    if (token != null) conn.setRequestProperty("Authorization", "Bearer $token")
    try {
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        android.util.Log.i("Nexprint", "POST $url -> HTTP $code")
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        android.util.Log.i("Nexprint", "POST body=" + text.take(300))
        if (code == 401 || code == 403) throw IOException("Access denied (HTTP $code). Sign in again.")
        if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
        return JSONObject(text)
    } finally {
        conn.disconnect()
    }
}

private fun getJsonArray(url: String, headers: Map<String, String> = emptyMap()): org.json.JSONArray {
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 20_000
    conn.setRequestProperty("User-Agent", USER_AGENT)
    conn.setRequestProperty("Accept", "application/json")
    headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
    try {
        val code = conn.responseCode
        if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
        return org.json.JSONArray(conn.inputStream.bufferedReader().use { it.readText() })
    } finally {
        conn.disconnect()
    }
}

/** Picks the best render URL from a Thingiverse image `sizes` array. */
private fun pickImageSize(sizes: org.json.JSONArray?): String? {
    if (sizes == null) return null
    val rank = mapOf("preview" to 3, "file" to 2, "thumb" to 1)
    var best: String? = null
    var bestScore = -1
    for (i in 0 until sizes.length()) {
        val s = sizes.optJSONObject(i) ?: continue
        val url = s.optString("url", "")
        if (!url.startsWith("http")) continue
        val typeScore = rank[s.optString("type", "")] ?: 0
        val sizeScore = when (s.optString("size", "")) {
            "large" -> 2
            "medium" -> 1
            else -> 0
        }
        val score = typeScore * 10 + sizeScore
        if (score > bestScore) {
            bestScore = score
            best = url
        }
    }
    return best
}

/** Printables.com GraphQL API (unofficial, used by the site's own frontend). */
private object PrintablesApi {
    private const val ENDPOINT = "https://api.printables.com/graphql/"

    private val headers = mapOf(
        "Content-Type" to "application/json",
        "Origin" to "https://www.printables.com",
        "Referer" to "https://www.printables.com/",
    )

    private fun post(operation: String, query: String, variables: JSONObject): JSONObject {
        val body = JSONObject()
            .put("operationName", operation)
            .put("query", query)
            .put("variables", variables)
        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Printables returned HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            json.optJSONArray("errors")?.takeIf { it.length() > 0 }?.let {
                throw IOException("Printables: ${it.getJSONObject(0).optString("message", "query failed")}")
            }
            return json.getJSONObject("data")
        } finally {
            conn.disconnect()
        }
    }

    fun search(q: String): List<ModelListing> {
        val query = """
            query SearchModels(${'$'}q: String!) {
              result: searchPrints2(query: ${'$'}q, printType: print, limit: 30, ordering: best_match) {
                items {
                  id name slug downloadCount
                  user { publicUsername }
                  image { filePath }
                  __typename
                }
              }
            }
        """.trimIndent()
        val data = post("SearchModels", query, JSONObject().put("q", q))
        val items = data.getJSONObject("result").getJSONArray("items")
        val out = ArrayList<ModelListing>(items.length())
        for (i in 0 until items.length()) {
            val o = items.getJSONObject(i)
            val image = o.optString("image", "")
                .takeIf { it.isNotBlank() && it != "null" }
                ?.let { JSONObject(it) }
                .let { img ->
                    val path = img?.optString("filePath")?.takeIf { it.isNotBlank() }
                    if (path != null) "https://media.printables.com/$path" else null
                }
            out.add(
                ModelListing(
                    source = ModelSource.PRINTABLES.id,
                    id = o.getString("id"),
                    name = o.optString("name", "Untitled"),
                    author = o.optJSONObject("user")?.optString("publicUsername", "")?.takeIf { it.isNotBlank() },
                    thumbnailUrl = image,
                    downloads = if (o.has("downloadCount")) o.optInt("downloadCount") else null,
                )
            )
        }
        return out
    }

    fun detail(listing: ModelListing): ModelDetail {
        val query = """
            query PrintDetail(${'$'}id: ID!) {
              print(id: ${'$'}id) {
                id name summary likesCount downloadCount datePublished
                user { publicUsername }
                images { filePath rotation }
                stls { id name fileSize }
                slas { id name fileSize }
                __typename
              }
            }
        """.trimIndent()
        val print = post("PrintDetail", query, JSONObject().put("id", listing.id)).getJSONObject("print")
        val images = ArrayList<String>()
        print.optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) {
                val path = arr.getJSONObject(i).optString("filePath", "")
                if (path.isNotBlank()) images.add("https://media.printables.com/" + path.trimStart('/'))
            }
        }
        val files = ArrayList<ModelFile>()
        for ((key, type) in listOf("stls" to "stl", "slas" to "sla")) {
            val arr = print.optJSONArray(key) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "")
                val fileId = o.optString("id", "")
                if (name.isBlank() || fileId.isBlank() || !isModelFile(name)) continue
                // Resolved to a temporary direct URL via getDownloadLink when downloading.
                files.add(
                    ModelFile(
                        name = name,
                        url = "printables://file/${listing.id}/$fileId/$type",
                        sizeBytes = if (o.has("fileSize")) o.optLong("fileSize") else null,
                    )
                )
            }
        }
        return ModelDetail(
            listing = listing.copy(
                author = print.optJSONObject("user")?.optString("publicUsername", "")?.takeIf { it.isNotBlank() }
                    ?: listing.author,
                downloads = if (print.has("downloadCount")) print.optInt("downloadCount") else listing.downloads,
            ),
            description = print.optString("summary", "").trim(),
            images = images,
            likes = if (print.has("likesCount")) print.optInt("likesCount") else null,
            published = print.optString("datePublished", "").take(10).takeIf { it.isNotBlank() },
            files = files,
        )
    }

    /** Resolves the temporary (24 h) direct download link for one model file. */
    fun downloadLink(printId: String, fileId: String, fileType: String): String {
        val mutation = """
            mutation GetDownloadLink(${'$'}id: ID!, ${'$'}modelId: ID!, ${'$'}fileType: DownloadFileTypeEnum!, ${'$'}source: DownloadSourceEnum!) {
              getDownloadLink(id: ${'$'}id, printId: ${'$'}modelId, fileType: ${'$'}fileType, source: ${'$'}source) {
                ok
                errors { field messages }
                output { link }
                __typename
              }
            }
        """.trimIndent()
        val vars = JSONObject()
            .put("id", fileId)
            .put("modelId", printId)
            .put("fileType", fileType)
            .put("source", "model_detail")
        val res = post("GetDownloadLink", mutation, vars).getJSONObject("getDownloadLink")
        val link = res.optJSONObject("output")?.optString("link", "").orEmpty()
        if (!res.optBoolean("ok") || link.isBlank()) {
            val msg = res.optJSONArray("errors")?.optJSONObject(0)?.optJSONArray("messages")?.optString(0)
            throw IOException(msg?.takeIf { it.isNotBlank() } ?: "Printables has no download link for this file.")
        }
        return link
    }
}

/** Thingiverse REST API (official, requires a user-supplied app token). */
private object ThingiverseApi {
    private const val BASE = "https://api.thingiverse.com"

    private fun auth(token: String) = mapOf("Authorization" to "Bearer $token")

    private fun thumbnail(o: JSONObject): String? {
        o.optJSONObject("preview_image")?.optJSONObject("default_file")?.optString("url", "")
            ?.takeIf { it.startsWith("http") }?.let { return it }
        o.optString("preview_image", "")
            .takeIf { it.startsWith("http") }?.let { return it }
        return o.optJSONObject("thumbnail")?.optString("default", "")
            ?.takeIf { it.isNotBlank() && it != "null" && it.startsWith("http") }
    }

    fun search(q: String, token: String): List<ModelListing> {
        // NOTE: /search?q=... is accepted but ignored (always returns the same generic list);
        // the term must go in the path.
        val term = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val url = "$BASE/search/$term?type=things&per_page=30&access_token=" + URLEncoder.encode(token, "UTF-8")
        val json = getJson(url, auth(token))
        val hits = json.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<ModelListing>(hits.length())
        for (i in 0 until hits.length()) {
            val o = hits.getJSONObject(i)
            if (!o.has("id")) continue
            out.add(
                ModelListing(
                    source = ModelSource.THINGIVERSE.id,
                    id = o.getString("id"),
                    name = o.optString("name", "Untitled"),
                    author = o.optJSONObject("creator")?.optString("name", "")?.takeIf { it.isNotBlank() },
                    thumbnailUrl = thumbnail(o),
                    downloads = if (o.has("download_count")) o.optInt("download_count") else null,
                )
            )
        }
        return out
    }

    fun detail(listing: ModelListing, token: String): ModelDetail {
        val tok = URLEncoder.encode(token, "UTF-8")
        val thing = getJson("$BASE/things/${listing.id}?access_token=$tok", auth(token))

        // Files live at /things/{id}/files; /things/{id} has no files array.
        val files = ArrayList<ModelFile>()
        try {
            val arr = getJsonArray(
                "$BASE/things/${listing.id}/files?connection_type=stl,3mf&access_token=$tok",
                auth(token),
            )
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("name", "")
                val downloadUrl = o.optString("download_url", "")
                if (name.isBlank() || downloadUrl.isBlank() || !isModelFile(name)) continue
                files.add(
                    ModelFile(
                        name = if (name.lowercase().endsWith(extOf(name))) name else name + extOf(name),
                        url = downloadUrl + "?access_token=" + tok,
                        sizeBytes = if (o.has("size")) o.optLong("size") else null,
                    )
                )
            }
        } catch (_: Exception) {
        }

        // Gallery images live at /things/{id}/images.
        val images = ArrayList<String>()
        try {
            val arr = getJsonArray("$BASE/things/${listing.id}/images?access_token=$tok", auth(token))
            for (i in 0 until arr.length().coerceAtMost(12)) {
                val best = pickImageSize(arr.getJSONObject(i).optJSONArray("sizes"))
                if (best != null) images.add(best)
            }
        } catch (_: Exception) {
        }
        if (images.isEmpty()) {
            listOfNotNull(thumbnail(thing), listing.thumbnailUrl).distinct().forEach { images.add(it) }
        }
        val description = listOf(
            thing.optString("description", ""),
            thing.optString("details", ""),
        ).filter { it.isNotBlank() }.joinToString("\n\n") { stripHtml(it) }
        return ModelDetail(
            listing = listing.copy(
                author = thing.optJSONObject("creator")?.optString("name", "")?.takeIf { it.isNotBlank() }
                    ?: listing.author,
                downloads = if (thing.has("download_count")) thing.optInt("download_count") else listing.downloads,
            ),
            description = description,
            images = images,
            likes = if (thing.has("like_count")) thing.optInt("like_count") else null,
            published = thing.optString("date", "").take(10).takeIf { it.isNotBlank() },
            files = files,
        )
    }
}

private fun stripHtml(html: String): String = html
    .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
    .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
    .replace(Regex("<[^>]+>"), "")
    .replace("&amp;", "&")
    .replace("&lt;", "<")
    .replace("&gt;", ">")
    .replace("&quot;", "\"")
    .replace("&#0?39;", "'")
    .replace("\\n", "\n")
    .replace(Regex("\n{3,}"), "\n\n")
    .trim()

/** Nexprint (nexprint.com, by Elegoo) gateway API. */
private object NexprintApi {
    private const val BASE = "https://www.nexprint.com/gateway/api/v1/model-library-server"

    /** Nexprint returns HTTP 200 with an envelope: code 0 = ok, 401 = not logged in. */
    private fun getData(url: String, token: String? = null): JSONObject {
        val headers = if (token != null) mapOf("Authorization" to "Bearer $token") else emptyMap()
        android.util.Log.i("Nexprint", "GET $url (token=${if (token != null) "present" else "none"})")
        val json = getJson(url, headers)
        when (val code = json.optInt("code", -1)) {
            0 -> {
                return when (val data = json.opt("data")) {
                    is JSONObject -> data
                    is org.json.JSONArray -> JSONObject().put("list", data)
                    is String -> JSONObject().put("url", data)
                    else -> JSONObject()
                }
            }
            401 -> throw IOException("Sign in with your Elegoo account to download from Nexprint.")
            else -> throw IOException(json.optString("msg", "Nexprint error (code $code)"))
        }
    }

    fun search(q: String): List<ModelListing> {
        val url = "$BASE/model-base-info/search?keyword=" + URLEncoder.encode(q, "UTF-8") +
            "&pageNum=1&pageSize=30"
        val list = getData(url).getJSONObject("pageResult").getJSONArray("list")
        val out = ArrayList<ModelListing>(list.length())
        for (i in 0 until list.length()) {
            val o = list.getJSONObject(i)
            if (!o.has("id")) continue
            out.add(
                ModelListing(
                    source = ModelSource.NEXPRINT.id,
                    id = o.getString("id"),
                    name = o.optString("modelName", "Untitled"),
                    author = o.optString("authorName", "").takeIf { it.isNotBlank() },
                    thumbnailUrl = o.optString("coverImgUrl", "").takeIf { it.isNotBlank() },
                    downloads = o.optJSONObject("statistics")
                        ?.takeIf { it.has("staticsModelDownloadCount") }
                        ?.optInt("staticsModelDownloadCount"),
                )
            )
        }
        return out
    }

    fun detail(listing: ModelListing): ModelDetail {
        val d = getData("$BASE/model-base-info/get?id=${listing.id}")
        val files = ArrayList<ModelFile>()
        val seenFiles = HashSet<String>()
        d.optJSONArray("modelFileList")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val name = o.optString("fileName", "")
                val fileId = o.optLong("fileId").takeIf { it > 0 } ?: continue
                if (name.isBlank() || !isModelFile(name)) continue
                if (!seenFiles.add(fileId.toString())) continue // API lists files more than once
                files.add(
                    ModelFile(
                        name = name,
                        url = "nexprint://file/$fileId/${listing.id}",
                        sizeBytes = if (o.has("fileSize")) o.optLong("fileSize") else null,
                    )
                )
            }
        }
        val images = ArrayList<String>()
        d.optJSONArray("modelPicList")?.let { arr ->
            for (i in 0 until arr.length().coerceAtMost(12)) {
                val url = arr.getJSONObject(i).optString("fileUrl", "")
                if (url.startsWith("http")) images.add(url)
            }
        }
        if (images.isEmpty()) {
            d.optString("coverImgUrl", "").takeIf { it.startsWith("http") }?.let { images.add(it) }
        }
        val stats = d.optJSONObject("statistics")
        val published = d.optLong("publishTime").takeIf { it > 0 }?.let {
            java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
        }
        return ModelDetail(
            listing = listing.copy(
                author = d.optString("authorName", "").takeIf { it.isNotBlank() } ?: listing.author,
                downloads = stats?.takeIf { it.has("staticsModelDownloadCount") }
                    ?.optInt("staticsModelDownloadCount") ?: listing.downloads,
            ),
            description = stripHtml(d.optString("modelDetail", "")),
            images = images,
            likes = stats?.takeIf { it.has("staticsModelFavoriteCount") }?.optInt("staticsModelFavoriteCount"),
            published = published,
            files = files,
        )
    }

    /** Resolves a file id to a temporary direct download URL. Requires a logged-in Elegoo account. */
    fun presignedDownloadUrl(fileId: String, modelId: String, token: String?): String {
        if (token.isNullOrBlank()) {
            throw IOException("Sign in with your Elegoo account to download from Nexprint.")
        }
        val fileInfo = JSONObject()
            .put("fileId", fileId)
            .put("type", "2") // 2 = MODEL_FILE
            .put("bizId", modelId)
        val body = JSONObject().put("fileInfoList", org.json.JSONArray().put(fileInfo))
        val json = postJson("$BASE/common/presigned-download-url", body, token)
        when (val code = json.optInt("code", -1)) {
            0 -> {}
            401 -> throw IOException("Sign in with your Elegoo account to download from Nexprint.")
            else -> throw IOException(json.optString("msg", "Nexprint error (code $code)"))
        }
        val files = json.optJSONObject("data")?.optJSONArray("fileInfoList")
        for (i in 0 until (files?.length() ?: 0)) {
            files?.optJSONObject(i)?.optString("resultUrl", "")
                ?.takeIf { it.startsWith("http") }?.let { return it }
        }
        android.util.Log.w("Nexprint", "presigned no url: " + json.toString().take(300))
        throw IOException("Nexprint returned no download link for this file.")
    }
}
