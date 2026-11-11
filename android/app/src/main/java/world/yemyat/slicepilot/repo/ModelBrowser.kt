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

/** One community comment on a model, as reported by the source site. */
data class ModelComment(
    val id: String,
    val author: String,
    val avatarUrl: String?,
    /** Source timestamp, e.g. "2023-10-18T12:59:39.766071+00:00". */
    val createdAt: String?,
    val text: String,
    /** Comments answering this one; empty on a reply itself. */
    val replies: List<ModelComment> = emptyList(),
    /** Photos the reviewer attached. Matrix renders them as a thumb strip with a "+N" tile,
     * and some Nexprint reviews are photos only. */
    val images: List<String> = emptyList(),
    /** Upvotes on this comment — Matrix prints "👍 0" under the body. */
    val likeCount: Int? = null,
    /** Answers it has — Matrix prints "💬 1". */
    val replyCount: Int? = null,
)

/**
 * One comments request's worth of rows, plus the comment count the source itself stated
 * when its response carries one. A live count from the comment list beats a counter cached
 * on the model: those keep counting deleted comments (Nexprint's `staticsModelCommentCount`
 * reported 6 on a model whose comment service returns 4).
 */
data class CommentPage(
    val rows: List<Pair<ModelComment, String?>> = emptyList(),
    /** What the source says it has, when its response says. */
    val total: Int? = null,
    /** Where the source's own numbering continues after this page, when rows carry more
     * comments than the source counts per row (Printables nests every reply inside its
     * root comment's row). Paged fetches use it instead of the cached row count. */
    val nextOffset: Int? = null,
)

/** Full detail of a model, shown when a search result is tapped. */
data class ModelDetail(
    val listing: ModelListing,
    val description: String,
    val images: List<String>,
    val likes: Int?,
    val published: String?,
    val files: List<ModelFile>,
    /**
     * Comments already fetched for this model. Sources that cannot report them yet
     * leave both defaults, so the UI can tell "none" (emptyList) from "unavailable" (null).
     */
    val comments: List<ModelComment> = emptyList(),
    /** Total on the site, which can be far larger than [comments]. */
    val commentsTotal: Int? = null,
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
    /** True while the first page of comments is being fetched for the open model. */
    val commentsLoading: Boolean = false,
    /** True while the next page of comments is being fetched. */
    val commentsLoadingMore: Boolean = false,
    /** The open model's comment source served its last page and has nothing more to
     * give, even though `commentsTotal` counts higher (deleted votes stay in site
     * counts, MakerWorld's pool ends below its header number). "Load more" is gone then. */
    val commentsExhausted: Boolean = false,
    /** Set when a comments page fails to load, so the section can offer a retry. */
    val commentsMoreError: String? = null,
    val bambuSignedIn: Boolean = false,
    val bambuSigningIn: Boolean = false,
    val bambuNeedsCode: Boolean = false,
    val bambuPendingEmail: String = "",
)

/** Declaration order is the fixed section display order. */
enum class ModelSource(
    val id: String,
    val label: String,
) {
    MAKERWORLD("makerworld", "MakerWorld"),
    PRINTABLES("printables", "Printables"),
    THINGIVERSE("thingiverse", "Thingiverse"),
    NEXPRINT("nexprint", "Nexprint"),
    ;

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

    // Comment rows fetched per "source|id" (each with its parent id), so the next page
    // can be appended and the threads rebuilt. See loadComments / loadMoreComments.
    private val commentsCache = HashMap<String, MutableList<Pair<ModelComment, String?>>>()

    // The source's next-page offset per "source|id", where its rows hold more comments
    // than the source's own paging counts (see CommentPage.nextOffset); cached row size
    // would overshoot there.
    private val commentsOffsets = HashMap<String, Int>()

    // "source|id" whose thread is finished: every page the source can serve has arrived,
    // even where that is fewer rows than `commentsTotal` — sites keep deleted comments in
    // their counts, and MakerWorld's comment pool ends below its header number. A fetch
    // that adds nothing marks the thread complete, so "Load more" disappears instead of
    // offering pages that add nothing.
    private val commentThreadsComplete = HashSet<String>()

    // "source|id" whose first comments page is in flight, so a tab switch can't
    // start the same request twice.
    private var commentsFetching: String? = null

    // Comment rows asked for per Printables request — Load-more advances its thread list
    // 10 roots at a time. The other sources fetch at their own size (see commentFetcher).
    private val commentsPageSize = 10
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

    fun bambuLogin(
        email: String,
        password: String,
        onDone: () -> Unit,
    ) {
        if (_state.value.bambuSigningIn) return
        _state.value = _state.value.copy(bambuSigningIn = true, error = null)
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { MakerworldApi.bambuLogin(email.trim(), password) }
                prefs.edit().putString("bambu_token", token).apply()
                _state.value = _state.value.copy(bambuSigningIn = false, bambuSignedIn = true)
                onDone()
            } catch (e: MakerworldApi.BambuVerificationRequired) {
                // Not a failure, so `error` stays empty. This message used to travel through the
                // shared error channel, which drew it in red — under the dialog's fields and at
                // the top of the browse feed — for the normal case of a password that worked.
                // The dialog now says it in its own words from bambuPendingEmail.
                _state.value =
                    _state.value.copy(
                        bambuSigningIn = false,
                        bambuNeedsCode = true,
                        bambuPendingEmail = email.trim(),
                        error = null,
                    )
            } catch (e: Exception) {
                _state.value = _state.value.copy(bambuSigningIn = false, error = readable(e))
            }
        }
    }

    /**
     * Drops a pending emailed code without using it — the only way back to the sign-in dialog's
     * email + password step. A code belongs to the account it was mailed for, so signing in as
     * someone else means throwing the pending one away rather than waiting on it.
     */
    fun bambuCancelVerification() {
        if (!_state.value.bambuNeedsCode) return
        _state.value =
            _state.value.copy(
                bambuNeedsCode = false,
                bambuPendingEmail = "",
                error = null,
            )
    }

    fun bambuVerify(
        code: String,
        onDone: () -> Unit,
    ) {
        if (_state.value.bambuSigningIn) return
        val email = _state.value.bambuPendingEmail
        _state.value = _state.value.copy(bambuSigningIn = true, error = null)
        scope.launch {
            try {
                val token = withContext(Dispatchers.IO) { MakerworldApi.bambuLoginWithCode(email, code.trim()) }
                prefs.edit().putString("bambu_token", token).apply()
                _state.value =
                    _state.value.copy(
                        bambuSigningIn = false,
                        bambuSignedIn = true,
                        bambuNeedsCode = false,
                        bambuPendingEmail = "",
                    )
                onDone()
            } catch (e: Exception) {
                _state.value = _state.value.copy(bambuSigningIn = false, error = readable(e))
            }
        }
    }

    val thingiverseToken: String
        get() =
            prefs.getString("thingiverse_token", null)?.takeIf { it.isNotBlank() }
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

    private fun searchSource(
        source: ModelSource,
        q: String,
        page: Int = 1,
    ): List<ModelListing> =
        when (source) {
            ModelSource.PRINTABLES -> {
                PrintablesApi.search(q, page)
            }

            ModelSource.THINGIVERSE -> {
                if (thingiverseToken.isBlank()) {
                    emptyList()
                } else {
                    ThingiverseApi.search(q, thingiverseToken, page)
                }
            }

            ModelSource.NEXPRINT -> {
                NexprintApi.search(q, page)
            }

            ModelSource.MAKERWORLD -> {
                MakerworldApi.search(q, page, bambuToken.takeIf { it.isNotBlank() })
            }
        }

    private fun popularSource(
        source: ModelSource,
        page: Int = 1,
    ): List<ModelListing> =
        when (source) {
            ModelSource.PRINTABLES -> {
                PrintablesApi.popular(page)
            }

            ModelSource.THINGIVERSE -> {
                if (thingiverseToken.isBlank()) {
                    emptyList()
                } else {
                    ThingiverseApi.popular(thingiverseToken, page)
                }
            }

            ModelSource.NEXPRINT -> {
                NexprintApi.popular(page)
            }

            ModelSource.MAKERWORLD -> {
                MakerworldApi.popular(page, bambuToken.takeIf { it.isNotBlank() })
            }
        }

    /**
     * Shows each source's popular models (the home feed shown with an empty search bar).
     * Sources that fail or return nothing are hidden, same as empty searches.
     */
    fun loadHome() {
        val st = _state.value
        if (st.query.isNotBlank()) return
        val cached =
            ModelSource.entries.mapNotNull { src ->
                resultsCache[homeKey(src)]?.let {
                    SourceSection(src, results = it, hasMore = it.isNotEmpty())
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
                val outcome: Result<List<ModelListing>> =
                    try {
                        Result.success(withContext(Dispatchers.IO) { popularSource(src) })
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                if (generation != searchGeneration) return@launch
                outcome
                    .onSuccess { results ->
                        if (results.isEmpty()) {
                            updateSection(src) { null }
                        } else {
                            resultsCache[homeKey(src)] = results
                            updateSection(src) {
                                SourceSection(src, results = results, hasMore = results.isNotEmpty())
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
        val cached =
            ModelSource.entries.mapNotNull { src ->
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
                val outcome: Result<List<ModelListing>> =
                    try {
                        Result.success(withContext(Dispatchers.IO) { searchSource(src, q) })
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                if (generation != searchGeneration) return@launch
                outcome
                    .onSuccess { results ->
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
            val outcome: Result<List<ModelListing>> =
                try {
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
                val merged =
                    if (more == null) {
                        cur.results
                    } else {
                        cur.results + more.filter { n -> cur.results.none { it.id == n.id } }
                    }
                when {
                    more.isNullOrEmpty() || merged.size == cur.results.size -> {
                        // Empty, failed, or a repeat of page 1 (sources whose API
                        // ignores paging): stop, don't spin forever.
                        cur.copy(loadingMore = false, hasMore = false)
                    }

                    else -> {
                        cur.copy(results = merged, page = page, loadingMore = false)
                    }
                }
            }
        }
    }

    /** Replaces, inserts (in fixed [ModelSource] order) or removes (transform → null) a section. */
    private fun updateSection(
        source: ModelSource,
        transform: (SourceSection?) -> SourceSection?,
    ) {
        val current = _state.value.sections.toMutableList()
        val idx = current.indexOfFirst { it.source == source }
        val updated = transform(current.getOrNull(idx))
        if (idx >= 0) current.removeAt(idx)
        if (updated != null) current.add(updated)
        current.sortBy { it.source.ordinal }
        _state.value = _state.value.copy(sections = current)
    }

    fun openDetail(listing: ModelListing) {
        _state.value =
            _state.value.copy(
                detail = null,
                loadingDetail = true,
                error = null,
                commentsLoading = false,
                commentsLoadingMore = false,
                commentsExhausted = "${listing.source}|${listing.id}" in commentThreadsComplete,
                commentsMoreError = null,
            )
        EventNotifier.trackEvent("model_detail_opened", mapOf("source" to listing.source, "name" to listing.name))
        scope.launch {
            try {
                val detail =
                    withContext(Dispatchers.IO) {
                        when (listing.source) {
                            ModelSource.PRINTABLES.id -> PrintablesApi.detail(listing)
                            ModelSource.NEXPRINT.id -> NexprintApi.detail(listing, cloudTokenProvider())
                            ModelSource.MAKERWORLD.id -> MakerworldApi.detail(listing)
                            else -> ThingiverseApi.detail(listing, thingiverseToken)
                        }
                    }
                _state.value = _state.value.copy(detail = detail, loadingDetail = false)
                loadComments(detail.listing)
            } catch (e: Exception) {
                _state.value = _state.value.copy(loadingDetail = false, error = readable(e))
            }
        }
    }

    /**
     * Where a model's comments come from, or null when the source has no comment API.
     * Only Printables paginates (`comments(limit:, offset:)`); MakerWorld, Thingiverse
     * and Nexprint answer the whole thread in one body, so their offset and limit are
     * ignored (MakerWorld's endpoint has no working offset — see its commentsPage).
     * Only Thingiverse needs a token.
     */
    private fun commentFetcher(listing: ModelListing): ((offset: Int, limit: Int) -> CommentPage)? =
        when (listing.source) {
            ModelSource.PRINTABLES.id -> {
                { offset, limit -> PrintablesApi.commentsPage(listing, offset, limit) }
            }

            ModelSource.MAKERWORLD.id -> {
                { _, _ -> MakerworldApi.commentsPage(listing) }
            }

            ModelSource.NEXPRINT.id -> {
                { _, _ -> NexprintApi.flatComments(listing, cloudTokenProvider()) }
            }

            ModelSource.THINGIVERSE.id -> {
                if (thingiverseToken.isBlank()) {
                    null
                } else {
                    { _, _ -> ThingiverseApi.flatComments(listing, thingiverseToken) }
                }
            }

            else -> {
                null
            }
        }

    /**
     * Fetches the first comments page of [listing] right after the detail is on screen,
     * so the extra request never delays the page itself. The rest is fetched by
     * [loadMoreComments], and every row is cached per source|id.
     */

    /**
     * Fetches the first comments page for [listing]. A failed fetch is remembered as
     * [BrowseState.commentsMoreError] instead of being swallowed, so the section can say
     * the truth rather than claiming a model with 400 comments has none.
     */
    fun loadComments(listing: ModelListing) {
        val fetcher = commentFetcher(listing) ?: return
        val key = "${listing.source}|${listing.id}"
        if (key in commentsCache || key == commentsFetching) return
        commentsFetching = key
        _state.value = _state.value.copy(commentsLoading = true, commentsMoreError = null)
        scope.launch {
            val page = runCatching { withContext(Dispatchers.IO) { fetcher(0, commentsPageSize) } }
            android.util.Log.i(
                "ModelBrowser",
                "comments first page $key -> " +
                    page.getOrNull()?.let { "${it.rows.size} rows next=${it.nextOffset} total=${it.total}" }
                    ?: "failed ${page.exceptionOrNull()}",
            )
            if (commentsFetching == key) {
                commentsFetching = null
                _state.value = _state.value.copy(commentsLoading = false)
            }
            val state = _state.value
            state.detail
                ?.takeIf { "${it.listing.source}|${it.listing.id}" == key }
                ?.let {
                    if (page.isFailure) {
                        _state.value = state.copy(commentsMoreError = page.exceptionOrNull()?.message)
                    } else {
                        saveComments(key, page.getOrThrow())
                    }
                }
        }
    }

    /** Fetches the next comments page of the open model and appends it. */
    fun loadMoreComments() {
        val st = _state.value
        val detail = st.detail ?: return
        if (st.commentsLoadingMore) return
        val fetcher = commentFetcher(detail.listing) ?: return
        val key = "${detail.listing.source}|${detail.listing.id}"
        val cached = commentsCache[key]
        if (cached == null) {
            android.util.Log.i("ModelBrowser", "comments more: nothing cached for $key — refetching first page")
            loadComments(detail.listing) // nothing cached — that is what a retry means
            return
        }
        val known = detail.commentsTotal ?: 0
        if (known > 0 && cached.size >= known) {
            android.util.Log.i("ModelBrowser", "comments more: $key cached ${cached.size} >= known $known — exhausted")
            commentThreadsComplete += key
            _state.value = st.copy(commentsExhausted = true)
            return // nothing left to ask for
        }
        _state.value = st.copy(commentsLoadingMore = true, commentsMoreError = null)
        scope.launch {
            val askOffset = commentsOffsets[key] ?: cached.size
            val page = runCatching { withContext(Dispatchers.IO) { fetcher(askOffset, commentsPageSize) } }
            val fresh = page.getOrNull()
            if (fresh != null) {
                val knownIds = cached.mapTo(HashSet()) { it.first.id }
                val added = cached.addAll(fresh.rows.filterNot { it.first.id in knownIds })
                fresh.nextOffset?.let { commentsOffsets[key] = it }
                // A page that adds nothing ends the thread — the source has served fewer
                // rows than its own count (deleted comments stay counted, MakerWorld's
                // pool stops below its header number), so no further page exists.
                if (!added) commentThreadsComplete += key
                android.util.Log.i(
                    "ModelBrowser",
                    "comments more $key offset=$askOffset -> ${fresh.rows.size} rows, $added new, " +
                        "cache=${cached.size} threads=${threadComments(cached).size}",
                )
            } else {
                android.util.Log.i("ModelBrowser", "comments more $key offset=$askOffset failed: ${page.exceptionOrNull()}")
            }
            val open = _state.value.detail
            _state.value =
                _state.value.copy(
                    commentsLoadingMore = false,
                    commentsExhausted = key in commentThreadsComplete,
                    commentsMoreError = page.exceptionOrNull()?.message,
                    detail =
                        open?.copy(
                            comments = threadComments(cached),
                            commentsTotal = page.getOrNull()?.total ?: open.commentsTotal,
                        ),
                )
        }
    }

    /** Caches comment rows and republishes them on the detail that is still open. */
    private fun saveComments(
        key: String,
        page: CommentPage,
    ) {
        commentsCache[key] = page.rows.toMutableList()
        page.nextOffset?.let { commentsOffsets[key] = it }
        val st = _state.value
        val detail = st.detail ?: return
        if ("${detail.listing.source}|${detail.listing.id}" != key) return
        // Prefer the count the comment service itself stated over the one the
        // detail response carries: model-row counters keep counting deleted
        // comments (Nexprint says 6 where its service returns 4). A zero from
        // the list must not erase a real count — comment gating can hand back an
        // empty tree for a model the site counts higher, and there the site
        // number is the honest one.
        val effectiveTotal =
            page.total?.takeIf { it > 0 }
                ?: detail.commentsTotal
                ?: page.rows.size
        // The thread is finished once the rows match that count (flat sources and full
        // fetches) or the source returns nothing at all on a counted model (its pool is
        // empty — deleted votes); "Load more" goes away either way.
        if (page.rows.isEmpty() || page.rows.size >= effectiveTotal) {
            commentThreadsComplete += key
        }
        _state.value =
            st.copy(
                commentsExhausted = key in commentThreadsComplete,
                commentsMoreError = null,
                detail = detail.copy(comments = threadComments(page.rows), commentsTotal = effectiveTotal),
            )
    }

    /** Stable library key for a file: the name alone, so the star is shared everywhere the
     * file shows (browse detail, home preview) and matches already-stored favorites. */
    fun favoriteKey(file: ModelFile): String = "file|${file.name}"

    /**
     * "Keep in My models": download the file and copy it into private storage
     * so it survives cache eviction, with a thumbnail for the library screen.
     */
    fun favoriteFile(
        detail: ModelDetail,
        file: ModelFile,
        onDone: (Boolean) -> Unit,
    ) {
        val key = favoriteKey(file)
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
        favorites.entries.value
            .firstOrNull { it.key == key }
            ?.let { favorites.remove(it.id) }
    }

    fun closeDetail() {
        _state.value = _state.value.copy(detail = null)
    }

    fun download(
        file: ModelFile,
        onDone: (String) -> Unit,
    ) {
        android.util.Log.i("ModelBrowser", "download tapped: ${file.name} busy=${_state.value.downloadingFile}")
        if (_state.value.downloadingFile != null) return
        _state.value = _state.value.copy(downloadingFile = file.name, downloadProgress = 0, error = null)
        scope.launch {
            try {
                val path =
                    withContext(Dispatchers.IO) {
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
                _state.value =
                    _state.value.copy(
                        downloadingFile = null,
                        downloadProgress = 0,
                        error = msg,
                        // Token expired: show the MakerWorld sign-in card again.
                        bambuSignedIn =
                            _state.value.bambuSignedIn &&
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
            // The sub-scheme (instance/ or profile/) is part of the reference: MakerWorld moved the
            // 3mf off the profile record, so which one we hold decides which endpoint answers.
            return MakerworldApi.downloadUrl(file.url.removePrefix("makerworld://"), bambuToken)
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

    private fun readable(e: Exception): String =
        when {
            e is java.net.SocketTimeoutException -> "The site took too long to respond."
            e is java.net.UnknownHostException -> "No network connection."
            e is IOException -> e.message ?: "Network request failed."
            else -> e.message ?: e.javaClass.simpleName
        }
}

private const val USER_AGENT =
    "Mozilla/5.0 (Linux; Android 13) SlicePilot/1.0 AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

// The Thingiverse app token (thingiverse.com/developers) comes from
// secrets/build.properties through BuildConfig.THINGIVERSE_TOKEN instead of living in
// source; a token entered in the app still overrides it, and an empty value leaves the
// source signed out. See ModelBrowser.thingiverseToken.
private const val DEFAULT_THINGIVERSE_TOKEN =
    world.yemyat.slicepilot.BuildConfig.THINGIVERSE_TOKEN

private val MODEL_EXTENSIONS = listOf(".stl", ".3mf", ".zip")

private fun isModelFile(name: String): Boolean {
    val lower = name.lowercase()
    return MODEL_EXTENSIONS.firstOrNull { lower.endsWith(it) } != null
}

private fun extOf(name: String): String {
    val lower = name.lowercase()
    return MODEL_EXTENSIONS.firstOrNull { lower.endsWith(it) } ?: ".stl"
}

private fun getJson(
    url: String,
    headers: Map<String, String> = emptyMap(),
): JSONObject {
    val conn = URL(url).openConnection() as HttpURLConnection
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

private fun postJson(
    url: String,
    body: JSONObject,
    token: String? = null,
): JSONObject {
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
        val text =
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()
                ?.use { it.readText() } ?: ""
        android.util.Log.i("Nexprint", "POST body=" + text.take(300))
        if (code == 401 || code == 403) throw IOException("Access denied (HTTP $code). Sign in again.")
        if (code !in 200..299) throw IOException("HTTP $code from ${URL(url).host}")
        return JSONObject(text)
    } finally {
        conn.disconnect()
    }
}

private fun getJsonArray(
    url: String,
    headers: Map<String, String> = emptyMap(),
): org.json.JSONArray {
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
        val sizeScore =
            when (s.optString("size", "")) {
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

/**
 * Rebuilds threads from flat comment rows: every top-level comment in the source's own
 * order with its replies (oldest first) nested inside. A reply whose root is not in
 * [rows] is dropped, which only matters while a paginated list is still arriving.
 *
 * Roots keep the order [rows] lists them in rather than being sorted by date: Printables'
 * comment paging is not date-ordered (live: offset 10 served 2026 comments whose offset
 * 0 ended at 2025-05-12), so a date sort scattered each Load-more batch through the
 * middle of the list instead of appending new threads where the reader is looking.
 */
internal fun threadComments(rows: List<Pair<ModelComment, String?>>): List<ModelComment> {
    val repliesByParent = rows.filter { it.second != null }.groupBy { it.second }
    return rows
        .filter { it.second == null }
        .map { (top, _) ->
            top.copy(
                replies =
                    repliesByParent[top.id]
                        .orEmpty()
                        .map { it.first }
                        .sortedBy { it.createdAt.orEmpty() },
            )
        }
}

/** Printables.com GraphQL API (unofficial, used by the site's own frontend). */
private object PrintablesApi {
    private const val ENDPOINT = "https://api.printables.com/graphql/"

    private val headers =
        mapOf(
            "Content-Type" to "application/json",
            "Origin" to "https://www.printables.com",
            "Referer" to "https://www.printables.com/",
        )

    private fun post(
        operation: String,
        query: String,
        variables: JSONObject,
    ): JSONObject {
        val body =
            JSONObject()
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

    /** Media-relative path to absolute URL; already-absolute URLs pass through. */
    private fun mediaUrl(path: String?): String? =
        path
            ?.takeIf { it.isNotBlank() && it != "null" }
            ?.let { if (it.startsWith("http")) it else "https://media.printables.com/" + it.trimStart('/') }

    /**
     * One page of the model's comments. `comments(limit:, offset:)` is the only paging
     * the field offers (no cursor, and the REST form of it 404s), and its offset counts
     * root comments only — replies ride inside each root comment's `replies` field, never
     * as rows of the field itself — so [CommentPage.nextOffset] continues after the roots
     * this page held, and [flatComments] expands both kinds of row.
     */
    fun commentsPage(
        listing: ModelListing,
        offset: Int,
        limit: Int,
    ): CommentPage {
        val query =
            """
            query PrintComments(${'$'}id: ID!, ${'$'}off: Int!, ${'$'}limit: Int!) {
              print(id: ${'$'}id) {
                id
                comments(limit: ${'$'}limit, offset: ${'$'}off) {
                  id text created
                  author { publicUsername avatarFilePath }
                  parent { id }
                  images { filePath }
                  replies { id text created author { publicUsername avatarFilePath } images { filePath } }
                }
                __typename
              }
            }
            """.trimIndent()
        val print =
            post("PrintComments", query, JSONObject().put("id", listing.id).put("off", offset).put("limit", limit))
                .getJSONObject("print")
        val arr = print.optJSONArray("comments")
        return CommentPage(
            flatComments(arr),
            nextOffset = arr?.let { offset + it.length() },
        )
    }

    /** Comment rows of a `comments` array in the source's order, each paired with its
     * parent id (null for a top-level comment). A root comment's nested `replies` become
     * rows too, paired with the root's id — threadComments nests them back into the
     * thread. */
    private fun flatComments(arr: org.json.JSONArray?): List<Pair<ModelComment, String?>> {
        if (arr == null) return emptyList()
        val flat = ArrayList<Pair<ModelComment, String?>>(arr.length())

        fun row(
            o: JSONObject,
            inheritedParentId: String?,
        ): Pair<ModelComment, String?>? {
            val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: return null
            val text = o.optString("text", "").trim()
            val author = o.optJSONObject("author")
            val pics = ArrayList<String>()
            o.optJSONArray("images")?.let { imgs ->
                for (j in 0 until imgs.length()) {
                    mediaUrl(imgs.optJSONObject(j)?.optString("filePath"))?.let(pics::add)
                }
            }
            // Text-less comments with attached photos (the site's photo-only reviews)
            // stay as image rows: dropping them would hide their root and orphan every
            // reply under it. Only entries with neither text nor photos are skipped.
            if (text.isEmpty() && pics.isEmpty()) return null
            return ModelComment(
                id = id,
                author = author?.optString("publicUsername", "").orEmpty().takeIf { it.isNotBlank() } ?: "Unknown",
                avatarUrl = mediaUrl(author?.optString("avatarFilePath")),
                createdAt = o.optString("created", "").takeIf { it.isNotBlank() },
                text = text,
                images = pics,
            ) to (o.optJSONObject("parent")?.optString("id", "")?.takeIf { it.isNotBlank() } ?: inheritedParentId)
        }

        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            row(o, null)?.let(flat::add)
            // Replies nest only under roots; the API echoes nothing here for a reply row.
            o.optJSONArray("replies")?.let { rs ->
                for (j in 0 until rs.length()) {
                    row(rs.optJSONObject(j) ?: continue, o.optString("id", "").takeIf { it.isNotBlank() })
                        ?.let(flat::add)
                }
            }
        }
        return flat
    }

    fun search(
        q: String,
        page: Int = 1,
    ): List<ModelListing> = querySearch(q, "best_match", page)

    /** Popular prints (empty query + popularity ordering), used for the home feed. */
    fun popular(page: Int = 1): List<ModelListing> = querySearch("", "popular", page)

    private fun querySearch(
        q: String,
        ordering: String,
        page: Int = 1,
    ): List<ModelListing> {
        val query =
            """
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
            val image =
                o
                    .optString("image", "")
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
                ),
            )
        }
        return out
    }

    fun detail(listing: ModelListing): ModelDetail {
        val query =
            """
            query PrintDetail(${'$'}id: ID!) {
              print(id: ${'$'}id) {
                id name summary likesCount downloadCount datePublished
                user { publicUsername }
                images { filePath rotation }
                stls { id name fileSize }
                slas { id name fileSize }
                commentCount
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
                    ),
                )
            }
        }
        return ModelDetail(
            listing =
                listing.copy(
                    author =
                        print.optJSONObject("user")?.optString("publicUsername", "")?.takeIf { it.isNotBlank() }
                            ?: listing.author,
                    downloads = if (print.has("downloadCount")) print.optInt("downloadCount") else listing.downloads,
                ),
            description = print.optString("summary", "").trim(),
            images = images,
            likes = if (print.has("likesCount")) print.optInt("likesCount") else null,
            published = print.optString("datePublished", "").take(10).takeIf { it.isNotBlank() },
            files = files,
            commentsTotal = print.optInt("commentCount", -1).takeIf { it >= 0 },
        )
    }

    /** Resolves the temporary (24 h) direct download link for one model file. */
    fun downloadLink(
        printId: String,
        fileId: String,
        fileType: String,
    ): String {
        val mutation =
            """
            mutation GetDownloadLink(${'$'}id: ID!, ${'$'}modelId: ID!, ${'$'}fileType: DownloadFileTypeEnum!, ${'$'}source: DownloadSourceEnum!) {
              getDownloadLink(id: ${'$'}id, printId: ${'$'}modelId, fileType: ${'$'}fileType, source: ${'$'}source) {
                ok
                errors { field messages }
                output { link }
                __typename
              }
            }
            """.trimIndent()
        val vars =
            JSONObject()
                .put("id", fileId)
                .put("modelId", printId)
                .put("fileType", fileType)
                .put("source", "model_detail")
        val res = post("GetDownloadLink", mutation, vars).getJSONObject("getDownloadLink")
        val link = res.optJSONObject("output")?.optString("link", "").orEmpty()
        if (!res.optBoolean("ok") || link.isBlank()) {
            val msg =
                res
                    .optJSONArray("errors")
                    ?.optJSONObject(0)
                    ?.optJSONArray("messages")
                    ?.optString(0)
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
        o
            .optJSONObject("preview_image")
            ?.optJSONObject("default_file")
            ?.optString("url", "")
            ?.takeIf { it.startsWith("http") }
            ?.let { return it }
        o
            .optString("preview_image", "")
            .takeIf { it.startsWith("http") }
            ?.let { return it }
        return o
            .optJSONObject("thumbnail")
            ?.optString("default", "")
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

    fun search(
        q: String,
        token: String,
        page: Int = 1,
    ): List<ModelListing> {
        // NOTE: /search?q=... is accepted but ignored (always returns the same generic list);
        // the term must go in the path.
        val term = URLEncoder.encode(q, "UTF-8").replace("+", "%20")
        val url =
            "$BASE/search/$term?type=things&per_page=30&page=$page&access_token=" +
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
    fun popular(
        token: String,
        page: Int = 1,
    ): List<ModelListing> {
        val url =
            "$BASE/popular?per_page=30&page=$page&access_token=" +
                URLEncoder.encode(token, "UTF-8")
        val arr = getJsonArray(url, auth(token))
        val out = ArrayList<ModelListing>(arr.length())
        for (i in 0 until arr.length()) {
            listing(arr.getJSONObject(i))?.let { out.add(it) }
        }
        return out
    }

    fun detail(
        listing: ModelListing,
        token: String,
    ): ModelDetail {
        val tok = URLEncoder.encode(token, "UTF-8")
        val thing = getJson("$BASE/things/${listing.id}?access_token=$tok", auth(token))

        // Files live at /things/{id}/files; /things/{id} has no files array.
        val files = ArrayList<ModelFile>()
        try {
            val arr =
                getJsonArray(
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
                    ),
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
        val description =
            listOf(
                thing.optString("description", ""),
                thing.optString("details", ""),
            ).filter { it.isNotBlank() }.joinToString("\n\n") { stripHtml(it) }
        return ModelDetail(
            listing =
                listing.copy(
                    author =
                        thing.optJSONObject("creator")?.optString("name", "")?.takeIf { it.isNotBlank() }
                            ?: listing.author,
                    downloads = if (thing.has("download_count")) thing.optInt("download_count") else listing.downloads,
                ),
            description = description,
            images = images,
            likes = if (thing.has("like_count")) thing.optInt("like_count") else null,
            // The new API schema names this "added" (an ISO timestamp); "date" never appears,
            // so reading it left every Thingiverse detail without a publish date.
            published =
                listOf(
                    thing.optString("date", ""),
                    thing.optString("added", ""),
                    thing.optString("created_at", ""),
                ).firstOrNull { it.isNotBlank() }?.take(10)?.takeIf { it.isNotBlank() },
            files = files,
            // The two counts differ by design (V29: comment_count 932 vs root_comment_count
            // 608, matching its 608 root comments exactly): the UI advertises top-level
            // comments like every other source, so prefer the root count and fall back to
            // the combined one where the API predates it.
            commentsTotal =
                if (thing.has("root_comment_count")) {
                    thing.optInt("root_comment_count")
                } else if (thing.has("comment_count")) {
                    thing.optInt("comment_count")
                } else {
                    null
                },
        )
    }

    /**
     * Every comment row of a thing, each paired with its parent id. One request returns
     * the whole thread list (the endpoint ignores paging: 1.9 MB for 1 017 comments),
     * and deleted or empty rows are skipped. Thread the result with [threadComments].
     */
    fun flatComments(
        listing: ModelListing,
        token: String,
    ): CommentPage {
        val url = "$BASE/things/${listing.id}/comments?access_token=" + URLEncoder.encode(token, "UTF-8")
        val arr =
            try {
                getJsonArray(url, auth(token))
            } catch (_: Exception) {
                return CommentPage()
            }
        val flat = ArrayList<Pair<ModelComment, String?>>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("id", "").takeIf { it.isNotBlank() } ?: continue
            if (o.optBoolean("is_deleted")) continue
            val body = o.optString("body", "").trim()
            if (body.isBlank()) continue
            val user = o.optJSONObject("user")
            val parentId =
                if (o.optBoolean("is_root_comment", true)) {
                    null
                } else {
                    o.optString("parent_id", "").takeIf { it.isNotBlank() }
                }
            flat.add(
                ModelComment(
                    id = id,
                    author = user?.optString("name", "").orEmpty().takeIf { it.isNotBlank() } ?: "Unknown",
                    avatarUrl = user?.optString("thumbnail", "")?.takeIf { it.startsWith("http") },
                    createdAt = o.optString("added", "").takeIf { it.isNotBlank() },
                    text = body,
                ) to parentId,
            )
        }
        return CommentPage(flat)
    }
}

private fun stripHtml(html: String): String =
    html
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&amp;", "&")
        // Non-breaking spaces arrive as &nbsp; (or &#160;/&#032;), and a double-escaped
        // &amp;nbsp; has just become &nbsp; after the pass above, so they are collapsed here
        // instead of rendering as literal "&nbsp;" runs in the description.
        .replace(Regex("&nbsp;|&#0*160;|&#0?32;", RegexOption.IGNORE_CASE), " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace(Regex("&quot;|&#0?34;", RegexOption.IGNORE_CASE), "\"")
        .replace(Regex("&apos;|&#0?39;", RegexOption.IGNORE_CASE), "'")
        .replace("\\n", "\n")
        .replace(Regex("^#{1,6}(?![0-9])[ \\t]*", RegexOption.MULTILINE), "") // Thingiverse writes markdown
        .replace(Regex("!\\[[^\\]]*]\\([^)]+\\)"), "") // images: no inline render
        .replace(Regex("\\[([^\\]]+)]\\(([^)]+)\\)"), "$1 ($2)") // keep the text, keep the url
        .replace(Regex("\\*\\*([^*]+)\\*\\*"), "$1")
        .replace(Regex("~~([^~]+)~~"), "$1")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

/** Nexprint (nexprint.com, by Elegoo) gateway API. */
private object NexprintApi {
    private const val BASE = "https://www.nexprint.com/gateway/api/v1/model-library-server"

    /** Nexprint returns HTTP 200 with an envelope: code 0 = ok, 401 = not logged in. */
    private fun getData(
        url: String,
        token: String? = null,
    ): JSONObject {
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

            401 -> {
                throw IOException("Sign in with your Elegoo account to download from Nexprint.")
            }

            else -> {
                throw IOException(json.optString("msg", "Nexprint error (code $code)"))
            }
        }
    }

    fun search(
        q: String,
        page: Int = 1,
    ): List<ModelListing> {
        val url =
            "$BASE/model-base-info/search?keyword=" + URLEncoder.encode(q, "UTF-8") +
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
                    downloads =
                        o
                            .optJSONObject("statistics")
                            ?.takeIf { it.has("staticsModelDownloadCount") }
                            ?.optInt("staticsModelDownloadCount"),
                ),
            )
        }
        return out
    }

    fun detail(
        listing: ModelListing,
        token: String? = null,
    ): ModelDetail {
        val d = getData("$BASE/model-base-info/get?id=${listing.id}", token)
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
                    ),
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
        val published =
            d.optLong("publishTime").takeIf { it > 0 }?.let {
                java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date(it))
            }
        return ModelDetail(
            listing =
                listing.copy(
                    author = d.optString("authorName", "").takeIf { it.isNotBlank() } ?: listing.author,
                    downloads =
                        stats
                            ?.takeIf { it.has("staticsModelDownloadCount") }
                            ?.optInt("staticsModelDownloadCount") ?: listing.downloads,
                ),
            description = stripHtml(d.optString("modelDetail", "")),
            images = images,
            likes = stats?.takeIf { it.has("staticsModelFavoriteCount") }?.optInt("staticsModelFavoriteCount"),
            published = published,
            files = files,
            commentsTotal = stats?.takeIf { it.has("staticsModelCommentCount") }?.optInt("staticsModelCommentCount"),
        )
    }

    /**
     * Every comment on a model in one body — `library/model-comment/commentTree` answers
     * anonymously and nests replies under each node's `children`, so the body arrives
     * already threaded; each node's own `parentId` feeds the shared tree builder.
     */

    /**
     * [token] is the signed-in Elegoo account token: without it the comment service answers
     * for public models only, so an account with the model in its library sees an empty
     * thread where the site counts comments. Same gateway and same bearer as the download
     * url above.
     */
    fun flatComments(
        listing: ModelListing,
        token: String? = null,
    ): CommentPage {
        val data =
            getData("$BASE/library/model-comment/commentTree?modelId=${listing.id}&sortRule=2", token)
        val rows = ArrayList<Pair<ModelComment, String?>>()
        val seen = HashSet<String>()

        fun walk(nodes: org.json.JSONArray?) {
            if (nodes == null) return
            for (i in 0 until nodes.length()) {
                val node = nodes.optJSONObject(i) ?: continue
                val info = node.optJSONObject("info")
                // optString renders a JSON null as the *string* "null", and Nexprint leaves
                // replyCommentId null on every root comment: without the "null" guard each
                // comment looked like a reply, threadComments found no root to hang them on,
                // and a model the site counts at 6 rendered "No comments yet".
                val parentId =
                    node.optLong("parentId").takeIf { it > 0 }?.toString()
                        ?: info
                            ?.optString("replyCommentId", "")
                            ?.takeIf { it.isNotBlank() && it != "0" && it != "null" }
                info?.toNexprintComment()?.let { if (seen.add(it.id)) rows.add(it to parentId) }
                walk(node.optJSONArray("children"))
            }
        }
        walk(data.optJSONArray("treeList"))
        val page = CommentPage(rows, data.optInt("total", -1).takeIf { it >= 0 })
        android.util.Log.i(
            "Nexprint",
            "commentTree model=${listing.id} total=${page.total} rows=${page.rows.size} " +
                "roots=${page.rows.count { it.second == null }} " +
                "replyRows=${page.rows.count { it.second != null}}",
        )
        return page
    }

    /** Maps a `ModelCommentRespVO`; Nexprint stamps time in epoch millis, not ISO text. */
    private fun JSONObject.toNexprintComment(): ModelComment? {
        val id = optString("id", "").takeIf { it.isNotBlank() } ?: return null
        // `content` is null whenever the comment isn't in the reader's language: the text the
        // site shows lives in curLanguageContent, whose `content` holds the translation and
        // `originalContent` the author's own words. "Dragon - Dragon" counts 6 comments while
        // only 4 carry a plain `content`, so the rest vanished from the list.
        val cur = optJSONObject("curLanguageContent")
        val text =
            listOf(
                optString("content", ""),
                cur?.optString("content", "").orEmpty(),
                cur?.optString("originalContent", "").orEmpty(),
            ).firstOrNull { it.isNotBlank() }
                ?.trim() ?: return null
        val user = optJSONObject("userInfo")
        val pics = ArrayList<String>()
        optJSONArray("picList")?.let { arr ->
            for (i in 0 until arr.length()) {
                arr
                    .optJSONObject(i)
                    ?.optString("fileUrl", "")
                    ?.takeIf { it.startsWith("http") }
                    ?.let(pics::add)
            }
        }
        return ModelComment(
            id = id,
            author =
                user?.optString("nickname", "")?.takeIf { it.isNotBlank() }
                    ?: user?.optString("userId", "")?.takeIf { it.isNotBlank() }
                    ?: "Unknown",
            avatarUrl = user?.optString("avatarUrl", "")?.takeIf { it.startsWith("http") },
            createdAt =
                optLong("createTime").takeIf { it > 0 }?.let {
                    java.text
                        .SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
                        .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                        .format(java.util.Date(it))
                },
            text = text,
            images = pics,
            likeCount = optInt("likeCount", -1).takeIf { it >= 0 },
            replyCount = optInt("commentCount", -1).takeIf { it >= 0 },
        )
    }

    /** Resolves a file id to a temporary direct download URL. Requires a logged-in Elegoo account. */
    fun presignedDownloadUrl(
        fileId: String,
        modelId: String,
        token: String?,
    ): String {
        if (token.isNullOrBlank()) {
            throw IOException("Sign in with your Elegoo account to download from Nexprint.")
        }
        val fileInfo =
            JSONObject()
                .put("fileId", fileId)
                .put("type", "2") // 2 = MODEL_FILE
                .put("bizId", modelId)
        val body = JSONObject().put("fileInfoList", org.json.JSONArray().put(fileInfo))
        val json = postJson("$BASE/common/presigned-download-url", body, token)
        when (val code = json.optInt("code", -1)) {
            0 -> {}

            401 -> {
                throw IOException("Sign in with your Elegoo account to download from Nexprint.")
            }

            else -> {
                throw IOException(json.optString("msg", "Nexprint error (code $code)"))
            }
        }
        val files = json.optJSONObject("data")?.optJSONArray("fileInfoList")
        for (i in 0 until (files?.length() ?: 0)) {
            files
                ?.optJSONObject(i)
                ?.optString("resultUrl", "")
                ?.takeIf { it.startsWith("http") }
                ?.let { return it }
        }
        android.util.Log.w("Nexprint", "presigned no url: " + json.toString().take(300))
        throw IOException("Nexprint returned no download link for this file.")
    }
}

/** MakerWorld (makerworld.com, by Bambu Lab) access. Primary route is the Bambu
 *  gateway Bambu Handy itself uses (`api.bambulab.com/v1/search-service/...`,
 *  offset-paginated, anonymous; Bambu bearer token attached when signed in).
 *  Scraping the web frontend mirrors it: CSRF cookie handshake, then reading
 *  the Next.js flight payload. Cloudflare blocks datacenter IPs; residential/mobile
 *  networks pass without a JS challenge. */
private object MakerworldApi {
    private const val SITE = "https://makerworld.com"
    private const val GATEWAY = "https://api.bambulab.com/v1/design-service"
    private const val SEARCH_SERVICE = "https://api.bambulab.com/v1/search-service"

    /**
     * Comment list for a design. Answers anonymously (no Bambu bearer needed) and refuses
     * any `limit` above [MAX_COMMENT_PAGE] with HTTP 400, unlike Printables/Thingiverse.
     */
    private const val COMMENT_SERVICE = "https://api.bambulab.com/v1/comment-service"

    /**
     * Widest `limit` the endpoint accepts — its own hard page size. Verified live: 64 answers
     * (even on designs with larger pools), 65 and above answer HTTP 400. The old 60 was an
     * earlier guess at the cap; 64 is what the server itself enforces.
     */
    private const val MAX_COMMENT_PAGE = 64

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
            val body =
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() } ?: ""
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
     * Keyword search: the gateway design search Bambu Handy uses
     * (`search-service/select/design2`, offset-paginated, works anonymously),
     * then the signed-in `design-service/design/search`, and the SSR scrape
     * (page 1 only) as the last resort.
     */
    fun search(
        q: String,
        page: Int = 1,
        token: String? = null,
    ): List<ModelListing> {
        runCatching { return designSearch(q, page, token) }
            .onFailure { android.util.Log.w("Makerworld", "design2 search failed", it) }
        if (!token.isNullOrBlank()) {
            runCatching { return searchApi(q, page, token) }
                .onFailure { android.util.Log.w("Makerworld", "search API failed, falling back to scrape", it) }
        }
        return if (page > 1) {
            emptyList()
        } else {
            scrape("$SITE/en/search/models?keyword=" + URLEncoder.encode(q, "UTF-8") + "&page=1", SEARCH_ENTRY)
        }
    }

    /** `search-service/select/design2` — Bambu Handy's design search; offset-paginated. */
    private fun designSearch(
        q: String,
        page: Int,
        token: String?,
    ): List<ModelListing> {
        val url =
            "$SEARCH_SERVICE/select/design2?keyword=" + URLEncoder.encode(q, "UTF-8") +
                "&offset=${(page - 1) * 30}&limit=30"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException(
                    "MakerWorld search returned HTTP $code " + conn.errorStream?.bufferedReader()?.use { it.readText().take(200) },
                )
            }
            return parseDesignHits(conn.inputStream.bufferedReader().use { it.readText() })
        } finally {
            conn.disconnect()
        }
    }

    private fun searchApi(
        q: String,
        page: Int,
        token: String,
    ): List<ModelListing> {
        val url =
            "$GATEWAY/design/search?keyword=" + URLEncoder.encode(q, "UTF-8") +
                "&sortValue=relevant&sortState=0&type=0&offset=${(page - 1) * 30}&limit=30"
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
     * Popular feed: the gateway's Trending nav (offset-paginated, works
     * anonymously) that Bambu Handy browses; the scraped home-page "For You"
     * feed (page 1 only) as fallback.
     */
    fun popular(
        page: Int = 1,
        token: String? = null,
    ): List<ModelListing> {
        runCatching { return navFeed(page, token) }
            .onFailure { android.util.Log.w("Makerworld", "nav feed failed, falling back to scrape", it) }
        return if (page > 1) emptyList() else scrape("$SITE/en/", FORYOU_ENTRY)
    }

    private fun navFeed(
        page: Int,
        token: String?,
    ): List<ModelListing> {
        val url = "$SEARCH_SERVICE/select/design/nav?navKey=Trending&offset=${(page - 1) * 30}&limit=30"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        token?.let { conn.setRequestProperty("Authorization", "Bearer $it") }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IOException(
                    "MakerWorld feed returned HTTP $code " + conn.errorStream?.bufferedReader()?.use { it.readText().take(200) },
                )
            }
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

    private fun scrape(
        url: String,
        entry: Regex,
    ): List<ModelListing> {
        if (cookieHeader == null) refreshSession()
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "text/html")
        cookieHeader?.let { conn.setRequestProperty("Cookie", it) }
        val html =
            try {
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
        android.util.Log.i(
            "MakerWorld",
            "scrape len=${html.length} marker=${html.indexOf("\"title\":\"")} matches=${entry.findAll(html).count()}",
        )
        for (m in entry.findAll(html)) {
            if (out.size >= 30) break
            val window = html.substring(m.range.last, minOf(m.range.last + 3000, html.length))
            val downloads =
                Regex(""""downloadCount":(\d+)""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
            val author =
                Regex(""""designCreator":\{"uid":\d+,"name":"([^"]*)"""")
                    .find(window)
                    ?.groupValues
                    ?.get(1)
                    ?.let { jsonUnescape(it) }
            val cover = jsonUnescape(m.groupValues[3])
            out.add(
                ModelListing(
                    source = ModelSource.MAKERWORLD.id,
                    id = m.groupValues[1],
                    name = jsonUnescape(m.groupValues[2]),
                    author = author,
                    thumbnailUrl = cover.takeIf { it.startsWith("http") },
                    downloads = downloads,
                ),
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
        val d =
            try {
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
                val instanceId = inst.optLong("id").takeIf { it > 0 }
                val plateTitle = inst.optString("title", "plate ${i + 1}")
                val size =
                    inst
                        .optJSONObject("extention")
                        ?.optJSONObject("modelInfo")
                        ?.optJSONObject("f3mf")
                        ?.optLong("size")
                        ?.takeIf { it > 0 }
                files.add(
                    ModelFile(
                        name = d.optString("title", "model") + " - " + plateTitle + ".3mf",
                        // The instance id is what the f3mf endpoint wants (see downloadUrl); the old
                        // profile pair is kept for the rare instance that carries no id.
                        url =
                            instanceId?.let { "makerworld://instance/$it" }
                                ?: "makerworld://profile/$profileId/$modelId",
                        sizeBytes = size,
                    ),
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
            listing =
                listing.copy(
                    author =
                        d.optJSONObject("designCreator")?.optString("name", "")?.takeIf { it.isNotBlank() }
                            ?: listing.author,
                    downloads = d.optInt("downloadCount", listing.downloads ?: 0),
                ),
            description = stripHtml(d.optString("summary", "")),
            images = images,
            likes = d.optInt("collectionCount").takeIf { it > 0 },
            published = d.optString("createTime", "").take(10).takeIf { it.isNotBlank() },
            files = files,
            commentsTotal = d.optInt("commentCount", -1).takeIf { it >= 0 },
        )
    }

    /**
     * The model's comment thread in one request. The endpoint's `offset` is ignored (any
     * offset answers with the same newest-first comments, verified live), so paging it
     * with a small limit would show one page forever and "Load more" would only ever
     * re-fetch duplicates — the fetch always asks the native page ([MAX_COMMENT_PAGE]),
     * and the rows it serves become the thread's truth ([CommentPage.total]). A reply
     * also carries its parent inline in `commentReply`, so both the reply and that
     * parent become rows and the thread survives.
     */
    fun commentsPage(listing: ModelListing): CommentPage {
        val url = "$COMMENT_SERVICE/comment?designId=${listing.id}&limit=$MAX_COMMENT_PAGE&offset=0"
        val arr = getJson(url).optJSONArray("comments") ?: return CommentPage()
        val rows = ArrayList<Pair<ModelComment, String?>>(arr.length())
        val seen = HashSet<String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val parent = o.optJSONObject("commentReply")?.toMakerworldComment()
            if (parent != null && seen.add(parent.id)) rows.add(parent to null)
            val comment = o.toMakerworldComment() ?: continue
            if (!seen.add(comment.id)) continue
            rows.add(comment to o.optLong("atCommentId").takeIf { it > 0 }?.toString())
        }

        // The response carries no count of its own, so the rows served are the truth —
        // they beat the model-row counter (design 3465138 counts 117 where its comment
        // service holds 47; see CommentPage).
        return CommentPage(rows, total = rows.size)
    }

    /** Maps a comment-service comment (or its inline `commentReply` parent) to [ModelComment]. */
    private fun JSONObject.toMakerworldComment(): ModelComment? {
        val id = optString("id", "").takeIf { it.isNotBlank() } ?: return null
        val text =
            optString("content", "")
                .trim()
                .ifBlank { optString("contentTranslated", "").trim() }
        if (text.isBlank()) return null
        val user = optJSONObject("user")
        val pics = ArrayList<String>()
        // The Bambu comment service answers `images` as a plain array of url strings
        // ("images": ["https://makerworld.bblmw.com/.../83e85dfa96718fbb.jpeg"]). Object
        // entries are accepted too, because reading only objects silently dropped every
        // photo the moment the shape turned out to be strings.
        optJSONArray("images")?.let { arr ->
            for (i in 0 until arr.length()) {
                (
                    arr.optString(i, "").takeIf { it.startsWith("http") }
                        ?: arr.optJSONObject(i)?.optString("url", "")?.takeIf { it.startsWith("http") }
                        ?: arr.optJSONObject(i)?.optString("fileUrl", "")?.takeIf { it.startsWith("http") }
                )?.let(pics::add)
            }
        }
        return ModelComment(
            id = id,
            author =
                user?.optString("name", "")?.takeIf { it.isNotBlank() }
                    ?: user?.optString("handle", "")?.takeIf { it.isNotBlank() }
                    ?: "Unknown",
            avatarUrl = user?.optString("avatar", "")?.takeIf { it.startsWith("http") },
            createdAt = optString("createTime", "").takeIf { it.isNotBlank() },
            text = text,
            images = pics,
            // Votes and answers are plain ints on the comment ("likeCount": 1, "replyCount": 1);
            // the earlier totalReactions guesses never matched, so cards showed no counts.
            likeCount =
                optInt("likeCount", -1).takeIf { it >= 0 }
                    ?: optInt("totalReactions", -1).takeIf { it >= 0 },
            replyCount = optInt("replyCount", -1).takeIf { it >= 0 },
        )
    }

    /**
     * Signs in to the Bambu cloud gateway (AES-encrypted password, like Bambu Studio).
     * Returns the access token (valid ~90 days).
     */
    class BambuVerificationRequired(
        message: String,
    ) : IOException(message)

    fun bambuLogin(
        email: String,
        password: String,
    ): String {
        // The gateway accepts several password encodings depending on region/version;
        // try the documented ones in order and report the last error if all fail.
        var lastError = IOException("Bambu sign-in failed.")
        val attempts =
            listOf(
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

    fun bambuLoginWithCode(
        email: String,
        code: String,
    ): String = bambuLoginOnce(JSONObject().put("account", email).put("code", code))

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
        val text =
            try {
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()
                    ?.use { it.readText() } ?: ""
            } finally {
                conn.disconnect()
            }
        android.util.Log.i("MakerWorld", "bambu login (encrypted=${body.opt("encrypted")}) -> ${text.take(200)}")
        val json =
            try {
                JSONObject(text)
            } catch (e: org.json.JSONException) {
                throw IOException("Bambu sign-in returned an unexpected response.")
            }
        json.optString("accessToken", "").takeIf { it.isNotBlank() }?.let { return it }
        val loginType = json.optString("loginType", "")
        if (loginType == "verifyCode") {
            throw BambuVerificationRequired("A sign-in code was emailed to your address.")
        }
        throw IOException(
            json.optString("error", "Bambu sign-in failed. Check the email and password.").ifBlank { "Bambu sign-in failed." },
        )
    }

    /** AES-128-CBC with the Bambu gateway's fixed key/IV (as used by Bambu Studio). */
    private fun bambuEncrypt(
        password: String,
        keyStr: String = "b47f7652e530d66a",
    ): String {
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
    fun downloadUrl(
        ref: String,
        token: String?,
    ): String {
        android.util.Log.i("MakerWorld", "presign ref=$ref token=${if (token.isNullOrBlank()) "none" else "len" + token.length}")
        if (token.isNullOrBlank()) {
            throw IOException("Sign in with your Bambu Lab account below to download from MakerWorld.")
        }
        // Two reference forms, because MakerWorld moved the 3mf off the profile record:
        //   instance/{id} → design-service f3mf route: {"name":…, "url":"https://…bblmw.com/…
        //                   {uuid}.3mf?at=…&exp=…&key=…"} — the signed link the site itself uses.
        //   profile/{pid}/{modelId} → the older iot-service route (documented by OpenBambuAPI),
        //                   which now answers "url": "" for files in the newer layout. Reading only
        //                   that empty string is what made every searchable model fail with
        //                   "MakerWorld returned no download link for this file."
        val url =
            when {
                ref.startsWith("instance/") -> {
                    "$GATEWAY/instance/${ref.removePrefix("instance/")}/f3mf?type=download"
                }

                ref.startsWith("profile/") -> {
                    val parts = ref.removePrefix("profile/").split("/")
                    if (parts.size < 2) throw IOException("Bad MakerWorld file reference.")
                    "https://api.bambulab.com/v1/iot-service/api/user/profile/${parts[0]}" +
                        "?model_id=" + URLEncoder.encode(parts[1], "UTF-8")
                }

                else -> {
                    throw IOException("Bad MakerWorld file reference.")
                }
            }
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
        val text =
            try {
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
    private fun jsonUnescape(s: String): String =
        try {
            org.json.JSONTokener("\"$s\"").nextValue() as String
        } catch (e: Exception) {
            s
        }
}
