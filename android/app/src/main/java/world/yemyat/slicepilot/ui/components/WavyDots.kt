package world.yemyat.slicepilot.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Three dots rising and falling one after another — the typing-indicator gesture,
 * app-wide, for "a fetch is in flight and its content will replace this".
 */
@Composable
fun WavyDots(
    modifier: Modifier = Modifier,
    dotSize: Dp = 7.dp,
    waveHeight: Dp = 5.dp,
    color: Color = MaterialTheme.colorScheme.primary,
) {
    // One shared looping 0→1 phase drives all three dots a third of a cycle apart;
    // three separate transitions would each visibly restart on their own timing.
    val phase by rememberInfiniteTransition(label = "wavy-dots").animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "phase",
    )
    Row(
        modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (dot in 0 until 3) {
            // Sine over the shared phase: continuous across the loop restart.
            val wave = (sin((phase + dot / 3f) * 2f * PI.toFloat()) + 1f) / 2f // 0..1
            Box(
                Modifier
                    .offset(y = waveHeight * (wave * 2f - 1f))
                    .alpha(0.5f + 0.5f * wave)
                    .size(dotSize)
                    .background(color, CircleShape),
            )
        }
    }
}
