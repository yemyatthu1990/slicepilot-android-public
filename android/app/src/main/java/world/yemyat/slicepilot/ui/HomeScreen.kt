package world.yemyat.slicepilot.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import world.yemyat.slicepilot.preview.ModelPreviewView
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.ui.theme.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    vm: AppViewModel,
    onPickModel: () -> Unit,
    onFullscreen: () -> Unit,
    onOpenConnect: () -> Unit,
    onOpenConfigure: () -> Unit,
) {
    val mesh by vm.mesh.collectAsState()
    val modelLoading by vm.modelLoading.collectAsState()
    val modelError by vm.modelError.collectAsState()
    val mErr = modelError
    val printers by vm.printers.collectAsState()
    val selectedPrinter by vm.selectedPrinterState.collectAsState()
    val availablePrinters by vm.availablePrinters.collectAsState()

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Spacer(Modifier.height(8.dp))
            BrandWordmark()
            Spacer(Modifier.height(18.dp))
            Text(
                "Make anything.",
                fontSize = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-1).sp,
            )
            Text(
                "Slice your models for your 3D printer.",
                fontSize = 17.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
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
                    title = when {
                        mErr != null -> "Model failed to load"
                        vm.modelPath != null -> "Model loaded"
                        else -> "Choose a model"
                    },
                    subtitle = when {
                        mErr != null -> mErr.code
                        vm.modelPath != null -> {
                            val f = java.io.File(vm.modelPath!!)
                            "${f.name} · ${(f.length() / 1024 / 1024.0).let { mb -> String.format("%.1f MB", mb) }}"
                        }
                        else -> "STL or 3MF from your phone"
                    },
                    onClick = onPickModel,
                )
                val isPrinterConnected = selectedPrinter != null || vm.cloudLoggedIn
                BigCard(
                    modifier = Modifier.weight(1f),
                    icon = {
                        Icon(
                            if (isPrinterConnected) {
                                if (selectedPrinter?.isLocal == true) Icons.Filled.Wifi else Icons.Filled.CloudDone
                            } else {
                                Icons.Filled.CloudOff
                            },
                            null,
                            tint = if (isPrinterConnected) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(30.dp),
                        )
                    },
                    iconBg = if (isPrinterConnected) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                    title = if (isPrinterConnected) "Connected" else "Connect printer",
                    subtitle = when {
                        selectedPrinter != null -> {
                            if (selectedPrinter!!.isLocal) {
                                "${selectedPrinter!!.name} · Local Wi-Fi"
                            } else {
                                "${selectedPrinter!!.name} · Cloud"
                            }
                        }
                        vm.cloudLoggedIn -> {
                            if (printers.isNotEmpty()) "${printers.size} printer${if (printers.size > 1) "s" else ""} · ${vm.cloudUser}"
                            else "Signed in as ${vm.cloudUser}"
                        }
                        else -> "Wi-Fi or Cloud"
                    },
                    onClick = onOpenConnect,
                )
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

            if (mesh != null && !modelLoading) {
                Text("Preview", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    ModelPreviewCanvas(mesh = mesh, onError = { vm.reportModelLoadError("render", it) })
                    FilledIconButton(
                        onClick = onFullscreen,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ),
                        modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    ) {
                        Icon(Icons.Filled.Fullscreen, "Fullscreen")
                    }
                }
            } else if (modelLoading) {
                Box(
                    Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(24.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }

            Spacer(Modifier.height(24.dp))

            BrandButton(
                text = "Print setup",
                onClick = { vm.openPrintSetup() },
                enabled = vm.modelPath != null && selectedPrinter != null,
                icon = { Icon(Icons.Filled.Print, null, tint = Color.White) },
                modifier = Modifier.fillMaxWidth(),
                height = 64.dp,
            )
            Spacer(Modifier.height(16.dp))
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
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .clickable(onClick = onClick),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(iconBg),
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
