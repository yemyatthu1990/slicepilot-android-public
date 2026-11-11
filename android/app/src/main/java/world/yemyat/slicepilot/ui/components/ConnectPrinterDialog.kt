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
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Print
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.Space
import world.yemyat.slicepilot.ui.theme.brandPrimaryGradient

/**
 * A model is loaded but no printer is connected, and the setup page needs one to slice for.
 * Before this dialog existed the pick quietly left the user on Home — the only hint being a
 * small preview — so it read as "did the upload work?". Ask in one line instead: name the
 * model, offer the printer sheet, and keep the setup page owed to whichever printer they pick.
 */
@Composable
fun ConnectPrinterDialog(
    modelName: String?,
    onConnect: () -> Unit,
    onLater: () -> Unit,
) {
    Dialog(onDismissRequest = onLater) {
        BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.lg) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // The loaded model, answered by the printer it needs — the same pairing as
                    // PrintSetup's header, so the ask reads as the next step and not an error.
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(RoundedCornerShape(Radius.sm))
                            .background(brandPrimaryGradient)
                            .border(1.5.dp, LanternGold.copy(alpha = 0.55f), RoundedCornerShape(Radius.sm)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Print,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                    Spacer(Modifier.width(Space.sm))
                    Box(
                        Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                Spacer(Modifier.height(Space.md))
                Text(
                    "Which printer?",
                    style = MaterialTheme.typography.headlineSmall,
                    // Explicit: inside a Dialog, LocalContentColor resolves to the dialog
                    // container's own colour, so an un-coloured title painted ink-on-ink in
                    // the dark theme (measured #121418 on #1B1E22).
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(Space.xs))
                Text(
                    if (modelName.isNullOrBlank()) {
                        "No printer is connected. Connect one to set this model up for printing."
                    } else {
                        "No printer is connected. Connect one to set $modelName up for printing."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Space.lg))
                BrandButton(
                    text = "Connect to printer",
                    onClick = onConnect,
                    modifier = Modifier.fillMaxWidth(),
                    height = 54.dp,
                )
                TextButton(onClick = onLater) {
                    Text("Choose later", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
