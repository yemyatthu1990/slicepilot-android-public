package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Print
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
    onSeeMore: (ModelSource) -> Unit,
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
    val demoMode by vm.demoModeState.collectAsState()
    // A print that still needs this model: its own banner owns the screen, so the model row
    // stops offering to replace or discard it. State alone is not enough - an idle printer that
    // answers status probes is not printing - hence live + PrintJobState.isInProgress.
    val printLive = printStatus?.live == true && printStatus?.state?.isInProgress == true

    val listState = rememberLazyListState()
    val fabExpanded by remember {
        derivedStateOf { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset < 80 }
    }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            // Home draws under the status bar unless it pads for it itself — the
            // root Scaffold has contentWindowInsets = 0 (see MainActivity).
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding(),
            contentPadding =
                PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 104.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            // Print in progress (started by this app, e.g. before a swipe-away):
            // the monitor is restored at startup, so this banner shows live
            // status like the download bar. Tap opens the print monitor.
            if (printLive) {
                item {
                    printStatus?.let { st ->
                        PrintInProgressBanner(status = st, onClick = { vm.openPrintMonitor() })
                    }
                }
            }

            // Header: wordmark + ambient printer status pill + theme toggle.
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BrandWordmark()
                    Spacer(Modifier.weight(1f))
                    PrinterStatusPill(
                        printer = selectedPrinter,
                        onClick = onOpenConnect,
                    )
                    val dark = LocalIsDarkTheme.current
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
                // Home used to stop at a name and a "Replace" button: the model was on screen
                // but useless, because nothing there led back to the page that prints it (the
                // 3D render and every setting live in the Print setup / Slice flow). The row now
                // opens that flow, replaces the file, or ends it — which is also what backing out
                // of print setup does, asked first (AppViewModel.leavePrintSetup).
                item {
                    LoadedModelRow(
                        modelPath = modelPath.orEmpty(),
                        onOpen = {
                            if (printLive) vm.openPrintMonitor() else vm.openPrintSetup()
                        },
                        onReplace = onPickLocalFile,
                        // The app's own demo cube is not the user's file to throw away, and a
                        // running print needs it.
                        onDiscard =
                            if (printLive || vm.printJobHoldsModel() || demoMode) {
                                null
                            } else {
                                { vm.discardModel() }
                            },
                    )
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
                    onSeeMore = onSeeMore,
                )
            }
        }

        // Collapses to icon-only while scrolling down so feed cards and "See more" stay clear.
        ExtendedFloatingActionButton(
            onClick = onPickLocalFile,
            expanded = fabExpanded,
            shape = RoundedCornerShape(18.dp),
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            icon = { Icon(Icons.Filled.FolderOpen, "Local model") },
            text = { Text("Local model", fontWeight = FontWeight.SemiBold) },
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = 16.dp),
        )
    }
}

/**
 * Compact header pill showing whether a printer is connected (green/gold dot + model) or
 * disconnected ("Connect"). Tapping opens the connect sheet.
 */
@Composable
private fun PrinterStatusPill(
    printer: ConnectedPrinter?,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(999.dp)
    val dotColor =
        when {
            printer == null -> MaterialTheme.colorScheme.outline
            printer.online -> MossGreen
            else -> LanternGold
        }
    val label =
        when {
            printer == null -> "Connect"
            printer.model.isNotBlank() -> printer.model
            else -> "Connected"
        }
    Surface(
        onClick = onClick,
        shape = shape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.25f)),
        modifier = Modifier.heightIn(min = 38.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor),
            )
            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 128.dp),
            )
        }
    }
}

/**
 * The loaded model as one compact row, shaped like a Files entry: name and size, tap to open
 * whatever the model is in (print setup, or the live print), plus the two answers a loaded
 * model can still take — pick another file, or discard this one.
 */
@Composable
private fun LoadedModelRow(
    modelPath: String,
    onOpen: () -> Unit,
    onReplace: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val file = java.io.File(modelPath)
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)), shape)
                .clickable(onClick = onOpen)
                .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                file.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (file.exists()) {
                    "%.1f MB".format(file.length() / 1024.0 / 1024.0)
                } else {
                    "File missing"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(onClick = onReplace) { Text("Replace") }
        onDiscard?.let {
            TextButton(onClick = it) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
        }
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
    partPoses: List<world.yemyat.slicepilot.preview.ModelTransform>? = null,
    partsSkipped: Set<Int> = emptySet(),
    selectedPart: Int = -1,
    controllerRef: (ModelPreviewView) -> Unit = {},
    onError: (Throwable) -> Unit = {},
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val controller = remember { ModelPreviewView(context) }
    LaunchedEffect(mesh, partPoses, partsSkipped, selectedPart) {
        mesh?.let {
            try {
                controller.setModel(it)
                // The poses the slicer will get, so this viewer shows the same plate the setup
                // sheet shows - without them a multi-piece file draws as the file authored it.
                controller.setPartPoses(partPoses, partsSkipped, selectedPart)
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
                        style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
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
