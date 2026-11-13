package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.PrintDisabled
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    val materials = remember(printer, filaments) { vm.printSetupMaterials() }
    val model = vm.modelPath?.let { java.io.File(it).name }

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
                val h =
                    parts.indices
                        .filter { it !in skipped }
                        .maxOfOrNull { parts[it].footprint(placedPoses[it])[2] }
                        ?.times(2f)
                        ?: 0f
                floatArrayOf(w, d, h)
            }

            else -> {
                floatArrayOf(meshData.maxX - meshData.minX, meshData.maxY - meshData.minY, meshData.maxZ - meshData.minZ)
            }
        }
    val fits = box[0] <= volume.first && box[1] <= volume.second && box[2] <= volume.third
    var fullscreen by remember { mutableStateOf(false) }

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
    LaunchedEffect(materialColor) { if (mesh != null) previewController.setModelColor(materialColor) }
    LaunchedEffect(plateColor) { if (mesh != null) previewController.setPlateColor(plateColor) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Print setup", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.backHome() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SummaryCard(
                model = model ?: "No model",
                printer = printer?.let { "${it.name} · ${it.model}${if (it.isLocal) " (Local)" else ""}" } ?: "No printer",
                nozzle = "$selectedNozzle mm",
            )

            // A picked file parses in the background; without this the page looks
            // empty (no preview yet) and reads as "did the upload work?".
            val loadErr = modelError
            if (model == null && loadErr != null) {
                WarningBanner(
                    "That file couldn't be opened",
                    "${loadErr.code} · ${loadErr.message}",
                )
            } else if (mesh == null && modelLoading) {
                val progress by vm.modelLoadProgress.collectAsState()
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        val pct = progress
                        CircularProgressIndicator(
                            progress = { (pct ?: 0) / 100f },
                            modifier = Modifier.size(22.dp),
                            strokeWidth = 2.dp,
                        )
                        Text(
                            pct?.let { "Reading model… $it%" } ?: "Reading model…",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (mesh != null) {
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(20.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    if (!fullscreen) {
                        SharedAndroidView(
                            view = previewController,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    FilledIconButton(
                        onClick = { fullscreen = true },
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                                contentColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Filled.Fullscreen, "Fullscreen")
                    }
                }
                if (!fits) {
                    WarningBanner(
                        "Model is larger than the build volume",
                        "Model ${box[0].toInt()}×${box[1].toInt()}×${box[2].toInt()} mm · " +
                            "Max ${volume.first.toInt()}×${volume.second.toInt()}×${volume.third.toInt()} mm",
                    )
                }
            }

            // Pieces: the UI half of per-object placement. Before this a 3MF holding two models
            // could only be moved as one blob — now each one can be rotated, moved or left off
            // the plate, and the plate can be re-laid out to fit them all.
            val partNames = mesh?.parts?.map { it.name }.orEmpty()
            if (partNames.size > 1) {
                PieceSection(
                    vm = vm,
                    names = partNames,
                    plateW = volume.first,
                    plateH = volume.second,
                )
            }

            SectionLabel("Material")
            MaterialDropdown(
                options = materials,
                selected = material,
                onSelect = { vm.setSetupMaterial(it) },
            )

            // When the printer reports its spools, the Material dropdown already lists them
            // per tray; only pick a bare slot manually when it reported nothing.
            if (printer?.isLocal == true && vm.lanSpools().isEmpty()) {
                // Slot 1 is the default; the CC2 has four inlets.
                val selectedTray = config.filamentTrayId ?: 0
                SectionLabel("Printer spool")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (0..3).forEach { trayId ->
                        FilterChip(
                            selected = selectedTray == trayId,
                            onClick = { vm.setPrintSlot(0, trayId) },
                            label = { Text("Slot ${trayId + 1}", maxLines = 1) },
                        )
                    }
                }
                Text(
                    "The printer didn't report its loaded filament — pick the slot it should load from.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = { vm.requestAccessCodeEntry() },
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text("Enter the printer's access code to load its filament", fontSize = 13.sp)
                }
            }

            SectionLabel("Build plate")
            ChipRow(
                options = listOf("Textured", "Smooth"),
                selected = plate,
                onSelect = { vm.setSetupPlate(it) },
            )

            SectionLabel("Quality")
            ChipRow(
                options = listOf("Draft", "Standard", "Quality"),
                selected = quality,
                onSelect = { vm.setSetupQuality(it) },
            )

            Spacer(Modifier.height(8.dp))

            BrandButton(
                text = "Next: Slice",
                onClick = { vm.openSlice() },
                enabled = vm.modelPath != null && printer != null && material != null && fits,
                icon = { Icon(Icons.Filled.Print, null, tint = Color.White) },
                modifier = Modifier.fillMaxWidth(),
                height = 64.dp,
            )
            Spacer(Modifier.height(24.dp))
        }
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

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/**
 * The pieces of the loaded model, in file order: tap a tile to pick the piece the viewer's
 * controls act on, tap the tile's print icon to leave that piece off the plate, and use
 * All / None instead of eight taps when only one of nine parts should print.
 *
 * Names come from the file (a 3MF names its objects); unnamed pieces are "Part 1", "Part 2", …
 *
 * The first version drew a FilterChip and a "prints/skipped" TextButton per piece in one
 * column: on a file of eight clips that was sixteen controls stacked twice as tall as the
 * preview, and the chips' outlines made the whole list read as selected at once. The pieces
 * are now a two-column grid of quiet tiles, and the chosen tile carries the same gold the
 * preview paints on that piece — the list and the plate agree about which one is meant.
 * The whole-model "Rotate 90°" button left with it: it duplicated the full-screen viewer's
 * Rotate, and on a file of symmetric parts it spun a piece you could not see turn.
 */
@Composable
private fun PieceSection(
    vm: AppViewModel,
    names: List<String>,
    plateW: Float,
    plateH: Float,
) {
    val selected by vm.selectedPart.collectAsState()
    val skipped by vm.partsSkipped.collectAsState()
    val printing = names.indices.count { it !in skipped }

    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            SectionLabel("Pieces")
            Text(
                text = "  $printing/${names.size}",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { vm.setAllPartsPrinted(true) }, enabled = printing != names.size) {
                Text("All", fontSize = 11.sp)
            }
            TextButton(onClick = { vm.setAllPartsPrinted(false) }, enabled = printing != 0) {
                Text("None", fontSize = 11.sp)
            }
            TextButton(onClick = { vm.arrangeParts(plateW, plateH) }) {
                Text("Re-arrange", fontSize = 11.sp)
            }
        }
        names.indices.chunked(2).forEach { rowIndices ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                modifier = Modifier.fillMaxWidth(),
            ) {
                rowIndices.forEach { index ->
                    PieceTile(
                        name = names[index],
                        selected = index == selected,
                        printed = index !in skipped,
                        onSelect = { vm.selectPart(index) },
                        onTogglePrint = { vm.setPartPrinted(index, index in skipped) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // An odd last piece keeps the grid's columns the same width.
                if (rowIndices.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/**
 * One piece in the grid. Selected pieces take the gold hairline (the same colour the preview
 * paints the piece with); pieces left off the plate get a struck-through name and a quiet
 * print-off icon, so "which ones are going" reads at a glance without a second column of
 * buttons.
 */
@Composable
private fun PieceTile(
    name: String,
    selected: Boolean,
    printed: Boolean,
    onSelect: () -> Unit,
    onTogglePrint: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(Radius.sm)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            modifier
                .clip(shape)
                .background(
                    when {
                        selected -> scheme.secondaryContainer
                        printed -> scheme.surface
                        else -> scheme.surfaceVariant.copy(alpha = 0.45f)
                    },
                    shape,
                ).border(
                    BorderStroke(
                        width = if (selected) 1.5.dp else 1.dp,
                        color = if (selected) LanternGold else Color.Transparent,
                    ),
                    shape,
                ).clickable(onClick = onSelect)
                .padding(start = 10.dp, end = 4.dp, top = 7.dp, bottom = 7.dp),
    ) {
        Text(
            text = name,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = if (printed) scheme.onSurface else scheme.onSurfaceVariant,
            textDecoration = if (printed) TextDecoration.None else TextDecoration.LineThrough,
            modifier = Modifier.weight(1f),
        )
        Box(
            modifier =
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .clickable(onClick = onTogglePrint),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (printed) Icons.Filled.Print else Icons.Filled.PrintDisabled,
                contentDescription = if (printed) "prints" else "skipped",
                modifier = Modifier.size(15.dp),
                tint = if (printed) LanternGold else scheme.onSurfaceVariant,
            )
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

@Composable
private fun SummaryCard(
    model: String,
    printer: String,
    nozzle: String,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SummaryRow("Model", model)
            SummaryRow("Printer", printer)
            SummaryRow("Nozzle", nozzle)
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MaterialDropdown(
    options: List<MaterialOption>,
    selected: String?,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedOpt = options.firstOrNull { it.key == selected }
    val selectedLabel = selectedOpt?.label ?: "Select material"
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (options.isNotEmpty()) expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            enabled = options.isNotEmpty(),
            leadingIcon = { if (selectedOpt != null) ColorDot(selectedOpt.colorHex) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    leadingIcon = { ColorDot(opt.colorHex) },
                    text = { Text(opt.label) },
                    onClick = {
                        onSelect(opt.key)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ColorDot(colorHex: String?) {
    val c =
        remember(colorHex) {
            colorHex?.let {
                try {
                    Color(android.graphics.Color.parseColor(it))
                } catch (_: Exception) {
                    null
                }
            }
        }
    Box(
        Modifier
            .size(18.dp)
            .clip(CircleShape)
            .background(c ?: MaterialTheme.colorScheme.outline)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

@Composable
private fun ChipRow(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { opt ->
            FilterChip(
                selected = opt == selected,
                onClick = { onSelect(opt) },
                label = { Text(opt, maxLines = 1) },
            )
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
            // Canvas ("C") CC1 profile: one nozzle, multiple materials via
            // mid-print Canvas swaps → model + support filament are selectable.
            if (obj.optInt("single_extruder_multi_material", 0) == 1) return 2
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
    val parts = mesh?.partsForRendering.orEmpty()

    /**
     * The pose this sheet owns. A file that is laid out piece by piece is posed piece by
     * piece, so these controls drive the piece the Pieces list picked: the renderer gives
     * every piece its own matrix and ignores the single whole-model transform, which meant
     * the buttons here moved the readout and left the plate alone. A single-piece file (and a
     * multi-part one nobody has laid out yet) still poses the whole model.
     */
    val partPose: Pose? =
        allPoses?.takeIf { it.size == parts.size && parts.size > 1 }?.getOrNull(selected)
    val pose: Pose = partPose ?: controller.currentTransform()
    val partTitle: String? = if (partPose == null) null else parts.getOrNull(selected)?.name
    var error by remember { mutableStateOf<String?>(null) }
    val step = 5f
    LaunchedEffect(error) {
        if (error != null) {
            delay(2200)
            error = null
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

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(Modifier.fillMaxSize().background(Color(0xFF1A1C20))) {
            SharedAndroidView(
                view = controller,
                modifier = Modifier.fillMaxSize(),
            )

            FilledIconButton(
                onClick = onClose,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = Color.White.copy(alpha = 0.85f),
                        contentColor = Color.Black,
                    ),
                modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
            ) {
                Icon(Icons.Filled.Close, "Close")
            }

            Column(
                modifier = Modifier.align(Alignment.TopStart).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OrientationCard(
                    pose,
                    footprint = partPose?.let { parts.getOrNull(selected)?.footprint(it) } ?: controller.footprintMm(),
                    title = partTitle,
                )

                SectionLabel("Put this face on the plate")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FaceChip(Face.BOTTOM, pose) {
                        applyPose({ it.forFace(Face.BOTTOM) }) { controller.setFaceDown(Face.BOTTOM) }
                    }
                    FaceChip(Face.TOP, pose) {
                        applyPose({ it.forFace(Face.TOP) }) { controller.setFaceDown(Face.TOP) }
                    }
                    FaceChip(Face.FRONT, pose) {
                        applyPose({ it.forFace(Face.FRONT) }) { controller.setFaceDown(Face.FRONT) }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FaceChip(Face.BACK, pose) {
                        applyPose({ it.forFace(Face.BACK) }) { controller.setFaceDown(Face.BACK) }
                    }
                    FaceChip(Face.LEFT, pose) {
                        applyPose({ it.forFace(Face.LEFT) }) { controller.setFaceDown(Face.LEFT) }
                    }
                    FaceChip(Face.RIGHT, pose) {
                        applyPose({ it.forFace(Face.RIGHT) }) { controller.setFaceDown(Face.RIGHT) }
                    }
                }

                SectionLabel("Rotate 90°")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    RotateButton("X") { applyPose({ it.rotated('x') }) { controller.rotateModel('x') } }
                    RotateButton("Y") { applyPose({ it.rotated('y') }) { controller.rotateModel('y') } }
                    RotateButton("Z") { applyPose({ it.rotated('z') }) { controller.rotateModel('z') } }
                }

                SectionLabel("Mirror")
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    MirrorChip("X", pose.mirrorX) {
                        applyPose({ it.mirrored('x') }) { controller.toggleMirror('x') }
                    }
                    MirrorChip("Y", pose.mirrorY) {
                        applyPose({ it.mirrored('y') }) { controller.toggleMirror('y') }
                    }
                    MirrorChip("Z", pose.mirrorZ) {
                        applyPose({ it.mirrored('z') }) { controller.toggleMirror('z') }
                    }
                }
            }

            error?.let { msg ->
                Surface(
                    color = Color(0xFFB3261E),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp),
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

            Column(
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MoveButton(Icons.Filled.ArrowUpward) { move(ScreenDirection.UP) }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(60.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    MoveButton(Icons.AutoMirrored.Filled.ArrowBack) { move(ScreenDirection.LEFT) }
                    MoveButton(Icons.AutoMirrored.Filled.ArrowForward) { move(ScreenDirection.RIGHT) }
                }
                MoveButton(Icons.Filled.ArrowDownward) { move(ScreenDirection.DOWN) }
                SectionLabel("Move on plate")
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
                modifier = Modifier.align(Alignment.BottomEnd).padding(24.dp),
            ) {
                Text("Reset", color = Color(0xFF1A1C20))
            }
        }
    }
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
) {
    Surface(color = Color.White.copy(alpha = 0.92f), shape = RoundedCornerShape(14.dp)) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text =
                    (if (title != null) "$title · " else "") +
                        "Rotating ${pose.rotX.toInt()}° X · ${pose.rotY.toInt()}° Y · ${pose.rotZ.toInt()}° Z",
                color = Color(0xFF1A1C20),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text =
                    "${footprint[0].format1()} × ${footprint[1].format1()} mm on plate · " +
                        "${footprint[2].format1()} mm tall",
                color = Color(0xFF5A5E66),
                fontSize = 11.sp,
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
    onClick: () -> Unit,
) {
    val selected = current == current.forFace(face)
    TextButton(
        onClick = onClick,
        modifier =
            Modifier.background(
                if (selected) Color(0xFF1A1C20) else Color.White.copy(alpha = 0.55f),
                RoundedCornerShape(10.dp),
            ),
    ) {
        Text(
            text = face.name.lowercase().replaceFirstChar { it.uppercase() },
            color = if (selected) Color.White else Color(0xFF1A1C20),
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun MirrorChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontWeight = FontWeight.Bold) },
    )
}

@Composable
private fun MoveButton(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    FilledIconButton(
        onClick = onClick,
        colors =
            IconButtonDefaults.filledIconButtonColors(
                containerColor = Color.White.copy(alpha = 0.85f),
                contentColor = Color.Black,
            ),
    ) {
        Icon(icon, null)
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
