package world.yemyat.slicepilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.ui.theme.*
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SliceScreen(vm: AppViewModel) {
    val config by vm.config.collectAsState()
    val mesh by vm.mesh.collectAsState()
    val quality by vm.setupQuality.collectAsState()
    val plate by vm.setupPlate.collectAsState()
    val material by vm.setupMaterial.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val nozzle by vm.selectedNozzle.collectAsState()
    val extruderCount = remember(config.printer) { extruderCountForProfile(context, config.printer ?: "") }
    val materialLabel = remember(material) {
        material?.let { vm.printSetupMaterials().firstOrNull { o -> o.key == it }?.label } ?: "—"
    }

    var custom by remember { mutableStateOf(
        config.layerHeight != null || config.wallLoops != null || config.topShellLayers != null ||
            config.bottomShellLayers != null || config.infillDensity != null || config.infillPattern != null
    ) }
    var advancedOpen by remember { mutableStateOf(custom) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Slice", fontWeight = FontWeight.Bold) },
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))

            SummaryCard(
                rows = listOf(
                    "Printer" to (printer?.let { "${it.name} · ${it.model}${if (it.isLocal) " (Local)" else ""}" } ?: "—"),
                    "Nozzle" to "$nozzle mm",
                    "Material" to materialLabel,
                    "Build plate" to plate,
                    "Quality" to quality,
                ),
            )

            // ---- Support ----
            SectionLabel("Support")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Enable support", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Switch(checked = config.supportEnabled, onCheckedChange = { vm.setSupportEnabled(it) })
                    }
                    if (config.supportEnabled) {
                        Text("Style", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = config.supportStyle == "grid",
                                onClick = { vm.setSupportStyle("grid") },
                                label = { Text("Grid") },
                            )
                            FilterChip(
                                selected = config.supportStyle == "trees",
                                onClick = { vm.setSupportStyle("trees") },
                                label = { Text("Trees") },
                            )
                        }
                        Text("Support filament", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (extruderCount <= 1) {
                            Text(
                                "Same as model (single extruder)",
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        } else {
                            val opts = listOf("Same as model") + (1 until extruderCount).map { "Extruder ${it + 1}" }
                            val selected = when (config.supportFilament) {
                                null -> "Same as model"
                                else -> "Extruder ${(config.supportFilament!!.toIntOrNull() ?: 0) + 1}"
                            }
                            SliceDropdown(
                                options = opts,
                                selected = selected,
                                onSelect = { label ->
                                    vm.setSupportFilament(
                                        if (label == "Same as model") null
                                        else label.filter { it.isDigit() }.let { if (it.isEmpty()) null else (it.toInt() - 1).toString() }
                                    )
                                },
                            )
                        }
                    }
                }
            }

            // ---- Advanced ----
            SectionLabel("Advanced")
            Card(
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Build, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Custom slice settings", fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                        Switch(checked = custom, onCheckedChange = { custom = it; advancedOpen = it })
                        Icon(
                            if (advancedOpen) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.clickable { advancedOpen = !advancedOpen },
                        )
                    }
                    if (advancedOpen) {
                        if (!custom) {
                            Text(
                                "Turn on to override the quality preset.",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            val lh = config.layerHeight ?: 0.2f
                            SliderField(
                                label = "Layer height",
                                value = lh,
                                valueRange = 0.08f..0.32f,
                                steps = 11,
                                valueLabel = String.format("%.2f mm", lh),
                                onValue = { vm.setLayerHeight((it * 50).roundToInt() / 50f) },
                            )
                            val walls = config.wallLoops ?: 2
                            SliderField(
                                label = "Wall loops",
                                value = walls.toFloat(),
                                valueRange = 1f..6f,
                                steps = 4,
                                valueLabel = "$walls",
                                onValue = { vm.setWallLoops(it.roundToInt()) },
                            )
                            val top = config.topShellLayers ?: 4
                            SliderField(
                                label = "Top shell layers",
                                value = top.toFloat(),
                                valueRange = 1f..8f,
                                steps = 6,
                                valueLabel = "$top",
                                onValue = { vm.setTopShellLayers(it.roundToInt()) },
                            )
                            val bot = config.bottomShellLayers ?: 4
                            SliderField(
                                label = "Bottom shell layers",
                                value = bot.toFloat(),
                                valueRange = 1f..8f,
                                steps = 6,
                                valueLabel = "$bot",
                                onValue = { vm.setBottomShellLayers(it.roundToInt()) },
                            )
                            val infill = config.infillDensity ?: 15
                            SliderField(
                                label = "Infill density",
                                value = infill.toFloat(),
                                valueRange = 0f..100f,
                                steps = 19,
                                valueLabel = "$infill%",
                                onValue = { vm.setInfillDensity(it.roundToInt()) },
                            )
                            Text("Infill pattern", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            SliceDropdown(
                                options = listOf("grid", "gyroid", "lines", "triangles", "honeycomb", "cubic", "concentric"),
                                selected = config.infillPattern ?: "grid",
                                onSelect = { vm.setInfillPattern(it) },
                            )
                            Text("Adhesion", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                listOf("auto", "none", "skirt", "brim", "raft").forEach { a ->
                                    FilterChip(
                                        selected = config.adhesion == a,
                                        onClick = { vm.setAdhesion(a) },
                                        label = { Text(a.replaceFirstChar { it.uppercase() }) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            BrandButton(
                text = "Slice",
                onClick = { vm.startSlice(bedType = vm.bedTypeForPlate(plate), autoPrint = false) },
                enabled = vm.modelPath != null && printer != null && material != null && mesh != null,
                icon = { Icon(Icons.Filled.ContentCut, null, tint = Color.White) },
                modifier = Modifier.fillMaxWidth(),
                height = 64.dp,
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun SummaryCard(rows: List<Pair<String, String>>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            rows.forEach { (label, value) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(value, fontSize = 13.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SliderField(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    valueLabel: String,
    onValue: (Float) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(valueLabel, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.coerceIn(valueRange.start, valueRange.endInclusive),
            onValueChange = onValue,
            valueRange = valueRange,
            steps = steps,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SliceDropdown(
    options: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}
