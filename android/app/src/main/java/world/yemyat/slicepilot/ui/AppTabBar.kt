package world.yemyat.slicepilot.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector

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
    NavigationBar(modifier = modifier) {
        AppTab.values().forEach { tab ->
            NavigationBarItem(
                selected = tab == selected,
                onClick = { vm.openScreen(tab.target) },
                icon = { Icon(tab.icon, contentDescription = tab.label) },
                label = { Text(tab.label) },
            )
        }
    }
}
