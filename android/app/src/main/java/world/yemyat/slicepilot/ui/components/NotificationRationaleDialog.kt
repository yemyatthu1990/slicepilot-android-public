package world.yemyat.slicepilot.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.Space
import world.yemyat.slicepilot.ui.theme.Vermillion
import world.yemyat.slicepilot.ui.theme.brandPrimaryGradient

/**
 * Rationale shown before the Android 13+ notification permission prompt: a bare
 * system dialog gets reflexively denied, so lead with what notifications are for,
 * in the app's own card/gradient style. Confirm fires the real system prompt.
 */
@Composable
fun NotificationRationaleDialog(onAllow: () -> Unit, onNotNow: () -> Unit) {
    Dialog(
        onDismissRequest = onNotNow,
        // Explicit choice only: tapping outside must not silently decline.
        properties = DialogProperties(dismissOnClickOutside = false, dismissOnBackPress = false),
    ) {
        BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.lg) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(brandPrimaryGradient)
                        .border(1.5.dp, LanternGold.copy(alpha = 0.55f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Notifications,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(30.dp),
                    )
                }
                Spacer(Modifier.height(Space.md))
                Text(
                    "Stay in the loop",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Black,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    "SlicePilot can keep you posted on your printer — so you can close the " +
                        "app and get on with your day.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.lg))
                RationaleRow(
                    icon = Icons.Filled.Print,
                    title = "Slicing progress",
                    subtitle = "Right in the shade, with a ping when the slice is ready",
                )
                RationaleRow(
                    icon = Icons.Filled.Notifications,
                    title = "Live print status",
                    subtitle = "Percent, layer and time left — with Pause, Resume and Stop",
                )
                RationaleRow(
                    icon = Icons.Filled.CheckCircle,
                    title = "Print finished",
                    subtitle = "An alert the moment a print ends, cleanly or not",
                )
                Spacer(Modifier.height(Space.lg))
                BrandButton(
                    text = "Allow notifications",
                    onClick = onAllow,
                    modifier = Modifier.fillMaxWidth(),
                    height = 54.dp,
                )
                TextButton(onClick = onNotNow) {
                    Text(
                        "Not now",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RationaleRow(icon: ImageVector, title: String, subtitle: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Space.xs),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(Radius.sm))
                .background(LanternGold.copy(alpha = 0.16f))
                .border(1.dp, LanternGold.copy(alpha = 0.35f), RoundedCornerShape(Radius.sm)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = Vermillion,
                modifier = Modifier.size(20.dp),
            )
        }
        Spacer(Modifier.width(Space.sm))
        Column(Modifier.padding(top = 1.dp)) {
            Text(title, fontSize = MaterialTheme.typography.bodyMedium.fontSize, fontWeight = FontWeight.Bold)
            Text(
                subtitle,
                fontSize = MaterialTheme.typography.bodySmall.fontSize,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
