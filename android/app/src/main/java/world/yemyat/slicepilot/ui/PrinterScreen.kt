package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.BrandSectionLabel
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.MossGreen
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.SegmentedControl
import world.yemyat.slicepilot.ui.theme.SettingsRow

/**
 * The Printer tab — the machine's home screen: identity, live job, live camera and
 * the controls sheet.
 *
 * Everything the old "Device status" page showed is here: the hero carries the job
 * and its Pause/Stop, the camera previews inline, and the remaining settings
 * (light, temperature targets, fans) sit in [PrinterControlsSheet]. Print setup is
 * deliberately not reachable from here: picking a model opens it straight away
 * (AppViewModel.onModelPicked), so a tile on this tab only ever led to that page in
 * its disabled "No model" state. Photos moved to the Files tab.
 *
 * Not connected: the discovery list, so the tab always shows what it found on the
 * network instead of one button stranded on an empty page.
 *
 * There is no back arrow: this is a top-level tab, and the tab bar below is the
 * only navigation that makes sense here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrinterScreen(
    vm: AppViewModel,
    onOpenConnect: () -> Unit,
) {
    val selectedPrinter by vm.selectedPrinterState.collectAsState()
    val availablePrinters by vm.availablePrinters.collectAsState()
    val status by vm.printStatus.collectAsState()
    val demoMode by vm.demoModeState.collectAsState()
    val selectedNozzle by vm.selectedNozzle.collectAsState()
    var controlsOpen by remember { mutableStateOf(false) }

    val printer = selectedPrinter

    // The tab owns the printer's live data while it is showing: the status monitor (or
    // the simulated job in a demo) and the one camera stream the card below renders.
    if (printer != null) {
        LaunchedEffect(printer.id) { vm.watchSelectedPrinter() }
        DisposableEffect(printer.id) {
            onDispose {
                // A local printer serves one camera stream, so the tab stops it when it is
                // left behind — but moving to the full-screen route is not leaving: that route
                // renders this very stream. Stopping it there emptied the frame the fullscreen
                // had just taken over, so fullscreen showed “Starting the camera…” forever.
                if (vm.screen.value != Screen.CameraFull) vm.stopCamera()
            }
        }
    }

    val nozzleSizes = remember(printer?.model) { vm.nozzleSizesFor(printer?.model ?: "") }
    val filament =
        remember(printer) {
            printer?.let { p ->
                vm.filamentFor(p.id)
                    ?: p.serial?.let { vm.filamentFor(it) }
                    ?: p.ip?.let { vm.filamentFor(it) }
            }
        }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Printer", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
            Spacer(Modifier.weight(1f))
            // The way into the connect sheet, and so the only way to Disconnect or pick a
            // different printer. It used to live only inside the "Other printers" block,
            // which needs a second printer to exist — with one connected there was no route
            // back out of it at all.
            if (printer != null) {
                TextButton(onClick = onOpenConnect) { Text("Manage") }
            }
        }

        if (printer == null) {
            DiscoverySection(vm = vm, printers = availablePrinters, onOpenConnect = onOpenConnect)
        } else {
            PrinterHero(
                vm = vm,
                name = printer.name,
                model = printer.model,
                isLocal = printer.isLocal,
                online = printer.online,
                status = status,
                filament = filament,
            )

            LiveCameraCard(vm = vm, demoMode = demoMode)

            SetupCard(
                nozzleSizes = nozzleSizes,
                selectedNozzle = selectedNozzle,
                onControls = { controlsOpen = true },
                onNozzle = { vm.selectNozzle(it) },
            )

            // Everything else you own, one tap to switch — replaces the old dropdown.
            if (availablePrinters.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandSectionLabel("Other printers")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availablePrinters.forEach { p ->
                            FilterChip(
                                selected = p.id == printer.id,
                                onClick = { vm.selectPrinter(p) },
                                label = {
                                    Text(
                                        p.name.ifBlank { p.model }.ifBlank { p.id },
                                        maxLines = 1,
                                        modifier = Modifier.widthIn(max = 220.dp),
                                    )
                                },
                            )
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }

    if (controlsOpen) {
        PrinterControlsSheet(vm = vm, onDismiss = { controlsOpen = false })
    }
}

/** The machine: who it is, what it is doing, and how hot it is. */
@Composable
private fun PrinterHero(
    vm: AppViewModel,
    name: String,
    model: String,
    isLocal: Boolean,
    online: Boolean,
    status: world.yemyat.slicepilot.cloud.PrintStatus?,
    filament: world.yemyat.slicepilot.cloud.FilamentInfo?,
) {
    BrandCard(modifier = Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            // Identity
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .background(
                            if (online) MossGreen else MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ).border(1.dp, LanternGold.copy(alpha = 0.35f), CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        name.ifBlank { model },
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        model,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                SourcePill(isLocal = isLocal)
            }

            // Live job: one line of wording for every state, including "the printer has
            // not answered yet" — that reads the same as an idle printer, so it comes
            // from the same place. Progress, detail text and the job controls only
            // appear once there is a status to read them from.
            val jobState = status?.state ?: PrintJobState.IDLE
            val progress = status?.progress ?: 0
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        jobLabel(jobState),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    if (progress in 1..100) {
                        Text(
                            "$progress%",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                }
                if (progress in 1..100) {
                    LinearProgressIndicator(
                        progress = { (progress / 100f).coerceIn(0f, 1f) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                val detail = status?.let { jobDetail(it) }
                if (detail != null) {
                    Text(
                        detail,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                status?.let { if (it.printing) PrinterJobActions(vm = vm, status = it) }
            }

            // Gauges
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GaugeTile("Nozzle", status?.nozzleTemp, status?.nozzleTarget, Modifier.weight(1f))
                GaugeTile("Bed", status?.bedTemp, status?.bedTarget, Modifier.weight(1f))
                if ((status?.chamberTemp ?: 0f) > 0f) {
                    GaugeTile("Chamber", status?.chamberTemp, null, Modifier.weight(1f))
                }
            }

            // Loaded filament
            if (filament != null && filament.present) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Loaded filament",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 1.2.sp,
                    )
                    FilamentSwatches(filament)
                }
            }
        }
    }
}

/**
 * The live view, on the tab itself.
 *
 * A printer serves exactly one camera at a time: method 1042 arms it and the MJPEG
 * server on :8080 accepts a single connection, and the cloud path is an Agora RTC
 * session kept alive by a 30 s heartbeat. So the tab owns the stream outright — it
 * arms it when it becomes visible and releases it when it leaves — which is also why
 * the separate camera/status page is gone: two screens competing for one stream was
 * the reason the preview used to need its own page.
 *
 * Demo mode has no camera to arm, so the frame shows the last shot instead.
 */
@Composable
private fun LiveCameraCard(
    vm: AppViewModel,
    demoMode: Boolean,
) {
    val photos by vm.photos.collectAsState()
    val cameraOn by vm.cameraOn.collectAsState()
    LaunchedEffect(Unit) { vm.refreshPhotos() }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BrandSectionLabel("Live camera")
        val shape = RoundedCornerShape(Radius.md)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(shape)
                    .background(if (demoMode) MaterialTheme.colorScheme.surfaceVariant else Color.Black)
                    .then(
                        if (demoMode) {
                            Modifier.border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)), shape)
                        } else {
                            Modifier
                        },
                    ),
            contentAlignment = Alignment.Center,
        ) {
            if (demoMode) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.CloudOff,
                        null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(26.dp),
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        if (photos.isEmpty()) {
                            "Demo printer — no camera feed"
                        } else {
                            "Demo printer — ${photos.size} photo(s), no live feed"
                        },
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else if (cameraOn) {
                // Pinch-zoomable preview (PrinterControlsSheet's shutter captures from it).
                CameraStage(vm = vm, modifier = Modifier.fillMaxSize())
                Row(
                    modifier =
                        Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CameraShutter(vm)
                    Box(
                        modifier =
                            Modifier
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                                .clickable { vm.openCameraFull() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Fullscreen,
                            contentDescription = "Full screen camera",
                            tint = Color.White,
                            modifier =
                                Modifier
                                    .padding(6.dp)
                                    .size(18.dp),
                        )
                    }
                }
            } else {
                // Arming takes a moment (method 1042, then the first frame).
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Starting the camera…",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun SourcePill(isLocal: Boolean) {
    val shape = RoundedCornerShape(Radius.sm)
    Surface(
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        ) {
            Icon(
                if (isLocal) Icons.Filled.CloudOff else Icons.Filled.CloudDone,
                null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (isLocal) "LAN" else "Cloud",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One temperature readout: current value with its target underneath. */
@Composable
private fun GaugeTile(
    label: String,
    current: Float?,
    target: Float?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 0.6.sp,
        )
        Text(
            current?.round1()?.plus(" °C") ?: "—",
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        // Only show the set-point row when the printer reported one, so an
        // unread gauge stays a compact two-line tile.
        val targetLine =
            when {
                target == null -> null
                target > 0f -> "→ ${target.round1()} °C"
                else -> "off"
            }
        targetLine?.let {
            Text(
                it,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

/**
 * The two settings that say how a print gets made, in one card: the live control
 * sheet, and the nozzle the slicer uses.
 *
 * They used to be a full-width ActionTile (a shape that only works in a grid of
 * two, so stretched it looked like a card with its contents in the wrong corner)
 * next to a row of FilterChips — chips read as "switch some of these on", while
 * the nozzle is a choice of exactly one. The row now opens, the segments choose.
 */
@Composable
private fun SetupCard(
    nozzleSizes: List<String>,
    selectedNozzle: String,
    onControls: () -> Unit,
    onNozzle: (String) -> Unit,
) {
    BrandCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = 14.dp,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SettingsRow(
                icon = Icons.Filled.Tune,
                title = "Controls",
                subtitle = "Pause · light · temps · fans",
                onClick = onControls,
            )
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrandSectionLabel("Nozzle size")
                SegmentedControl(
                    options = nozzleSizes.map { "$it mm" },
                    selectedIndex = nozzleSizes.indexOf(selectedNozzle),
                    onSelect = { onNozzle(nozzleSizes[it]) },
                )
            }
        }
    }
}

/**
 * Nothing selected yet: what the app can see on the network and in the cloud,
 * each connectable in one tap, with the sheet for adding or signing in.
 */
@Composable
private fun DiscoverySection(
    vm: AppViewModel,
    printers: List<ConnectedPrinter>,
    onOpenConnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (printers.isEmpty()) {
            BrandCard(modifier = Modifier.fillMaxWidth()) {
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            "No printer found yet",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            "Wake the printer on your Wi-Fi, or sign in to the cloud — " +
                                "anything discovered shows up here, ready to connect.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    BrandButton(
                        text = "Connect to printer",
                        onClick = onOpenConnect,
                        modifier = Modifier.fillMaxWidth(),
                        height = 54.dp,
                    )
                }
            }
        } else {
            BrandSectionLabel("Nearby printers")
            printers.forEach { p ->
                val shape = RoundedCornerShape(Radius.md)
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(shape)
                            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                            .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)), shape)
                            .clickable { vm.selectPrinter(p) }
                            .padding(16.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .background(
                                    if (p.online) MossGreen else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                        )
                        Column(Modifier.weight(1f)) {
                            Text(
                                p.name.ifBlank { p.model }.ifBlank { p.id },
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                listOfNotNull(
                                    p.model.takeIf { it.isNotBlank() && it != p.name },
                                    p.ip?.takeIf { it.isNotBlank() } ?: if (p.isLocal) null else "Cloud",
                                ).joinToString(" · ").ifBlank { " " },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            "Connect",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = LanternGold,
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Searching Wi-Fi and cloud",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onOpenConnect) { Text("Add another") }
            }
        }
    }
}

/** Coloured swatch per loaded tray (falls back to the single reported filament). */
@Composable
private fun FilamentSwatches(filament: world.yemyat.slicepilot.cloud.FilamentInfo) {
    val trays =
        filament.trays.ifEmpty {
            listOf(
                world.yemyat.slicepilot.cloud
                    .LoadedTray(0, filament.name, filament.name, filament.colorHex),
            )
        }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .clip(RoundedCornerShape(Radius.sm))
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        trays.forEachIndexed { idx, tray ->
            if (idx > 0) Spacer(Modifier.width(14.dp))
            val swatchColor =
                remember(tray.colorHex) {
                    tray.colorHex?.let {
                        try {
                            android.graphics.Color.parseColor(it)
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(
                            swatchColor?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
                            CircleShape,
                        ).border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    tray.type.ifBlank { tray.name }.ifBlank { "Filament" },
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }
    }
}

/**
 * The job state as one line, worded like the print monitor does. Every screen that
 * names a job uses this, so "no status yet" (reported as IDLE) and a printer that
 * answered "idle" cannot drift apart.
 */
private fun jobLabel(state: PrintJobState): String =
    when (state) {
        PrintJobState.CONNECTING -> "Connecting…"
        PrintJobState.IDLE -> "Idle — nothing printing right now"
        PrintJobState.PRINTING -> "Printing"
        PrintJobState.PAUSING -> "Pausing…"
        PrintJobState.PAUSED -> "Paused"
        PrintJobState.RESUMING -> "Resuming…"
        PrintJobState.STOPPING -> "Stopping…"
        PrintJobState.STOPPED -> "Stopped"
        PrintJobState.COMPLETED -> "Completed"
        PrintJobState.ERROR -> "Error"
    }

/** "Layer 184/297 · 1h 12m left" — whichever parts are known. */
private fun jobDetail(status: world.yemyat.slicepilot.cloud.PrintStatus): String? {
    val parts =
        listOfNotNull(
            if (status.currentLayer > 0 && status.totalLayer > 0) {
                "Layer ${status.currentLayer}/${status.totalLayer}"
            } else {
                null
            },
            status.remainingSec?.takeIf { it > 0 }?.let {
                if (it >= 3600) "${it / 3600}h ${(it % 3600) / 60}m left" else "${it / 60}m left"
            },
            status.filename.takeIf { it.isNotBlank() },
        )
    return parts.ifEmpty { null }?.joinToString(" · ")
}

/** "0.2" style temperature text without 0.12345678 noise. */
private fun Float.round1(): String {
    val tenths = (this * 10).toInt()
    return "${tenths / 10}.${tenths % 10}"
}
