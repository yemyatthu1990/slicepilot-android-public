package world.yemyat.slicepilot.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
    get() =
        Brush.linearGradient(
            colors = listOf(LanternGold, Vermillion, RiverTeal),
        )

val brandDeepGradient: Brush
    get() =
        Brush.linearGradient(
            colors = listOf(IndigoNight, Vermillion),
        )

/**
 * What a button is for, spelled in how it looks. Before this the app drew the
 * same action five different ways (a gradient pill here, a flat M3 button there,
 * an ad-hoc circle in a list row), so a button's meaning had to be re-learned on
 * every screen. Every button now asks for a role and gets one look from it.
 */
enum class ButtonRole {
    /** The one thing to do on this screen. The only role that floats. */
    Primary,

    /** A real alternative to Primary — the card surface with its gold hairline. */
    Secondary,

    /** A quiet action next to a Primary one — flat tint, no edge. */
    Tonal,

    /** Destroys something (Stop, Delete) — the error container, so it reads
     * as the one tap that cannot be undone. */
    Destructive,
}

/**
 * The three heights buttons come in. Screens used to invent their own
 * (50/54/56/62/64 dp), so the same role was a different size on every screen.
 */
object ButtonSize {
    val Small = 44.dp
    val Medium = 52.dp
    val Large = 60.dp
}

/** The paint for a role, resolved against the active theme. */
private data class ButtonPaint(
    val background: Brush,
    val content: Color,
    val ring: BorderStroke?,
    val elevated: Boolean,
)

@Composable
private fun buttonPaint(
    role: ButtonRole,
    enabled: Boolean,
): ButtonPaint {
    val scheme = MaterialTheme.colorScheme
    if (!enabled) {
        return ButtonPaint(
            background = SolidColor(scheme.onSurface.copy(alpha = 0.10f)),
            content = scheme.onSurface.copy(alpha = 0.38f),
            ring = null,
            elevated = false,
        )
    }
    return when (role) {
        // Vermillion with a gold hairline: the wordmark's colour pair, and the
        // same fill every other filled button in the app already uses. The old
        // three-stop gold→vermillion→teal fill went muddy brown at button size
        // and put white label text on a gold field it could not contrast with.
        ButtonRole.Primary -> {
            ButtonPaint(
                background = SolidColor(scheme.primary),
                content = scheme.onPrimary,
                ring = BorderStroke(1.dp, LanternGold.copy(alpha = 0.55f)),
                elevated = true,
            )
        }

        // Matches BrandCard exactly, so a secondary action looks like it
        // belongs to the card it sits in.
        ButtonRole.Secondary -> {
            ButtonPaint(
                background = SolidColor(scheme.surface),
                content = scheme.onSurface,
                ring = BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)),
                elevated = false,
            )
        }

        ButtonRole.Tonal -> {
            ButtonPaint(
                background = SolidColor(scheme.secondaryContainer),
                content = scheme.onSecondaryContainer,
                ring = null,
                elevated = false,
            )
        }

        ButtonRole.Destructive -> {
            ButtonPaint(
                background = SolidColor(scheme.errorContainer),
                content = scheme.onErrorContainer,
                ring = null,
                elevated = false,
            )
        }
    }
}

/**
 * One choice out of a short list, drawn as a single control with the chosen
 * segment raised. The Files shelf switcher and the printer's nozzle picker ask
 * the same question — "which one of these" — so they share a look instead of one
 * screen drawing chips, which read as multi-select, and another drawing tabs.
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return
    val shape = RoundedCornerShape(Radius.md)
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)), shape)
                .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEachIndexed { index, option ->
            val active = index == selectedIndex
            Box(
                modifier =
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(Radius.sm))
                        .background(
                            if (active) {
                                MaterialTheme.colorScheme.surface
                            } else {
                                Color.Transparent
                            },
                        ).clickable { onSelect(index) }
                        .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option,
                    fontSize = 14.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1,
                    color =
                        if (active) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
        }
    }
}

/**
 * A row that opens something: icon, title, what is behind it, chevron. It
 * replaces the stacked icon-over-text tile whenever the action takes the full
 * width: there the tile read as a card with its contents fallen into the
 * top-left corner, and nothing said it was tappable.
 */
@Composable
fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: ButtonRole = ButtonRole.Secondary,
) {
    val paint = buttonPaint(role, enabled = true)
    val shape = RoundedCornerShape(Radius.md)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.99f else 1f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "settingsRowScale",
    )

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = 64.dp)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.clip(shape)
                .background(paint.background)
                .then(
                    paint.ring?.let { Modifier.border(it, shape) }
                        ?: Modifier,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    onClick = onClick,
                ).padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                subtitle,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(
            Icons.Filled.ChevronRight,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** The corner a button of a given height gets — small buttons are less round. */
private fun buttonShape(height: Dp) = RoundedCornerShape(if (height <= ButtonSize.Small) Radius.sm else Radius.md)

@Composable
fun BrandButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    role: ButtonRole = ButtonRole.Primary,
    enabled: Boolean = true,
    icon: (@Composable () -> Unit)? = null,
    loading: Boolean = false,
    height: Dp = ButtonSize.Medium,
) {
    val paint = buttonPaint(role, enabled)
    val shape = buttonShape(height)
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.97f else 1f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "brandButtonScale",
    )

    Box(
        modifier =
            modifier
                .height(height)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.then(
                    if (paint.elevated) {
                        Modifier.shadow(
                            12.dp,
                            shape,
                            ambientColor = LanternGold.copy(alpha = 0.30f),
                            spotColor = Vermillion.copy(alpha = 0.36f),
                        )
                    } else {
                        Modifier
                    },
                ).clip(shape)
                .background(paint.background)
                .then(
                    paint.ring?.let { Modifier.border(it, shape) }
                        ?: Modifier,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled && !loading,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        // Caller-supplied icons have no tint of their own most of the time, so
        // the role's label colour is what they inherit.
        CompositionLocalProvider(LocalContentColor provides paint.content) {
            if (loading) {
                CircularProgressIndicator(
                    modifier = Modifier.height(20.dp),
                    color = paint.content,
                    strokeWidth = 2.dp,
                )
            } else {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    icon?.invoke()
                    Text(
                        text,
                        fontSize =
                            when {
                                height >= ButtonSize.Large -> 16.sp
                                height >= ButtonSize.Medium -> 15.sp
                                else -> 14.sp
                            },
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.1.sp,
                        maxLines = 1,
                        color = paint.content,
                    )
                }
            }
        }
    }
}

/**
 * The icon-sized sibling of [BrandButton] — the same roles at the same cost, so
 * the trash icon in a row, the header's settings gear and the sheet's back arrow
 * stop each being their own one-off shape.
 */
@Composable
fun BrandIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    role: ButtonRole = ButtonRole.Tonal,
    enabled: Boolean = true,
) {
    val paint = buttonPaint(role, enabled)
    val shape = CircleShape
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.90f else 1f,
        animationSpec = spring(dampingRatio = 0.55f),
        label = "brandIconButtonScale",
    )

    Box(
        modifier =
            modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }.then(
                    if (paint.elevated) {
                        Modifier.shadow(
                            10.dp,
                            shape,
                            ambientColor = LanternGold.copy(alpha = 0.30f),
                            spotColor = Vermillion.copy(alpha = 0.36f),
                        )
                    } else {
                        Modifier
                    },
                ).clip(shape)
                .background(paint.background)
                .then(
                    paint.ring?.let { Modifier.border(it, shape) }
                        ?: Modifier,
                ).clickable(
                    interactionSource = interactionSource,
                    indication = null,
                    enabled = enabled,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            modifier = Modifier.size(size * 0.45f),
            tint = paint.content,
        )
    }
}

@Composable
fun BrandCard(
    modifier: Modifier = Modifier,
    contentPadding: Dp = 20.dp,
    background: Color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(Radius.lg)
    Box(
        modifier =
            modifier
                .shadow(
                    10.dp,
                    shape,
                    ambientColor = LanternGold.copy(alpha = 0.16f),
                    spotColor = Vermillion.copy(alpha = 0.12f),
                ).clip(shape)
                .background(background)
                .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)), shape)
                .padding(contentPadding),
    ) { content() }
}

@Composable
fun BrandSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    // Both the type and the colour now come from the theme. They used to be hardcoded (12sp,
    // 1.2sp tracking, BrandBlue): tracking meant for capitals spread mixed-case names apart,
    // the size sat under the 14sp card titles it heads, and BrandBlue is Vermillion - right on
    // cream, but a dark red that sinks into the dark theme's background next to a light
    // "See more". colorScheme.primary is Vermillion in the light theme and its dark-theme
    // equivalent in the dark one, so a label matches whatever theme draws it.
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
fun BrandWordmark(
    modifier: Modifier = Modifier,
    dark: Boolean = LocalIsDarkTheme.current,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .requiredWidth(40.dp)
                    .height(36.dp)
                    .shadow(
                        10.dp,
                        RoundedCornerShape(13.dp),
                        ambientColor = LanternGold.copy(alpha = 0.35f),
                        spotColor = Vermillion.copy(alpha = 0.35f),
                    ).clip(RoundedCornerShape(13.dp))
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
