package world.yemyat.slicepilot.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import world.yemyat.slicepilot.preview.ModelPreviewView
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.ui.theme.*
import kotlinx.coroutines.delay
import kotlin.math.max

@Composable
fun SlicingScreen(vm: AppViewModel) {
    val progress by vm.sliceProgress.collectAsState()
    val status by vm.sliceStatus.collectAsState()
    var showCancelConfirm by remember { mutableStateOf(false) }
    // Intercept the system back gesture/button while slicing: ask first.
    androidx.activity.compose.BackHandler(enabled = !showCancelConfirm) { showCancelConfirm = true }
    val mesh by vm.mesh.collectAsState()
    val modelTransform by vm.modelTransform.collectAsState()
    val reveal by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "sliceReveal",
    )
    var stalled by remember { mutableStateOf(false) }
    LaunchedEffect(progress) {
        stalled = false
        delay(2500)
        stalled = true
    }
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val bedHalf = remember(config.printer) {
        val v = printVolumeForProfile(context, config.printer ?: "") ?: Triple(256f, 256f, 256f)
        Pair(v.first / 2f, v.second / 2f)
    }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Box(Modifier.weight(1f)) {
            LiveSliceView(
                mesh = mesh,
                revealFraction = reveal,
                transform = modelTransform,
                bedHalfX = bedHalf.first,
                bedHalfY = bedHalf.second,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainer,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            shadowElevation = 12.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "Slicing",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (stalled) {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    )
                }
                Text(
                    status,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                OutlinedButton(
                    onClick = { showCancelConfirm = true },
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) { Text("Cancel") }
            }
        }
    }

    if (showCancelConfirm) {
        AlertDialog(
            onDismissRequest = { showCancelConfirm = false },
            title = { Text("Stop slicing?") },
            text = { Text("The slice in progress will be cancelled and the G-code will not be generated.") },
            confirmButton = {
                TextButton(onClick = {
                    showCancelConfirm = false
                    vm.cancelSlice()
                }) { Text("Stop slicing") }
            },
            dismissButton = {
                TextButton(onClick = { showCancelConfirm = false }) { Text("Keep slicing") }
            },
        )
    }
}

@Composable
fun LiveSliceView(
    mesh: MeshData?,
    revealFraction: Float,
    transform: AppViewModel.ModelTransform = AppViewModel.ModelTransform(),
    bedHalfX: Float = 128f,
    bedHalfY: Float = 128f,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val context = LocalContext.current
    val controller = remember { ModelPreviewView(context) }
    val transformArray = remember(transform) {
        floatArrayOf(
            transform.rotX, transform.rotY, transform.rotZ,
            if (transform.mirrorX) 1f else 0f,
            if (transform.mirrorY) 1f else 0f,
            if (transform.mirrorZ) 1f else 0f,
            transform.offsetX, transform.offsetY,
        )
    }
    val viewportDark = LocalIsDarkTheme.current
    LaunchedEffect(viewportDark) {
        if (viewportDark) controller.setSceneBackground(0.098f, 0.106f, 0.122f)
        else controller.setSceneBackground(0.914f, 0.937f, 0.973f)
    }
    LaunchedEffect(mesh, transformArray, bedHalfX, bedHalfY) {
        mesh?.let {
            controller.setMeshSlabs(it, transformArray, bedHalfX, bedHalfY)
            controller.setRevealFraction(revealFraction)
        }
    }
    LaunchedEffect(revealFraction) {
        controller.setRevealFraction(revealFraction)
    }
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
fun SlicePreviewScreen(
    vm: AppViewModel,
    preview: GcodePreview,
    onSaveGcode: () -> Unit,
    onPrint: () -> Unit,
) {
    var layerCount by remember { mutableFloatStateOf(preview.layers.size.toFloat()) }
    var fullscreen by remember { mutableStateOf(false) }
    val stats by vm.gcodeStats.collectAsState()
    val total = preview.layers.size
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val bedHalf = remember(config.printer, preview) {
        val v = printVolumeForProfile(context, config.printer ?: "")
        if (v != null) {
            Pair(v.first / 2f, v.second / 2f)
        } else {
            val half = max((preview.maxX - preview.minX) / 2f, (preview.maxY - preview.minY) / 2f).coerceAtLeast(1f)
            Pair(half, half)
        }
    }
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                GcodePreviewCanvas(
                    preview = preview,
                    visibleLayers = layerCount.toInt().coerceIn(1, total),
                    bedHalfX = bedHalf.first,
                    bedHalfY = bedHalf.second,
                    modelColorHex = stats?.filamentColor,
                    modifier = Modifier.fillMaxSize(),
                )
                FilledIconButton(
                    onClick = { fullscreen = !fullscreen },
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                    ),
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(
                        if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
                        if (fullscreen) "Exit fullscreen" else "Fullscreen",
                    )
                }
            }
            if (!fullscreen) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        stats?.let { s ->
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                StatCell("Time", s.estimatedTimeLabel ?: "—")
                                StatCell("Filament", s.filamentGrams?.let { String.format("%.1f g", it) } ?: "—")
                                StatCell("Length", s.filamentMm?.let { String.format("%.1f m", it / 1000f) } ?: "—")
                                StatCell("Layers", (s.layerCount ?: total).toString())
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                        Text(
                            "Layer ${layerCount.toInt().coerceIn(1, total)} of $total",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp,
                        )
                        Slider(
                            value = layerCount,
                            onValueChange = { layerCount = it },
                            valueRange = 1f..total.toFloat().coerceAtLeast(1f),
                            steps = 0,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedButton(
                                onClick = onSaveGcode,
                                shape = RoundedCornerShape(Radius.md),
                                modifier = Modifier.weight(1f).height(54.dp),
                            ) {
                                Icon(Icons.Filled.Save, null)
                                Spacer(Modifier.width(8.dp))
                                Text("Save G-code")
                            }
                            BrandButton(
                                text = "Print",
                                onClick = onPrint,
                                icon = { Icon(Icons.Filled.Print, null, tint = Color.White) },
                                modifier = Modifier.weight(1f),
                                height = 54.dp,
                            )
                        }
                        TextButton(onClick = { vm.backHome() }) { Text("Back to home") }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun FullscreenViewer(vm: AppViewModel, onClose: () -> Unit) {
    val mesh by vm.mesh.collectAsState()
    Box(Modifier.fillMaxSize()) {
        mesh?.let { ModelPreviewCanvas(mesh = it) }
        FilledIconButton(
            onClick = onClose,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
            ),
            modifier = Modifier.align(Alignment.TopEnd).padding(24.dp),
        ) {
            Icon(Icons.Filled.Close, "Close")
        }
    }
}
