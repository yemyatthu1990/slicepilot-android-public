package world.yemyat.slicepilot.ui.components

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private data class SootCharacter(
    val xFraction: Float,
    val yFraction: Float,
    val phase: Float,
    val scale: Float,
)

@Composable
fun SpiritedCharacterScene(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "spiritedScene")
    val time by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4200),
            repeatMode = RepeatMode.Restart,
        ),
        label = "spiritedSceneTime",
    )

    val characters = remember {
        listOf(
            SootCharacter(0.22f, 0.68f, 0.0f, 1.0f),
            SootCharacter(0.50f, 0.58f, 1.7f, 1.25f),
            SootCharacter(0.78f, 0.72f, 3.1f, 0.88f),
        )
    }

    Canvas(modifier) {
        val radius = size.minDimension * 0.085f

        drawSparkle(
            center = Offset(size.width * 0.12f, size.height * 0.22f),
            size = size.minDimension * 0.035f,
            color = Color(0xFFFFD88A),
            alpha = 0.55f,
            spin = time * 2.1f,
        )
        drawSparkle(
            center = Offset(size.width * 0.88f, size.height * 0.18f),
            size = size.minDimension * 0.028f,
            color = Color(0xFFFFC7A8),
            alpha = 0.48f,
            spin = time * 2.8f + 1.2f,
        )
        drawSparkle(
            center = Offset(size.width * 0.68f, size.height * 0.28f),
            size = size.minDimension * 0.022f,
            color = Color(0xFF9DE7DB),
            alpha = 0.42f,
            spin = time * 3.4f + 2.4f,
        )

        for (character in characters) {
            val bob = sin((time * 2f * PI.toFloat() + character.phase) * 1.8f) * size.height * 0.055f
            val center = Offset(size.width * character.xFraction, size.height * character.yFraction + bob)
            drawSootSprite(center, radius * character.scale, time + character.phase)
        }
    }
}

private fun DrawScope.drawSootSprite(center: Offset, radius: Float, t: Float) {
    val body = Color(0xFF171717)
    val fluff = Color(0xFF2A2A2A)
    val eyeWhite = Color.White
    val pupil = Color(0xFF070707)
    val blush = Color(0xFFFFB3C6)
    val blink = ((t * 1.7f) % 1f) < 0.08f
    val look = sin(t * 2.3f) * radius * 0.05f

    drawRoundRect(
        color = Color.Black.copy(alpha = 0.08f),
        cornerRadius = CornerRadius(radius * 0.8f, radius * 0.35f),
        size = Size(radius * 2.2f, radius * 0.55f),
        topLeft = Offset(center.x - radius * 1.1f, center.y + radius * 0.88f),
    )

    for (i in 0 until 10) {
        val angle = (i / 10f) * (2f * PI.toFloat()) + t * 0.45f
        drawCircle(
            color = fluff,
            radius = radius * 0.58f,
            center = Offset(
                x = center.x + cos(angle) * radius * 0.78f,
                y = center.y + sin(angle) * radius * 0.72f,
            ),
        )
    }

    drawCircle(color = body, radius = radius, center = center)

    if (blink) {
        drawLine(
            color = eyeWhite,
            start = Offset(center.x - radius * 0.42f, center.y - radius * 0.08f),
            end = Offset(center.x - radius * 0.12f, center.y - radius * 0.08f),
            strokeWidth = radius * 0.08f,
        )
        drawLine(
            color = eyeWhite,
            start = Offset(center.x + radius * 0.12f, center.y - radius * 0.08f),
            end = Offset(center.x + radius * 0.42f, center.y - radius * 0.08f),
            strokeWidth = radius * 0.08f,
        )
    } else {
        drawCircle(
            color = eyeWhite,
            radius = radius * 0.26f,
            center = Offset(center.x - radius * 0.28f, center.y - radius * 0.08f),
        )
        drawCircle(
            color = eyeWhite,
            radius = radius * 0.26f,
            center = Offset(center.x + radius * 0.28f, center.y - radius * 0.08f),
        )
        drawCircle(
            color = pupil,
            radius = radius * 0.12f,
            center = Offset(center.x - radius * 0.24f + look, center.y - radius * 0.05f),
        )
        drawCircle(
            color = pupil,
            radius = radius * 0.12f,
            center = Offset(center.x + radius * 0.32f + look, center.y - radius * 0.05f),
        )
    }

    drawCircle(
        color = blush,
        radius = radius * 0.16f,
        center = Offset(center.x - radius * 0.58f, center.y + radius * 0.18f),
        alpha = 0.55f,
    )
    drawCircle(
        color = blush,
        radius = radius * 0.16f,
        center = Offset(center.x + radius * 0.58f, center.y + radius * 0.18f),
        alpha = 0.55f,
    )
    drawLine(
        color = Color(0xFF070707),
        start = Offset(center.x - radius * 0.12f, center.y + radius * 0.28f),
        end = Offset(center.x + radius * 0.12f, center.y + radius * 0.28f),
        strokeWidth = radius * 0.07f,
    )
}

private fun DrawScope.drawSparkle(center: Offset, size: Float, color: Color, alpha: Float, spin: Float) {
    val stroke = (size * 0.16f).coerceAtLeast(1f)
    rotate(degrees = spin * 42f, pivot = center) {
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
    drawCircle(color, size * 0.2f, center, alpha = alpha)
}
