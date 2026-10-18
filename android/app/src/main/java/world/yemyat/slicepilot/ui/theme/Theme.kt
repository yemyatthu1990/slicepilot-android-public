package world.yemyat.slicepilot.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.view.WindowCompat

/** True when the app currently renders its dark palette (explicit toggle or system). */
val LocalIsDarkTheme = staticCompositionLocalOf { false }

private val LightColors = lightColorScheme(
    primary = Vermillion,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFE0D8),
    onPrimaryContainer = Color(0xFF410B05),
    secondary = RiverTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFC9EFE8),
    onSecondaryContainer = Color(0xFF00201D),
    tertiary = LanternGold,
    onTertiary = Color(0xFF2A1A00),
    tertiaryContainer = Color(0xFFFFE7B8),
    onTertiaryContainer = Color(0xFF2A1A00),
    error = Red600,
    onError = Color.White,
    errorContainer = Red200,
    onErrorContainer = Color(0xFF410002),
    background = PaperCream,
    onBackground = SumiInk,
    surface = WashiWhite,
    onSurface = SumiInk,
    surfaceVariant = LightSurfaceWarm,
    onSurfaceVariant = LightOnSurfaceWarm,
    outline = LightOutlineWarm,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB49E),
    onPrimary = Color(0xFF5C1A0F),
    primaryContainer = Color(0xFF7A2A1C),
    onPrimaryContainer = Color(0xFFFFDAD2),
    secondary = Color(0xFF7FD8CB),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF11514A),
    onSecondaryContainer = Color(0xFFBFE7E1),
    tertiary = Color(0xFFF3C96B),
    onTertiary = Color(0xFF422D00),
    tertiaryContainer = Color(0xFF604300),
    onTertiaryContainer = Color(0xFFFFE7B8),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Red200,
    background = NightBackground,
    onBackground = NightOnBackground,
    surface = NightSurface,
    onSurface = NightOnBackground,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = NightOnSurfaceVariant,
    outline = NightOutline,
)

private val SpiritedShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp),
)

@Composable
fun ElegooSlicerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalIsDarkTheme provides darkTheme) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ElegooTypography,
            shapes = SpiritedShapes,
            content = content,
        )
    }
}
