package world.yemyat.slicepilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
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
import world.yemyat.slicepilot.ui.theme.Blue600
import java.net.HttpURLConnection
import java.net.URL

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(vm: AppViewModel, browser: ModelBrowser) {
    val st by browser.state.collectAsState()

    val detail = st.detail
    androidx.activity.compose.BackHandler(enabled = detail != null || st.loadingDetail) {
        browser.closeDetail()
    }
    if (st.loadingDetail) {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        return
    }
    if (detail != null) {
        ModelDetailScreen(
            detail = detail,
            downloadingFile = st.downloadingFile,
            progress = st.downloadProgress,
            onBack = { browser.closeDetail() },
            onDownload = { file ->
                browser.download(file) { path ->
                    browser.closeDetail()
                    vm.requestLoadModel(path)
                }
            },
        )
        return
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
                    placeholder = { Text("Search models…") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                )
                FilledIconButton(
                    onClick = { browser.search() },
                    enabled = !st.searching && st.query.isNotBlank(),
                    modifier = Modifier.size(56.dp),
                ) {
                    if (st.searching) {
                        CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Search, "Search")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ModelSource.entries.forEach { src ->
                    FilterChip(
                        selected = st.source == src,
                        onClick = { browser.setSource(src) },
                        label = { Text(src.label) },
                    )
                }
            }

            if (st.source == ModelSource.THINGIVERSE && browser.thingiverseToken.isBlank()) {
                ThingiverseTokenField(browser)
            }

            st.error?.let {
                Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp)
            }

            if (st.searching && st.results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (st.results.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (st.query.isBlank()) "Search Printables or Thingiverse without leaving the app."
                        else "No results.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    contentPadding = PaddingValues(bottom = 24.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(st.results, key = { it.source + it.id }) { item ->
                        ModelCard(item) { browser.openDetail(item) }
                    }
                }
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
private fun ModelCard(item: ModelListing, onClick: () -> Unit) {
    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Column {
            RemoteThumbnail(item.thumbnailUrl, Modifier.fillMaxWidth().height(120.dp))
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    item.name,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
                item.author?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                item.downloads?.let {
                    Text("⬇ $it", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
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
    onBack: () -> Unit,
    onDownload: (ModelFile) -> Unit,
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
                    RemoteThumbnail(detail.images[page], Modifier.fillMaxSize())
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
                                Text(file.name, fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
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

private val thumbnailCache = android.util.LruCache<String, ImageBitmap>(24)

private fun loadBitmap(url: String): ImageBitmap? {
    thumbnailCache.get(url)?.let { return it }
    return try {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 SlicePilot/1.0")
        try {
            if (conn.responseCode !in 200..299) return null
            val bmp = conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) } ?: return null
            thumbnailCache.put(url, bmp.asImageBitmap())
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
) {
    var image by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        image = url?.let { withContext(Dispatchers.IO) { loadBitmap(it) } }
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
