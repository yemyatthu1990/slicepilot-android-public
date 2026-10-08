package world.yemyat.slicepilot.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Brand signature palette (Elegoo-inspired deep blue -> cyan)
val BrandNavy = Color(0xFF0A2A5E)
val BrandBlue = Color(0xFF1565C0)
val BrandSky = Color(0xFF2FA8E8)
val BrandCyan = Color(0xFF36C5F0)
val BrandInk = Color(0xFF0B1B2B)

object Radius {
    val sm = 12.dp
    val md = 18.dp
    val lg = 24.dp
    val xl = 30.dp
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
        colors = listOf(BrandBlue, BrandSky, BrandCyan),
    )

val brandDeepGradient: Brush
    get() = Brush.linearGradient(
        colors = listOf(BrandNavy, BrandBlue),
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
    height: androidx.compose.ui.unit.Dp = 60.dp,
) {
    val shape = RoundedCornerShape(Radius.md)
    val alpha = if (enabled) 1f else 0.4f
    Box(
        modifier = modifier
            .height(height)
            .then(if (enabled) Modifier.shadow(10.dp, shape, ambientColor = BrandBlue, spotColor = BrandSky) else Modifier)
            .clip(shape)
            .background(if (enabled) gradient else SolidColor(MaterialTheme.colorScheme.surfaceVariant))
            .clickable(enabled = enabled && !loading) { onClick() },
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
                    fontWeight = FontWeight.SemiBold,
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
    contentPadding: androidx.compose.ui.unit.Dp = 18.dp,
    background: Color = MaterialTheme.colorScheme.surface,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        modifier = modifier
            .shadow(6.dp, shape, ambientColor = BrandNavy.copy(alpha = 0.10f), spotColor = BrandBlue.copy(alpha = 0.14f))
            .clip(shape)
            .background(background)
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
fun BrandWordmark(modifier: Modifier = Modifier, dark: Boolean = isSystemInDarkTheme()) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier,
    ) {
        Box(
            Modifier
                .width(38.dp)
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(brandPrimaryGradient),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "S",
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                color = Color.White,
            )
        }
        Text(
            "LICEPILOT",
            fontSize = 18.sp,
            fontWeight = FontWeight.Black,
            color = if (dark) Color.White else BrandInk,
            letterSpacing = 2.sp,
        )
    }
}
