package world.yemyat.slicepilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.*
import androidx.compose.material3.IconButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import world.yemyat.slicepilot.repo.ModelBrowser
import world.yemyat.slicepilot.repo.ModelComment
import world.yemyat.slicepilot.repo.ModelDetail
import world.yemyat.slicepilot.repo.ModelFile
import world.yemyat.slicepilot.repo.ModelListing
import world.yemyat.slicepilot.repo.ModelSource
import world.yemyat.slicepilot.repo.SourceSection
import world.yemyat.slicepilot.ui.components.WavyDots
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.BrandIconButton
import world.yemyat.slicepilot.ui.theme.BrandSectionLabel
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.Space
import world.yemyat.slicepilot.ui.theme.brandPrimaryGradient
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelFindFeed(
    browser: ModelBrowser,
    onSeeMore: (ModelSource) -> Unit,
) {
    val st by browser.state.collectAsState()
    // Listings the user kept ("My models") — cards show a filled star badge.
    val favEntries by browser.favorites.entries.collectAsState()
    val favListings =
        remember(favEntries) {
            favEntries
                .mapNotNull { e ->
                    e.listingId.takeIf { it.isNotBlank() }?.let { "${e.source}|$it" }
                }.toSet()
        }
    val focusManager = LocalFocusManager.current

    // Empty search bar shows each site's popular models.
    LaunchedEffect(Unit) { browser.loadHome() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = st.query,
                onValueChange = { browser.setQuery(it) },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Search all sites…") },
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions =
                    KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            browser.search()
                        },
                    ),
                leadingIcon = { Icon(Icons.Filled.Search, null) },
            )
            BrandIconButton(
                icon = Icons.Filled.Search,
                contentDescription = "Search",
                onClick = { browser.search() },
                size = ButtonSize.Large,
                role = ButtonRole.Primary,
                enabled = st.query.isNotBlank(),
            )
        }

        if (browser.thingiverseToken.isBlank()) {
            ThingiverseTokenField(browser)
        }

        st.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
        }

        // One loading state for the whole feed, never one per source: each site used to draw
        // its own spinner under its own header, so a cold start ran four animations at once
        // on an otherwise empty page. Sources that are still in flight render nothing; the
        // placeholder below covers the empty feed, and a single line reports what is left once
        // some results are already on screen.
        val loadingSections = st.sections.filter { it.loading }
        val readySections = st.sections.filterNot { it.loading }
        // The first frame happens before loadHome() has created its sections; without this
        // the page flashes its "search these sites" hint and then jumps to the loaders.
        val starting = !st.hasSearched && !st.isHomeFeed && st.sections.isEmpty() && st.error == null

        when {
            readySections.isEmpty() && (loadingSections.isNotEmpty() || starting) -> {
                FeedLoadingCard(popular = st.isHomeFeed)
            }

            readySections.isEmpty() -> {
                Text(
                    when {
                        st.hasSearched -> {
                            "No results."
                        }

                        st.isHomeFeed -> {
                            "Couldn't load popular models. Search above."
                        }

                        else -> {
                            "Search Printables, Thingiverse, Nexprint and MakerWorld without leaving the app."
                        }
                    },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Space.lg),
                )
            }

            else -> {
                readySections.forEach { section ->
                    SourceSectionBlock(
                        section = section,
                        popular = st.isHomeFeed,
                        favoritedListings = favListings,
                        onOpen = { browser.openDetail(it) },
                        onSeeMore = { onSeeMore(section.source) },
                    )
                }
                if (loadingSections.isNotEmpty()) {
                    FeedStillLoading(loadingSections.map { it.source.label })
                }
            }
        }
    }
}

/**
 * The feed's only loading animation: one card saying what is on its way, drawn until the
 * first source answers. It is deliberately the shared [WavyDots] gesture rather than a
 * spinner, so the wait reads as the same "content will replace this" as every other fetch
 * in the app.
 */
@Composable
private fun FeedLoadingCard(popular: Boolean) {
    BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.lg) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            WavyDots()
            Spacer(Modifier.width(Space.xs))
            Text(
                if (popular) "Loading popular models…" else "Searching every site…",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Sources still answering once results are on screen — named, so the wait is specific. */
@Composable
private fun FeedStillLoading(sources: List<String>) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
        modifier = Modifier.fillMaxWidth().padding(top = Space.xs, bottom = Space.sm),
    ) {
        WavyDots(dotSize = 6.dp, waveHeight = 3.dp)
        Spacer(Modifier.width(4.dp))
        Text(
            "Still loading ${sources.joinToString(", ")}…",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Full-screen model detail overlay driven by [ModelBrowser] state.
 * Draws itself when a detail is open (or loading) and handles the back gesture.
 * Returns true when it is showing, so hosts can skip their own content.
 */
@Composable
fun ModelDetailHost(
    vm: AppViewModel,
    browser: ModelBrowser,
): Boolean {
    val st by browser.state.collectAsState()
    val detail = st.detail
    val active = detail != null || st.loadingDetail
    BackHandler(enabled = active) { browser.closeDetail() }
    if (st.loadingDetail) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return true
    }
    if (detail != null) {
        val favEntries by browser.favorites.entries.collectAsState()
        ModelDetailScreen(
            detail = detail,
            downloadingFile = st.downloadingFile,
            favoritingFile = st.favoritingFile,
            favoritedKeys = favEntries.map { it.key }.toSet(),
            fileKey = { f -> browser.favoriteKey(f) },
            onFavorite = { f -> browser.favoriteFile(detail, f) {} },
            onUnfavorite = { key -> browser.unfavoriteFile(key) },
            progress = st.downloadProgress,
            error = st.error,
            bambuSignedIn = st.bambuSignedIn,
            bambuSigningIn = st.bambuSigningIn,
            bambuNeedsCode = st.bambuNeedsCode,
            bambuPendingEmail = st.bambuPendingEmail,
            nexprintSignedIn = vm.cloudLoggedIn,
            onBack = { browser.closeDetail() },
            onDownload = { file ->
                browser.download(file) { path ->
                    browser.closeDetail()
                    vm.requestLoadModel(path)
                }
            },
            onBambuSignIn = { email, pass, onDone -> browser.bambuLogin(email, pass, onDone) },
            onBambuVerify = { code, onDone -> browser.bambuVerify(code, onDone) },
            onBambuUseAnotherAccount = { browser.bambuCancelVerification() },
            onNexprintSignIn = { email, pass, onResult ->
                vm.nativeLogin(email, pass) { ok ->
                    onResult(if (ok) null else vm.cloudMessage.value)
                }
            },
            commentsLoading = st.commentsLoading,
            commentsLoadingMore = st.commentsLoadingMore,
            commentsExhausted = st.commentsExhausted,
            commentsMoreError = st.commentsMoreError,
            onLoadMoreComments = { browser.loadMoreComments() },
        )
        return true
    }
    return false
}

@Composable
private fun SourceSectionBlock(
    favoritedListings: Set<String> = emptySet(),
    section: SourceSection,
    popular: Boolean,
    onOpen: (ModelListing) -> Unit,
    onSeeMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandSectionLabel(
                if (popular) "Popular on ${section.source.label}" else section.source.label,
            )
            Spacer(Modifier.weight(1f))
            if (section.results.size > 4) {
                TextButton(onClick = onSeeMore) { Text("See more") }
            }
        }
        // A source still fetching is simply absent: the feed owns the one loading animation
        // (see ModelFindFeed), so a half-arrived search never shows a spinner per site.
        section.results.take(4).chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                rowItems.forEach { item ->
                    ModelCard(
                        item,
                        Modifier.weight(1f),
                        favorited = "${item.source}|${item.id}" in favoritedListings,
                    ) { onOpen(item) }
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One source's full list, drawn over the host page ("See more" from the feed).
 * Infinite scrolls and closes with the back arrow or the back gesture.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceListOverlay(
    section: SourceSection,
    onBack: () -> Unit,
    favoritedListings: Set<String> = emptySet(),
    onLoadMore: () -> Unit = {},
    onOpen: (ModelListing) -> Unit,
) {
    BackHandler(onBack = onBack)
    // Infinite scroll: near the grid's end, fetch the next page.
    val gridState = rememberLazyGridState()
    LaunchedEffect(gridState, section.hasMore, section.loadingMore, section.loading) {
        snapshotFlow { gridState.layoutInfo }.collect { info ->
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            if (section.hasMore && !section.loadingMore && !section.loading &&
                info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 4
            ) {
                onLoadMore()
            }
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(section.source.label, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(Space.md),
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize(),
        ) {
            items(section.results, key = { it.id }) { item ->
                ModelCard(
                    item,
                    favorited = "${item.source}|${item.id}" in favoritedListings,
                ) { onOpen(item) }
            }
            if (section.loadingMore) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().padding(20.dp),
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(Space.sm))
                        Text(
                            "Loading more…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ThingiverseTokenField(browser: ModelBrowser) {
    var token by remember { mutableStateOf("") }
    BrandCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = Space.md,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Thingiverse API token required",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Create a free app at thingiverse.com/developers, paste the Access Token here.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = token,
                    onValueChange = { token = it },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Access token") },
                    shape = RoundedCornerShape(12.dp),
                )
                BrandButton(
                    text = "Save",
                    onClick = { browser.setThingiverseToken(token) },
                    role = ButtonRole.Secondary,
                    height = ButtonSize.Small,
                )
            }
        }
    }
}

@Composable
internal fun ModelCard(
    item: ModelListing,
    modifier: Modifier = Modifier,
    favorited: Boolean = false,
    onClick: () -> Unit,
) {
    BrandCard(
        modifier = modifier.clickable(onClick = onClick),
        contentPadding = 0.dp,
    ) {
        Column {
            Box {
                RemoteThumbnail(item.thumbnailUrl, Modifier.fillMaxWidth().height(120.dp))
                if (favorited) {
                    Icon(
                        Icons.Filled.Star,
                        "In My models",
                        tint = LanternGold,
                        modifier =
                            Modifier
                                .align(Alignment.TopEnd)
                                .padding(Space.xs)
                                .size(22.dp)
                                .background(Color.Black.copy(alpha = 0.6f), CircleShape)
                                .padding(3.dp),
                    )
                }
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Always render author/downloads rows (empty when absent) so cards stay equal height.
                Text(
                    item.author?.takeIf { it.isNotBlank() } ?: " ",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Text(
                    item.downloads?.let { "⬇ $it" } ?: " ",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ModelDetailScreen(
    detail: ModelDetail,
    downloadingFile: String?,
    progress: Int,
    /** Last failure reported by the source (sign-in, download), shown in the dialogs. */
    error: String?,
    bambuSignedIn: Boolean,
    bambuSigningIn: Boolean,
    bambuNeedsCode: Boolean,
    /** The account Bambu Lab mailed the code to, named by the dialog's code step. */
    bambuPendingEmail: String,
    nexprintSignedIn: Boolean,
    onBack: () -> Unit,
    onDownload: (ModelFile) -> Unit,
    favoritingFile: String?,
    favoritedKeys: Set<String>,
    fileKey: (ModelFile) -> String,
    onFavorite: (ModelFile) -> Unit,
    onUnfavorite: (String) -> Unit,
    /** Signs in, then calls `onDone` so the file picker the user asked for can open. */
    onBambuSignIn: (email: String, password: String, onDone: () -> Unit) -> Unit,
    onBambuVerify: (code: String, onDone: () -> Unit) -> Unit,
    /** Drops a pending code so the MakerWorld dialog returns to its email + password step. */
    onBambuUseAnotherAccount: () -> Unit,
    /** Elegoo sign-in for Nexprint: null result means signed in, else the failure text. */
    onNexprintSignIn: (email: String, password: String, onResult: (String?) -> Unit) -> Unit,
    commentsLoading: Boolean,
    commentsLoadingMore: Boolean,
    /** The model's comments source has served its last page. */
    commentsExhausted: Boolean,
    commentsMoreError: String?,
    onLoadMoreComments: () -> Unit,
) {
    val listing = detail.listing

    // "Download & slice" is one button, like Home's "Local model" pill, instead of a list of
    // files: the files it offers live in a picker dialog, opened once the source's account
    // requirement is met. Printables and Thingiverse serve files anonymously; MakerWorld
    // wants a Bambu Lab account and Nexprint an Elegoo one, so those two show their sign-in
    // dialog first and the picker follows a successful sign-in.
    var pickingFile by remember(listing.source, listing.id) { mutableStateOf(false) }
    var signingInFor by remember(listing.source, listing.id) { mutableStateOf<ModelSource?>(null) }
    val downloading = downloadingFile != null
    val signedIn =
        when (listing.source) {
            ModelSource.MAKERWORLD.id -> bambuSignedIn
            ModelSource.NEXPRINT.id -> nexprintSignedIn
            else -> true
        }

    fun openFilePicker() {
        signingInFor = null
        pickingFile = true
    }

    fun startDownloadFlow() {
        when {
            // A download is already running and its progress lives in the picker.
            downloading -> pickingFile = true

            signedIn -> pickingFile = true

            // `signedIn` is false only for the two account-gated sources.
            else -> signingInFor = ModelSource.from(listing.source)
        }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        listing.name,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        // The detail page's one action, floating like Home's "Local model" pill: it asks the
        // source for a sign-in when the model needs an account, then offers the files.
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { startDownloadFlow() },
                shape = RoundedCornerShape(18.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                icon = {
                    if (downloading) {
                        CircularProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Filled.Download, null)
                    }
                },
                text = {
                    Text(
                        if (downloading) "Downloading $progress%" else "Download & slice",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            if (detail.images.isNotEmpty()) {
                val scope = rememberCoroutineScope()
                val pagerState = rememberPagerState { detail.images.size }
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(Radius.md)),
                ) { page ->
                    RemoteThumbnail(detail.images[page], Modifier.fillMaxSize(), maxDimPx = 1200)
                }
                if (detail.images.size > 1) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        detail.images.forEachIndexed { index, img ->
                            val selected = pagerState.currentPage == index
                            RemoteThumbnail(
                                img,
                                Modifier
                                    .size(90.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .border(
                                        if (selected) 2.dp else 0.dp,
                                        BrandCyan,
                                        RoundedCornerShape(12.dp),
                                    ).clickable {
                                        scope.launch { pagerState.animateScrollToPage(index) }
                                    },
                            )
                        }
                    }
                }
            }
            Text(listing.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                listing.author?.let {
                    Text("by $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                listing.downloads?.let { Text("⬇ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                detail.likes?.let { Text("♥ $it", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            detail.published?.let {
                Text("Published $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (detail.description.isNotBlank()) {
                DescriptionCard(detail)
            }

            if (detail.commentsTotal != null) {
                CommentsSection(
                    detail,
                    loadingFirstPage = commentsLoading,
                    loadingMore = commentsLoadingMore,
                    exhausted = commentsExhausted,
                    moreError = commentsMoreError,
                    onLoadMore = onLoadMoreComments,
                )
            }

            // Room for the floating download pill, which draws over the page's end.
            Spacer(Modifier.height(96.dp))
        }
    }

    if (pickingFile) {
        DownloadPickerDialog(
            files = detail.files,
            downloadingFile = downloadingFile,
            progress = progress,
            favoritingFile = favoritingFile,
            favoritedKeys = favoritedKeys,
            fileKey = fileKey,
            error = error,
            onPick = onDownload,
            onFavorite = onFavorite,
            onUnfavorite = onUnfavorite,
            onDismiss = { pickingFile = false },
        )
    }
    when (signingInFor) {
        ModelSource.MAKERWORLD -> {
            MakerworldSignInDialog(
                signingIn = bambuSigningIn,
                needsCode = bambuNeedsCode,
                pendingEmail = bambuPendingEmail,
                error = error,
                onSignIn = { mail, pass -> onBambuSignIn(mail, pass) { openFilePicker() } },
                onVerify = { code -> onBambuVerify(code) { openFilePicker() } },
                onUseAnotherAccount = onBambuUseAnotherAccount,
                onDismiss = { signingInFor = null },
            )
        }

        ModelSource.NEXPRINT -> {
            ElegooSignInDialog(
                onSignIn = onNexprintSignIn,
                onSignedIn = { openFilePicker() },
                onDismiss = { signingInFor = null },
            )
        }

        else -> {
            Unit
        }
    }
}

/**
 * The model's blurb, clamped to [CollapsedDescriptionLines] until tapped. The caller skips it
 * entirely when the source returned no description, so a model without a summary renders nothing
 * rather than an empty card.
 *
 * Every source feeds the same `ModelDetail.description` — MakerWorld's `modelDetail`, the
 * Thingiverse `description` + `details`, Printables' and Nexprint's `summary` — and MakerWorld
 * in particular returns thousands of characters, which used to push the comments section off the
 * bottom of the page. So the card shows a short preview plus "Show more", and the whole card is
 * the tap target: a six-line wall of text is easier to tap than a small button under it.
 *
 * The toggle only appears when the text is genuinely longer than the preview ([CollapsibleMinChars]
 * characters or more than [CollapsedDescriptionLines] lines), because Compose cannot report
 * "this was truncated" once `maxLines` caps the layout, and measuring the full text costs a second
 * layout pass. A one-line Printables summary therefore stays a plain card with no dead control.
 */
@Composable
private fun DescriptionCard(
    detail: ModelDetail,
    modifier: Modifier = Modifier,
) {
    val text = detail.description
    // Keyed on the model, so opening a different model starts collapsed again.
    var expanded by rememberSaveable(detail.listing.id) { mutableStateOf(false) }
    val collapsible =
        text.length > CollapsibleMinChars ||
            text.count { it == '\n' } + 1 > CollapsedDescriptionLines
    val showFull = expanded || !collapsible

    BrandCard(
        modifier =
            modifier
                .clickable(enabled = collapsible) { expanded = !expanded }
                .animateContentSize(),
        contentPadding = Space.md,
    ) {
        Column {
            Text(
                text,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = if (showFull) Int.MAX_VALUE else CollapsedDescriptionLines,
                overflow = if (showFull) TextOverflow.Clip else TextOverflow.Ellipsis,
            )
            if (collapsible) {
                Text(
                    if (expanded) "Show less" else "Show more",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/**
 * The character count above which a description is worth collapsing, and the line count shown
 * while collapsed. Character-based because sources pad descriptions with markup-derived newlines,
 * so a short MakerWorld blurb can already be several lines without being long.
 */
private const val CollapsibleMinChars = 400
private const val CollapsedDescriptionLines = 6

/**
 * The file picker behind "Download & slice": the model's downloadable files, where tapping a
 * row starts the download that feeds the slicer. The star still keeps a permanent copy in
 * "My models", so keeping and slicing stay separate choices.
 */
@Composable
private fun DownloadPickerDialog(
    files: List<ModelFile>,
    downloadingFile: String?,
    progress: Int,
    favoritingFile: String?,
    favoritedKeys: Set<String>,
    fileKey: (ModelFile) -> String,
    error: String?,
    onPick: (ModelFile) -> Unit,
    onFavorite: (ModelFile) -> Unit,
    onUnfavorite: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        // A download in flight owns the dialog until it lands (it closes the model page
        // when the file is handed to the slicer).
        onDismissRequest = { if (downloadingFile == null) onDismiss() },
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.md),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(Modifier.padding(vertical = Space.sm)) {
                Row(
                    Modifier.fillMaxWidth().padding(start = Space.md, end = Space.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Download & slice",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onDismiss, enabled = downloadingFile == null) {
                        Icon(Icons.Filled.Close, "Close")
                    }
                }
                if (files.isEmpty()) {
                    Text(
                        "No downloadable STL/3MF files found for this model.",
                        Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        files.forEach { file ->
                            val isDownloading = downloadingFile == file.name
                            // Star: keep a permanent copy in "My models" (private storage),
                            // distinct from downloading to slice now.
                            val favKey = fileKey(file)
                            val favorited = favKey in favoritedKeys
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable(enabled = downloadingFile == null) { onPick(file) }
                                    .padding(start = Space.md, end = Space.xs, top = 10.dp, bottom = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        file.name,
                                        fontSize = 14.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    Text(
                                        file.sizeBytes
                                            ?.let { "%.1f MB".format(it / 1024.0 / 1024.0) }
                                            ?: "Tap to slice",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    if (isDownloading) {
                                        LinearProgressIndicator(
                                            progress = { progress / 100f },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(top = 6.dp)
                                                    .height(6.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                            color = BrandCyan,
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = {
                                        if (favorited) onUnfavorite(favKey) else onFavorite(file)
                                    },
                                    enabled = downloadingFile == null && favoritingFile == null,
                                ) {
                                    if (favoritingFile == file.name) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp,
                                        )
                                    } else {
                                        Icon(
                                            if (favorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                            if (favorited) "Remove from My models" else "Keep in My models",
                                            tint =
                                                if (favorited) {
                                                    LanternGold
                                                } else {
                                                    MaterialTheme.colorScheme.onSurfaceVariant
                                                },
                                        )
                                    }
                                }
                                Icon(
                                    Icons.Filled.Download,
                                    "Download",
                                    tint =
                                        if (downloadingFile == null) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    modifier = Modifier.padding(start = Space.xs, end = Space.sm),
                                )
                            }
                        }
                    }
                }
                error?.let {
                    Text(
                        it,
                        Modifier.padding(horizontal = Space.md, vertical = Space.xs),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

/** The most digits Bambu Lab ever mails as a sign-in code, and the number that submits it. */
private const val BambuCodeDigits = 6

/**
 * The widest an account dialog gets. Past this (a tablet, a phone in landscape) a single field
 * stops reading as a field, so the dialog caps and stays centred instead of stretching edge to
 * edge across the window.
 */
private val AuthDialogMaxWidth = 460.dp

/** Which half of an account sign-in [SignInDialog] is drawing. */
private enum class SignInStage {
    /** Email and password. */
    Credentials,

    /** The code Bambu Lab mailed after accepting the password — MakerWorld only. */
    EmailedCode,
}

/**
 * MakerWorld's account wall, shown when "Download & slice" is tapped while signed out.
 *
 * Bambu Lab answers an accepted password with an emailed code rather than a token for every
 * account — 2FA is not optional on their cloud — so this is one window with two stages:
 * [SignInStage.Credentials] and [SignInStage.EmailedCode]. The stages swap the form rather than
 * appending to it. They used to stack: the moment the code field appeared it sat underneath a
 * password that had just been spent, which read as "type it again?", and the "a code was emailed"
 * line arrived in error red like a failure because that is the channel it travelled on (see
 * [ModelBrowser.bambuLogin]).
 */
@Composable
private fun MakerworldSignInDialog(
    signingIn: Boolean,
    needsCode: Boolean,
    /** The account the code went to, so the code stage can name it instead of guesswork. */
    pendingEmail: String,
    error: String?,
    onSignIn: (email: String, password: String) -> Unit,
    onVerify: (code: String) -> Unit,
    /** Leaves the code stage unused, to sign in as someone else. */
    onUseAnotherAccount: () -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var code by rememberSaveable { mutableStateOf("") }
    // The sign-in only reports failures through the shared error channel, so it is shown
    // once this dialog has actually asked for something.
    var attempted by rememberSaveable { mutableStateOf(false) }

    SignInDialog(
        title = if (needsCode) "Check your email" else "Sign in to MakerWorld",
        mark = if (needsCode) Icons.Filled.Email else Icons.Filled.Person,
        message =
            if (needsCode) {
                makerworldCodeMessage(pendingEmail)
            } else {
                "MakerWorld downloads need a free Bambu Lab account."
            },
        stage = if (needsCode) SignInStage.EmailedCode else SignInStage.Credentials,
        emailLabel = "Bambu Lab email",
        email = email,
        onEmail = { email = it },
        password = password,
        onPassword = { password = it },
        busy = signingIn,
        // A failure only after an attempt: the shared channel can still be holding an older
        // one (a failed search), which used to greet the user as a red line on an untouched form.
        error = error?.takeIf { attempted || needsCode },
        submitLabel = if (needsCode) "Verify code" else "Sign in",
        canSubmit =
            if (needsCode) {
                code.isNotBlank()
            } else {
                email.isNotBlank() && password.isNotBlank()
            },
        onSubmit = {
            attempted = true
            if (needsCode) onVerify(code) else onSignIn(email, password)
        },
        onDismiss = onDismiss,
        code = code,
        onCode = { next ->
            code = next
            // The sixth digit is a complete answer, so it submits itself. Reading the code in
            // the mail app, coming back here, typing it, and only then finding the button is the
            // slowest thing about this flow. Triggering from the edit rather than from a
            // LaunchedEffect on `code` is deliberate: a wrong code leaves the field as it was and
            // flips `signingIn` back to false, and an effect keyed on that would resubmit the
            // same wrong code forever.
            if (next.length == BambuCodeDigits && !signingIn) {
                attempted = true
                onVerify(next)
            }
        },
        onBackToCredentials = if (needsCode) onUseAnotherAccount else null,
    )
}

/** What the code stage says, naming the address the code went to whenever we still know it. */
private fun makerworldCodeMessage(pendingEmail: String): String =
    if (pendingEmail.isBlank()) {
        "Bambu Lab has emailed a $BambuCodeDigits-digit sign-in code to your account. Enter it below."
    } else {
        "Bambu Lab has emailed a $BambuCodeDigits-digit sign-in code to $pendingEmail. Enter it below."
    }

/**
 * Nexprint's account wall: Elegoo cloud credentials, the same ones cloud printing uses.
 * Drawn through the same [SignInDialog] as MakerWorld, so the two account walls are one size
 * and shape instead of one per file.
 */
@Composable
private fun ElegooSignInDialog(
    onSignIn: (email: String, password: String, onResult: (String?) -> Unit) -> Unit,
    onSignedIn: () -> Unit,
    onDismiss: () -> Unit,
) {
    var email by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    SignInDialog(
        title = "Sign in to Nexprint",
        mark = Icons.Filled.Person,
        message = "Nexprint downloads need a free Elegoo account.",
        stage = SignInStage.Credentials,
        emailLabel = "Elegoo email",
        email = email,
        onEmail = { email = it },
        password = password,
        onPassword = { password = it },
        busy = busy,
        error = failure,
        submitLabel = "Sign in",
        canSubmit = email.isNotBlank() && password.isNotBlank(),
        onSubmit = {
            if (!busy) {
                busy = true
                failure = null
                onSignIn(email, password) { message ->
                    busy = false
                    if (message == null) onSignedIn() else failure = message
                }
            }
        },
        onDismiss = onDismiss,
    )
}

/**
 * The shared shell of the two account walls: source mark, title, close, the one line saying what
 * the source wants, the fields, the failure line, and the action.
 *
 * The size is what it used to get wrong. It was a bare `Dialog` with a wrap-content `Surface`,
 * and Android clamps a dialog without `usePlatformDefaultWidth = false` to the platform's narrow
 * width (~320 dp on a phone), so every field became a sliver, the password row crammed its label
 * against the eye icon, and MakerWorld's verification code — the one field people actually
 * struggle with — looked like an afterthought bolted underneath. The shell now sizes itself to
 * the window up to [AuthDialogMaxWidth] and scrolls when the keyboard eats the height, so the
 * same dialog works at every screen size and in landscape.
 *
 * Its shape, hairline and spacing come from the same tokens as every other surface in the app
 * ([BrandCard], [Space], [Radius]), and both MakerWorld stages plus Nexprint's single one draw
 * through it, so "the sign-in dialog" is one size in the app instead of three.
 *
 * Going full width also removes the notion of tapping "outside" to close it — the window *is* the
 * screen, exactly as in [AllCommentsDialog] and the QR scanner — so closing means the ✕ or Back.
 * For this dialog that is the safer half of the trade: an errant tap used to be able to throw away
 * a password that had just been typed.
 */
@Composable
private fun SignInDialog(
    title: String,
    /** The header glyph: a person for credentials, an envelope for the emailed code. */
    mark: ImageVector,
    message: String,
    stage: SignInStage,
    emailLabel: String,
    email: String,
    onEmail: (String) -> Unit,
    password: String,
    onPassword: (String) -> Unit,
    busy: Boolean,
    /** A failure from the source, drawn under the fields in the error colour. */
    error: String?,
    submitLabel: String,
    canSubmit: Boolean,
    /** The dialog's one action, whose meaning follows [stage]. */
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
    /** MakerWorld's typed code; only read in [SignInStage.EmailedCode]. */
    code: String = "",
    onCode: (String) -> Unit = {},
    /** Offered in the code stage to return to the credentials form. */
    onBackToCredentials: (() -> Unit)? = null,
) {
    var showPassword by rememberSaveable { mutableStateOf(false) }
    // One gate for every way the form can be submitted — the button, the keyboard's Done key,
    // and the code field's auto-submit — so `canSubmit` cannot be bypassed by one of them.
    val submit: () -> Unit = { if (!busy && canSubmit) onSubmit() }

    androidx.compose.ui.window.Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        // The reason the old dialog was a sliver: without this the window keeps the platform's
        // narrow dialog width.
        properties =
            androidx.compose.ui.window
                .DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // fillMaxSize + verticalScroll (in that order) keeps the card centred in whatever the
        // window has left — with the keyboard open, in landscape — and scrolls it instead of
        // clipping when the content is taller than that.
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.lg, vertical = Space.md),
            contentAlignment = Alignment.Center,
        ) {
            BrandCard(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .widthIn(max = AuthDialogMaxWidth),
                contentPadding = Space.lg,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // The same gradient tile the wordmark and ConnectPrinterDialog use, so an
                        // account wall looks like it belongs to this app before it says whose
                        // account it is.
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(brandPrimaryGradient),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = mark,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                                tint = Color.White,
                            )
                        }
                        Text(
                            title,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleLarge,
                            // Inside a Dialog the local content colour resolves to the window's
                            // own theme, which painted ink-on-ink in the dark theme — the same
                            // trap ConnectPrinterDialog documents.
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        IconButton(onClick = onDismiss, enabled = !busy) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    Text(
                        message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    when (stage) {
                        SignInStage.EmailedCode -> {
                            AuthField(
                                value = code,
                                // Digits only and capped at the real length: copying a code out of
                                // a mail app usually drags a space or a line break with it.
                                onValueChange = { raw ->
                                    onCode(raw.filter { it.isDigit() }.take(BambuCodeDigits))
                                },
                                label = "Verification code",
                                keyboardType = KeyboardType.Number,
                                onSubmit = submit,
                                textStyle =
                                    TextStyle(
                                        fontSize = 20.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        letterSpacing = 8.sp,
                                        textAlign = TextAlign.Center,
                                    ),
                            )
                        }

                        SignInStage.Credentials -> {
                            AuthField(
                                value = email,
                                onValueChange = onEmail,
                                label = emailLabel,
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next,
                                onSubmit = submit,
                            )
                            AuthField(
                                value = password,
                                onValueChange = onPassword,
                                label = "Password",
                                keyboardType = KeyboardType.Password,
                                onSubmit = submit,
                                visualTransformation =
                                    if (showPassword) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                trailingIcon = {
                                    IconButton(onClick = { showPassword = !showPassword }) {
                                        Icon(
                                            imageVector =
                                                if (showPassword) {
                                                    Icons.Filled.VisibilityOff
                                                } else {
                                                    Icons.Filled.Visibility
                                                },
                                            contentDescription =
                                                if (showPassword) {
                                                    "Hide password"
                                                } else {
                                                    "Show password"
                                                },
                                            tint = MaterialTheme.colorScheme.onSurface,
                                        )
                                    }
                                },
                            )
                        }
                    }

                    error?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    BrandButton(
                        text = submitLabel,
                        onClick = submit,
                        modifier = Modifier.fillMaxWidth(),
                        role = ButtonRole.Primary,
                        enabled = !busy && canSubmit,
                        loading = busy,
                        height = ButtonSize.Medium,
                    )

                    onBackToCredentials?.let { back ->
                        TextButton(
                            onClick = back,
                            enabled = !busy,
                            modifier = Modifier.align(Alignment.CenterHorizontally),
                        ) {
                            Text(
                                "Use a different account",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * One labelled input at the size every other field in the app uses — the app's 14 dp corner, the
 * same width as the search field — with the keyboard's action wired to the dialog's own action.
 * The fields this replaces declared no `keyboardActions` at all, so the keyboard's "next" and
 * "done" keys did nothing and the only way forward was to find the button.
 */
@Composable
private fun AuthField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    keyboardType: KeyboardType,
    onSubmit: () -> Unit,
    imeAction: ImeAction = ImeAction.Done,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    trailingIcon: (@Composable () -> Unit)? = null,
    textStyle: TextStyle = TextStyle.Default,
) {
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        textStyle = textStyle,
        singleLine = true,
        shape = RoundedCornerShape(Radius.sm),
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType, imeAction = imeAction),
        keyboardActions =
            if (imeAction == ImeAction.Next) {
                KeyboardActions(onNext = { focusManager.moveFocus(FocusDirection.Next) })
            } else {
                KeyboardActions(onDone = { onSubmit() })
            },
        visualTransformation = visualTransformation,
        trailingIcon = trailingIcon,
    )
}

// Remote images are loaded by RemoteImage.kt — one shared HTTP client, a memory cache, a byte
// cache on disk and CDN-side resizing. The inline per-URL loader that used to sit here is
// gone; BrowseScreen only renders the bitmaps that loader produces.

/**
 * Community comments of a model. A source that cannot report them leaves
 * `ModelDetail.commentsTotal` null, so the section is not rendered at all;
 * an empty list renders "No comments yet" instead — while a fetch is in flight it shows
 * [WavyDots] instead of either, so a model is never called empty mid-load.
 *
 * The header never shows a count: sources count differently (some include replies,
 * some keep deleted comments, some serve fewer than they advertise), so no single
 * number is honest everywhere. The list itself is the truth. "Show more" opens
 * [AllCommentsDialog] rather than expanding the card inline.
 */
@Composable
private fun CommentsSection(
    detail: ModelDetail,
    loadingFirstPage: Boolean,
    loadingMore: Boolean,
    /** The source has served every comment it has; no "Load more" is offered. */
    exhausted: Boolean,
    moreError: String?,
    onLoadMore: () -> Unit,
) {
    // All fetched comments, shown in a dialog instead of expanding the card in place.
    var showAll by remember(detail.listing.id) { mutableStateOf(false) }
    // The dialog list's scroll position, kept here so loading another page never resets it.
    val dialogScroll = remember(detail.listing.id) { ScrollState(0) }
    val all = detail.comments
    // Comments previewed in the card before the "Show more" action.
    val collapsedCount = 3
    val siteTotal = detail.commentsTotal ?: all.size
    // Another page the source can still serve: while the site counts more than we hold,
    // and until a fetch proves it has nothing left (see ModelBrowser's thread completion).
    val hasMore = !exhausted && (all.size + all.sumOf { it.replies.size }) < siteTotal
    // Any comments request of this model in flight, first page or next page.
    val fetching = loadingFirstPage || loadingMore

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrandSectionLabel("Comments")
        if (all.isEmpty()) {
            if (fetching) {
                // The text is on its way — dots, never a premature "No comments yet.".
                BrandCard(contentPadding = 14.dp) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        WavyDots()
                        Text(
                            "Loading comments\u2026",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            } else if (siteTotal > 0 && moreError != null) {
                // The site counts comments we could not fetch — that is a failure, not an
                // empty thread, and tapping asks for the first page again.
                BrandCard(contentPadding = 14.dp) {
                    TextButton(
                        onClick = onLoadMore,
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(0.dp),
                    ) {
                        Text(
                            "Couldn't load comments \u2014 tap to retry",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            } else {
                BrandCard(contentPadding = 14.dp) {
                    Text(
                        "No comments yet.",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            BrandCard(contentPadding = 16.dp) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    all.take(collapsedCount).forEach { CommentRow(it) }
                    if (all.size > collapsedCount) {
                        TextButton(onClick = { showAll = true }) {
                            Text("Show more", fontSize = 13.sp)
                        }
                    } else if (hasMore) {
                        // Nothing hidden on the card, but the source still has more: offer the
                        // next page right here — a dialog of the same comments would be empty.
                        CommentsMoreRow(
                            loadingMore = loadingMore,
                            moreError = moreError,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
            if (showAll) {
                AllCommentsDialog(
                    detail = detail,
                    listScroll = dialogScroll,
                    hasMore = hasMore,
                    loadingMore = loadingMore,
                    moreError = moreError,
                    onLoadMore = onLoadMore,
                    onDismiss = { showAll = false },
                )
            }
        }
    }
}

/** The next-page offer — dots while fetching, a button otherwise, plus the failure line. */
@Composable
private fun CommentsMoreRow(
    loadingMore: Boolean,
    moreError: String?,
    onLoadMore: () -> Unit,
) {
    if (loadingMore) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WavyDots()
            Text(
                "Loading more comments\u2026",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        TextButton(onClick = onLoadMore) {
            Text("Load more comments", fontSize = 13.sp)
        }
    }
    moreError?.let {
        Text(
            "Couldn't load more comments: $it",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.error,
        )
    }
}

/**
 * Every fetched comment of the model on a full screen — the card's "Show more" opens
 * this instead of expanding in place, so long threads read page by page like the sites
 * themselves. Its foot offers the next page while the source has one.
 */
@Composable
private fun AllCommentsDialog(
    detail: ModelDetail,
    /** The list's scroll position, owned by [CommentsSection] — a scroll state remembered
     * inside this Dialog resets whenever [detail] replaces the captured content (loading
     * more comments would jump the reader back to the top). */
    listScroll: ScrollState,
    /** Whether the source still has a next page to offer at the foot. */
    hasMore: Boolean,
    loadingMore: Boolean,
    moreError: String?,
    onLoadMore: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        // Full width, but inside the system bars — the header must not sit under the status bar.
        properties =
            androidx.compose.ui.window
                .DialogProperties(
                    usePlatformDefaultWidth = false,
                ),
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Close",
                        )
                    }
                    Text(
                        "Comments",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(48.dp))
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(listScroll)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    detail.comments.forEach { CommentRow(it) }
                    // The thread's end: the next page, or silence when everything is here.
                    if (hasMore) {
                        CommentsMoreRow(
                            loadingMore = loadingMore,
                            moreError = moreError,
                            onLoadMore = onLoadMore,
                        )
                    }
                }
            }
        }
    }
}

/** One top-level comment, with its replies listed indented below the body. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CommentRow(comment: ModelComment) {
    // The open photo plus its siblings, so swiping pages through every picture the comment
    // carries (the header counts them 1/2, 2/2), tap or back closes it.
    var viewer by remember { mutableStateOf<Pair<List<String>, Int>?>(null) }
    viewer?.let { (photos, start) ->
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { viewer = null },
            properties =
                androidx.compose.ui.window
                    .DialogProperties(
                        usePlatformDefaultWidth = false,
                        decorFitsSystemWindows = false,
                    ),
        ) {
            val pager =
                androidx.compose.foundation.pager.rememberPagerState(
                    pageCount = { photos.size },
                    initialPage = start.coerceIn(0, photos.lastIndex),
                )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black),
            ) {
                androidx.compose.foundation.pager.HorizontalPager(
                    state = pager,
                    modifier = Modifier.fillMaxSize(),
                ) { page ->
                    RemoteThumbnail(
                        photos[page],
                        Modifier
                            .fillMaxSize()
                            .clickable(onClick = { viewer = null }),
                        contentScale = ContentScale.Fit,
                        maxDimPx = 1600,
                    )
                }
                if (photos.size > 1) {
                    Text(
                        "${pager.currentPage + 1}/${photos.size}",
                        modifier =
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 48.dp),
                        fontSize = 13.sp,
                        color = androidx.compose.ui.graphics.Color.White,
                    )
                }
            }
        }
    }
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CommentAvatar(comment.author, comment.avatarUrl, Modifier.size(28.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                comment.author,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            comment.createdAt?.let {
                Spacer(Modifier.width(8.dp))
                Text(it.take(10), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Text(
            comment.text,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(start = 36.dp, top = 3.dp),
        )
        // Photos the reviewer attached. Matrix shows three thumbs plus a "+N" tile, and a
        // Nexprint print review is often unreadable without them (some are photos only).
        if (comment.images.isNotEmpty()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                comment.images.take(3).forEach { url ->
                    RemoteThumbnail(
                        url,
                        Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .clickable { viewer = comment.images to comment.images.indexOf(url) },
                        maxDimPx = 192,
                    )
                }
                if (comment.images.size > 3) {
                    Box(
                        Modifier
                            .size(56.dp)
                            .clip(MaterialTheme.shapes.small)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .clickable { viewer = comment.images to 3 },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "+${comment.images.size - 3}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // The vote and answer counts Matrix prints under the body ("👍 0  💬 1"). Replies are
        // counted here and listed below, exactly like the site's thread page.
        if (comment.likeCount != null || comment.replyCount != null) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                comment.likeCount?.let {
                    Text(
                        "\ud83d\udc4d $it",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                comment.replyCount?.let {
                    Text(
                        "\ud83d\udcac $it",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        comment.replies.forEach { reply ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 36.dp, top = 6.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Text("↳", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
                Text(
                    reply.author,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    reply.text,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Round avatar, falling back to the author's initial when the site has no image. */
@Composable
private fun CommentAvatar(
    name: String,
    url: String?,
    modifier: Modifier = Modifier,
) {
    if (url != null) {
        RemoteThumbnail(url, modifier.clip(CircleShape), maxDimPx = 96)
    } else {
        Box(
            modifier =
                modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                name
                    .trim()
                    .firstOrNull()
                    ?.uppercaseChar()
                    ?.toString() ?: "?",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Every picture on this screen goes through the shared [RemoteImage] loader, which pools HTTP
 * connections, caches what it downloaded and asks the CDN for the size actually displayed.
 * The inline `loadBitmap` it used to live beside is now unused and scheduled for removal.
 */
@Composable
private fun RemoteThumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimPx: Int = 512,
) {
    RemoteImage(
        url = url,
        modifier = modifier,
        contentScale = contentScale,
        maxDimPx = maxDimPx,
    )
}

/**
 * App-wide top banner shown while a model file is downloading, so users
 * understand why download buttons are disabled while browsing elsewhere.
 */
@Composable
fun DownloadProgressBanner(
    name: String,
    progress: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier =
            modifier
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ).padding(horizontal = 16.dp, vertical = 8.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 6.dp,
        shadowElevation = 6.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Filled.Download,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    "Downloading",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    name,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "$progress%",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
