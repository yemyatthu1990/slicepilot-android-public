package world.yemyat.slicepilot.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.NativeLib
import world.yemyat.slicepilot.tracking.EventNotifier

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConfigureSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val current by vm.config.collectAsState()
    val printers = remember { parseJsonStringArray(NativeLib.nativeGetPrinters()) }
    var processList by remember { mutableStateOf<List<String>>(emptyList()) }
    var filamentList by remember { mutableStateOf<List<String>>(emptyList()) }
    var printer by remember { mutableStateOf(current.printer ?: printers.firstOrNull { it.contains("Neptune 4 0.4", true) } ?: printers.firstOrNull()) }
    var process by remember { mutableStateOf(current.process) }
    var filament by remember { mutableStateOf(current.filament) }
    var support by remember { mutableStateOf(current.supportEnabled) }
    var supportStyle by remember { mutableStateOf(current.supportStyle) }

    fun selectProcess(p: String?) {
        process = p
        if (p != null) {
            EventNotifier.trackEvent("process_preset_selected", mapOf("process" to p))
        }
        if (p != null && NativeLib.nativeSelectProcess(p)) {
            filamentList = parseJsonStringArray(NativeLib.nativeGetCompatibleFilaments())
            if (filament == null || filament !in filamentList) filament = filamentList.firstOrNull()
        }
    }

    fun selectPrinter(p: String?) {
        printer = p
        if (p != null && NativeLib.nativeSelectPrinter(p)) {
            processList = parseJsonStringArray(NativeLib.nativeGetCompatibleProcesses())
            selectProcess(processList.firstOrNull())
        }
    }

    LaunchedEffect(Unit) {
        if (printer != null) selectPrinter(printer) else selectPrinter(printers.firstOrNull())
    }

    fun persist() {
        val filamentColor = if (filament == current.filament) current.filamentColor else null
        vm.updateConfig(
            SliceConfig(
                printer = printer,
                process = process,
                filament = filament,
                filamentColor = filamentColor,
                supportEnabled = support,
                supportStyle = supportStyle,
            )
        )
    }

    ModalBottomSheet(onDismissRequest = { persist(); onDismiss() }) {
        Column(
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Configure print", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Optional — defaults are used if you don't change anything.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            PickerDropdown("Printer", printers, printer, enabled = true) { selectPrinter(it); persist() }
            PickerDropdown("Process", processList, process, enabled = processList.isNotEmpty()) { selectProcess(it); persist() }
            PickerDropdown(
                "Filament",
                filamentList,
                filament,
                enabled = filamentList.isNotEmpty(),
            ) {
                filament = it
                EventNotifier.trackEvent("filament_selected", mapOf("source" to "configure", "filament" to it))
                persist()
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("Support material", fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                Switch(checked = support, onCheckedChange = { support = it; persist() })
            }
            if (support) {
                PickerDropdown(
                    "Support style",
                    listOf("grid", "trees"),
                    supportStyle,
                    enabled = true,
                ) { supportStyle = it; persist() }
            }

            Button(
                onClick = { persist(); onDismiss() },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().height(54.dp),
            ) {
                Text("Done", fontSize = 17.sp)
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickerDropdown(
    label: String,
    options: List<String>,
    selected: String?,
    enabled: Boolean = true,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { if (enabled) expanded = it }) {
        OutlinedTextField(
            value = selected ?: "",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { opt ->
                DropdownMenuItem(
                    text = { Text(opt) },
                    onClick = { onSelect(opt); expanded = false },
                )
            }
        }
    }
}

fun parseJsonStringArray(s: String): List<String> {
    val out = mutableListOf<String>()
    var i = 0
    while (i < s.length && s[i] != '[') i++
    i++
    val cur = StringBuilder()
    var inStr = false
    var esc = false
    while (i < s.length) {
        val c = s[i]
        if (inStr) {
            when {
                esc -> { cur.append(unescapeChar(c)); esc = false }
                c == '\\' -> esc = true
                c == '"' -> { out.add(cur.toString()); cur.setLength(0); inStr = false }
                else -> cur.append(c)
            }
        } else {
            if (c == '"') inStr = true
            else if (c == ']') break
        }
        i++
    }
    return out
}

private fun unescapeChar(c: Char): Char = when (c) {
    'n' -> '\n'
    'r' -> '\r'
    't' -> '\t'
    'b' -> '\b'
    'f' -> ''
    '"' -> '"'
    '\\' -> '\\'
    else -> c
}
