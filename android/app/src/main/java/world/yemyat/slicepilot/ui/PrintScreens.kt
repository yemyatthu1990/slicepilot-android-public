package world.yemyat.slicepilot.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Light
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.cloud.PrintStatus
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.Green200
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius
import kotlin.math.roundToInt

@Composable
fun PrintingScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
) {
    val sim by vm.printSim.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
    val monitor by vm.printStatus.collectAsState()

    // Crossfade the "sending to printer" phase into the unified device status view
    // (progress, controls, camera and light) so the switch doesn't jar.
    val showMonitor = sim.complete && monitor != null
    AnimatedContent(
        targetState = showMonitor,
        transitionSpec = {
            ContentTransform(
                fadeIn(tween(450)) + scaleIn(tween(450), initialScale = 0.96f),
                fadeOut(tween(300)),
            )
        },
        label = "printPhase",
    ) { show ->
        if (show) {
            DeviceStatusScreen(vm = vm, printMode = true)
        } else {
            PrintStartScreen(vm = vm, sim = sim, printer = printer, onBack = onBack)
        }
    }
}

@Composable
private fun PrintStartScreen(
    vm: AppViewModel,
    sim: AppViewModel.PrintSimState,
    printer: ConnectedPrinter?,
    onBack: () -> Unit,
) {
    val fileName = vm.suggestedGcodeName
    val errorColor = MaterialTheme.colorScheme.error
    val accent =
        when {
            sim.failed -> errorColor
            sim.complete -> Green200
            else -> BrandCyan
        }

    val animatedProgress by animateFloatAsState(
        targetValue = sim.progress,
        animationSpec = tween(durationMillis = 650, easing = FastOutSlowInEasing),
        label = "printProgress",
    )
    val infinite = rememberInfiniteTransition(label = "printPulse")
    val pulse by infinite.animateFloat(
        initialValue = 0.92f,
        targetValue = 1.08f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
    val glow by infinite.animateFloat(
        initialValue = 0.10f,
        targetValue = 0.26f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "glow",
    )
    val density = androidx.compose.ui.platform.LocalDensity.current
    val shake = remember { Animatable(0f) }
    LaunchedEffect(sim.failed) {
        if (sim.failed) {
            val distance = with(density) { 10.dp.toPx() }
            repeat(3) {
                shake.animateTo(-distance, tween(70, easing = FastOutSlowInEasing))
                shake.animateTo(distance, tween(70, easing = FastOutSlowInEasing))
            }
            shake.animateTo(0f, tween(90))
        }
    }

    Box(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        sim.failed -> "Print failed"
                        sim.complete -> "Print started"
                        else -> "Sending to printer"
                    },
                    color = MaterialTheme.colorScheme.onBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        if (sim.failed) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(176.dp)
                                        .offset { IntOffset(shake.value.roundToInt(), 0) }
                                        .background(errorColor.copy(alpha = 0.16f), CircleShape),
                            )
                            Box(
                                modifier =
                                    Modifier
                                        .size(124.dp)
                                        .offset { IntOffset(shake.value.roundToInt(), 0) }
                                        .background(errorColor.copy(alpha = 0.28f), CircleShape),
                            )
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = errorColor,
                                modifier =
                                    Modifier
                                        .size(72.dp)
                                        .offset { IntOffset(shake.value.roundToInt(), 0) },
                            )
                        } else {
                            Box(
                                modifier =
                                    Modifier
                                        .size(184.dp)
                                        .scale(pulse)
                                        .background(accent.copy(alpha = glow), CircleShape),
                            )
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(152.dp),
                                color = accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeWidth = 10.dp,
                            )
                            Icon(
                                if (sim.complete) Icons.Filled.CheckCircle else Icons.Filled.CloudUpload,
                                contentDescription = null,
                                tint = accent,
                                modifier =
                                    Modifier
                                        .size(58.dp)
                                        .scale(if (sim.complete) 1f else pulse),
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    Text(
                        sim.message,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fileName,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    printer?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${it.name} · ${if (it.isLocal) "Local Wi-Fi" else "Cloud"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(Radius.lg),
                border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    vm.printSimSteps.forEachIndexed { index, step ->
                        val complete = sim.complete || index < sim.stepIndex
                        val active = !sim.complete && index == sim.stepIndex
                        AnimatedVisibility(
                            visible = complete || active,
                            enter = fadeIn(tween(220)) + slideInVertically(tween(260)) { it / 2 },
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    when {
                                        complete -> Icons.Filled.CheckCircle
                                        active && sim.failed -> Icons.Filled.ErrorOutline
                                        active -> Icons.Filled.Print
                                        else -> Icons.Filled.RadioButtonUnchecked
                                    },
                                    contentDescription = null,
                                    tint =
                                        when {
                                            complete -> Green200
                                            active && sim.failed -> errorColor
                                            active -> BrandCyan
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    modifier =
                                        Modifier
                                            .size(22.dp)
                                            .scale(if (active && !sim.failed) pulse else 1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    step.label,
                                    color =
                                        if (complete || active) {
                                            MaterialTheme.colorScheme.onSurface
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    fontSize = 15.sp,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                                )
                                Spacer(Modifier.weight(1f))
                                Text(
                                    when {
                                        complete -> "Done"
                                        active -> "Working…"
                                        else -> ""
                                    },
                                    color =
                                        when {
                                            complete -> Green200
                                            active -> BrandCyan
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            if (sim.complete) {
                BrandButton(
                    text = "Done",
                    onClick = { vm.finishPrintToHome() },
                    modifier = Modifier.fillMaxWidth(),
                    height = ButtonSize.Large,
                )
            } else {
                BrandButton(
                    text = if (sim.failed) "Back" else "Cancel",
                    onClick = onBack,
                    role = if (sim.failed) ButtonRole.Destructive else ButtonRole.Secondary,
                    modifier = Modifier.fillMaxWidth(),
                    height = ButtonSize.Large,
                )
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

/**
 * The printer screen, shared by two entries: "Device status" from the home card and the
 * print monitor after a print starts ([printMode]). Live status pill, camera preview and
 * case light at the top; a compact progress card with pause/resume/stop while printing;
 * temperature and fan controls (Matrix gates fans to the idle state).
 */
@Composable
fun DeviceStatusScreen(
    vm: AppViewModel,
    printMode: Boolean = false,
) {
    val mon by vm.printStatus.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
    val cameraOn by vm.cameraOn.collectAsState()
    val demoMode by vm.demoModeState.collectAsState()
    val status = mon
    val errorColor = MaterialTheme.colorScheme.error
    val pausedColor = LanternGold
    val state = status?.state
    val live = status?.live == true
    val printing =
        status != null && (
            state == PrintJobState.PRINTING || state == PrintJobState.PAUSING ||
                state == PrintJobState.PAUSED || state == PrintJobState.RESUMING ||
                state == PrintJobState.STOPPING
        )
    val accent =
        when {
            state == PrintJobState.ERROR -> errorColor
            state?.isPaused == true -> pausedColor
            printing -> BrandCyan
            else -> Green200
        }
    val headline =
        when {
            status == null -> "Connecting to printer…"
            !live -> "No live status from printer"
            state == PrintJobState.PRINTING -> "Printing"
            state == PrintJobState.PAUSING -> "Pausing…"
            state == PrintJobState.PAUSED -> "Paused"
            state == PrintJobState.RESUMING -> "Resuming…"
            state == PrintJobState.STOPPING -> "Stopping…"
            state == PrintJobState.COMPLETED -> "Print completed"
            state == PrintJobState.STOPPED -> "Print stopped"
            state == PrintJobState.ERROR -> "Printer reported an error"
            else -> "Idle"
        }
    val animatedProgress by animateFloatAsState(
        targetValue = (status?.progress ?: 0) / 100f,
        animationSpec = tween(durationMillis = 900, easing = FastOutSlowInEasing),
        label = "deviceProgress",
    )
    var confirmPause by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    if (confirmPause) {
        AlertDialog(
            onDismissRequest = { confirmPause = false },
            title = { Text("Pause print?") },
            text = {
                Text("The printer will pause at the current position. You can resume from here or from the notification.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPause = false
                    vm.pausePrint()
                }) { Text("Pause") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPause = false }) { Text("Keep printing") }
            },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop print?") },
            text = {
                Text(
                    "The print will be stopped and the printer will cool down. A stopped print cannot be resumed — the file has to be started again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    vm.stopPrintJob()
                }) { Text("Stop print", color = errorColor) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Keep printing") }
            },
        )
    }
    // Fan sliders: CC2 PWM 0-255 mapped to percent; follow status until the user drags.
    val fanPctFromStatus = ((status?.partFanSpeed ?: 0).coerceIn(0, 255) * 100 + 127) / 255
    var fanPct by remember { mutableIntStateOf(fanPctFromStatus) }
    LaunchedEffect(fanPctFromStatus) { fanPct = fanPctFromStatus }
    val auxPctFromStatus = ((status?.auxFanSpeed ?: 0).coerceIn(0, 255) * 100 + 127) / 255
    var auxPct by remember { mutableIntStateOf(auxPctFromStatus) }
    LaunchedEffect(auxPctFromStatus) { auxPct = auxPctFromStatus }

    Box(
        modifier =
            Modifier
                .fillMaxSize(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(horizontal = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { if (printMode) vm.finishPrintToHome() else vm.closeDeviceStatus() }) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                        tint = MaterialTheme.colorScheme.onBackground,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (printMode) "Print monitor" else "Device status",
                        color = MaterialTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    printer?.let {
                        Text(
                            "${it.name} · ${it.model} · ${if (it.isLocal) "Local" else "Cloud"}",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 13.sp,
                        )
                    }
                }
                // Case light: labeled pill (a bare bulb icon reads like a notification).
                if (status != null && live) {
                    val lightOn = status?.lightOn == true
                    Surface(
                        onClick = { vm.setCaseLight(!lightOn) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (lightOn) pausedColor.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, if (lightOn) pausedColor else LanternGold.copy(alpha = 0.22f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Light,
                                contentDescription = null,
                                tint = if (lightOn) pausedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Light",
                                color = if (lightOn) pausedColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                    Spacer(Modifier.width(4.dp))
                }
                // Camera preview toggle (LAN: MJPEG :8080; cloud: Agora RTC).
                if (status != null && live && printer != null && !demoMode) {
                    IconButton(onClick = { vm.toggleCamera() }) {
                        Icon(
                            if (cameraOn) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            contentDescription = "Camera",
                            tint = if (cameraOn) BrandCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = accent.copy(alpha = 0.18f),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        Modifier
                            .size(9.dp)
                            .clip(CircleShape)
                            .background(accent),
                    )
                    Text(
                        headline,
                        color = accent,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }

            Spacer(Modifier.height(28.dp))
            AnimatedVisibility(
                visible = cameraOn,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200)),
            ) {
                Column {
                    Box(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(16f / 9f)
                                .clip(RoundedCornerShape(Radius.md))
                                .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        // Pinch-zoomable live view (also openable full screen).
                        CameraStage(vm, Modifier.fillMaxSize())
                        Row(
                            modifier =
                                Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            // Save the picture on screen, then open the rotated full-screen view.
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
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
            if (status == null) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    color = BrandCyan,
                    strokeWidth = 3.dp,
                )
            } else if (printMode && !live) {
                Spacer(Modifier.height(24.dp))
                Text(
                    "Connecting to live status…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                )
            } else {
                AnimatedVisibility(
                    visible = printing,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 4 },
                    exit = fadeOut(tween(250)),
                ) {
                    // Compact progress card (was a big ring) with the print controls inline.
                    Surface(
                        shape = RoundedCornerShape(Radius.md),
                        color = MaterialTheme.colorScheme.surface,
                        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "${status.progress}%",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Spacer(Modifier.width(14.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        headline,
                                        color = accent,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    status.filename.takeIf { it.isNotBlank() }?.let {
                                        Text(
                                            it,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                            LinearProgressIndicator(
                                progress = { animatedProgress },
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(5.dp)),
                                color = accent,
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                strokeCap = StrokeCap.Round,
                            )
                            Row(modifier = Modifier.fillMaxWidth()) {
                                MonitorInfo("Layer", "${status.currentLayer}/${status.totalLayer}", Modifier.weight(1f))
                                MonitorInfo("Remaining", deviceFmtRemaining(status.remainingSec), Modifier.weight(1f))
                                MonitorInfo("Elapsed", deviceFmtRemaining(status.elapsedSec), Modifier.weight(1f))
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (status.state.isPaused) vm.resumePrint() else confirmPause = true
                                    },
                                    modifier =
                                        Modifier
                                            .weight(1f)
                                            .height(48.dp),
                                    shape = RoundedCornerShape(Radius.md),
                                    border = BorderStroke(1.dp, accent),
                                ) {
                                    Icon(
                                        if (status.state.isPaused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                                        contentDescription = null,
                                        tint = accent,
                                        modifier = Modifier.size(20.dp),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        if (status.state.isPaused) "Resume" else "Pause",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = accent,
                                    )
                                }
                                BrandButton(
                                    text = "Stop",
                                    onClick = { confirmStop = true },
                                    role = ButtonRole.Destructive,
                                    icon = {
                                        Icon(
                                            Icons.Filled.Stop,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    height = 48.dp,
                                )
                            }
                        }
                    }
                }
                AnimatedVisibility(
                    visible = !printing && !cameraOn,
                    enter = fadeIn(tween(300)),
                    exit = fadeOut(tween(200)),
                ) {
                    // The camera preview replaces the idle printer glyph.
                    Column {
                        Icon(
                            Icons.Filled.Print,
                            contentDescription = null,
                            tint = accent,
                            modifier = Modifier.size(64.dp),
                        )
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Surface(
                shape = RoundedCornerShape(Radius.md),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 16.dp),
                ) {
                    MonitorInfo(
                        "Nozzle",
                        status?.let { "${it.nozzleTemp.deviceTemp()}° / ${it.nozzleTarget.deviceTemp()}°" } ?: "–",
                        Modifier.weight(1f),
                    )
                    MonitorInfo(
                        "Bed",
                        status?.let { "${it.bedTemp.deviceTemp()}° / ${it.bedTarget.deviceTemp()}°" } ?: "–",
                        Modifier.weight(1f),
                    )
                    status?.takeIf { it.chamberTemp > 0f }?.let {
                        MonitorInfo("Chamber", "${it.chamberTemp.deviceTemp()}°", Modifier.weight(1f))
                    }
                }
            }
            if (status != null && live) {
                Spacer(Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(Radius.md),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            TargetMenu(
                                label = "Nozzle",
                                currentTemp = status?.nozzleTarget?.toInt() ?: -1,
                                presets = listOf(0, 180, 200, 220, 240),
                                onPick = { vm.setNozzleTarget(it) },
                            )
                            TargetMenu(
                                label = "Bed",
                                currentTemp = status?.bedTarget?.toInt() ?: -1,
                                presets = listOf(0, 45, 60, 80),
                                onPick = { vm.setBedTarget(it) },
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        if (printing) {
                            // Matrix gates fan control to the idle state (machine status 26);
                            // while printing the firmware auto-controls fans (G-code, chamber
                            // and bed temperature), so mirror it: show speed, no sliders.
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Fans (printer-controlled)",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "Model $fanPct% · Aux $auxPct%",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Model fan",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$fanPct%",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Slider(
                                value = fanPct.toFloat(),
                                onValueChange = { fanPct = it.toInt() },
                                onValueChangeFinished = { vm.setFanSpeed(fanPct) },
                                valueRange = 0f..100f,
                                colors =
                                    SliderDefaults.colors(
                                        thumbColor = BrandCyan,
                                        activeTrackColor = BrandCyan,
                                    ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    "Aux fan",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$auxPct%",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Slider(
                                value = auxPct.toFloat(),
                                onValueChange = { auxPct = it.toInt() },
                                onValueChangeFinished = { vm.setAuxFanSpeed(auxPct) },
                                valueRange = 0f..100f,
                                colors =
                                    SliderDefaults.colors(
                                        thumbColor = BrandCyan,
                                        activeTrackColor = BrandCyan,
                                    ),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(8.dp))
            when {
                printMode && state?.isTerminal == true -> {
                    BrandButton(
                        text = "Done",
                        onClick = { vm.finishPrintToHome() },
                        modifier = Modifier.fillMaxWidth(),
                        height = 56.dp,
                    )
                }

                printMode -> {
                    // Printing: no Done button — the header back arrow leaves; the
                    // print and its notification keep going.
                }

                else -> {
                    TextButton(onClick = { vm.closeDeviceStatus() }) {
                        Text("Back to home", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun deviceFmtRemaining(seconds: Int?): String =
    when {
        seconds == null || seconds <= 0 -> "—"
        seconds >= 3600 -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        else -> "${seconds / 60}m"
    }

/** Compact temperature target menu (device status, keeps the card minimal). */
@Composable
private fun TargetMenu(
    label: String,
    currentTemp: Int,
    presets: List<Int>,
    onPick: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Surface(
        onClick = { expanded = true },
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)),
    ) {
        Box {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$label ${if (currentTemp <= 0) "Off" else "$currentTemp°"}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(if (t == 0) "Off" else "$t °C") },
                        onClick = {
                            expanded = false
                            onPick(t)
                        },
                    )
                }
            }
        }
    }
}

private fun Float.deviceTemp(): String = ((this * 10).toInt() / 10f).toString()

@Composable
private fun MonitorInfo(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp,
        )
    }
}

// --- live camera: inline preview and the full-screen route -----------------

/**
 * The shutter on the live view: one tap saves the picture currently shown as a
 * photo. For a moment afterwards it shows that photo, which doubles as the
 * confirmation (a snackbar would be off-screen in the rotated full-screen view).
 */
@Composable
internal fun CameraShutter(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    diameter: Dp = 30.dp,
) {
    var shotState by remember { mutableStateOf<AppViewModel.CameraPhoto?>(null) }
    LaunchedEffect(shotState) {
        if (shotState != null) {
            delay(2600)
            shotState = null
        }
    }
    val cameraOn by vm.cameraOn.collectAsState()
    val shot = shotState
    Box(
        modifier
            .size(diameter)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.55f))
            // While a shot is in hand the button is the shortcut to the gallery;
            // otherwise it takes one.
            .clickable(enabled = cameraOn) {
                if (shot != null) vm.openPhotos() else vm.captureCameraPhoto { shotState = it }
            },
        contentAlignment = Alignment.Center,
    ) {
        val photo = shot?.bitmap
        if (photo != null) {
            Image(
                bitmap = photo.asImageBitmap(),
                contentDescription = "Photo just taken",
                contentScale = ContentScale.Crop,
                modifier =
                    Modifier
                        .size(diameter)
                        .clip(CircleShape),
            )
        } else {
            Icon(
                Icons.Filled.PhotoCamera,
                contentDescription = "Take a photo",
                tint = Color.White,
                modifier = Modifier.size(diameter * 0.55f),
            )
        }
    }
}

/**
 * The live camera picture (LAN MJPEG frame or cloud Agora RTC surface) with
 * pinch-zoom and pan; double-tap resets the zoom. Used inline on the device
 * status screen and, full screen, by [CameraFullscreen].
 */
@Composable
fun CameraStage(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
) {
    val frame by vm.cameraFrame.collectAsState()
    val surface by vm.cameraSurface.collectAsState()
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }

    fun scaled(source: Modifier): Modifier =
        source.graphicsLayer {
            scaleX = zoom
            scaleY = zoom
            translationX = pan.x
            translationY = pan.y
        }

    val gesture =
        Modifier
            .pointerInput(Unit) {
                detectTransformGestures { _, panBy, scaleBy, _ ->
                    val next = (zoom * scaleBy).coerceIn(1f, 6f)
                    pan = if (next == 1f) Offset.Zero else pan + panBy
                    zoom = next
                }
            }.pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    zoom = 1f
                    pan = Offset.Zero
                })
            }

    Box(
        modifier = modifier.then(gesture),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap: Bitmap? = frame
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Camera preview",
                contentScale = contentScale,
                modifier = scaled(Modifier.fillMaxSize()),
            )
        } else {
            val rtcSurface = surface
            if (rtcSurface != null) {
                AndroidView(
                    factory = { rtcSurface },
                    modifier = scaled(Modifier.fillMaxSize()),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
    }
}

/**
 * The full-screen camera route (Screen.CameraFull): the activity itself turns to
 * landscape and hides its system bars, so the picture — not a dialog floating
 * over a still-visible page — is the whole screen (feedback: "the underlying
 * screen also rotate and it's leaking"). Orientation and bar visibility are
 * restored when the route closes; MainActivity declares
 * `configChanges="orientation|screenSize"`, so the rotation never recreates it.
 */
@Composable
fun CameraFullscreen(
    vm: AppViewModel,
    onClose: () -> Unit,
) {
    val view = LocalView.current
    val activity = remember(view) { view.context.findActivity() }
    DisposableEffect(view) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        val controller = window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        val previousBehavior = controller?.systemBarsBehavior
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window?.let { WindowCompat.setDecorFitsSystemWindows(it, false) }
        controller?.apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose {
            controller?.apply {
                previousBehavior?.let { systemBarsBehavior = it }
                show(WindowInsetsCompat.Type.systemBars())
            }
            activity?.requestedOrientation =
                previousOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // The printer frame is landscape: crop it to the rotated screen instead of
        // letterboxing, then pinch-zoom / pan the same way as the inline preview.
        CameraStage(
            vm = vm,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        FilledIconButton(
            onClick = onClose,
            colors =
                IconButtonDefaults.filledIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                ),
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
        ) {
            Icon(Icons.Filled.Close, "Close camera")
        }
        // Shutter on the camera-side edge, where the thumb rests in landscape.
        CameraShutter(
            vm = vm,
            modifier =
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 20.dp, bottom = 12.dp),
            diameter = 48.dp,
        )
    }
}

/** The Activity a Compose view is attached to (unwraps the ContextWrapper chain). */
private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

// --- the photo gallery: what the shutter saved ------------------------------------

/**
 * The Photos shelf of the Files tab: every photo the camera took, newest first, with
 * a full-size viewer. The system gallery holds the same files, but the printer's
 * shots belong with the printer — so they live next to the models the app keeps.
 *
 * The Files tab draws the header; this is the content, plus the viewer it opens.
 */
@Composable
fun PhotosTab(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val photos by vm.photos.collectAsState()
    var opened by remember { mutableStateOf<AppViewModel.CameraPhoto?>(null) }
    // The live entry for the opened photo, so a save or delete shows up at once.
    val photo = opened?.let { open -> photos.firstOrNull { it.name == open.name } ?: open }
    if (photo != null) {
        PhotoViewer(
            vm = vm,
            photo = photo,
            onBack = { opened = null },
        )
        return
    }

    if (photos.isEmpty()) {
        Column(
            modifier
                .fillMaxSize()
                .padding(top = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                Icons.Filled.PhotoCamera,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                "No photos yet",
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Take one with the shutter on the printer camera.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(photos, key = { it.name }) { item ->
                PhotoCard(
                    vm = vm,
                    photo = item,
                    onClick = { opened = item },
                )
            }
        }
    }
}

/** One gallery cell: the picture and when it was taken. */
@Composable
private fun PhotoCard(
    vm: AppViewModel,
    photo: AppViewModel.CameraPhoto,
    onClick: () -> Unit,
) {
    val bitmap = rememberPhotoBitmap(vm, photo, 600)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(14.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = photo.name,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                )
            }
        }
        Text(
            photoCaption(photo.takenAt),
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** One photo at full size, with the date and a delete button. */
@Composable
private fun PhotoViewer(
    vm: AppViewModel,
    photo: AppViewModel.CameraPhoto,
    onBack: () -> Unit,
) {
    val view = LocalView.current
    val side = if (view.width > view.height) view.width else 1080
    val bitmap = rememberPhotoBitmap(vm, photo, side)
    var notice by remember(photo) { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(2600)
            notice = null
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this photo?") },
            text = {
                Text(
                    if (photo.galleryUri != null) {
                        "\"${photo.name}\" will be deleted from the app and from Pictures/SlicePilot."
                    } else {
                        "\"${photo.name}\" will be deleted from the app."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deletePhoto(photo)
                    onBack()
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text("Keep", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = photo.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                strokeWidth = 2.dp,
            )
        }
        // Top: back and when the picture was taken.
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .statusBarsPadding()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    "Back",
                    tint = Color.White,
                )
            }
            Text(
                photoCaption(photo.takenAt),
                fontSize = 13.sp,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
        }
        // Bottom: a right-aligned pill of actions — share, save (or the fact that it
        // is already saved), delete — floating on a gradient so the picture keeps the
        // frame. Feedback about an action shows as a line above the pill.
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000))))
                    .navigationBarsPadding()
                    .padding(start = 12.dp, end = 12.dp, top = 28.dp, bottom = 8.dp),
            horizontalAlignment = Alignment.End,
        ) {
            notice?.let {
                Text(
                    it,
                    fontSize = 11.sp,
                    color = Color.White.copy(alpha = 0.85f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Surface(
                shape = RoundedCornerShape(999.dp),
                color = Color(0x66000000),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        onClick = {
                            notice =
                                if (vm.sharePhoto(photo)) {
                                    "Opening share…"
                                } else {
                                    "Couldn't share this photo"
                                }
                        },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Share,
                                null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text("Share", fontSize = 13.sp, color = Color.White)
                        }
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(Color.White.copy(alpha = 0.28f)),
                    )
                    if (photo.galleryUri != null) {
                        TextButton(
                            onClick = { notice = "Already in Pictures/SlicePilot" },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    null,
                                    tint = Green200,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Saved", fontSize = 13.sp, color = Color.White)
                            }
                        }
                    } else {
                        TextButton(
                            onClick = {
                                vm.savePhotoToGallery(photo) { uri ->
                                    notice =
                                        if (uri != null) {
                                            "Saved to Pictures/SlicePilot"
                                        } else {
                                            "Couldn't save this photo"
                                        }
                                }
                            },
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Filled.FileDownload,
                                    null,
                                    tint = Color.White,
                                    modifier = Modifier.size(15.dp),
                                )
                                Spacer(Modifier.width(6.dp))
                                Text("Save", fontSize = 13.sp, color = Color.White)
                            }
                        }
                    }
                    Box(
                        Modifier
                            .width(1.dp)
                            .height(16.dp)
                            .background(Color.White.copy(alpha = 0.28f)),
                    )
                    TextButton(
                        onClick = { confirmDelete = true },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Icon(
                            Icons.Filled.Delete,
                            "Delete photo",
                            tint = Color.White,
                            modifier = Modifier.size(15.dp),
                        )
                    }
                }
            }
        }
    }
}

/** Decode [photo] off the main thread; a capture hands over its own bitmap. */
@Composable
private fun rememberPhotoBitmap(
    vm: AppViewModel,
    photo: AppViewModel.CameraPhoto,
    maxPx: Int,
): Bitmap? {
    var bitmap by remember(photo, maxPx) { mutableStateOf(photo.bitmap) }
    LaunchedEffect(photo, maxPx) {
        if (bitmap == null) bitmap = withContext(Dispatchers.IO) { vm.loadPhotoBitmap(photo, maxPx) }
    }
    return bitmap
}

/** "23 Oct · 00:31" — the caption under a photo. */
private fun photoCaption(takenAt: Long): String =
    java.text
        .SimpleDateFormat("d MMM · HH:mm", java.util.Locale.US)
        .format(java.util.Date(takenAt))
