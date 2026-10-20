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

/** Per-source search results shown as one section in the combined results list. */
data class SourceSection(
    val source: ModelSource,
    val loading: Boolean = false,
    val results: List<ModelListing> = emptyList(),
    /** Last fetched page (page 1 is loaded by search/loadHome). */
    val page: Int = 1,
    /** More pages may exist: the list grid auto-fetches them while scrolling. */
    val hasMore: Boolean = false,
    val loadingMore: Boolean = false,
)

data class BrowseState(
    val query: String = "",
    /** Sections always in the fixed display order of [ModelSource]. */
    val sections: List<SourceSection> = emptyList(),
    /** True while the sections show each site's popular models (empty search bar). */
    val isHomeFeed: Boolean = false,
    val hasSearched: Boolean = false,
    val error: String? = null,
    val detail: ModelDetail? = null,
    val loadingDetail: Boolean = false,
    val downloadingFile: String? = null,
    /** File name whose star ("keep in My models") action is downloading. */
    val favoritingFile: String? = null,
    val downloadProgress: Int = 0,
    val bambuSignedIn: Boolean = false,
    val bambuSigningIn: Boolean = false,
    val bambuNeedsCode: Boolean = false,
    val bambuPendingEmail: String = "",
)

/** Declaration order is the fixed section display order. */
enum class ModelSource(val id: String, val label: String) {
    MAKERWORLD("makerworld", "MakerWorld"),
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
    val favorites: FavoritesStore,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val _state = MutableStateFlow(BrowseState())
    val state: StateFlow<BrowseState> = _state.asStateFlow()

    // Results cache keyed by "source|query", so re-visiting a tab is instant.
    private val resultsCache = HashMap<String, List<ModelListing>>()
    private var searchGeneration = 0
    private var homeLoaded = false

    /** Cache key for each source's popular-models (home feed) list. */
    private fun homeKey(source: ModelSource) = "${source.id}|@home"

    val bambuToken: String
        get() = prefs.getString("bambu_token", "").orEmpty()

    init {
        if (bambuToken.isNotBlank()) {
            _state.value = _state.value.copy(bambuSignedIn = true)
        }
        // Debug aid: `touch cache/dump-html` to capture scraped MakerWorld pages.
        MakerworldApi.dumpDir = File(cacheDir, "dump").takeIf { File(cacheDir, "dump-html").exists() }
    }

    fun bambuLogin(email: String, password: String, onDone: () -> Unit) {
        if (_state.value.bambuSigningIn) return
        _state.value = _state.value.copy(bambuSigningIn = true, error = null)
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { MakerworldApi.bambuLogin(email.trim(), password) }
                prefs.edit().putString("bambu_token", token).apply()
                _state.value = _state.value.copy(bambuSigningIn = false, bambuSignedIn = true)
                onDone()
        } catch (e: MakerworldApi.BambuVerificationRequired) {
            _state.value = _state.value.copy(
                bambuSigningIn = false,
                bambuNeedsCode = true,
                bambuPendingEmail = email.trim(),
                error = "A sign-in code was emailed to ${email.trim()}. Enter it below.",
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(bambuSigningIn = false, error = readable(e))
        }
        }
    }

    fun bambuVerify(code: String, onDone: () -> Unit) {
        if (_state.value.bambuSigningIn) return
        val email = _state.value.bambuPendingEmail
        _state.value = _state.value.copy(bambuSigningIn = true, error = null)
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { MakerworldApi.bambuLoginWithCode(email, code.trim()) }
                prefs.edit().putString("bambu_token", token).apply()
                _state.value = _state.value.copy(
                    bambuSigningIn = false, bambuSignedIn = true,
                    bambuNeedsCode = false, bambuPendingEmail = "",
                )
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(bambuSigningIn = false, error = readable(e))
            }
        }
    }

    val thingiverseToken: String
        get() = prefs.getString("thingiverse_token", null)?.takeIf { it.isNotBlank() }
            ?: DEFAULT_THINGIVERSE_TOKEN

    fun setThingiverseToken(token: String) {
        prefs.edit().putString("thingiverse_token", token.trim()).apply()
        _state.value = _state.value.copy(error = null)
    }

    fun setQuery(q: String) {
        _state.value = _state.value.copy(query = q)
        // Clearing the search bar goes back to the popular-models home feed.
        if (q.isBlank() && _state.value.hasSearched) loadHome()
    }

    private fun searchSource(source: ModelSource, q: String, page: Int = 1): List<ModelListing> =
        when (source) {
            ModelSource.PRINTABLES -> PrintablesApi.search(q, page)
            ModelSource.THINGIVERSE ->
                if (thingiverseToken.isBlank()) emptyList()
                else ThingiverseApi.search(q, thingiverseToken, page)
            ModelSource.NEXPRINT -> NexprintApi.search(q, page)
            ModelSource.MAKERWORLD -> MakerworldApi.search(q, page, bambuToken.takeIf { it.isNotBlank() })
        }

    private fun popularSource(source: ModelSource, page: Int = 1): List<ModelListing> =
        when (source) {
            ModelSource.PRINTABLES -> PrintablesApi.popular(page)
            ModelSource.THINGIVERSE ->
                if (thingiverseToken.isBlank()) emptyList()
                else ThingiverseApi.popular(thingiverseToken, page)
            ModelSource.NEXPRINT -> NexprintApi.popular(page)
            ModelSource.MAKERWORLD -> MakerworldApi.popular(page, bambuToken.takeIf { it.isNotBlank() })
        }

    /**
     * Shows each source's popular models (the home feed shown with an empty search bar).
     * Sources that fail or return nothing are hidden, same as empty searches.
     */
    fun loadHome() {
        val st = _state.value
        if (st.query.isNotBlank()) return
        val cached = ModelSource.entries.mapNotNull { src ->
            resultsCache[homeKey(src)]?.let {
                SourceSection(src, results = it, hasMore = homeHasMore(src, it))
            }
        }
        if (cached.isNotEmpty()) {
            _state.value = st.copy(sections = cached, isHomeFeed = true, hasSearched = false, error = null)
            return
        }
        if (homeLoaded) return
        homeLoaded = true
        val generation = ++searchGeneration
        _state.value = st.copy(sections = emptyList(), isHomeFeed = true, hasSearched = false, error = null)
        for (src in ModelSource.entries) {
            if (src == ModelSource.THINGIVERSE && thingiverseToken.isBlank()) continue
            updateSection(src) { SourceSection(src, loading = true) }
            scope.launch {
                val outcome: Result<List<ModelListing>> = try {
                    Result.success(withContext(Dispatchers.IO) { popularSource(src) })
                } catch (e: Exception) {
                    Result.failure(e)
                }
                if (generation != searchGeneration) return@launch
                outcome.onSuccess { results ->
                    if (results.isEmpty()) {
                        updateSection(src) { null }
                    } else {
                        resultsCache[homeKey(src)] = results
                        updateSection(src) {
                            SourceSection(src, results = results, hasMore = homeHasMore(src, results))
                        }
                    }
                }.onFailure {
                    android.util.Log.w("ModelBrowser", "${src.id} popular failed", it)
                    updateSection(src) { null }
                }
            }
        }
    }

    /** Searches every source in parallel; each completes into its own section. */
    fun search() {
        val st = _state.value
        val q = st.query.trim()
        if (q.isEmpty()) return
        val generation = ++searchGeneration
        EventNotifier.trackEvent("model_search", mapOf("sources" to "all", "query" to q))
        // Cached results show instantly; the rest stream in as they arrive.
        val cached = ModelSource.entries.mapNotNull { src ->
            resultsCache["${src.id}|$q"]?.let {
                SourceSection(src, results = it, hasMore = it.isNotEmpty())
            }
        }
        _state.value = st.copy(sections = cached, hasSearched = true, isHomeFeed = false, error = null)
        for (src in ModelSource.entries) {
            if (src == ModelSource.THINGIVERSE && thingiverseToken.isBlank()) continue
            if (cached.any { it.source == src }) continue
            updateSection(src) { SourceSection(src, loading = true) }
            scope.launch {
                val outcome: Result<List<ModelListing>> = try {
                    Result.success(withContext(Dispatchers.IO) { searchSource(src, q) })
                } catch (e: Exception) {
                    Result.failure(e)
                }
                if (generation != searchGeneration) return@launch
                outcome.onSuccess { results ->
                    if (results.isEmpty()) {
                        updateSection(src) { null } // empty → section disappears
                    } else {
                        resultsCache["${src.id}|$q"] = results
                        updateSection(src) {
                            SourceSection(src, results = results, hasMore = results.isNotEmpty())
                        }
                    }
                }.onFailure {
                    android.util.Log.w("ModelBrowser", "${src.id} search failed", it)
                    updateSection(src) { null } // failed → section is hidden
                }
            }
        }
    }

    /** Feeds with more pages: everything that returned a full first page. */
    private fun homeHasMore(source: ModelSource, results: List<ModelListing>): Boolean =
        results.isNotEmpty()

    /** Fetches the next results page for a section and appends it (scroll pagination). */
    fun loadMore(source: ModelSource) {
        val st = _state.value
        val section = st.sections.firstOrNull { it.source == source } ?: return
        if (section.loading || section.loadingMore || !section.hasMore) return
        val generation = searchGeneration
        val page = section.page + 1
        val q = st.query.trim()
        val home = st.isHomeFeed
        updateSection(source) { it?.copy(loadingMore = true) }
        scope.launch {
            val outcome: Result<List<ModelListing>> = try {
                Result.success(
                    withContext(Dispatchers.IO) {
                        if (home) popularSource(source, page) else searchSource(source, q, page)
                    },
                )
            } catch (e: Exception) {
                Result.failure(e)
            }
            if (generation != searchGeneration) return@launch
            updateSection(source) { cur ->
                cur ?: return@updateSection null
                val more = outcome.getOrNull()
                val merged = if (more == null) cur.results
                    else cur.results + more.filter { n -> cur.results.none { it.id == n.id } }
                when {
                    more.isNullOrEmpty() || merged.size == cur.results.size ->
                        // Empty, failed, or a repeat of page 1 (sources whose API
                        // ignores paging): stop, don't spin forever.
                        cur.copy(loadingMore = false, hasMore = false)
                    else ->
                        cur.copy(results = merged, page = page, loadingMore = false)
                }
            }
        }
    }

    /** Replaces, inserts (in fixed [ModelSource] order) or removes (transform → null) a section. */
    private fun updateSection(source: ModelSource, transform: (SourceSection?) -> SourceSection?) {
        val current = _state.value.sections.toMutableList()
        val idx = current.indexOfFirst { it.source == source }
        val updated = transform(current.getOrNull(idx))
        if (idx >= 0) current.removeAt(idx)
        if (updated != null) current.add(updated)
        current.sortBy { it.source.ordinal }
        _state.value = _state.value.copy(sections = current)
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
                        ModelSource.MAKERWORLD.id -> MakerworldApi.detail(listing)
                        else -> ThingiverseApi.detail(listing, thingiverseToken)
                    }
                }
                _state.value = _state.value.copy(detail = detail, loadingDetail = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingDetail = false, error = readable(e))
            }
        }
    }

    /** Keyed on the file name so the star is shared everywhere the file shows
     * (browse detail, home preview of the loaded model). */
    fun favoriteKey(detail: ModelDetail, file: ModelFile): String = "file|${file.name}"

    /**
     * "Keep in My models": download the file and copy it into private storage
     * so it survives cache eviction, with a thumbnail for the library screen.
     */
    fun favoriteFile(detail: ModelDetail, file: ModelFile, onDone: (Boolean) -> Unit) {
        val key = favoriteKey(detail, file)
        if (favorites.isFavorited(key)) {
            onDone(true)
            return
        }
        if (_state.value.favoritingFile != null) return
        _state.value = _state.value.copy(favoritingFile = file.name, downloadProgress = 0, error = null)
        scope.launch {
            try {
                val path = withContext(Dispatchers.IO) { downloadToCache(file) }
                val entry = favorites.add(File(path), file.name, detail.listing.source, key, detail.listing.id)
                favorites.setThumb(entry, detail.listing.thumbnailUrl ?: detail.images.firstOrNull())
                android.util.Log.i("ModelBrowser", "favorited ${file.name} (${entry.path})")
                _state.value = _state.value.copy(favoritingFile = null)
                onDone(true)
            } catch (e: Exception) {
                android.util.Log.w("ModelBrowser", "favorite failed", e)
                _state.value = _state.value.copy(favoritingFile = null, error = readable(e))
                onDone(false)
            }
        }
    }

    fun unfavoriteFile(key: String) {
        favorites.entries.value.firstOrNull { it.key == key }?.let { favorites.remove(it.id) }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null)
    }

    fun download(file: ModelFile, onDone: (String) -> Unit) {
        android.util.Log.i("ModelBrowser", "download tapped: ${file.name} busy=${_state.value.downloadingFile}")
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
                val msg = readable(e)
                android.util.Log.w("ModelBrowser", "download failed: $msg", e)
                _state.value = _state.value.copy(
                    downloadingFile = null,
                    downloadProgress = 0,
                    error = msg,
                    // Token expired: show the MakerWorld sign-in card again.
                    bambuSignedIn = _state.value.bambuSignedIn &&
                        !(file.url.startsWith("makerworld://") && msg.contains("Sign in")),
                )
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
        if (file.url.startsWith("makerworld://")) {
            return MakerworldApi.downloadUrl(file.url.removePrefix("makerworld://profile/"), bambuToken)
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

    fun search(q: String, page: Int = 1): List<ModelListing> = querySearch(q, "best_match", page)

    /** Popular prints (empty query + popularity ordering), used for the home feed. */
    fun popular(page: Int = 1): List<ModelListing> = querySearch("", "popular", page)

    private fun querySearch(q: String, ordering: String, page: Int = 1): List<ModelListing> {
        val query = """
            query SearchModels(${'$'}q: String!, ${'$'}off: Int!) {
              result: searchPrints2(query: ${'$'}q, printType: print, limit: 30, ordering: $ordering, offset: ${'$'}off) {
                items {
                  id name slug downloadCount
                  user { publicUsername }
                  image { filePath }
                  __typename
                }
              }
            }
        """.trimIndent()
        val data = post("SearchModels", query, JSONObject().put("q", q).put("off", (page - 1) * 30))
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

    private fun listing(o: JSONObject): ModelListing? {
        if (!o.has("id")) return null
        return ModelListing(
            source = ModelSource.THINGIVERSE.id,
            id = o.getString("id"),
            name = o.optString("name", "Untitled"),
            author = o.optJSONObject("creator")?.optString("name", "")?.takeIf { it.isNotBlank() },
            thumbnailUrl = thumbnail(o),
            downloads = if (o.has("download_count")) o.optInt("download_count") else null,
        )
    }

    fun search(q: String, token: String, page: Int = 1): List<ModelListing> {
        // NOTE: /search?q=... is accepted but ignored (always returns the same generic list);
        // the term must go in the path.
        val term = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val url = "$BASE/search/$term?type=things&per_page=30&page=$page&access_token=" +
            URLEncoder.encode(token, "UTF-8")
        val json = getJson(url, auth(token))
        val hits = json.optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<ModelListing>(hits.length())
        for (i in 0 until hits.length()) {
            listing(hits.getJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    /** Popular things (the site's home feed). */
    fun popular(token: String, page: Int = 1): List<ModelListing> {
        val url = "$BASE/popular?per_page=30&page=$page&access_token=" +
            URLEncoder.encode(token, "UTF-8")
        val arr = getJsonArray(url, auth(token))
        val out = ArrayList<ModelListing>(arr.length())
        for (i in 0 until arr.length()) {
            listing(arr.getJSONObject(i))?.let { out.add(it) }
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
    .replace("&#0?34;", "\"")
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

    fun search(q: String, page: Int = 1): List<ModelListing> {
        val url = "$BASE/model-base-info/search?keyword=" + URLEncoder.encode(q, "UTF-8") +
            "&pageNo=$page&pageSize=30"
        return parseList(getData(url))
    }

    /** Empty keyword returns the site's front-page (popular) model list. */
    fun popular(page: Int = 1): List<ModelListing> {
        val url = "$BASE/model-base-info/search?keyword=&pageNo=$page&pageSize=30"
        return parseList(getData(url))
    }

    private fun parseList(data: JSONObject): List<ModelListing> {
        val list = data.getJSONObject("pageResult").getJSONArray("list")
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

/** MakerWorld (makerworld.com, by Bambu Lab) GraphQL API. Mirrors the web frontend:
 *  CSRF cookie handshake, then POSTs to /en/api/graphql. Cloudflare blocks datacenter
 *  IPs; residential/mobile networks pass without a JS challenge. */private object MakerworldApi {
    private const val SITE = "https://makerworld.com"
    private const val GATEWAY = "https://api.bambulab.com/v1/design-service"
    private const val SEARCH_SERVICE = "https://api.bambulab.com/v1/search-service"

    @Volatile private var cookieHeader: String? = null

    /** When set (debug), scrape() writes the last fetched page into this directory. */
    @Volatile var dumpDir: File? = null

    /** Visits makerworld.com to pass the Cloudflare check and collect session cookies. */
    private fun refreshSession() {
        val conn = URL("$SITE/en").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "text/html")
        try {
            val code = conn.responseCode
            val body = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
            if (body.contains("Just a moment")) {
                throw IOException("MakerWorld is blocking this network (Cloudflare check). Try mobile data or Wi-Fi and retry.")
            }
            val keep = LinkedHashMap<String, String>()
            conn.headerFields["Set-Cookie"]?.forEach { sc ->
                val pair = sc.substringBefore(';')
                val name = pair.substringBefore('=').trim()
                if (name.isNotBlank() && !keep.containsKey(name)) keep[name] = pair
            }
            cookieHeader = keep.values.joinToString("; ").takeIf { it.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Searches by scraping the SSR search page: MakerWorld's data APIs sit behind
     * Cloudflare/signed-request gates, but the Next.js HTML response embeds the
     * full result list in its flight payload.
     */
    /**
     * Keyword search: the Bambu gateway search endpoint (paginates by offset)
     * when signed in to Bambu; the SSR scrape otherwise, which only has page 1.
     */
    fun search(q: String, page: Int = 1, token: String? = null): List<ModelListing> {
        if (!token.isNullOrBlank()) {
            runCatching { return searchApi(q, page, token) }
                .onFailure { android.util.Log.w("Makerworld", "search API failed, falling back to scrape", it) }
        }
        return if (page > 1) emptyList() else
            scrape("$SITE/en/search/models?keyword=" + URLEncoder.encode(q, "UTF-8") + "&page=1", SEARCH_ENTRY)
    }

    private fun searchApi(q: String, page: Int, token: String): List<ModelListing> {
        val url = "$GATEWAY/design/search?keyword=" + URLEncoder.encode(q, "UTF-8") +
            "&sortValue=relevant&sortState=0&type=0&offset=${'$'}{(page - 1) * 30}&limit=30"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Authorization", "Bearer $token")
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("Bambu search returned HTTP $code")
            return parseDesignHits(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Popular feed: the gateway's Trending nav (offset-paginated) when signed
     * in to Bambu; the scraped home-page "For You" feed otherwise, page 1 only.
     */
    fun popular(page: Int = 1, token: String? = null): List<ModelListing> {
        if (!token.isNullOrBlank()) {
            runCatching { return navFeed(page) }
                .onFailure { android.util.Log.w("Makerworld", "nav feed failed, falling back to scrape", it) }
        }
        return if (page > 1) emptyList() else scrape("$SITE/en/", FORYOU_ENTRY)
    }

    private fun navFeed(page: Int): List<ModelListing> {
        val url = "$SEARCH_SERVICE/select/design/nav?navKey=Trending&offset=${'$'}{(page - 1) * 30}&limit=30"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("MakerWorld feed returned HTTP $code")
            return parseDesignHits(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    /** Shared `{total, hits[]}` design-list shape of the Bambu gateway feeds. */
    private fun parseDesignHits(text: String): List<ModelListing> {
        val hits = JSONObject(text).optJSONArray("hits") ?: return emptyList()
        val out = ArrayList<ModelListing>(hits.length())
        for (i in 0 until hits.length()) {
            val o = hits.getJSONObject(i)
            val id = o.opt("id")?.toString()?.takeIf { it.isNotBlank() && it != "0" } ?: continue
            out.add(
                ModelListing(
                    source = ModelSource.MAKERWORLD.id,
                    id = id,
                    name = o.optString("title", "Untitled").ifBlank { "Untitled" },
                    author = o.optJSONObject("designCreator")?.optString("name", "")?.takeIf { it.isNotBlank() },
                    thumbnailUrl = o.optString("cover", "").takeIf { it.startsWith("http") },
                    downloads = o.optInt("downloadCount").takeIf { it > 0 },
                ),
            )
        }
        return out
    }

    /** Design entry as embedded in the search results flight payload. */
    private val SEARCH_ENTRY =
        Regex(""""id":(\d+),"title":"([^"]*)","slug":"[^"]*","titleTranslated":"[^"]*","cover":"([^"]*)"""")

    /** Design entry nested under "design" in the home page's foryouData payload. */
    private val FORYOU_ENTRY =
        Regex(""""design":\{"id":(\d+),"designType":\d+,"title":"([^"]*)","slug":"[^"]*","titleTranslated":"[^"]*","cover":"([^"]*)"""")

    private fun scrape(url: String, entry: Regex): List<ModelListing> {
        if (cookieHeader == null) refreshSession()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "text/html")
        cookieHeader?.let { conn.setRequestProperty("Cookie", it) }
        val html = try {
            val code = conn.responseCode
            if (code == 403) throw IOException("MakerWorld is blocking this network (Cloudflare check).")
            if (code !in 200..299) throw IOException("MakerWorld returned HTTP $code")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        if (html.contains("Just a moment")) {
            throw IOException("MakerWorld is blocking this network (Cloudflare check).")
        }
        dumpDir?.let { dir ->
            runCatching {
                dir.mkdirs()
                val name = "mw_" + url.substringAfter("/en/").substringBefore("?").replace('/', '_') + ".html"
                java.io.File(dir, name).writeText(html)
            }
        }
        val out = ArrayList<ModelListing>()
        android.util.Log.i("MakerWorld", "scrape len=${html.length} marker=${html.indexOf("\"title\":\"")} matches=${entry.findAll(html).count()}")
        for (m in entry.findAll(html)) {
            if (out.size >= 30) break
            val window = html.substring(m.range.last, minOf(m.range.last + 3000, html.length))
            val downloads = Regex(""""downloadCount":(\d+)""").find(window)?.groupValues?.get(1)?.toIntOrNull()
            val author = Regex(""""designCreator":\{"uid":\d+,"name":"([^"]*)"""")
                .find(window)?.groupValues?.get(1)?.let { jsonUnescape(it) }
            val cover = jsonUnescape(m.groupValues[3])
            out.add(
                ModelListing(
                    source = ModelSource.MAKERWORLD.id,
                    id = m.groupValues[1],
                    name = jsonUnescape(m.groupValues[2]),
                    author = author,
                    thumbnailUrl = cover.takeIf { it.startsWith("http") },
                    downloads = downloads,
                )
            )
        }
        return out
    }

    /** Model detail from the public Bambu cloud design-service API. */
    fun detail(listing: ModelListing): ModelDetail {
        val conn = URL("$GATEWAY/design/${listing.id}").openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        val d = try {
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("MakerWorld detail returned HTTP $code")
            JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
        val modelId = d.optString("modelId", "")
        val files = ArrayList<ModelFile>()
        d.optJSONArray("instances")?.let { arr ->
            for (i in 0 until arr.length()) {
                val inst = arr.getJSONObject(i)
                val profileId = inst.optLong("profileId").takeIf { it > 0 } ?: continue
                val plateTitle = inst.optString("title", "plate ${i + 1}")
                val size = inst.optJSONObject("extention")?.optJSONObject("modelInfo")
                    ?.optJSONObject("f3mf")?.optLong("size")?.takeIf { it > 0 }
                files.add(
                    ModelFile(
                        name = d.optString("title", "model") + " - " + plateTitle + ".3mf",
                        url = "makerworld://profile/$profileId/$modelId",
                        sizeBytes = size,
                    )
                )
            }
        }
        val images = ArrayList<String>()
        d.optJSONObject("designExtension")?.optJSONArray("design_pictures")?.let { arr ->
            for (i in 0 until arr.length().coerceAtMost(12)) {
                val url = arr.getJSONObject(i).optString("url", "")
                if (url.startsWith("http")) images.add(url)
            }
        }
        if (images.isEmpty()) d.optString("coverUrl", "").takeIf { it.startsWith("http") }?.let { images.add(it) }
        return ModelDetail(
            listing = listing.copy(
                author = d.optJSONObject("designCreator")?.optString("name", "")?.takeIf { it.isNotBlank() }
                    ?: listing.author,
                downloads = d.optInt("downloadCount", listing.downloads ?: 0),
            ),
            description = stripHtml(d.optString("summary", "")),
            images = images,
            likes = d.optInt("collectionCount").takeIf { it > 0 },
            published = d.optString("createTime", "").take(10).takeIf { it.isNotBlank() },
            files = files,
        )
    }

    /**
     * Signs in to the Bambu cloud gateway (AES-encrypted password, like Bambu Studio).
     * Returns the access token (valid ~90 days).
     */
    class BambuVerificationRequired(message: String) : IOException(message)

    fun bambuLogin(email: String, password: String): String {
        // The gateway accepts several password encodings depending on region/version;
        // try the documented ones in order and report the last error if all fail.
        var lastError = IOException("Bambu sign-in failed.")
        val attempts = listOf(
            JSONObject().put("account", email).put("password", password),
            JSONObject().put("account", email).put("password", bambuEncrypt(password)).put("encrypted", true),
            JSONObject()
                .put("account", email)
                .put("password", bambuEncrypt(password, "d303da87bed66592"))
                .put("encrypted", true),
        )
        for (body in attempts) {
            try {
                return bambuLoginOnce(body)
            } catch (e: BambuVerificationRequired) {
                throw e
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError
    }

    fun bambuLoginWithCode(email: String, code: String): String =
        bambuLoginOnce(JSONObject().put("account", email).put("code", code))

    private fun bambuLoginOnce(body: JSONObject): String {
        val conn = URL("https://api.bambulab.com/v1/user-service/user/login").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("x-bbl-language", "en-US")
        conn.setRequestProperty("x-bbl-region-global", "true")
        val text = try {
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } finally {
            conn.disconnect()
        }
        android.util.Log.i("MakerWorld", "bambu login (encrypted=${body.opt("encrypted")}) -> ${text.take(200)}")
        val json = try {
            JSONObject(text)
        } catch (e: org.json.JSONException) {
            throw IOException("Bambu sign-in returned an unexpected response.")
        }
        json.optString("accessToken", "").takeIf { it.isNotBlank() }?.let { return it }
        val loginType = json.optString("loginType", "")
        if (loginType == "verifyCode") {
            throw BambuVerificationRequired("A sign-in code was emailed to your address.")
        }
        throw IOException(json.optString("error", "Bambu sign-in failed. Check the email and password.").ifBlank { "Bambu sign-in failed." })
    }

    /** AES-128-CBC with the Bambu gateway's fixed key/IV (as used by Bambu Studio). */
    private fun bambuEncrypt(password: String, keyStr: String = "b47f7652e530d66a"): String {
        val key = javax.crypto.spec.SecretKeySpec(keyStr.toByteArray(Charsets.UTF_8), "AES")
        val iv = javax.crypto.spec.IvParameterSpec("55282a2660e28773".toByteArray(Charsets.UTF_8))
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/PKCS7Padding")
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, key, iv)
        return android.util.Base64.encodeToString(
            cipher.doFinal(password.toByteArray(Charsets.UTF_8)),
            android.util.Base64.NO_WRAP,
        )
    }

    /** Resolves a plate (profile id) to a short-lived presigned 3MF URL. */
    fun downloadUrl(ref: String, token: String?): String {
        android.util.Log.i("MakerWorld", "presign ref=$ref token=${if (token.isNullOrBlank()) "none" else "len" + token.length}")
        val parts = ref.split("/")
        if (parts.size < 2) throw IOException("Bad MakerWorld file reference.")
        if (token.isNullOrBlank()) {
            throw IOException("Sign in with your Bambu Lab account below to download from MakerWorld.")
        }
        val url = "https://api.bambulab.com/v1/iot-service/api/user/profile/${parts[0]}" +
            "?model_id=" + URLEncoder.encode(parts[1], "UTF-8")
        android.util.Log.i("MakerWorld", "presign GET $url")
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("x-bbl-language", "en-US")
        conn.setRequestProperty("x-bbl-region-global", "true")
        val text = try {
            val code = conn.responseCode
            when (code) {
                401, 403 -> throw IOException("Sign in with your Bambu Lab account below to download from MakerWorld.")
                in 200..299 -> conn.inputStream.bufferedReader().use { it.readText() }
                else -> throw IOException("MakerWorld download returned HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
        val json = JSONObject(text)
        return json.optString("url", "").takeIf { it.startsWith("http") }
            ?: throw IOException("MakerWorld returned no download link for this file.")
    }


    /** Decodes a JSON string literal body (handles \" \\ \n \uXXXX). */
    private fun jsonUnescape(s: String): String = try {
        org.json.JSONTokener("\"$s\"").nextValue() as String
    } catch (e: Exception) {
        s
    }
}
