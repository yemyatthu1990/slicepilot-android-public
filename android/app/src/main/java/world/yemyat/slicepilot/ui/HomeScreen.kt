package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import world.yemyat.slicepilot.NativeLib
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.preview.ModelPreviewView
import world.yemyat.slicepilot.repo.ModelBrowser
import world.yemyat.slicepilot.repo.ModelSource
import world.yemyat.slicepilot.ui.components.SharedAndroidView
import world.yemyat.slicepilot.ui.theme.*

/**
 * Home: the state of the current project (printer, loaded model, live print) on
 * top, the model feed (search + one block per source) below it.
 *
 * Local files are the floating "Local model" pill: browsing is the page itself,
 * so the only action that needs to float is opening a file from this device.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPickLocalFile: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenConfigure: () -> Unit,
    browser: ModelBrowser,
) {
    val modelLoading by vm.modelLoading.collectAsState()
    val modelError by vm.modelError.collectAsState()
    val mErr = modelError
    val progress by vm.modelLoadProgress.collectAsState()
    val printStatus by vm.printStatus.collectAsState()
    val selectedPrinter by vm.selectedPrinterState.collectAsState()
    val modelPath = vm.modelPath
    val hasModel = modelPath != null
    val printerReady = selectedPrinter != null

    // "See more" from the feed draws one source's full list over the page.
    val browseState by browser.state.collectAsState()
    var expandedSource by remember { mutableStateOf<ModelSource?>(null) }
    val expanded = expandedSource?.let { src -> browseState.sections.firstOrNull { it.source == src } }
    // A new search replaces the sections: drop an overlay whose source went away.
    LaunchedEffect(expandedSource, expanded) {
        if (expandedSource != null && expanded == null) expandedSource = null
    }
    val favEntries by browser.favorites.entries.collectAsState()
    val favoritedListings =
        remember(favEntries) {
            favEntries
                .mapNotNull { e ->
                    e.listingId.takeIf { it.isNotBlank() }?.let { "${e.source}|$it" }
                }.toSet()
        }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            // Home draws under the status bar unless it pads for it itself — the
            // root Scaffold has contentWindowInsets = 0 (see MainActivity).
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            contentPadding =
                PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Print in progress (started by this app, e.g. before a swipe-away):
            // the monitor is restored at startup, so this banner shows live
            // status like the download bar. Tap opens the print monitor.
            if (printStatus?.live == true &&
                printStatus?.state in
                setOf(
                    world.yemyat.slicepilot.cloud.PrintJobState.CONNECTING,
                    world.yemyat.slicepilot.cloud.PrintJobState.PRINTING,
                    world.yemyat.slicepilot.cloud.PrintJobState.PAUSING,
                    world.yemyat.slicepilot.cloud.PrintJobState.PAUSED,
                    world.yemyat.slicepilot.cloud.PrintJobState.RESUMING,
                )
            ) {
                item {
                    printStatus?.let { st ->
                        PrintInProgressBanner(status = st, onClick = { vm.openPrintMonitor() })
                    }
                }
            }

            // Header: wordmark + actions, one compact row.
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BrandWordmark()
                    Spacer(Modifier.weight(1f))
                    val dark = LocalIsDarkTheme.current
                    BrandIconButton(
                        icon = Icons.Filled.Tune,
                        contentDescription = "Printer settings",
                        onClick = onOpenConfigure,
                        role = ButtonRole.Secondary,
                    )
                    BrandIconButton(
                        icon = if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                        contentDescription = if (dark) "Light mode" else "Dark mode",
                        onClick = { vm.toggleTheme() },
                        role = ButtonRole.Secondary,
                    )
                }
            }

            if (!NativeLib.isLoaded) {
                item {
                    val ctx = LocalContext.current
                    BigCard(
                        icon = {
                            Icon(
                                Icons.Filled.Warning,
                                null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(30.dp),
                            )
                        },
                        iconBg = MaterialTheme.colorScheme.errorContainer,
                        title = "Slicing engine missing",
                        subtitle = "The native library failed to load. Reinstall SlicePilot from Google Play.",
                        onClick = {
                            val uri = android.net.Uri.parse("market://details?id=${ctx.packageName}")
                            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
                            runCatching { ctx.startActivity(intent) }
                                .onFailure {
                                    ctx.startActivity(
                                        android.content.Intent(
                                            android.content.Intent.ACTION_VIEW,
                                            android.net.Uri.parse("https://play.google.com/store/apps/details?id=${ctx.packageName}"),
                                        ),
                                    )
                                }
                        },
                    )
                }
            }

            if (modelLoading) {
                item {
                    val loadStart = remember { android.os.SystemClock.elapsedRealtime() }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val p = progress
                            if (p != null && p > 0) {
                                CircularProgressIndicator(
                                    progress = { p / 100f },
                                    modifier = Modifier.size(48.dp),
                                )
                                Spacer(Modifier.height(12.dp))
                                Text("Loading model… $p%", fontSize = 13.sp)
                                val elapsed =
                                    (android.os.SystemClock.elapsedRealtime() - loadStart) / 1000.0
                                if (p >= 5) {
                                    val remaining = elapsed * (100 - p) / p
                                    if (remaining > 2) {
                                        Text(
                                            "~${remaining.toInt()} s left",
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            } else {
                                CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            }
                        }
                    }
                }
            }

            if (hasModel) {
                // Home only reports what is loaded and lets you change it — the 3D
                // render lives in the Slice flow (SlicePreviewScreen,
                // FullscreenViewer), next to the settings that produced it.
                item {
                    val modelFile = modelPath?.let { java.io.File(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            modelFile?.let {
                                Text(
                                    it.name,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    "%.1f MB".format(it.length() / 1024.0 / 1024.0),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = onPickLocalFile) { Text("Replace") }
                    }
                }

                // Loaded but no printer: connecting is the next step.
                if (!printerReady) {
                    item {
                        BigCard(
                            modifier = Modifier.fillMaxWidth(),
                            icon = {
                                Icon(
                                    Icons.Filled.CloudOff,
                                    null,
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(30.dp),
                                )
                            },
                            iconBg = MaterialTheme.colorScheme.surfaceVariant,
                            title = "Connect your printer",
                            subtitle = "Wi-Fi or Cloud",
                            onClick = onOpenConnect,
                        )
                    }
                }
            }

            if (mErr != null) {
                item {
                    Surface(
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
                            Column(Modifier.weight(1f)) {
                                Text(
                                    "Model failed to load",
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                                Text(
                                    "${mErr.code} · ${mErr.message}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                )
                            }
                            TextButton(onClick = { vm.dismissModelError() }) { Text("Dismiss") }
                        }
                    }
                }
            }

            // The Find tab's feed, inline: search + one block per source.
            item {
                ModelFindFeed(
                    browser = browser,
                    onSeeMore = { expandedSource = it },
                )
            }
        }

        // Local files float above the feed: wide, rounded-rectangle (not a circle),
        // so the label says what it opens.
        ExtendedFloatingActionButton(
            onClick = onPickLocalFile,
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = { Icon(Icons.Filled.FolderOpen, null) },
            text = { Text("Local model", fontWeight = FontWeight.SemiBold) },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 20.dp),
        )

        // Full list for one source, opened from the feed's "See more".
        expanded?.let { section ->
            SourceListOverlay(
                section = section,
                onBack = { expandedSource = null },
                favoritedListings = favoritedListings,
                onLoadMore = { browser.loadMore(section.source) },
                onOpen = { browser.openDetail(it) },
            )
        }

        // Model detail opened from the feed or a source list, over everything.
        ModelDetailHost(vm, browser)
    }
}

@Composable
fun BigCard(
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
    iconBg: Color,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)),
        tonalElevation = 2.dp,
        shadowElevation = 8.dp,
        modifier =
            modifier
                .clip(shape)
                .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(iconBg)
                        .border(1.dp, LanternGold.copy(alpha = 0.18f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { icon() }
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
    }
}

@Composable
fun GcodePreviewCanvas(
    preview: world.yemyat.slicepilot.model.GcodePreview,
    visibleLayers: Int,
    bedHalfX: Float = 128f,
    bedHalfY: Float = 128f,
    modelColorHex: String? = null,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { ModelPreviewView(context) }
    LaunchedEffect(preview, bedHalfX, bedHalfY, modelColorHex) {
        controller.setGcodePreview(preview, bedHalfX, bedHalfY, modelColorHex)
    }
    LaunchedEffect(visibleLayers) { controller.setVisibleLayerCount(visibleLayers) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        controller.onResume()
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        controller.onPause()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        controller.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            controller.destroy()
        }
    }
    SharedAndroidView(view = controller, modifier = modifier)
}

@Composable
fun ModelPreviewCanvas(
    mesh: MeshData?,
    modifier: Modifier = Modifier.fillMaxSize(),
    controllerRef: (ModelPreviewView) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { ModelPreviewView(context) }
    LaunchedEffect(mesh) {
        mesh?.let {
            try {
                controller.setModel(it)
            } catch (t: Throwable) {
                try {
                    controller.clearModel()
                } catch (_: Throwable) {
                }
                onError(t)
            }
        }
    }
    LaunchedEffect(controller) { controllerRef(controller) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        controller.onResume()
                    }

                    Lifecycle.Event.ON_PAUSE -> {
                        controller.onPause()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        controller.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            controller.destroy()
        }
    }
    SharedAndroidView(view = controller, modifier = modifier)
}

/**
 * Home banner for a live print (started via this app): state, progress, file and
 * remaining time; tapping opens the print monitor screen. Styled like the
 * model-download banner in the feed.
 */
@Composable
private fun PrintInProgressBanner(
    status: world.yemyat.slicepilot.cloud.PrintStatus,
    onClick: () -> Unit,
) {
    val title =
        when (status.state) {
            world.yemyat.slicepilot.cloud.PrintJobState.PAUSED -> "Print paused"
            world.yemyat.slicepilot.cloud.PrintJobState.PAUSING -> "Pausing…"
            world.yemyat.slicepilot.cloud.PrintJobState.RESUMING -> "Resuming…"
            world.yemyat.slicepilot.cloud.PrintJobState.CONNECTING -> "Connecting to printer…"
            else -> "Printing"
        }
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 4.dp,
        shadowElevation = 4.dp,
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    Icons.Filled.Print,
                    null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.weight(1f))
                if (status.progress in 1..100) {
                    Text(
                        "${status.progress}%",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            val detail =
                buildString {
                    if (status.filename.isNotBlank()) append(status.filename)
                    if (status.currentLayer > 0 && status.totalLayer > 0) {
                        if (isNotEmpty()) append(" · ")
                        append("Layer ${status.currentLayer}/${status.totalLayer}")
                    }
                    val rem = status.remainingSec
                    if (rem != null && rem > 0) {
                        if (isNotEmpty()) append(" · ")
                        append(
                            if (rem >= 3600) {
                                "${rem / 3600}h ${(rem % 3600) / 60}m left"
                            } else {
                                "${rem / 60}m left"
                            },
                        )
                    }
                }
            if (detail.isNotBlank()) {
                Text(
                    detail,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (status.state == world.yemyat.slicepilot.cloud.PrintJobState.CONNECTING) {
                LinearProgressIndicator(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                )
            } else {
                LinearProgressIndicator(
                    progress = { (status.progress / 100f).coerceIn(0f, 1f) },
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
}
