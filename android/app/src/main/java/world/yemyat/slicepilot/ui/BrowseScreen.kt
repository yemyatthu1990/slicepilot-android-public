package world.yemyat.slicepilot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
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
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import world.yemyat.slicepilot.repo.ModelBrowser
import world.yemyat.slicepilot.repo.ModelDetail
import world.yemyat.slicepilot.repo.ModelFile
import world.yemyat.slicepilot.repo.ModelListing
import world.yemyat.slicepilot.repo.ModelSource
import world.yemyat.slicepilot.repo.SourceSection
import world.yemyat.slicepilot.ui.theme.Blue600
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: AppViewModel, browser: ModelBrowser) {
    val st by browser.state.collectAsState()
    var expandedSource by remember { mutableStateOf<ModelSource?>(null) }
    val focusManager = LocalFocusManager.current

    // Empty search bar shows each site's popular models.
    LaunchedEffect(Unit) { browser.loadHome() }

    if (ModelDetailHost(vm, browser)) return

    // "See more" full list for one source.
    expandedSource?.let { src ->
        val section = st.sections.firstOrNull { it.source == src }
        if (section == null) {
            expandedSource = null
        } else {
            SourceListScreen(
                section = section,
                onBack = { expandedSource = null },
                onOpen = { browser.openDetail(it) },
            )
            return
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find models", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.backHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = st.query,
                    onValueChange = { browser.setQuery(it) },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Search all sites…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            focusManager.clearFocus()
                            browser.search()
                        },
                    ),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                FilledIconButton(
                    onClick = { browser.search() },
                    enabled = st.query.isNotBlank(),
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(Icons.Filled.Search, "Search")
                }
            }

            if (browser.thingiverseToken.isBlank()) {
                ThingiverseTokenField(browser)
            }

            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (st.sections.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        when {
                            st.hasSearched -> "No results."
                            st.isHomeFeed -> "Couldn't load popular models. Search above."
                            else -> "Search Printables, Thingiverse, Nexprint and MakerWorld without leaving the app."
                        },
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(20.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(st.sections, key = { it.source.id }) { section ->
                        SourceSectionBlock(
                            section = section,
                            popular = st.isHomeFeed,
                            onOpen = { browser.openDetail(it) },
                            onSeeMore = { expandedSource = section.source },
                        )
                    }
                }
            }
        }
    }
}

/**
 * Full-screen model detail overlay driven by [ModelBrowser] state.
 * Draws itself when a detail is open (or loading) and handles the back gesture.
 * Returns true when it is showing, so hosts can skip their own content.
 */
@Composable
fun ModelDetailHost(vm: AppViewModel, browser: ModelBrowser): Boolean {
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
        var nexprintSigningIn by rememberSaveable { mutableStateOf(false) }
        ModelDetailScreen(
            detail = detail,
            downloadingFile = st.downloadingFile,
            progress = st.downloadProgress,
            bambuSignedIn = st.bambuSignedIn,
            bambuSigningIn = st.bambuSigningIn,
            bambuNeedsCode = st.bambuNeedsCode,
            nexprintSignedIn = vm.cloudLoggedIn,
            nexprintSigningIn = nexprintSigningIn,
            onBack = { browser.closeDetail() },
            onDownload = { file ->
                browser.download(file) { path ->
                    browser.closeDetail()
                    vm.requestLoadModel(path)
                }
            },
            onBambuSignIn = { email, pass -> browser.bambuLogin(email, pass, onDone = {}) },
            onBambuVerify = { code -> browser.bambuVerify(code, onDone = {}) },
            onNexprintSignIn = { email, pass ->
                nexprintSigningIn = true
                vm.nativeLogin(email, pass) { nexprintSigningIn = false }
            },
        )
        return true
    }
    return false
}

@Composable
private fun SourceSectionBlock(
    section: SourceSection,
    popular: Boolean,
    onOpen: (ModelListing) -> Unit,
    onSeeMore: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (popular) "Popular on ${section.source.label}" else section.source.label,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.weight(1f))
            if (!section.loading && section.results.size > 4) {
                TextButton(onClick = onSeeMore) { Text("See more") }
            }
        }
        if (section.loading) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
            ) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(
                    if (popular) "Loading ${section.source.label}…" else "Searching ${section.source.label}…",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            section.results.take(4).chunked(2).forEach { rowItems ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    rowItems.forEach { item ->
                        ModelCard(item, Modifier.weight(1f)) { onOpen(item) }
                    }
                    if (rowItems.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SourceListScreen(
    section: SourceSection,
    onBack: () -> Unit,
    onOpen: (ModelListing) -> Unit,
) {
    BackHandler(onBack = onBack)
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
        }
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(16.dp),
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            items(section.results, key = { it.id }) { item ->
                ModelCard(item) { onOpen(item) }
            }
        }
    }
}

@Composable
private fun ThingiverseTokenField(browser: ModelBrowser) {
    var token by remember { mutableStateOf("") }
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Button(onClick = { browser.setThingiverseToken(token) }) { Text("Save") }
            }
        }
    }
}

@Composable
internal fun ModelCard(item: ModelListing, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column {
            RemoteThumbnail(item.thumbnailUrl, Modifier.fillMaxWidth().height(120.dp))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    minLines = 2,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
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
    bambuSignedIn: Boolean,
    bambuSigningIn: Boolean,
    bambuNeedsCode: Boolean,
    nexprintSignedIn: Boolean,
    nexprintSigningIn: Boolean,
    onBack: () -> Unit,
    onDownload: (ModelFile) -> Unit,
    onBambuSignIn: (String, String) -> Unit,
    onBambuVerify: (String) -> Unit,
    onNexprintSignIn: (String, String) -> Unit,
) {
    val listing = detail.listing
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(listing.name, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        }
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .clip(RoundedCornerShape(20.dp)),
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
                                        Blue600,
                                        RoundedCornerShape(12.dp),
                                    )
                                    .clickable {
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
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        detail.description,
                        Modifier.padding(14.dp),
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            if (detail.listing.source == "makerworld" && !bambuSignedIn) {
                var email by rememberSaveable { mutableStateOf("") }
                var password by rememberSaveable { mutableStateOf("") }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "MakerWorld downloads need a free Bambu Lab account",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        if (bambuNeedsCode) {
                            var code by rememberSaveable { mutableStateOf("") }
                            OutlinedTextField(
                                value = code,
                                onValueChange = { code = it },
                                label = { Text("Verification code") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = KeyboardType.Number,
                                ),
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Button(
                                onClick = { onBambuVerify(code) },
                                enabled = !bambuSigningIn && code.isNotBlank(),
                            ) {
                                if (bambuSigningIn) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text("Verifying…")
                                } else {
                                    Text("Enter code")
                                }
                            }
                        } else {
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Bambu Lab email") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        var showPassword by rememberSaveable { mutableStateOf(false) }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onBambuSignIn(email, password) },
                            enabled = !bambuSigningIn && email.isNotBlank() && password.isNotBlank(),
                        ) {
                            if (bambuSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Signing in…")
                            } else {
                                Text("Sign in")
                            }
                        }
                        }
                    }
                }
            }

            if (detail.listing.source == "nexprint" && !nexprintSignedIn) {
                var email by rememberSaveable { mutableStateOf("") }
                var password by rememberSaveable { mutableStateOf("") }
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Nexprint downloads need a free Elegoo account",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                        OutlinedTextField(
                            value = email,
                            onValueChange = { email = it },
                            label = { Text("Elegoo email") },
                            singleLine = true,
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        var showPassword by rememberSaveable { mutableStateOf(false) }
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            singleLine = true,
                            visualTransformation = if (showPassword) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                            ),
                            trailingIcon = {
                                IconButton(onClick = { showPassword = !showPassword }) {
                                    Icon(
                                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                        contentDescription = if (showPassword) "Hide password" else "Show password",
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Button(
                            onClick = { onNexprintSignIn(email, password) },
                            enabled = !nexprintSigningIn && email.isNotBlank() && password.isNotBlank(),
                        ) {
                            if (nexprintSigningIn) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Signing in…")
                            } else {
                                Text("Sign in")
                            }
                        }
                    }
                }
            }

            Text(
                "Download & slice",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (detail.files.isEmpty()) {
                Text(
                    "No downloadable STL/3MF files found for this model.",
                    Modifier.padding(vertical = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                detail.files.forEach { file ->
                    val isDownloading = downloadingFile == file.name
                    Card(
                        shape = RoundedCornerShape(14.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(file.name, fontSize = 14.sp)
                                file.sizeBytes?.let {
                                    Text(
                                        String.format("%.1f MB", it / 1024.0 / 1024.0),
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (isDownloading) {
                                    LinearProgressIndicator(
                                        progress = { progress / 100f },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 6.dp)
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = Blue600,
                                    )
                                }
                            }
                            FilledIconButton(
                                onClick = { onDownload(file) },
                                enabled = downloadingFile == null,
                            ) {
                                Icon(Icons.Filled.Download, "Download")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// --- Tiny remote-image loader with an in-memory cache (no external deps) ---

/** Throttle image fetch/decode so fast scrolling doesn't flood the IO pool. */
private val imageDispatcher = Dispatchers.IO.limitedParallelism(4)

/** Memory-bounded bitmap cache (quarter of the app heap), keyed by decode size + url. */
private val thumbnailCache = object : android.util.LruCache<String, ImageBitmap>(
    (Runtime.getRuntime().maxMemory() / 4).toInt(),
) {
    override fun sizeOf(key: String, value: ImageBitmap): Int =
        (value as? android.graphics.Bitmap)?.byteCount ?: 0
}

/** Fetches and decodes an image, downsampled so its larger side is around [maxDim] pixels. */
private fun loadBitmap(url: String, maxDim: Int): ImageBitmap? {
    val key = "$maxDim|$url"
    thumbnailCache.get(key)?.let { return it }
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 SlicePilot/1.0")
        try {
            if (conn.responseCode !in 200..299) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= maxDim && bounds.outHeight / (sample * 2) >= maxDim) {
                sample *= 2
            }
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
                ?: return null
            thumbnailCache.put(key, bmp.asImageBitmap())
            bmp.asImageBitmap()
        } finally {
            conn.disconnect()
        }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun RemoteThumbnail(
    url: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    maxDimPx: Int = 512,
) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url, maxDimPx) {
        image = url?.let { withContext(imageDispatcher) { loadBitmap(it, maxDimPx) } }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        image?.let {
            androidx.compose.foundation.Image(
                bitmap = it,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * App-wide top banner shown while a model file is downloading, so users
 * understand why download buttons are disabled while browsing elsewhere.
 */
@Composable
fun DownloadProgressBanner(name: String, progress: Int, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .windowInsetsPadding(
                WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
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
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
            )
        }
    }
}
