package world.yemyat.slicepilot.ui

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Material 3 canonical width size classes:
 * - [Compact]: < 600dp (phones in portrait)
 * - [Medium]: 600dp .. < 840dp (small tablets, foldables, tablets in portrait)
 * - [Expanded]: >= 840dp (tablets in landscape, desktop/ChromeOS)
 */
enum class WindowWidthClass {
    Compact,
    Medium,
    Expanded,
}

/**
 * Adaptive layout parameters for the Home / Browse feed screens, derived from the
 * available container width so split-screen, rotation, and tablets all scale cleanly.
 */
data class FeedLayoutSpec(
    val widthClass: WindowWidthClass,
    val columns: Int,
    val previewLimitAll: Int,
    val previewLimitSingle: Int,
    val horizontalPaddingDp: Int,
    val cardSpacingDp: Int,
    val maxContentWidthDp: Int,
    val useNavigationRail: Boolean,
    val inlineSearchAndChips: Boolean,
)

private const val MEDIUM_BREAKPOINT_DP = 600
private const val EXPANDED_BREAKPOINT_DP = 840
private const val MAX_CONTENT_WIDTH_DP = 1200

fun windowWidthClassFor(widthDp: Int): WindowWidthClass =
    when {
        widthDp >= EXPANDED_BREAKPOINT_DP -> WindowWidthClass.Expanded
        widthDp >= MEDIUM_BREAKPOINT_DP -> WindowWidthClass.Medium
        else -> WindowWidthClass.Compact
    }

fun windowWidthClassFor(width: Dp): WindowWidthClass = windowWidthClassFor(width.value.toInt())

fun feedLayoutSpecFor(widthDp: Int): FeedLayoutSpec =
    when (val cls = windowWidthClassFor(widthDp)) {
        WindowWidthClass.Compact ->
            FeedLayoutSpec(
                widthClass = cls,
                columns = 2,
                previewLimitAll = 4,
                previewLimitSingle = 8,
                horizontalPaddingDp = 20,
                cardSpacingDp = 12,
                maxContentWidthDp = MAX_CONTENT_WIDTH_DP,
                useNavigationRail = false,
                inlineSearchAndChips = false,
            )

        WindowWidthClass.Medium ->
            FeedLayoutSpec(
                widthClass = cls,
                columns = 3,
                previewLimitAll = 6,
                previewLimitSingle = 9,
                horizontalPaddingDp = 24,
                cardSpacingDp = 14,
                maxContentWidthDp = MAX_CONTENT_WIDTH_DP,
                useNavigationRail = true,
                inlineSearchAndChips = false,
            )

        WindowWidthClass.Expanded ->
            FeedLayoutSpec(
                widthClass = cls,
                columns = 4,
                previewLimitAll = 8,
                previewLimitSingle = 12,
                horizontalPaddingDp = 32,
                cardSpacingDp = 16,
                maxContentWidthDp = MAX_CONTENT_WIDTH_DP,
                useNavigationRail = true,
                inlineSearchAndChips = true,
            )
    }

fun feedLayoutSpecFor(width: Dp): FeedLayoutSpec = feedLayoutSpecFor(width.value.toInt())

/**
 * Width class for the top-level navigation shell (decides bottom NavigationBar vs leading
 * NavigationRail using the full window width, before any rail is subtracted).
 */
fun shouldUseNavigationRail(windowWidthDp: Int): Boolean =
    windowWidthClassFor(windowWidthDp) != WindowWidthClass.Compact

fun shouldUseNavigationRail(windowWidth: Dp): Boolean = shouldUseNavigationRail(windowWidth.value.toInt())

/** True when a screen has enough horizontal width for a Material 3 two-pane layout. */
fun shouldUseTwoPane(widthDp: Int): Boolean = windowWidthClassFor(widthDp) == WindowWidthClass.Expanded

fun shouldUseTwoPane(width: Dp): Boolean = shouldUseTwoPane(width.value.toInt())

/** Columns for Files tab row shelves (Models / G-code): 1 on phones, 2 on tablets. */
fun libraryListColumnsFor(widthDp: Int): Int =
    if (windowWidthClassFor(widthDp) == WindowWidthClass.Compact) 1 else 2

fun libraryListColumnsFor(width: Dp): Int = libraryListColumnsFor(width.value.toInt())

/** Columns for the Photos grid: 2 on Compact, 3 on Medium, 4 on Expanded. */
fun photoGridColumnsFor(widthDp: Int): Int = feedLayoutSpecFor(widthDp).columns

fun photoGridColumnsFor(width: Dp): Int = photoGridColumnsFor(width.value.toInt())

/** Readable max width for single-column content on Medium tablets (e.g., portrait). */
val MediumSingleColumnMaxWidth: Dp = 720.dp

val FeedLayoutSpec.horizontalPadding: Dp get() = horizontalPaddingDp.dp
val FeedLayoutSpec.cardSpacing: Dp get() = cardSpacingDp.dp
val FeedLayoutSpec.maxContentWidth: Dp get() = maxContentWidthDp.dp
