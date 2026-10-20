package world.yemyat.slicepilot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Light
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.Green200
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.brandDeepGradient
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.cloud.PrintStatus
import kotlin.math.roundToInt

@Composable
fun PrintingScreen(vm: AppViewModel, onBack: () -> Unit) {
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
    val errorColor = Color(0xFFFF6B6B)
    val accent = when {
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
        modifier = Modifier
            .fillMaxSize()
            .background(brandDeepGradient),
    ) {
        Column(
            modifier = Modifier
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
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    when {
                        sim.failed -> "Print failed"
                        sim.complete -> "Print started"
                        else -> "Sending to printer"
                    },
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                )
            }

            Spacer(Modifier.height(12.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(contentAlignment = Alignment.Center) {
                        if (sim.failed) {
                            Box(
                                modifier = Modifier
                                    .size(176.dp)
                                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                                    .background(errorColor.copy(alpha = 0.16f), CircleShape),
                            )
                            Box(
                                modifier = Modifier
                                    .size(124.dp)
                                    .offset { IntOffset(shake.value.roundToInt(), 0) }
                                    .background(errorColor.copy(alpha = 0.28f), CircleShape),
                            )
                            Icon(
                                Icons.Filled.ErrorOutline,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(72.dp)
                                    .offset { IntOffset(shake.value.roundToInt(), 0) },
                            )
                        } else {
                            Box(
                                modifier = Modifier
                                    .size(184.dp)
                                    .scale(pulse)
                                    .background(Color.White.copy(alpha = glow), CircleShape),
                            )
                            CircularProgressIndicator(
                                progress = { animatedProgress },
                                modifier = Modifier.size(152.dp),
                                color = accent,
                                trackColor = Color.White.copy(alpha = 0.16f),
                                strokeWidth = 10.dp,
                            )
                            Icon(
                                if (sim.complete) Icons.Filled.CheckCircle else Icons.Filled.CloudUpload,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier
                                    .size(58.dp)
                                    .scale(if (sim.complete) 1f else pulse),
                            )
                        }
                    }

                    Spacer(Modifier.height(28.dp))
                    Text(
                        sim.message,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        fileName,
                        color = Color.White.copy(alpha = 0.82f),
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    printer?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${it.name} · ${if (it.isLocal) "Local Wi-Fi" else "Cloud"}",
                            color = Color.White.copy(alpha = 0.68f),
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Surface(
                color = Color.White.copy(alpha = 0.10f),
                shape = RoundedCornerShape(Radius.lg),
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
                                    tint = when {
                                        complete -> Green200
                                        active && sim.failed -> errorColor
                                        active -> BrandCyan
                                        else -> Color.White.copy(alpha = 0.55f)
                                    },
                                    modifier = Modifier
                                        .size(22.dp)
                                        .scale(if (active && !sim.failed) pulse else 1f),
                                )
                                Spacer(Modifier.width(12.dp))
                                Text(
                                    step.label,
                                    color = Color.White.copy(
                                        alpha = when {
                                            complete -> 0.95f
                                            active -> 1f
                                            else -> 0.65f
                                        },
                                    ),
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
                                    color = when {
                                        complete -> Green200
                                        active -> BrandCyan
                                        else -> Color.White.copy(alpha = 0.55f)
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
                    height = 56.dp,
                )
            } else {
                Button(
                    onClick = onBack,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(Radius.md),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (sim.failed) errorColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.12f),
                        contentColor = Color.White,
                    ),
                ) {
                    Text(
                        if (sim.failed) "Back" else "Cancel",
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
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
fun DeviceStatusScreen(vm: AppViewModel, printMode: Boolean = false) {
    val mon by vm.printStatus.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
    val cameraOn by vm.cameraOn.collectAsState()
    val cameraFrame by vm.cameraFrame.collectAsState()
    val cameraSurface by vm.cameraSurface.collectAsState()
    val demoMode by vm.demoModeState.collectAsState()
    val status = mon
    val errorColor = Color(0xFFFF6B6B)
    val pausedColor = Color(0xFFFFC96B)
    val state = status?.state
    val live = status?.live == true
    val printing = status != null && (
        state == PrintJobState.PRINTING || state == PrintJobState.PAUSING ||
            state == PrintJobState.PAUSED || state == PrintJobState.RESUMING ||
            state == PrintJobState.STOPPING
        )
    val accent = when {
        state == PrintJobState.ERROR -> errorColor
        state?.isPaused == true -> pausedColor
        printing -> BrandCyan
        else -> Green200
    }
    val headline = when {
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
                Text("The print will be stopped and the printer will cool down. A stopped print cannot be resumed — the file has to be started again.")
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
        modifier = Modifier
            .fillMaxSize()
            .background(brandDeepGradient),
    ) {
        Column(
            modifier = Modifier
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
                        tint = Color.White,
                    )
                }
                Spacer(Modifier.width(4.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (printMode) "Print monitor" else "Device status",
                        color = Color.White,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    printer?.let {
                        Text(
                            "${it.name} · ${it.model} · ${if (it.isLocal) "Local" else "Cloud"}",
                            color = Color.White.copy(alpha = 0.7f),
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
                        color = if (lightOn) pausedColor.copy(alpha = 0.25f) else Color.White.copy(alpha = 0.08f),
                        border = BorderStroke(1.dp, if (lightOn) pausedColor else Color.White.copy(alpha = 0.18f)),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Icon(
                                Icons.Filled.Light,
                                contentDescription = null,
                                tint = if (lightOn) pausedColor else Color.White.copy(alpha = 0.7f),
                                modifier = Modifier.size(16.dp),
                            )
                            Text(
                                "Light",
                                color = if (lightOn) pausedColor else Color.White.copy(alpha = 0.7f),
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
                            tint = if (cameraOn) BrandCyan else Color.White.copy(alpha = 0.45f),
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
                        color = Color.White,
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(16f / 9f)
                        .clip(RoundedCornerShape(Radius.md))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    cameraFrame?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Camera preview",
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: cameraSurface?.let { surface ->
                        AndroidView(
                            factory = { surface },
                            modifier = Modifier.fillMaxSize(),
                        )
                    } ?: CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = BrandCyan,
                        strokeWidth = 2.dp,
                    )
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
                    color = Color.White.copy(alpha = 0.68f),
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
                    color = Color.White.copy(alpha = 0.07f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${status.progress}%",
                                color = Color.White,
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
                                        color = Color.White.copy(alpha = 0.65f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        LinearProgressIndicator(
                            progress = { animatedProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp)),
                            color = accent,
                            trackColor = Color.White.copy(alpha = 0.16f),
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
                                modifier = Modifier
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
                                    color = Color.White,
                                )
                            }
                            Button(
                                onClick = { confirmStop = true },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(48.dp),
                                shape = RoundedCornerShape(Radius.md),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = errorColor.copy(alpha = 0.22f),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    Icons.Filled.Stop,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("Stop", fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                            }
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
                color = Color.White.copy(alpha = 0.07f),
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
                    color = Color.White.copy(alpha = 0.07f),
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
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "Model ${fanPct}% · Aux ${auxPct}%",
                                    color = Color.White,
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
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$fanPct%",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Slider(
                                value = fanPct.toFloat(),
                                onValueChange = { fanPct = it.toInt() },
                                onValueChangeFinished = { vm.setFanSpeed(fanPct) },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
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
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 13.sp,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    "$auxPct%",
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            Slider(
                                value = auxPct.toFloat(),
                                onValueChange = { auxPct = it.toInt() },
                                onValueChangeFinished = { vm.setAuxFanSpeed(auxPct) },
                                valueRange = 0f..100f,
                                colors = SliderDefaults.colors(
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
                printMode && state?.isTerminal == true -> BrandButton(
                    text = "Done",
                    onClick = { vm.finishPrintToHome() },
                    modifier = Modifier.fillMaxWidth(),
                    height = 56.dp,
                )
                printMode -> {
                    // Printing: no Done button — the header back arrow leaves; the
                    // print and its notification keep going.
                }
                else -> TextButton(onClick = { vm.closeDeviceStatus() }) {
                    Text("Back to home", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }
    }
}

private fun deviceFmtRemaining(seconds: Int?): String = when {
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
        color = Color.White.copy(alpha = 0.08f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f)),
    ) {
        Box {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "$label ${if (currentTemp <= 0) "Off" else "$currentTemp°"}",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.6f),
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
private fun MonitorInfo(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            value,
            color = Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            label,
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
        )
    }
}


