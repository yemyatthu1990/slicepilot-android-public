package world.yemyat.slicepilot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesomeMosaic
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrintDisabled
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlinx.coroutines.delay
import world.yemyat.slicepilot.model.PartPlacement
import world.yemyat.slicepilot.preview.ScreenDirection
import world.yemyat.slicepilot.ui.components.SharedAndroidView
import world.yemyat.slicepilot.ui.theme.*
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.Space
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrintSetupScreen(vm: AppViewModel) {
    val material by vm.setupMaterial.collectAsState()
    val quality by vm.setupQuality.collectAsState()
    val plate by vm.setupPlate.collectAsState()
    val selectedNozzle by vm.selectedNozzle.collectAsState()
    val mesh by vm.mesh.collectAsState()
    val modelLoading by vm.modelLoading.collectAsState()
    val modelError by vm.modelError.collectAsState()
    val config by vm.config.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
    val filaments by vm.filaments.collectAsState()
    val modelFilamentInfo by vm.modelFilamentInfo.collectAsState()
    val multiColourEnabled by vm.multiColourEnabled.collectAsState()
    val amsSlotOverrides by vm.amsSlotOverrides.collectAsState()
    val materials = remember(printer, filaments, config.printer) { vm.printSetupMaterials() }
    val amsCapable = remember(printer, filaments, config.printer) { vm.amsCapablePrinter() }
    val reportedTrays = remember(printer, filaments) { vm.reportedPrinterTrays() }
    val multiAssignments =
        remember(modelFilamentInfo, multiColourEnabled, amsSlotOverrides, printer, filaments, config.printer) {
            vm.activeMultiColourAssignments()
        }
    val multiColourActive = multiAssignments.size > 1
    val model = vm.modelPath?.let { java.io.File(it).name }
    // Which machine to slice for and which slicer preset to use: the two rows Home's
    // "Configure print" sheet owned before that sheet folded into this page.
    val setupProcess by vm.setupProcess.collectAsState()
    val printerOptions = remember(printer, config.printer) { vm.setupPrinterOptions() }
    val processOptions = remember(config.printer) { vm.setupProcessOptions() }
    val process = setupProcess ?: vm.printSetupProcess(quality, processOptions)

    LaunchedEffect(materials) {
        if (material == null || materials.none { it.key == material }) {
            materials.firstOrNull()?.key?.let { vm.setSetupMaterial(it) }
        }
    }
    val materialColor = materials.firstOrNull { it.key == material }?.colorHex
    val plateColor = vm.plateColorHex(plate)
    val context = androidx.compose.ui.platform.LocalContext.current
    val volume = remember(config.printer) { printVolumeForProfile(context, config.printer ?: "") ?: Triple(256f, 256f, 256f) }
    val poses by vm.partPoses.collectAsState()
    val skipped by vm.partsSkipped.collectAsState()
    val selectedPart by vm.selectedPart.collectAsState()
    // What the plate has to hold, in mm. A laid-out multi-part file is measured as the pieces
    // it actually puts down: the file's own box is usually far wider than the packed layout
    // (re-arranging exists precisely because of that), and pieces left off the plate must not
    // count against it. A file with no per-piece layout still reports the file's box.
    val meshData = mesh
    val parts = meshData?.partsForRendering.orEmpty()
    val placedPoses = poses?.takeIf { it.size == parts.size && parts.size > 1 }
    val box: FloatArray =
        when {
            meshData == null -> {
                floatArrayOf(0f, 0f, 0f)
            }

            placedPoses != null -> {
                val included = List(parts.size) { it !in skipped }
                val (w, d) = PartPlacement.unionFootprint(parts, placedPoses, included)
                floatArrayOf(w, d, PartPlacement.printedHeightMm(parts, placedPoses, skipped))
            }

            else -> {
                floatArrayOf(meshData.maxX - meshData.minX, meshData.maxY - meshData.minY, meshData.maxZ - meshData.minZ)
            }
        }
    val fits = box[0] <= volume.first && box[1] <= volume.second && box[2] <= volume.third
    var fullscreen by remember { mutableStateOf(false) }
    var showAdvanced by remember { mutableStateOf(false) }
    var showPartsSheet by remember { mutableStateOf(false) }

    val previewController =
        remember {
            world.yemyat.slicepilot.preview
                .ModelPreviewView(context)
        }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(previewController) {
        val obs =
            androidx.lifecycle.LifecycleEventObserver { _, event ->
                when (event) {
                    androidx.lifecycle.Lifecycle.Event.ON_RESUME -> {
                        previewController.onResume()
                    }

                    androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> {
                        previewController.onPause()
                    }

                    else -> {}
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        previewController.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            previewController.destroy()
        }
    }
    LaunchedEffect(mesh) {
        mesh?.let {
            try {
                previewController.setModelWithPlate(it, materialColor, plateColor, volume.first / 2f, volume.second / 2f)
                // A file the parser split into pieces gets its layout worked out up front, so the
                // plate shows what the slicer will get; a single-piece file keeps using the
                // whole-model transform below.
                if (it.parts.size > 1) vm.arrangeParts(volume.first, volume.second)
                previewController.setPartPoses(vm.partPoses.value, vm.partsSkipped.value, vm.selectedPart.value)
                val initialMulti = vm.activeMultiColourAssignments()
                if (initialMulti.size > 1) {
                    previewController.setModelFilamentColors(
                        initialMulti.map { a ->
                            world.yemyat.slicepilot.preview.PreviewGeometry.parseColor(a.spoolColorHex)
                                ?: world.yemyat.slicepilot.preview.PreviewGeometry.DEFAULT_MODEL_COLOR
                        },
                    )
                } else {
                    previewController.setModelFilamentColors(null)
                }
                val t = vm.modelTransform.value
                previewController.setModelTransform(
                    floatArrayOf(
                        t.rotX,
                        t.rotY,
                        t.rotZ,
                        if (t.mirrorX) 1f else 0f,
                        if (t.mirrorY) 1f else 0f,
                        if (t.mirrorZ) 1f else 0f,
                        t.offsetX,
                        t.offsetY,
                    ),
                )
                vm.setModelTransform(previewController.modelTransform())
            } catch (t: Throwable) {
                try {
                    previewController.clearModel()
                } catch (_: Throwable) {
                }
                vm.reportModelLoadError("render", t)
            }
        }
    }
    // The plate has to follow the layout. setPartPoses only ran on the first mesh load, so
    // rotating a piece, skipping it or re-arranging the set changed the state - and the
    // slicer's answer - while the picture stayed as it was.
    // The plate has to follow the layout and the selection. setPartPoses only ran on the first
    // mesh load, so rotating a piece, skipping it or re-arranging the set changed the state -
    // and the slicer's answer - while the picture stayed as it was.
    LaunchedEffect(poses, skipped, selectedPart) {
        if (mesh != null) previewController.setPartPoses(poses, skipped, selectedPart)
    }
    LaunchedEffect(mesh, multiAssignments) {
        if (mesh != null) {
            if (multiAssignments.size > 1) {
                previewController.setModelFilamentColors(
                    multiAssignments.map { a ->
                        world.yemyat.slicepilot.preview.PreviewGeometry.parseColor(a.spoolColorHex)
                            ?: world.yemyat.slicepilot.preview.PreviewGeometry.DEFAULT_MODEL_COLOR
                    },
                )
            } else {
                previewController.setModelFilamentColors(null)
            }
        }
    }
    LaunchedEffect(materialColor) { if (mesh != null) previewController.setModelColor(materialColor) }
    LaunchedEffect(plateColor) { if (mesh != null) previewController.setPlateColor(plateColor) }

    // Why "Next: Slice" can't be tapped yet, in words. The button used to fade to grey without
    // saying why - so the page read as broken whenever the printer simply hadn't been picked - and
    // it scrolled away with the content, so answering "can I print now?" meant scrolling to the
    // bottom. It is pinned now, and it explains itself.
    val blockReason: String? =
        when {
            model == null -> "Pick a model to slice"
            modelError != null -> "That file couldn't be opened"
            mesh == null && modelLoading -> "Still reading the model\u2026"
            printer == null -> "Pick the printer to send this to"
            !multiColourActive && material == null -> "Pick the material to print with"
            parts.size > 1 && skipped.size >= parts.size -> "Select at least one part to print"
            !fits -> "Model is larger than the build volume"
            else -> null
        }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
            TopAppBar(
                title = { Text("Print setup", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    // The flow's exit, not just a navigation: leaving asks about the model
                    // (see AppViewModel.leavePrintSetup) instead of leaving it stranded on Home.
                    IconButton(onClick = { vm.leavePrintSetup() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
        bottomBar = {
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .navigationBarsPadding()
                            .padding(horizontal = Space.lg, vertical = Space.sm),
                    verticalArrangement = Arrangement.spacedBy(Space.xs),
                ) {
                    val reason = blockReason
                    if (reason != null) {
                        Text(
                            reason,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp),
                        )
                    }
                    BrandButton(
                        text = "Slice",
                        onClick = { vm.sliceFromSetup() },
                        enabled = reason == null,
                        icon = { Icon(Icons.Filled.ContentCut, null, tint = Color.White) },
                        modifier = Modifier.fillMaxWidth(),
                        height = ButtonSize.Large,
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg, vertical = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.lg),
        ) {
            // One card for the model: what it is, which printer it goes to, and what it looks like
            // on the plate. The old page stacked a SummaryCard of exactly that text *above* a
            // 300.dp preview, so the first screenful answered the same question twice.
            BrandCard {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                model ?: "No model",
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                (
                                    printer?.let {
                                        "${it.name} \u00b7 ${it.model}${if (it.isLocal) " (Local)" else ""}"
                                    } ?: "No printer selected"
                                ) + " \u00b7 $selectedNozzle mm",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (mesh != null && !fullscreen) {
                            BrandIconButton(
                                icon = Icons.Filled.Fullscreen,
                                contentDescription = "Open the preview full screen",
                                onClick = { fullscreen = true },
                            )
                        }
                    }
                    val loadErr = modelError
                    if (model == null) {
                        Text(
                            "No file selected yet \u2014 pick one from Home or the Files tab.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (loadErr != null) {
                        Text(
                            "That file couldn't be opened \u00b7 ${loadErr.code} ${loadErr.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    } else if (mesh == null && modelLoading) {
                        val progress = vm.modelLoadProgress.collectAsState().value
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            CircularProgressIndicator(
                                progress = { (progress ?: 0) / 100f },
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.dp,
                            )
                            Text(
                                progress?.let { "Reading model\u2026 $it%" } ?: "Reading model\u2026",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else if (mesh != null && !fullscreen) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .height(240.dp)
                                    .clip(RoundedCornerShape(Radius.md))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                        ) {
                            SharedAndroidView(
                                view = previewController,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    if (mesh != null && !fits) {
                        WarningBanner(
                            "Model is larger than the build volume",
                            "Model ${box[0].toInt()}\u00d7${box[1].toInt()}\u00d7${box[2].toInt()} mm \u00b7 " +
                                "Max ${volume.first.toInt()}\u00d7${volume.second.toInt()}\u00d7${volume.third.toInt()} mm",
                        )
                    }
                    // Multi-part models stay paired with the 3D plate right above them: a compact
                    // horizontal part selector + selected-part controls keep the plate visible as
                    // you pick/skip parts, while "Manage" opens the full checklist sheet.
                    val partNames = mesh?.parts?.map { it.name }.orEmpty()
                    if (partNames.size > 1) {
                        InlinePartsStrip(
                            names = partNames,
                            selectedIndex = selectedPart.coerceIn(0, partNames.lastIndex),
                            skipped = skipped,
                            onSelect = { vm.selectPart(it) },
                            onTogglePrint = { idx -> vm.setPartPrinted(idx, idx in skipped) },
                            onArrange = { vm.arrangeParts(volume.first, volume.second) },
                            onPose = { fullscreen = true },
                            onManage = { showPartsSheet = true },
                        )
                    }
                }
            }

            // Everything configurable in one card, in the order the choices stack up: what to
            // print with (material, plate, quality), then the machine and the slicer preset it
            // will be cut with. Printer and Process used to sit at the top of this card, where
            // their values were the least interesting answer to "what is this print" and the
            // process name was cut off; they now close the page, next to the button that uses
            // them, and print setup's header already names the machine up top.
            BrandCard {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    val isMultiColourModel = modelFilamentInfo?.isMultiColour == true
                    if (isMultiColourModel && amsCapable) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            BrandSectionLabel("Colour mode")
                            SegmentedControl(
                                options = listOf("Multi-colour (AMS)", "Single colour"),
                                selectedIndex = if (multiColourEnabled) 0 else 1,
                                onSelect = { vm.setMultiColourEnabled(it == 0) },
                            )
                        }
                    } else if (isMultiColourModel && !amsCapable) {
                        WarningBanner(
                            "Single-colour printer",
                            "This 3MF uses ${modelFilamentInfo?.usedFilaments?.size ?: 2} colours, " +
                                "but this printer has no AMS/Canvas \u2014 it will slice in one colour.",
                        )
                    }

                    if (multiColourActive) {
                        AmsFilamentMapSection(
                            assignments = multiAssignments,
                            reportedTrays = reportedTrays,
                            hasCustomMapping = amsSlotOverrides.isNotEmpty(),
                            isLocalWithoutTrays = printer?.isLocal == true && reportedTrays.isEmpty(),
                            onSelectSlot = { idx, slot -> vm.setAmsSlotForModelFilament(idx, slot) },
                            onAutoMatch = { vm.autoMatchAmsSlots() },
                            onEnterAccessCode = { vm.requestAccessCodeEntry() },
                        )
                    } else {
                        DropdownRow(
                            label = "Material",
                            options = materials.map { MenuOption(it.label, it, filamentColor(it.colorHex)) },
                            selected = materials.firstOrNull { opt -> opt.key == material },
                            onSelect = { vm.setSetupMaterial(it.key) },
                        )

                        // When the printer reports its spools, the Material dropdown already lists them
                        // per tray; only pick a bare slot manually when it reported nothing.
                        if (printer?.isLocal == true && vm.lanSpools().isEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                                BrandSectionLabel("Printer spool")
                                Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                                    (0..3).forEach { trayId ->
                                        FilterChip(
                                            selected = (config.filamentTrayId ?: 0) == trayId,
                                            onClick = { vm.setPrintSlot(0, trayId) },
                                            label = { Text("Slot ${trayId + 1}", maxLines = 1) },
                                        )
                                    }
                                }
                                Text(
                                    "The printer didn't report its loaded filament \u2014 pick the slot it should load from.",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { vm.requestAccessCodeEntry() },
                                    contentPadding = PaddingValues(0.dp),
                                ) {
                                    Text(
                                        "Enter the printer's access code to load its filament",
                                        fontSize = 13.sp,
                                    )
                                }
                            }
                        }
                    }

                    val plates = listOf("Textured", "Smooth")
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        BrandSectionLabel("Build plate")
                        SegmentedControl(
                            options = plates,
                            selectedIndex = plates.indexOf(plate).coerceAtLeast(0),
                            onSelect = { vm.setSetupPlate(plates[it]) },
                        )
                    }

                    val qualities = listOf("Draft", "Standard", "Quality")
                    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                        BrandSectionLabel("Quality")
                        SegmentedControl(
                            options = qualities,
                            selectedIndex = qualities.indexOf(quality).coerceAtLeast(0),
                            onSelect = { vm.setSetupQuality(qualities[it]) },
                        )
                    }

                    // The last two choices name the machine and the slicer preset the slice will
                    // use, which is exactly what "Next: Slice" is about to do.
                    if (printerOptions.isNotEmpty()) {
                        DropdownRow(
                            label = "Printer",
                            options = printerOptions.map { MenuOption(it, it) },
                            selected = printerOptions.firstOrNull { it == config.printer },
                            enabled = printer != null,
                            onSelect = { vm.setSetupPrinter(it) },
                        )
                    }
                    if (processOptions.isNotEmpty()) {
                        DropdownRow(
                            label = "Process",
                            options = processOptions.map { MenuOption(it, it) },
                            selected = process,
                            onSelect = { vm.setSetupProcess(it) },
                        )
                    }
                }
            }

            SwitchRow(
                icon = Icons.Filled.Build,
                title = "Supports",
                subtitle =
                    if (config.supportEnabled) {
                        "On · ${config.supportStyle.replaceFirstChar { it.uppercaseChar() }}" +
                            if (config.supportOnPlateOnly) " · plate only" else ""
                    } else {
                        "Off · enable for overhangs"
                    },
                checked = config.supportEnabled,
                onCheckedChange = { vm.setSupportEnabled(it) },
            )

            SettingsRow(
                icon = Icons.Filled.Tune,
                title = "Advanced settings",
                subtitle = advancedSummary(config),
                onClick = { showAdvanced = true },
            )

            Spacer(Modifier.height(Space.xs))
        }
    }

    if (showAdvanced) {
        AdvancedSettingsSheet(
            vm = vm,
            onDismiss = { showAdvanced = false },
        )
    }

    if (showPartsSheet && parts.size > 1) {
        PartsSheet(
            parts = parts,
            poses = poses,
            selectedIndex = selectedPart.coerceIn(0, parts.lastIndex),
            skipped = skipped,
            onTogglePrint = { idx -> vm.setPartPrinted(idx, idx in skipped) },
            onSelectAll = { vm.setAllPartsPrinted(true) },
            onSelectNone = { vm.setAllPartsPrinted(false) },
            onArrange = { vm.arrangeParts(volume.first, volume.second) },
            onPosePart = { idx ->
                vm.selectPart(idx)
                showPartsSheet = false
                fullscreen = true
            },
            onDismiss = { showPartsSheet = false },
        )
    }

        if (fullscreen) {
            PrintSetupFullscreen(
                vm = vm,
                controller = previewController,
                volume = volume,
                onClose = { fullscreen = false },
            )
        }
    }
}

/**
 * Compact multi-part controls right beneath the 3D build plate so users can tap parts and watch
 * the gold highlight / skip state update live on the plate without scrolling past a wall of rows.
 */
@Composable
private fun InlinePartsStrip(
    names: List<String>,
    selectedIndex: Int,
    skipped: Set<Int>,
    onSelect: (Int) -> Unit,
    onTogglePrint: (Int) -> Unit,
    onArrange: () -> Unit,
    onPose: () -> Unit,
    onManage: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val printingCount = names.indices.count { it !in skipped }
    val selectedPrinted = selectedIndex !in skipped
    val selectedName = names.getOrElse(selectedIndex) { "Part ${selectedIndex + 1}" }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        HorizontalDivider(color = LanternGold.copy(alpha = 0.18f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            BrandSectionLabel("Parts")
            Text(
                "· $printingCount of ${names.size}",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(
                onClick = onArrange,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Icon(Icons.Filled.AutoAwesomeMosaic, null, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(4.dp))
                Text("Arrange", fontSize = 12.sp)
            }
            TextButton(
                onClick = onManage,
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text("Manage", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        val partListState = rememberLazyListState()
        LaunchedEffect(selectedIndex, names.size) {
            if (selectedIndex in names.indices) {
                partListState.animateScrollToItem(selectedIndex)
            }
        }
        LazyRow(
            state = partListState,
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(names) { index, name ->
                val isSelected = index == selectedIndex
                val isPrinted = index !in skipped
                val shape = RoundedCornerShape(Radius.sm)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier =
                        Modifier
                            .clip(shape)
                            .background(
                                when {
                                    isSelected -> scheme.secondaryContainer
                                    isPrinted -> scheme.surfaceVariant.copy(alpha = 0.55f)
                                    else -> scheme.surfaceVariant.copy(alpha = 0.28f)
                                },
                            ).border(
                                BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color =
                                        if (isSelected) {
                                            LanternGold
                                        } else {
                                            LanternGold.copy(alpha = 0.16f)
                                        },
                                ),
                                shape,
                            ).clickable { onSelect(index) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    Box(
                        Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(
                                when {
                                    !isPrinted -> scheme.outline.copy(alpha = 0.45f)
                                    isSelected -> LanternGold
                                    else -> MossGreen
                                },
                            ),
                    )
                    Text(
                        text = name,
                        fontSize = 13.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                        color = if (isPrinted) scheme.onSurface else scheme.onSurfaceVariant,
                        textDecoration = if (isPrinted) TextDecoration.None else TextDecoration.LineThrough,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 140.dp),
                    )
                }
            }
        }

        // Quick action bar for the currently selected part: skip/include or jump into 3D pose.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(scheme.surfaceVariant.copy(alpha = 0.48f))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    selectedName,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    if (selectedPrinted) "Included on plate" else "Skipped",
                    fontSize = 11.sp,
                    color = if (selectedPrinted) scheme.onSurfaceVariant else scheme.error,
                )
            }
            Surface(
                onClick = { onTogglePrint(selectedIndex) },
                shape = RoundedCornerShape(10.dp),
                color = if (selectedPrinted) scheme.surface else scheme.errorContainer,
                border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        if (selectedPrinted) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = if (selectedPrinted) scheme.onSurface else scheme.onErrorContainer,
                    )
                    Text(
                        if (selectedPrinted) "Skip" else "Include",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (selectedPrinted) scheme.onSurface else scheme.onErrorContainer,
                    )
                }
            }
            Surface(
                onClick = onPose,
                shape = RoundedCornerShape(10.dp),
                color = scheme.primaryContainer,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Filled.OpenWith,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = scheme.onPrimaryContainer,
                    )
                    Text(
                        "Pose",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onPrimaryContainer,
                    )
                }
            }
        }
    }
}

/**
 * Full checklist for multi-part models (bulk All/None, dimensions per part, Auto-arrange, and
 * direct jump into 3D Pose for any part).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PartsSheet(
    parts: List<world.yemyat.slicepilot.model.MeshPart>,
    poses: List<Pose>?,
    selectedIndex: Int,
    skipped: Set<Int>,
    onTogglePrint: (Int) -> Unit,
    onSelectAll: () -> Unit,
    onSelectNone: () -> Unit,
    onArrange: () -> Unit,
    onPosePart: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val printing = parts.indices.count { it !in skipped }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = scheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = Space.lg, vertical = Space.sm),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Parts", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "$printing of ${parts.size} will print",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onSelectAll, enabled = printing != parts.size) {
                    Text("All")
                }
                TextButton(onClick = onSelectNone, enabled = printing != 0) {
                    Text("None")
                }
            }

            BrandButton(
                text = "Auto-arrange on plate",
                onClick = onArrange,
                role = ButtonRole.Tonal,
                icon = { Icon(Icons.Filled.AutoAwesomeMosaic, null, modifier = Modifier.size(18.dp)) },
                height = ButtonSize.Small,
                modifier = Modifier.fillMaxWidth(),
            )

            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 380.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(parts) { index, part ->
                    val printed = index !in skipped
                    val isSelected = index == selectedIndex
                    val dims =
                        remember(part, poses, index) {
                            val pose = poses?.getOrNull(index) ?: Pose()
                            part.footprint(pose)
                        }
                    val shape = RoundedCornerShape(Radius.sm)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(shape)
                                .background(
                                    if (printed) scheme.surfaceVariant.copy(alpha = 0.45f) else scheme.surfaceVariant.copy(alpha = 0.2f),
                                ).border(
                                    BorderStroke(
                                        width = if (isSelected) 1.5.dp else 1.dp,
                                        color = if (isSelected) LanternGold else LanternGold.copy(alpha = 0.14f),
                                    ),
                                    shape,
                                ).toggleable(
                                    value = printed,
                                    role = Role.Checkbox,
                                    onValueChange = { onTogglePrint(index) },
                                ).padding(start = 10.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                    ) {
                        Checkbox(checked = printed, onCheckedChange = null)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            Text(
                                part.name,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (printed) scheme.onSurface else scheme.onSurfaceVariant,
                                textDecoration = if (printed) TextDecoration.None else TextDecoration.LineThrough,
                            )
                            Text(
                                "${dims[0].format1()} × ${dims[1].format1()} × ${dims[2].format1()} mm",
                                fontSize = 11.sp,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        TextButton(
                            onClick = { onPosePart(index) },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        ) {
                            Text("Pose", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }

            BrandButton(
                text = "Done",
                onClick = onDismiss,
                role = ButtonRole.Primary,
                height = ButtonSize.Medium,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.xs))
        }
    }
}

@Composable
private fun AmsFilamentMapSection(
    assignments: List<ResolvedFilamentAssignment>,
    reportedTrays: List<world.yemyat.slicepilot.cloud.LoadedTray>,
    hasCustomMapping: Boolean,
    isLocalWithoutTrays: Boolean,
    onSelectSlot: (Int, FilamentSlot) -> Unit,
    onAutoMatch: () -> Unit,
    onEnterAccessCode: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val slots = remember(reportedTrays) { MultiColourMapping.availableSlots(reportedTrays) }
    val trayBySlot = remember(reportedTrays) { reportedTrays.associateBy { FilamentSlot(it.canvasId, it.trayId) } }
    val multiCanvas = remember(slots) { slots.map { it.canvasId }.distinct().size > 1 }
    val visibleRows = remember(assignments) { assignments.filter { it.used }.ifEmpty { assignments } }

    Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BrandSectionLabel("AMS filament map")
            Spacer(Modifier.width(6.dp))
            Text(
                "\u00b7 ${visibleRows.size} colours",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (hasCustomMapping && reportedTrays.isNotEmpty()) {
                TextButton(
                    onClick = onAutoMatch,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                ) {
                    Text("Auto-match", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (visibleRows.size > slots.size) {
            Text(
                "This model uses ${visibleRows.size} colours, while ${slots.size} AMS slots are available \u2014 " +
                    "extra colours share a slot.",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
        }

        visibleRows.forEach { assignment ->
            var menuOpen by remember(assignment.modelIndex) { mutableStateOf(false) }
            val modelDot = filamentColor(assignment.modelColorHex) ?: scheme.primary
            val spoolDot = filamentColor(assignment.spoolColorHex) ?: modelDot
            val slotTag = MultiColourMapping.formatSlotTag(assignment.slot, multiCanvas)
            val tray = trayBySlot[assignment.slot]
            val slotSummary =
                if (tray != null) {
                    "$slotTag \u00b7 ${tray.type.ifBlank { tray.name.ifBlank { "Spool" } }}"
                } else {
                    "Slot ${assignment.slot.trayId + 1} \u00b7 ${assignment.spoolType}"
                }

            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(scheme.surfaceVariant.copy(alpha = 0.45f))
                        .border(1.dp, LanternGold.copy(alpha = 0.16f), RoundedCornerShape(Radius.sm))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(modelDot)
                        .border(1.dp, scheme.outline.copy(alpha = 0.35f), CircleShape),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        "Colour ${assignment.modelIndex + 1}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = scheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        assignment.modelType,
                        fontSize = 11.sp,
                        color = scheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = scheme.onSurfaceVariant,
                )
                Box {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = scheme.surface,
                        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.25f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(spoolDot)
                                    .border(1.dp, scheme.outline.copy(alpha = 0.35f), CircleShape),
                            )
                            Text(
                                slotSummary,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = scheme.onSurface,
                                maxLines = 1,
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        slots.forEach { candidate ->
                            val ct = trayBySlot[candidate]
                            val tag = MultiColourMapping.formatSlotTag(candidate, multiCanvas)
                            val cDot = filamentColor(ct?.colorHex)
                            val label =
                                if (ct != null) {
                                    "$tag \u00b7 ${ct.type.ifBlank { ct.name.ifBlank { "Spool" } }}"
                                } else {
                                    "Slot ${candidate.trayId + 1}"
                                }
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    ) {
                                        if (cDot != null) {
                                            Box(
                                                Modifier
                                                    .size(14.dp)
                                                    .clip(CircleShape)
                                                    .background(cDot)
                                                    .border(1.dp, scheme.outline.copy(alpha = 0.35f), CircleShape),
                                            )
                                        }
                                        Text(
                                            label,
                                            fontWeight = if (candidate == assignment.slot) FontWeight.Bold else FontWeight.Normal,
                                        )
                                    }
                                },
                                onClick = {
                                    menuOpen = false
                                    onSelectSlot(assignment.modelIndex, candidate)
                                },
                            )
                        }
                    }
                }
            }
        }

        if (isLocalWithoutTrays) {
            Text(
                "The printer didn't report its loaded AMS spools \u2014 mapping to Slots 1\u20134 directly.",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant,
            )
            TextButton(
                onClick = onEnterAccessCode,
                contentPadding = PaddingValues(0.dp),
            ) {
                Text("Enter the printer's access code to load its spools", fontSize = 13.sp)
            }
        }
    }
}

@Composable
private fun WarningBanner(
    title: String,
    detail: String,
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Warning,
                null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(detail, fontSize = 12.sp, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
    }
}

fun printVolumeForProfile(
    context: android.content.Context,
    profileName: String,
): Triple<Float, Float, Float>? =
    readPrintableArea(context, profileName)?.let { area ->
        Triple(area[2] - area[0], area[3] - area[1], area[4])
    }

private fun readPrintableArea(
    context: android.content.Context,
    profileName: String,
): FloatArray? {
    if (profileName.isBlank()) return null
    val am = context.assets
    val base = "profiles/Elegoo/machine"
    return try {
        for (group in (am.list(base) ?: emptyArray())) {
            val path = "$base/$group/$profileName.json"
            val text =
                try {
                    am.open(path).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    continue
                }
            val obj = org.json.JSONObject(text)
            val area = obj.optJSONArray("printable_area") ?: continue
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until area.length()) {
                val parts = area.getString(i).split("x")
                if (parts.size != 2) continue
                val x = parts[0].toFloatOrNull() ?: continue
                val y = parts[1].toFloatOrNull() ?: continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
            val h = obj.optString("printable_height", "0").toFloatOrNull() ?: 0f
            if (maxX > minX && maxY > minY && h > 0f) {
                return floatArrayOf(minX, minY, maxX, maxY, h)
            }
        }
        null
    } catch (e: Exception) {
        null
    }
}

fun extruderCountForProfile(
    context: android.content.Context,
    profileName: String,
): Int {
    if (profileName.isBlank()) return 1
    val am = context.assets
    val base = "profiles/Elegoo/machine"
    return try {
        for (group in (am.list(base) ?: emptyArray())) {
            val path = "$base/$group/$profileName.json"
            val text =
                try {
                    am.open(path).bufferedReader().use { it.readText() }
                } catch (e: Exception) {
                    continue
                }
            val obj = org.json.JSONObject(text)
            // Single-nozzle Canvas / AMS printers (CC2, CC1 0.4C) address up to 4 spools.
            if (obj.optInt("single_extruder_multi_material", 0) == 1 || obj.optInt("support_mms", 0) == 1) return 4
            val nd = obj.optJSONArray("nozzle_diameter")
            if (nd != null && nd.length() > 0) return nd.length()
        }
        1
    } catch (e: Exception) {
        1
    }
}

private typealias Pose = world.yemyat.slicepilot.preview.ModelTransform
private typealias Face = world.yemyat.slicepilot.preview.ModelTransform.Face

private enum class FullscreenTool(val label: String) {
    FACE("Face"),
    ROTATE("Rotate"),
    MIRROR("Mirror"),
    MOVE("Move"),
}

@Composable
private fun PrintSetupFullscreen(
    vm: AppViewModel,
    controller: world.yemyat.slicepilot.preview.ModelPreviewView,
    volume: Triple<Float, Float, Float>,
    onClose: () -> Unit,
) {
    // One source of truth: the pose the app is actually using. The first version
    // of this sheet kept its own mx/my/mz booleans, so a change the renderer refused
    // still lit the chip up, and the angles had no place on screen at all — which is how
    // a model could end up saved as rot=(270, 720, 630) with nobody able to see it.
    val mesh by vm.mesh.collectAsState()
    val allPoses by vm.partPoses.collectAsState()
    val skipped by vm.partsSkipped.collectAsState()
    val selected by vm.selectedPart.collectAsState()
    val wholeModelTransform by vm.modelTransform.collectAsState()
    val darkTheme by vm.themeDark.collectAsState()
    val parts = mesh?.partsForRendering.orEmpty()

    /**
     * The pose this sheet owns. A file that is laid out piece by piece is posed piece by
     * piece, so these controls drive the piece the Pieces list picked: the renderer gives
     * every piece its own matrix and ignores the single whole-model transform, which meant
     * the buttons here moved the readout and left the plate alone. A single-piece file (and a
     * multi-part one nobody has laid out yet) still poses the whole model — observed via
     * [vm.modelTransform] so Face/Mirror/Orientation chips recompose immediately on every tap.
     */
    val wholePose =
        Pose(
            mirrorX = wholeModelTransform.mirrorX,
            mirrorY = wholeModelTransform.mirrorY,
            mirrorZ = wholeModelTransform.mirrorZ,
            rotX = wholeModelTransform.rotX,
            rotY = wholeModelTransform.rotY,
            rotZ = wholeModelTransform.rotZ,
            offsetX = wholeModelTransform.offsetX,
            offsetY = wholeModelTransform.offsetY,
        )
    val partPose: Pose? =
        allPoses?.takeIf { it.size == parts.size && parts.size > 1 }?.getOrNull(selected)
    val pose: Pose = partPose ?: wholePose
    val partTitle: String? = if (partPose == null) null else parts.getOrNull(selected)?.name
    var activeTool by remember { mutableStateOf(FullscreenTool.FACE) }
    var error by remember { mutableStateOf<String?>(null) }
    val step = 5f
    LaunchedEffect(error) {
        if (error != null) {
            delay(2200)
            error = null
        }
    }

    BackHandler(enabled = true) { onClose() }

    // The 3D viewport uses a light background; keep system bar icons dark while fullscreen
    // is shown so clock/battery and gesture pill remain legible even in dark theme.
    val view = LocalView.current
    DisposableEffect(view, darkTheme) {
        val window = view.context.findActivity()?.window
        val insetsController = window?.let { WindowCompat.getInsetsController(it, view) }
        insetsController?.isAppearanceLightStatusBars = true
        insetsController?.isAppearanceLightNavigationBars = true
        onDispose {
            insetsController?.isAppearanceLightStatusBars = !darkTheme
            insetsController?.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    /** Pose the selected piece, or say why the plate could not hold it. */
    fun applyToPart(next: Pose?) {
        val m = mesh ?: return
        val all = allPoses ?: return
        val label = parts.getOrNull(selected)?.name ?: "Part ${selected + 1}"
        if (next == null) {
            error = "$label can't be posed that way"
            return
        }
        val updated = all.mapIndexed { i, current -> if (i == selected) next else current }
        val included = List(parts.size) { it !in skipped }
        if (!PartPlacement.fits(m, updated, included, volume.first, volume.second, volume.third)) {
            error = "$label doesn't fit on the build plate in that pose"
            return
        }
        vm.setPartPose(selected, next)
    }

    /** Ask the renderer for a whole-model pose and publish the one it accepted. */
    fun applyToModel(applied: Boolean) {
        if (!applied) {
            error = "That pose doesn't fit on the build plate"
            return
        }
        vm.setModelTransform(controller.modelTransform())
    }

    /**
     * One Rotate / Mirror / Move / Face button with two meanings: the selected piece when the
     * file has a per-piece layout, the whole model when it has none.
     */
    fun applyPose(
        onPart: (Pose) -> Pose?,
        onModel: () -> Boolean,
    ) {
        val current = partPose
        if (current != null) applyToPart(onPart(current)) else applyToModel(onModel())
    }

    /**
     * The four Move arrows. A direction here is a *screen* direction, and the preview's camera
     * is what turns it into plate millimetres, so the piece follows the arrow however the plate
     * is orbited. The arrows used to add to the plate's own +X/-X/+Y/-Y, which only lined up
     * with the arrow from one camera angle - see OrbitCamera.plateDelta.
     */
    fun move(direction: ScreenDirection) {
        val current = partPose
        if (current == null) {
            applyToModel(controller.nudgeScreen(direction, step))
            return
        }
        val (dx, dy) = controller.screenDelta(direction, step)
        applyToPart(current.nudged(dx, dy))
    }

    // Render directly inside the edge-to-edge Activity window rather than a Dialog so system
    // bar insets (status bar at top, gesture/3-button navigation bar at bottom) match the real
    // window frame on every Android version without Dialog window clipping or offset quirks.
    // Registering a pointerInput node on the overlay cards stops sibling hit-testing from
    // falling through gaps into the GLSurfaceView behind them while keeping child clicks and
    // LazyRow horizontal scrolling untouched.
    val blockBackgroundTouches =
        Modifier.pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    awaitPointerEvent(PointerEventPass.Main)
                }
            }
        }

    Box(Modifier.fillMaxSize().background(Color(0xFF1A1C20))) {
        SharedAndroidView(
            view = controller,
            modifier = Modifier.fillMaxSize(),
        )

        // Top bar: pose readout + close, inset-safe so it never sits under the status bar.
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.statusBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                    ).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().then(blockBackgroundTouches),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OrientationCard(
                    pose,
                    footprint = partPose?.let { parts.getOrNull(selected)?.footprint(it) } ?: controller.footprintMm(),
                    title = partTitle,
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = onClose,
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = Color.White.copy(alpha = 0.90f),
                            contentColor = Color(0xFF1A1C20),
                        ),
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(Icons.Filled.Close, "Close")
                }
            }

            error?.let { msg ->
                Surface(
                    color = Color(0xFFB3261E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                ) {
                    Text(
                        msg,
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
            }
        }

        // Bottom tool dock: one active tool at a time so the 3D plate stays unobstructed,
        // with Reset clearly readable on the dark dock surface.
        Surface(
            color = Color(0xE81C2026),
            shape = RoundedCornerShape(Radius.lg),
            border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.25f)),
            modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.navigationBars
                            .union(WindowInsets.displayCutout)
                            .only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal),
                    ).padding(horizontal = 16.dp, vertical = 12.dp)
                    .then(blockBackgroundTouches),
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // For multi-part models, let users switch parts, skip/include, and auto-arrange
                // right inside fullscreen without closing the 3D editor.
                if (parts.size > 1) {
                    val activeIdx = selected.coerceIn(0, parts.lastIndex)
                    val activePrinted = activeIdx !in skipped
                    val partListState = rememberLazyListState()
                    LaunchedEffect(activeIdx, parts.size) {
                        partListState.animateScrollToItem(activeIdx)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        LazyRow(
                            state = partListState,
                            modifier = Modifier.weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            itemsIndexed(parts) { index, part ->
                                val isSel = index == activeIdx
                                val isPrinted = index !in skipped
                                val pillShape = RoundedCornerShape(10.dp)
                                Box(
                                    modifier =
                                        Modifier
                                            .heightIn(min = 36.dp)
                                            .clip(pillShape)
                                            .background(
                                                when {
                                                    isSel -> LanternGold
                                                    isPrinted -> Color.White.copy(alpha = 0.14f)
                                                    else -> Color.White.copy(alpha = 0.06f)
                                                },
                                            ).clickable { vm.selectPart(index) }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        part.name,
                                        color =
                                            when {
                                                isSel -> Color(0xFF1A1C20)
                                                isPrinted -> Color.White
                                                else -> Color.White.copy(alpha = 0.45f)
                                            },
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        textDecoration = if (isPrinted) TextDecoration.None else TextDecoration.LineThrough,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.widthIn(max = 120.dp),
                                    )
                                }
                            }
                        }
                        IconButton(
                            onClick = { vm.setPartPrinted(activeIdx, !activePrinted) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                if (activePrinted) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                contentDescription = if (activePrinted) "Skip part" else "Include part",
                                tint = if (activePrinted) Color.White else Color(0xFFFFB49E),
                                modifier = Modifier.size(19.dp),
                            )
                        }
                        IconButton(
                            onClick = { vm.arrangeParts(volume.first, volume.second) },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesomeMosaic,
                                contentDescription = "Auto-arrange parts",
                                tint = Color.White,
                                modifier = Modifier.size(19.dp),
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        modifier =
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(3.dp),
                        horizontalArrangement = Arrangement.spacedBy(3.dp),
                    ) {
                        FullscreenTool.values().forEach { tool ->
                            val active = tool == activeTool
                            Box(
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 38.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(if (active) Color.White else Color.Transparent)
                                        .clickable { activeTool = tool }
                                        .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    tool.label,
                                    fontSize = 12.sp,
                                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                    color = if (active) Color(0xFF1A1C20) else Color.White.copy(alpha = 0.80f),
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                    TextButton(
                        onClick = {
                            error = null
                            val current = partPose
                            if (current != null) {
                                // Back to where the file authored this piece, not to the plate centre.
                                applyToPart(parts.getOrNull(selected)?.poseAt(volume.first, volume.second))
                            } else {
                                controller.resetModelTransform()
                                vm.resetModelTransform()
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "Reset",
                            color = Color(0xFFFFB49E),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                when (activeTool) {
                    FullscreenTool.FACE -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FaceChip(Face.BOTTOM, pose, Modifier.weight(1f)) {
                                    applyPose({ it.forFace(Face.BOTTOM) }) { controller.setFaceDown(Face.BOTTOM) }
                                }
                                FaceChip(Face.TOP, pose, Modifier.weight(1f)) {
                                    applyPose({ it.forFace(Face.TOP) }) { controller.setFaceDown(Face.TOP) }
                                }
                                FaceChip(Face.FRONT, pose, Modifier.weight(1f)) {
                                    applyPose({ it.forFace(Face.FRONT) }) { controller.setFaceDown(Face.FRONT) }
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FaceChip(Face.BACK, pose, Modifier.weight(1f)) {
                                    applyPose({ it.forFace(Face.BACK) }) { controller.setFaceDown(Face.BACK) }
                                }
                                FaceChip(Face.LEFT, pose, Modifier.weight(1f)) {
                                    applyPose({ it.forFace(Face.LEFT) }) { controller.setFaceDown(Face.LEFT) }
                                }
                                FaceChip(Face.RIGHT, pose, Modifier.weight(1f)) {
                                    applyPose({ it.forFace(Face.RIGHT) }) { controller.setFaceDown(Face.RIGHT) }
                                }
                            }
                        }
                    }

                    FullscreenTool.ROTATE -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            listOf("X" to 'x', "Y" to 'y', "Z" to 'z').forEach { (label, axis) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    RotateButton(label) {
                                        applyPose({ it.rotated(axis) }) { controller.rotateModel(axis) }
                                    }
                                    Text(
                                        "$label +90°",
                                        color = Color.White.copy(alpha = 0.85f),
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                            }
                        }
                    }

                    FullscreenTool.MIRROR -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            MirrorChip("Mirror X", pose.mirrorX, Modifier.weight(1f)) {
                                applyPose({ it.mirrored('x') }) { controller.toggleMirror('x') }
                            }
                            MirrorChip("Mirror Y", pose.mirrorY, Modifier.weight(1f)) {
                                applyPose({ it.mirrored('y') }) { controller.toggleMirror('y') }
                            }
                            MirrorChip("Mirror Z", pose.mirrorZ, Modifier.weight(1f)) {
                                applyPose({ it.mirrored('z') }) { controller.toggleMirror('z') }
                            }
                        }
                    }

                    FullscreenTool.MOVE -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            MoveButton(Icons.Filled.ArrowUpward, "Move up") { move(ScreenDirection.UP) }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(20.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                MoveButton(Icons.AutoMirrored.Filled.ArrowBack, "Move left") { move(ScreenDirection.LEFT) }
                                Text(
                                    "${step.toInt()} mm",
                                    color = Color.White.copy(alpha = 0.72f),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                MoveButton(Icons.AutoMirrored.Filled.ArrowForward, "Move right") { move(ScreenDirection.RIGHT) }
                            }
                            MoveButton(Icons.Filled.ArrowDownward, "Move down") { move(ScreenDirection.DOWN) }
                        }
                    }
                }
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

/**
 * Says out loud what the model is doing: the angles behind the pose and the footprint the
 * plate has to hold. This is the readout that was missing when preview and slice disagreed
 * — the numbers now come from the same pose both of them use.
 */
@Composable
private fun OrientationCard(
    pose: Pose,
    footprint: FloatArray,
    title: String? = null,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = Color(0xE81C2026),
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.25f)),
        modifier = modifier,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text =
                    (if (title != null) "$title · " else "") +
                        "${pose.rotX.toInt()}° X · ${pose.rotY.toInt()}° Y · ${pose.rotZ.toInt()}° Z",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text =
                    "${footprint[0].format1()} × ${footprint[1].format1()} mm on plate · " +
                        "${footprint[2].format1()} mm tall",
                color = Color.White.copy(alpha = 0.75f),
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun Float.format1(): String = String.format(java.util.Locale.US, "%.1f", this)

/** One of the six faces of the authored bounding box, marked when it is the one on the plate. */
@Composable
private fun FaceChip(
    face: Face,
    current: Pose,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val selected = current.downFace() == face
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier =
            modifier
                .heightIn(min = 40.dp)
                .clip(shape)
                .background(if (selected) LanternGold else Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = face.name.lowercase().replaceFirstChar { it.uppercase() },
            color = if (selected) Color(0xFF1A1C20) else Color.White,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun MirrorChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier =
            modifier
                .heightIn(min = 44.dp)
                .clip(shape)
                .background(if (selected) LanternGold else Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onClick)
                .padding(horizontal = 10.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            color = if (selected) Color(0xFF1A1C20) else Color.White,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.90f),
                contentColor = Color(0xFF1A1C20),
            ),
        modifier = Modifier.size(44.dp),
    ) {
        Icon(icon, contentDescription)
    }
}

@Composable
private fun RotateButton(
    axis: String,
    onClick: () -> Unit,
) {
    val axisColor =
        when (axis) {
            "X" -> Color(0xFFD32F2F)
            "Y" -> Color(0xFF388E3C)
            else -> Color(0xFF1976D2)
        }
    FilledIconButton(
        onClick = onClick,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.85f),
                contentColor = Color.Black,
            ),
    ) {
        Canvas(modifier = Modifier.size(30.dp)) {
            val cx = size.width / 2f
            val cy = size.height / 2f
            val sw = 2.dp.toPx()
            val lineHalf = 11.dp.toPx()
            val arcR = 7.dp.toPx()
            val arcSw = 1.5.dp.toPx()
            val headS = 3.dp.toPx()

            when (axis) {
                "X" -> drawLine(axisColor, Offset(cx - lineHalf, cy), Offset(cx + lineHalf, cy), sw)
                "Y" -> drawLine(axisColor, Offset(cx, cy - lineHalf), Offset(cx, cy + lineHalf), sw)
                else -> drawCircle(axisColor, radius = 2.5.dp.toPx(), center = Offset(cx, cy))
            }

            drawArc(
                color = Color.Black,
                startAngle = 150f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = Offset(cx - arcR, cy - arcR),
                size = Size(arcR * 2f, arcR * 2f),
                style = Stroke(width = arcSw),
            )

            val endRad = Math.toRadians(30.0)
            val ex = cx + arcR * cos(endRad).toFloat()
            val ey = cy + arcR * sin(endRad).toFloat()
            val tx = sin(endRad).toFloat()
            val ty = -cos(endRad).toFloat()
            val px = -ty
            val py = tx
            val path =
                Path().apply {
                    moveTo(ex + tx * headS, ey + ty * headS)
                    lineTo(ex - tx * headS * 0.5f + px * headS * 0.7f, ey - ty * headS * 0.5f + py * headS * 0.7f)
                    lineTo(ex - tx * headS * 0.5f - px * headS * 0.7f, ey - ty * headS * 0.5f - py * headS * 0.7f)
                    close()
                }
            drawPath(path, Color.Black)
        }
    }
}
