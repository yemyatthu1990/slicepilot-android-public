package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.cloud.PrintStatus
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.BrandSectionLabel
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius

/**
 * Everything that can be set on the machine, in one sheet: the job actions, the case
 * light, the temperature targets and the fans.
 *
 * The Printer tab keeps the hero and the live camera on screen and everything else
 * here, so the tab stays a dashboard instead of turning into a settings form (the
 * dark print-monitor page in [DeviceStatusScreen] shows the same controls inline,
 * because there the controls *are* the page).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterControlsSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val status by vm.printStatus.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Every control below needs a real answer from the printer — a slider at a
            // made-up temperature is worse than no slider, so while the printer has not
            // answered the sheet says so instead of showing empty fields.
            val current: PrintStatus? = status
            if (current == null) {
                Text(
                    "Reading the printer… controls appear as soon as it answers.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // Job actions only when a job exists — an idle printer has nothing to
                // pause, and the hero shows the same buttons in the same situation.
                if (current.printing) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrandSectionLabel("Print job")
                        PrinterJobActions(vm = vm, status = current)
                    }
                }

                if (current.live) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        BrandSectionLabel("Case light")
                        CaseLightToggle(vm = vm, on = current.lightOn == true)
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandSectionLabel("Temperature targets")
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        TargetPicker(
                            label = "Nozzle",
                            currentTemp = current.nozzleTarget?.toInt() ?: -1,
                            presets = listOf(0, 180, 200, 220, 240),
                            onPick = { vm.setNozzleTarget(it) },
                            modifier = Modifier.weight(1f),
                        )
                        TargetPicker(
                            label = "Bed",
                            currentTemp = current.bedTarget?.toInt() ?: -1,
                            presets = listOf(0, 45, 60, 80),
                            onPick = { vm.setBedTarget(it) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BrandSectionLabel("Fans")
                    FanControls(vm = vm, status = current, printing = current.printing)
                }
            }
        }
    }
}

/**
 * Pause / Resume and Stop for a running job, with the two confirmations. Shared by
 * the tab hero (where a job is on screen, so the buttons belong there) and the
 * controls sheet.
 */
@Composable
fun PrinterJobActions(
    vm: AppViewModel,
    status: PrintStatus,
    modifier: Modifier = Modifier,
) {
    var confirmPause by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }
    val paused = status.state.isPaused

    if (confirmPause) {
        AlertDialog(
            onDismissRequest = { confirmPause = false },
            title = { Text("Pause print?") },
            text = {
                Text("The printer will pause at the current position. You can resume from here or from the notification.")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmPause = false
                    vm.pausePrint()
                }) { Text("Pause") }
            },
            dismissButton = {
                TextButton(onClick = { confirmPause = false }) { Text("Keep printing") }
            },
        )
    }
    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("Stop print?") },
            text = {
                Text(
                    "The print will be stopped and the printer will cool down. A stopped print cannot be resumed — the file has to be started again.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmStop = false
                    vm.stopPrintJob()
                }) { Text("Stop print", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmStop = false }) { Text("Keep printing") }
            },
        )
    }

    // Both actions used to be plain M3 buttons with hand-picked container
    // colours; they now ask for their role, so "resume" and "stop" look the way
    // they look everywhere else in the app.
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BrandButton(
            text = if (paused) "Resume" else "Pause",
            onClick = {
                if (paused) vm.resumePrint() else confirmPause = true
            },
            modifier = Modifier.weight(1f),
            role = if (paused) ButtonRole.Primary else ButtonRole.Tonal,
            icon = {
                Icon(
                    if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause,
                    null,
                    modifier = Modifier.size(18.dp),
                )
            },
            height = ButtonSize.Small,
        )
        BrandButton(
            text = "Stop",
            onClick = { confirmStop = true },
            modifier = Modifier.weight(1f),
            role = ButtonRole.Destructive,
            icon = { Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp)) },
            height = ButtonSize.Small,
        )
    }
}

/** Case light as a labeled pill, so it doesn't read like a notification badge. */
@Composable
private fun CaseLightToggle(
    vm: AppViewModel,
    on: Boolean,
) {
    val shape = RoundedCornerShape(Radius.sm)
    Surface(
        onClick = { vm.setCaseLight(!on) },
        shape = shape,
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, LanternGold.copy(alpha = if (on) 0.45f else 0.18f)),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Icon(
                Icons.Filled.Lightbulb,
                null,
                modifier = Modifier.size(16.dp),
                tint = if (on) LanternGold else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                if (on) "On" else "Off",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** A temperature set-point picker: current value plus the printer's preset list. */
@Composable
private fun TargetPicker(
    label: String,
    currentTemp: Int,
    presets: List<Int>,
    onPick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.sm)
    Surface(
        onClick = { expanded = true },
        shape = shape,
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.18f)),
        modifier = modifier,
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        label,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        letterSpacing = 0.6.sp,
                    )
                    Text(
                        if (currentTemp <= 0) "Off" else "$currentTemp °C",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                presets.forEach { t ->
                    DropdownMenuItem(
                        text = { Text(if (t == 0) "Off" else "$t °C") },
                        onClick = {
                            expanded = false
                            onPick(t)
                        },
                    )
                }
            }
        }
    }
}

/**
 * Fan speeds. While printing the firmware drives them from the G-code (and Matrix
 * mirrors that by hiding the sliders), so the speeds are shown but not editable;
 * when idle the sliders follow the printer until the user drags one.
 */
@Composable
private fun FanControls(
    vm: AppViewModel,
    status: PrintStatus,
    printing: Boolean,
) {
    if (printing) {
        val shape = RoundedCornerShape(Radius.sm)
        Surface(
            shape = shape,
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                Text(
                    "Printer-controlled",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Model ${status.partFanSpeed.pct()} · Aux ${status.auxFanSpeed.pct()}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        return
    }

    val modelPctFromStatus = (status.partFanSpeed.coerceIn(0, 255) * 100 + 127) / 255
    val auxPctFromStatus = (status.auxFanSpeed.coerceIn(0, 255) * 100 + 127) / 255
    var modelPct by remember { mutableIntStateOf(modelPctFromStatus) }
    var auxPct by remember { mutableIntStateOf(auxPctFromStatus) }
    LaunchedEffect(modelPctFromStatus) { modelPct = modelPctFromStatus }
    LaunchedEffect(auxPctFromStatus) { auxPct = auxPctFromStatus }

    FanSlider(
        label = "Model fan",
        percent = modelPct,
        onValueChange = { modelPct = it },
        onCommit = { vm.setFanSpeed(it) },
    )
    FanSlider(
        label = "Aux fan",
        percent = auxPct,
        onValueChange = { auxPct = it },
        onCommit = { vm.setAuxFanSpeed(it) },
    )
}

@Composable
private fun FanSlider(
    label: String,
    percent: Int,
    onValueChange: (Int) -> Unit,
    onCommit: (Int) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                label,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Text("$percent%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = percent.toFloat(),
            onValueChange = { onValueChange(it.toInt()) },
            onValueChangeFinished = { onCommit(percent) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(thumbColor = BrandCyan, activeTrackColor = BrandCyan),
        )
    }
}

/** PWM 0-255 → "80%", the unit the firmware reports fan speeds in. */
private fun Int.pct(): String = "${(this * 100) / 255}%"

/** Whether a job occupies the printer (so its controls are locked to the firmware). */
internal val PrintStatus.printing: Boolean
    get() =
        state == PrintJobState.PRINTING || state == PrintJobState.PAUSING ||
            state == PrintJobState.PAUSED || state == PrintJobState.RESUMING ||
            state == PrintJobState.STOPPING
