package world.yemyat.slicepilot.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.RiverTeal
import world.yemyat.slicepilot.ui.theme.SakuraPink
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

private data class FloatingSpirit(
    val x: Float,
    val y: Float,
    val radius: Float,
    val speed: Float,
    val phase: Float,
    val kind: Int,
)

@Composable
fun SpiritedBackground(
    modifier: Modifier = Modifier,
    darkTheme: Boolean = isSystemInDarkTheme(),
) {
    val transition = rememberInfiniteTransition(label = "spiritedBackground")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24000),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spiritedTime",
    )

    val spirits = remember {
        val random = Random(1708)
        List(30) {
            FloatingSpirit(
                x = random.nextFloat(),
                y = random.nextFloat(),
                radius = 10f + random.nextFloat() * 34f,
                speed = 0.08f + random.nextFloat() * 0.22f,
                phase = random.nextFloat() * 100f,
                kind = random.nextInt(3),
            )
        }
    }

    val paperTop = if (darkTheme) Color(0xFF15181D) else Color(0xFFFFF7E8)
    val paperMid = if (darkTheme) Color(0xFF1B2026) else Color(0xFFF8E7D8)
    val paperBottom = if (darkTheme) Color(0xFF22303A) else Color(0xFFE8F3EE)
    val accentA = if (darkTheme) Color(0xFFFFB49E) else SakuraPink
    val accentB = if (darkTheme) Color(0xFFF3C96B) else LanternGold
    val accentC = if (darkTheme) Color(0xFF7FD8CB) else RiverTeal
    val sparkle = if (darkTheme) Color(0xFFFFE7B8) else Color(0xFFFFD88A)

    Canvas(modifier) {
        drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(paperTop, paperMid, paperBottom),
            ),
        )

        drawSoftBlob(
            center = Offset(size.width * 0.18f, size.height * 0.16f),
            radius = size.minDimension * 0.42f,
            color = accentA,
            alpha = if (darkTheme) 0.16f else 0.24f,
        )
        drawSoftBlob(
            center = Offset(size.width * 0.82f, size.height * 0.28f),
            radius = size.minDimension * 0.36f,
            color = accentB,
            alpha = if (darkTheme) 0.14f else 0.20f,
        )
        drawSoftBlob(
            center = Offset(size.width * 0.52f, size.height * 0.86f),
            radius = size.minDimension * 0.48f,
            color = accentC,
            alpha = if (darkTheme) 0.12f else 0.18f,
        )

        for (spirit in spirits) {
            val drift = (spirit.y + time * spirit.speed) % 1f
            val x = spirit.x * size.width + sin((time * 2f * PI.toFloat() + spirit.phase) * 1.25f) * 26f
            val y = drift * size.height
            val pulse = 0.82f + 0.18f * sin((time * 2f * PI.toFloat() + spirit.phase) * 2.1f)
            val center = Offset(x, y)

            when (spirit.kind) {
                0 -> drawSoftBlob(center, spirit.radius * pulse, accentB, if (darkTheme) 0.12f else 0.18f)
                1 -> drawSootSprite(center, spirit.radius * 0.36f * pulse, if (darkTheme) 0.32f else 0.28f, time + spirit.phase)
                else -> drawSparkle(center, spirit.radius * 0.28f * pulse, sparkle, if (darkTheme) 0.34f else 0.42f, time + spirit.phase)
            }
        }
    }
}

private fun DrawScope.drawSoftBlob(center: Offset, radius: Float, color: Color, alpha: Float) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(color.copy(alpha = alpha), Color.Transparent),
            center = center,
            radius = radius.coerceAtLeast(1f),
        ),
        radius = radius.coerceAtLeast(1f),
        center = center,
    )
}

private fun DrawScope.drawSootSprite(center: Offset, radius: Float, alpha: Float, wiggle: Float) {
    val body = Color(0xFF151515)
    val fluff = Color(0xFF242424)
    val eyeWhite = Color.White
    val pupil = Color(0xFF080808)

    for (i in 0 until 8) {
        val angle = (i / 8f) * (2f * PI.toFloat()) + wiggle * 0.35f
        drawCircle(
            color = fluff,
            radius = radius * 0.58f,
            center = Offset(
                x = center.x + cos(angle) * radius * 0.72f,
                y = center.y + sin(angle) * radius * 0.72f,
            ),
            alpha = alpha,
        )
    }

    drawCircle(color = body, radius = radius, center = center, alpha = alpha)
    drawCircle(
        color = eyeWhite,
        radius = radius * 0.25f,
        center = Offset(center.x - radius * 0.28f, center.y - radius * 0.08f),
        alpha = alpha,
    )
    drawCircle(
        color = eyeWhite,
        radius = radius * 0.25f,
        center = Offset(center.x + radius * 0.28f, center.y - radius * 0.08f),
        alpha = alpha,
    )
    drawCircle(
        color = pupil,
        radius = radius * 0.11f,
        center = Offset(center.x - radius * 0.24f, center.y - radius * 0.05f),
        alpha = alpha,
    )
    drawCircle(
        color = pupil,
        radius = radius * 0.11f,
        center = Offset(center.x + radius * 0.32f, center.y - radius * 0.05f),
        alpha = alpha,
    )
}

private fun DrawScope.drawSparkle(center: Offset, size: Float, color: Color, alpha: Float, spin: Float) {
    val stroke = (size * 0.14f).coerceAtLeast(1f)
    rotate(degrees = spin * 38f, pivot = center) {
        drawLine(color, Offset(center.x - size, center.y), Offset(center.x + size, center.y), stroke, alpha = alpha)
        drawLine(color, Offset(center.x, center.y - size), Offset(center.x, center.y + size), stroke, alpha = alpha)
        drawLine(
            color = color.copy(alpha = alpha * 0.55f),
            start = Offset(center.x - size * 0.55f, center.y - size * 0.55f),
            end = Offset(center.x + size * 0.55f, center.y + size * 0.55f),
            strokeWidth = stroke * 0.7f,
        )
        drawLine(
            color = color.copy(alpha = alpha * 0.55f),
            start = Offset(center.x + size * 0.55f, center.y - size * 0.55f),
            end = Offset(center.x - size * 0.55f, center.y + size * 0.55f),
            strokeWidth = stroke * 0.7f,
        )
    }
    drawCircle(color, size * 0.18f, center, alpha = alpha)
}
