package world.yemyat.slicepilot.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

val BrandNavy = IndigoNight
val BrandBlue = Vermillion
val BrandSky = LanternGold
val BrandCyan = RiverTeal
val BrandInk = SumiInk

object Radius {
    val sm = 14.dp
    val md = 20.dp
    val lg = 28.dp
    val xl = 36.dp
}

object Space {
    val xs = 6.dp
    val sm = 10.dp
    val md = 16.dp
    val lg = 24.dp
    val xl = 32.dp
}

val brandPrimaryGradient: Brush
    get() = Brush.linearGradient(
        colors = listOf(LanternGold, Vermillion, RiverTeal),
    )

val brandDeepGradient: Brush
    get() = Brush.linearGradient(
        colors = listOf(IndigoNight, Vermillion),
    )

@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
    gradient: Brush = brandPrimaryGradient,
    height: androidx.compose.ui.unit.Dp = 62.dp,
) {
    val shape = RoundedCornerShape(Radius.md)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "cuteButtonScale",
    )
    val alpha = if (enabled) 1f else 0.4f

    Box(
        modifier = modifier
            .height(height)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .then(
                if (enabled) {
                    Modifier.shadow(
                        14.dp,
                        shape,
                        ambientColor = LanternGold.copy(alpha = 0.28f),
                        spotColor = Vermillion.copy(alpha = 0.36f),
                    )
                } else {
                    Modifier
                },
            )
            .clip(shape)
            .background(if (enabled) gradient else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
            .border(
                width = if (enabled) 1.5.dp else 1.dp,
                color = if (enabled) LanternGold.copy(alpha = 0.35f) else Color.Transparent,
                shape = shape,
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled && !loading,
            ) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(
                modifier = Modifier.height(24.dp),
                color = Color.White,
                strokeWidth = 2.5.dp,
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                icon?.invoke()
                Text(
                    text,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = alpha),
                    letterSpacing = 0.2.sp,
                )
            }
        }
    }
}

@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    contentPadding: androidx.compose.ui.unit.Dp = 20.dp,
    background: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        modifier = modifier
            .shadow(
                10.dp,
                shape,
                ambientColor = LanternGold.copy(alpha = 0.16f),
                spotColor = Vermillion.copy(alpha = 0.12f),
            )
            .clip(shape)
            .background(background)
            .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)), shape)
            .padding(contentPadding),
    ) { content() }
}

@Composable
fun BrandSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.padding(start = 2.dp, bottom = 2.dp),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        color = BrandBlue,
        letterSpacing = 1.2.sp,
    )
}

@Composable
fun BrandWordmark(modifier: Modifier = Modifier, dark: Boolean = LocalIsDarkTheme.current) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .requiredWidth(40.dp)
                .height(36.dp)
                .shadow(
                    10.dp,
                    RoundedCornerShape(13.dp),
                    ambientColor = LanternGold.copy(alpha = 0.35f),
                    spotColor = Vermillion.copy(alpha = 0.35f),
                )
                .clip(RoundedCornerShape(13.dp))
                .background(brandPrimaryGradient)
                .border(1.5.dp, LanternGold.copy(alpha = 0.55f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "S",
                fontSize = 23.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
        }
        Text(
            "LICEPILOT",
            fontSize = 16.sp,
            fontWeight = FontWeight.Black,
            color = if (dark) PaperCream else BrandInk,
            letterSpacing = 1.5.sp,
            maxLines = 1,
            softWrap = false,
            modifier = Modifier.requiredWidth(IntrinsicSize.Max),
        )
    }
}
