package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import kotlinx.coroutines.launch
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.TravelExplore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import world.yemyat.slicepilot.NativeLib
import world.yemyat.slicepilot.preview.ModelPreviewView
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.repo.ModelBrowser
import world.yemyat.slicepilot.repo.ModelSource
import world.yemyat.slicepilot.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPickModel: () -> Unit,
    onFullscreen: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenConfigure: () -> Unit,
    onBrowse: () -> Unit,
    onLibrary: () -> Unit,
    browser: ModelBrowser,
    favorites: world.yemyat.slicepilot.repo.FavoritesStore,
) {
    val mesh by vm.mesh.collectAsState()
    val modelLoading by vm.modelLoading.collectAsState()
    val modelError by vm.modelError.collectAsState()
    val mErr = modelError
    val printers by vm.printers.collectAsState()
    val selectedPrinter by vm.selectedPrinterState.collectAsState()
    val availablePrinters by vm.availablePrinters.collectAsState()
    val browse by browser.state.collectAsState()
    val favEntriesHome by favorites.entries.collectAsState()

    // Popular-models strip (empty search-bar feed) for the "no model yet" state.
    LaunchedEffect(Unit) { browser.loadHome() }

    val hasModel = vm.modelPath != null
    val printerReady = selectedPrinter != null

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            containerColor = Color.Transparent,
            bottomBar = {
                HomeCta(
                    hasModel = hasModel,
                    printerReady = printerReady,
                    onFindModel = onBrowse,
                    onConnect = onOpenConnect,
                    onPrintSetup = { vm.openPrintSetup() },
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Spacer(Modifier.height(8.dp))

                // Header: wordmark + actions, one compact row.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    BrandWordmark()
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onLibrary) {
                        BadgedBox(
                            badge = {
                                if (favEntriesHome.isNotEmpty()) {
                                    // Offset into the icon corner: the scroll
                                    // container clips badges that overflow.
                                    Badge(
                                        modifier = Modifier.offset(x = (-2).dp, y = 6.dp),
                                    ) {
                                        Text(
                                            if (favEntriesHome.size > 99) "99+"
                                            else favEntriesHome.size.toString(),
                                        )
                                    }
                                }
                            },
                        ) {
                            Icon(
                                Icons.Filled.CollectionsBookmark,
                                contentDescription = "My models",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    val dark = LocalIsDarkTheme.current
                    IconButton(onClick = { vm.toggleTheme() }) {
                        Icon(
                            if (dark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = if (dark) "Light mode" else "Dark mode",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // Printer status takes the tagline's slot when connected —
                // long names stay on one readable line, no extra header row.
                val printerName =
                    selectedPrinter?.name ?: if (vm.cloudLoggedIn) "Cloud" else null
                if (printerName != null) {
                    Row {
                        PrinterStatusChip(
                            modifier = Modifier.widthIn(max = 300.dp),
                            name = printerName,
                            isLocal = selectedPrinter?.isLocal == true,
                            onClick = onOpenConnect,
                        )
                    }
                } else {
                    Text(
                        "Slice with a little magic.",
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (!NativeLib.isLoaded) {
                    val ctx = LocalContext.current
                    BigCard(
                        icon = {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
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
                                        )
                                    )
                                }
                        },
                    )
                }

                if (!hasModel) {
                    // State A: start a project — find a model online or pick a file.
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BigCard(
                            modifier = Modifier.weight(1f),
                            icon = {
                                Icon(Icons.Filled.TravelExplore, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                            },
                            iconBg = MaterialTheme.colorScheme.secondaryContainer,
                            title = "Find models",
                            subtitle = "MakerWorld, Printables & more",
                            onClick = onBrowse,
                        )
                        BigCard(
                            modifier = Modifier.weight(1f),
                            icon = {
                                if (mErr != null) {
                                    Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(30.dp))
                                } else {
                                    Icon(Icons.Filled.FolderOpen, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                                }
                            },
                            iconBg = if (mErr != null) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,
                            title = if (mErr != null) "Model failed" else "Choose a file",
                            subtitle = if (mErr != null) mErr.code else "STL or 3MF from your phone",
                            onClick = onPickModel,
                        )
                    }
                    PopularModelStrip(
                        browser = browser,
                        favoritedListings = remember(favEntriesHome) {
                            favEntriesHome.mapNotNull { e ->
                                e.listingId.takeIf { it.isNotBlank() }?.let { "${e.source}|$it" }
                            }.toSet()
                        },
                        onOpen = { browser.openDetail(it) },
                        onBrowseAll = onBrowse,
                    )
                } else {
                    // States B/C: the loaded model is the hero — preview first.
                    if (mesh != null && !modelLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .clip(RoundedCornerShape(24.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            ModelPreviewCanvas(mesh = mesh, onError = { vm.reportModelLoadError("render", it) })
                            Row(
                                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val favEntries by favorites.entries.collectAsState()
                                val modelFile = vm.modelPath?.let { java.io.File(it) }
                                val favKey = modelFile?.name?.let { "file|$it" }
                                // Match the key and the stored file name: favorites
                                // dedupe same-named files as "name(2).ext", and loaded
                                // paths carry that deduped name.
                                fun isKept(e: world.yemyat.slicepilot.repo.FavoriteModel): Boolean =
                                    modelFile != null && favKey != null &&
                                        (e.key == favKey || java.io.File(e.path).name == modelFile.name)
                                val favorited = favEntries.any { isKept(it) }
                                val scope = androidx.compose.runtime.rememberCoroutineScope()
                                FilledIconButton(
                                    onClick = {
                                        val f = modelFile ?: return@FilledIconButton
                                        val k = favKey ?: return@FilledIconButton
                                        val kept = favEntries.firstOrNull { isKept(it) }
                                        if (kept != null) {
                                            // Keep the file on disk when it is
                                            // the loaded model (opened from the
                                            // library) — un-starring must not
                                            // empty the project.
                                            favorites.remove(
                                                kept.id,
                                                deleteFile = kept.path != vm.modelPath,
                                            )
                                        } else {
                                            scope.launch {
                                                favorites.add(f, f.nameWithoutExtension, "local", k)
                                            }
                                        }
                                    },
                                    enabled = modelFile?.exists() == true,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                ) {
                                    Icon(
                                        if (favorited) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                        "Keep in My models",
                                        tint = if (favorited) Color(0xFFFFC96B)
                                        else MaterialTheme.colorScheme.onSurface,
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                FilledIconButton(
                                    onClick = onFullscreen,
                                    colors = IconButtonDefaults.filledIconButtonColors(
                                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                    ),
                                ) {
                                    Icon(Icons.Filled.Fullscreen, "Fullscreen")
                                }
                            }
                        }
                    } else if (modelLoading) {
                        val progress by vm.modelLoadProgress.collectAsState()
                        val loadStart = remember { android.os.SystemClock.elapsedRealtime() }
                        Box(
                            Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp))
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
                                        if (remaining > 2)
                                            Text(
                                                "~${remaining.toInt()} s left",
                                                fontSize = 12.sp,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                    }
                                } else {
                                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                                }
                            }
                        }
                    }
                    val modelFile = vm.modelPath?.let { java.io.File(it) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            modelFile?.let {
                                Text(it.name, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                Text(
                                    "%.1f MB".format(it.length() / 1024.0 / 1024.0),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        TextButton(onClick = onPickModel) { Text("Replace") }
                    }
                    // State B: model loaded but no printer — connect is the next step.
                    if (!printerReady) {
                        BigCard(
                            modifier = Modifier.fillMaxWidth(),
                            icon = {
                                Icon(Icons.Filled.CloudOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(30.dp))
                            },
                            iconBg = MaterialTheme.colorScheme.surfaceVariant,
                            title = "Connect your printer",
                            subtitle = "Wi-Fi or Cloud",
                            onClick = onOpenConnect,
                        )
                    }
                }

                val nozzleSizes = remember(selectedPrinter?.model) { vm.nozzleSizesFor(selectedPrinter?.model ?: "") }
                val selectedNozzle by vm.selectedNozzle.collectAsState()
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 2.dp,
                    shadowElevation = 4.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text("Printer", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        var printerMenu by remember { mutableStateOf(false) }
                        if (!printerReady) {
                            // Nothing to select before a printer is connected:
                            // this row is the connect entry point, not a picker.
                            OutlinedButton(
                                onClick = onOpenConnect,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(Icons.Filled.CloudOff, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Connect to printer")
                            }
                        } else
                        ExposedDropdownMenuBox(
                            expanded = printerMenu,
                            onExpandedChange = { if (availablePrinters.isNotEmpty()) printerMenu = it }
                        ) {
                            OutlinedTextField(
                                value = selectedPrinter?.let {
                                    "${it.name} · ${it.model}${if (it.isLocal) " (Local)" else ""}"
                                } ?: "Select a printer",
                                onValueChange = {},
                                readOnly = true,
                                enabled = availablePrinters.isNotEmpty(),
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(printerMenu) },
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.menuAnchor().fillMaxWidth(),
                            )
                            ExposedDropdownMenu(expanded = printerMenu, onDismissRequest = { printerMenu = false }) {
                                availablePrinters.forEach { p ->
                                    DropdownMenuItem(
                                        text = {
                                            Text(
                                                if (p.isLocal) "${p.name} · ${p.model} (Local ${p.ip ?: ""})"
                                                else "${p.name} · ${p.model}${if (p.online) "" else " (offline)"}"
                                            )
                                        },
                                        onClick = { vm.selectPrinter(p); printerMenu = false },
                                    )
                                }
                            }
                        }
                        if (selectedPrinter != null) {
                            Text("Nozzle size", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                nozzleSizes.forEach { size ->
                                    FilterChip(
                                        selected = size == selectedNozzle,
                                        onClick = { vm.selectNozzle(size) },
                                        label = { Text("$size mm", maxLines = 1) },
                                    )
                                }
                            }
                            Spacer(Modifier.height(2.dp))
                            OutlinedButton(
                                onClick = { vm.openDeviceStatus() },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(14.dp),
                            ) {
                                Icon(
                                    Icons.Filled.MonitorHeart,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Device status")
                            }
                        }
                        val curPrinter = selectedPrinter
                        val curFilament = curPrinter?.let {
                            vm.filamentFor(it.id)
                                ?: it.serial?.let { s -> vm.filamentFor(s) }
                                ?: it.ip?.let { ip -> vm.filamentFor(ip) }
                        }
                        if (curFilament != null && curFilament.present) {
                            Text("Loaded filament", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                            ) {
                                val trays = curFilament.trays.ifEmpty {
                                    listOf(world.yemyat.slicepilot.cloud.LoadedTray(0, curFilament.name, curFilament.name, curFilament.colorHex))
                                }
                                trays.forEachIndexed { idx, tray ->
                                    if (idx > 0) Spacer(Modifier.width(14.dp))
                                    val swatchColor = remember(tray.colorHex) {
                                        tray.colorHex?.let {
                                            try { android.graphics.Color.parseColor(it) } catch (_: Exception) { null }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(16.dp)
                                                .background(
                                                    swatchColor?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
                                                    CircleShape,
                                                )
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            tray.type.ifBlank { tray.name }.ifBlank { "Filament" },
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                if (mErr != null) {
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
                                Text("Model failed to load", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onErrorContainer)
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

                Spacer(Modifier.height(24.dp))
            }
        }

        // Model detail opened from the popular strip, full screen over home.
        ModelDetailHost(vm, browser)
    }
}

/** Sticky bottom button that always names the user's next step. */
@Composable
private fun HomeCta(
    hasModel: Boolean,
    printerReady: Boolean,
    onFindModel: () -> Unit,
    onConnect: () -> Unit,
    onPrintSetup: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        modifier = Modifier.windowInsetsPadding(
            WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom),
        ),
    ) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 12.dp)) {
            val (text, icon, onClick) = when {
                !hasModel -> Triple(
                    "Find a model",
                    Icons.Filled.TravelExplore,
                    onFindModel,
                )
                !printerReady -> Triple(
                    "Connect printer",
                    Icons.Filled.CloudOff,
                    onConnect,
                )
                else -> Triple(
                    "Print setup",
                    Icons.Filled.Print,
                    onPrintSetup,
                )
            }
            BrandButton(
                text = text,
                onClick = onClick,
                icon = { Icon(icon, null, tint = Color.White) },
                modifier = Modifier.fillMaxWidth(),
                height = 60.dp,
            )
        }
    }
}

/** Compact printer status pill in the header; tap opens the Connect screen. */
@Composable
private fun PrinterStatusChip(
    name: String?,
    isLocal: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val connected = name != null
    Surface(
        modifier = modifier,
        shape = CircleShape,
        color = if (connected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)),
        onClick = onClick,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Icon(
                when {
                    !connected -> Icons.Filled.CloudOff
                    isLocal -> Icons.Filled.Wifi
                    else -> Icons.Filled.CloudDone
                },
                null,
                modifier = Modifier.size(16.dp),
                tint = if (connected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                name ?: "Connect",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 220.dp),
                color = if (connected) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Horizontal popular-models rails (MakerWorld, Printables) for the no-model state. */
@Composable
private fun PopularModelStrip(
    browser: ModelBrowser,
    favoritedListings: Set<String> = emptySet(),
    onOpen: (world.yemyat.slicepilot.repo.ModelListing) -> Unit,
    onBrowseAll: () -> Unit,
) {
    val st by browser.state.collectAsState()
    val sections = st.sections.filter {
        it.source == ModelSource.MAKERWORLD || it.source == ModelSource.PRINTABLES
    }
    sections.forEach { section ->
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Popular on ${section.source.label}",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onBrowseAll) { Text("See all") }
            }
            if (section.loading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp),
                ) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("Loading ${section.source.label}…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (section.results.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(section.results.take(10), key = { it.id }) { item ->
                        ModelCard(
                            item,
                            Modifier.width(170.dp),
                            favorited = "${item.source}|${item.id}" in favoritedListings,
                        ) { onOpen(item) }
                    }
                }
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
        modifier = modifier
            .clip(shape)
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
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
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
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
    AndroidView(factory = { controller }, modifier = modifier)
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
                try { controller.clearModel() } catch (_: Throwable) {}
                onError(t)
            }
        }
    }
    LaunchedEffect(controller) { controllerRef(controller) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> controller.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.onPause()
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
    AndroidView(factory = { controller }, modifier = modifier)
}
