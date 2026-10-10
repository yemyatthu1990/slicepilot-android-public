package world.yemyat.slicepilot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.Green200
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.brandDeepGradient
import kotlin.math.roundToInt

@Composable
fun PrintingScreen(vm: AppViewModel, onBack: () -> Unit) {
    val sim by vm.printSim.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
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
