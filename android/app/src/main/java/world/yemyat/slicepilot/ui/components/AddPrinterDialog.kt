package world.yemyat.slicepilot.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.ui.AppViewModel

/**
 * The app's half of Matrix's Add-device screen, which asks for one thing only: the PIN the
 * printer's settings show (`pincode`). The cloud's other values — `pcode` and `serialNo`, the two
 * `check-pincode` compares — are not asked for because they are not the user's to remember: the QR
 * on the panel carries them and the cloud answers them from the PIN. Whatever a scan or the cloud
 * gave is still shown here, since it decides which printer pairing will claim.
 *
 * [world.yemyat.slicepilot.cloud.PairingCode] normalises what is typed or scanned, so a pasted
 * `PinCode: …` or a stray space still pairs. The values are hoisted to the caller because the QR
 * scanner lives outside this dialog and writes into them.
 */
@Composable
fun AddPrinterDialog(
    account: String,
    attempt: AppViewModel.PairingAttempt?,
    machine: String,
    pincode: String,
    onPincodeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSubmit: () -> Unit,
    onScanQr: () -> Unit,
) {
    val busy = attempt?.busy == true

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add printer", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Claim a printer for $account — type the PIN its settings screen shows, or " +
                        "scan the QR code on the panel.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (machine.isNotBlank()) {
                    // Not a field: the printer's identity, read from a scan (or the cloud's answer),
                    // shown so pairing can be stopped before it claims the wrong machine. Either of
                    // the cloud's identifiers can be here, so the line is labelled plainly.
                    Text(
                        "Machine · $machine",
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = pincode,
                    onValueChange = onPincodeChange,
                    label = { Text("PIN code") },
                    singleLine = true,
                    enabled = !busy,
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Text,
                            imeAction = ImeAction.Done,
                        ),
                    keyboardActions =
                        KeyboardActions(
                            onDone = { if (pincode.isNotBlank()) onSubmit() },
                        ),
                    supportingText = { Text("On the printer's settings screen") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("Pairing…", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                attempt?.error?.let {
                    Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.error)
                }
                OutlinedButton(
                    onClick = onScanQr,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.QrCode2, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Scan QR code")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onSubmit,
                enabled = pincode.isNotBlank() && !busy,
            ) {
                Text("Pair")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
