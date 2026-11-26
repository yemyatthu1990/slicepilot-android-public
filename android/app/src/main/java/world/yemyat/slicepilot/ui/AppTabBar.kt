package world.yemyat.slicepilot.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import world.yemyat.slicepilot.ui.theme.LanternGold

/**
 * The app's top-level destinations. Every other [Screen] is a task flow (print
 * setup, slice, slicing, preview, printing, device status): those keep their own
 * back navigation and hide this bar.
 */
enum class AppTab(
    val label: String,
    val icon: ImageVector,
    val target: Screen,
) {
    HOME("Home", Icons.Filled.Home, Screen.Home),
    PRINTER("Printer", Icons.Filled.Print, Screen.Printer),
    FILES("Files", Icons.Filled.CollectionsBookmark, Screen.Library),
    ;

    companion object {
        /** The tab to highlight for [screen], or null while a task flow is open. */
        fun forScreen(screen: Screen): AppTab? = values().firstOrNull { it.target == screen }
    }
}

/**
 * Bottom navigation — Home / Printer / Files.
 *
 * Home carries the model feed (search, popular rails, one block per site), so
 * the old Find tab would only have shown the same content one tap deeper.
 */
@Composable
fun AppTabBar(
    vm: AppViewModel,
    modifier: Modifier = Modifier,
) {
    val screen by vm.screen.collectAsState()
    val selected = AppTab.forScreen(screen) ?: return
    val barColors =
        NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    Column(modifier = modifier) {
        HorizontalDivider(thickness = 1.dp, color = LanternGold.copy(alpha = 0.22f))
        NavigationBar(
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
        ) {
            AppTab.values().forEach { tab ->
                NavigationBarItem(
                    selected = tab == selected,
                    onClick = { vm.openScreen(tab.target) },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    colors = barColors,
                )
            }
        }
    }
}

/**
 * Leading navigation rail for Medium and Expanded widths (>= 600dp), per Material 3
 * adaptive navigation guidelines. Houses the primary "Local model" FAB in the rail header
 * on Home so it never overlaps feed cards on tablets.
 */
@Composable
fun AppNavigationRail(
    vm: AppViewModel,
    onPickLocalFile: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screen by vm.screen.collectAsState()
    val selected = AppTab.forScreen(screen) ?: return
    val railColors =
        NavigationRailItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onSecondaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.onSurface,
            indicatorColor = MaterialTheme.colorScheme.secondaryContainer,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    Row(modifier = modifier.fillMaxHeight()) {
        NavigationRail(
            containerColor = MaterialTheme.colorScheme.surface,
            header = {
                // Keep the primary "Local model" FAB pinned in the rail header across all
                // top-level tabs so navigation destinations never shift vertically on tab switch.
                FloatingActionButton(
                    onClick = onPickLocalFile,
                    shape = RoundedCornerShape(16.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(defaultElevation = 2.dp),
                    modifier = Modifier.padding(top = 12.dp, bottom = 8.dp),
                ) {
                    Icon(Icons.Filled.FolderOpen, contentDescription = "Local model")
                }
            },
        ) {
            Spacer(Modifier.height(8.dp))
            AppTab.values().forEach { tab ->
                NavigationRailItem(
                    selected = tab == selected,
                    onClick = { vm.openScreen(tab.target) },
                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                    label = { Text(tab.label) },
                    alwaysShowLabel = true,
                    colors = railColors,
                )
            }
        }
        VerticalDivider(
            modifier = Modifier.fillMaxHeight(),
            thickness = 1.dp,
            color = LanternGold.copy(alpha = 0.22f),
        )
    }
}
