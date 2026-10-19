package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.*
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.ClickableText
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.cloud.CloudPrinter
import world.yemyat.slicepilot.ui.theme.LanternGold

private data class PrinterBrand(
    val id: String,
    val label: String,
)

private val supportedPrinterBrands = listOf(
    PrinterBrand("elegoo", "Elegoo"),
)

private const val ELEGOO_TERMS_URL = "https://www.elegoo.com/pages/terms-of-service"
private const val ELEGOO_PRIVACY_URL = "https://www.elegoo.com/pages/privacy-policy"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectSheet(vm: AppViewModel, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val printers by vm.printers.collectAsState()
    val filaments by vm.filaments.collectAsState()
    val selectedPrinter by vm.selectedPrinterState.collectAsState()

    var selectedBrand by rememberSaveable {
        mutableStateOf(if (vm.selectedPrinter != null) "elegoo" else null)
    }
    var selectedTab by rememberSaveable {
        mutableIntStateOf(
            if (vm.selectedPrinter?.isLocal == true) 1 else 0
        )
    }

    LaunchedEffect(selectedPrinter) {
        if (selectedPrinter != null) {
            selectedBrand = "elegoo"
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = LanternGold.copy(alpha = 0.45f)) },
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val connectedPrinter = selectedPrinter
            if (connectedPrinter != null) {
                Text("Printer connected", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Connected to Elegoo over ${if (connectedPrinter.isLocal) "local Wi-Fi" else "cloud"}.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )

                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    border = BorderStroke(2.dp, MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Icon(
                            if (connectedPrinter.isLocal) Icons.Filled.Wifi else Icons.Filled.CloudDone,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Column(Modifier.weight(1f)) {
                            Text(connectedPrinter.name, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                buildString {
                                    append(connectedPrinter.model)
                                    if (connectedPrinter.isLocal && !connectedPrinter.ip.isNullOrBlank()) {
                                        append(" · ")
                                        append(connectedPrinter.ip)
                                    }
                                },
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Connected", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Text("Done", fontSize = 16.sp)
                    }
                    OutlinedButton(
                        onClick = { vm.clearSelectedPrinter() },
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(52.dp),
                    ) {
                        Text("Disconnect", fontSize = 16.sp)
                    }
                }
            } else {
                Text("Connect your printer", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Text(
                    "Choose a printer brand, then connect over local Wi-Fi or cloud.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                )

                Text(
                    "Printer brand",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    supportedPrinterBrands.forEach { brand ->
                        FilterChip(
                            selected = selectedBrand == brand.id,
                            onClick = { selectedBrand = brand.id },
                            label = { Text(brand.label) },
                        )
                    }
                }

                when (selectedBrand) {
                    "elegoo" -> {
                        PrimaryTabRow(
                            selectedTabIndex = selectedTab,
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)),
                        ) {
                            Tab(
                                selected = selectedTab == 0,
                                onClick = { selectedTab = 0 },
                                text = { Text("Cloud", fontWeight = FontWeight.SemiBold) },
                                icon = {
                                    Icon(
                                        if (vm.cloudLoggedIn) Icons.Filled.CloudDone else Icons.Filled.CloudOff,
                                        contentDescription = null,
                                    )
                                },
                            )
                            Tab(
                                selected = selectedTab == 1,
                                onClick = { selectedTab = 1 },
                                text = { Text("Local Network", fontWeight = FontWeight.SemiBold) },
                                icon = { Icon(Icons.Filled.Wifi, contentDescription = null) },
                            )
                        }

                        if (selectedTab == 0) {
                            CloudSection(
                                vm = vm,
                                printers = printers,
                                filaments = filaments,
                                selectedPrinter = selectedPrinter,
                                onDismiss = onDismiss,
                            )
                        } else {
                            LocalNetworkSection(
                                vm = vm,
                                selectedPrinter = selectedPrinter,
                                filaments = filaments,
                                onDismiss = onDismiss,
                            )
                        }
                    }
                    null -> {
                        Text(
                            "Select a printer brand to continue.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                    else -> {
                        Text(
                            "More printer brands coming soon.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun LocalNetworkSection(
    vm: AppViewModel,
    selectedPrinter: ConnectedPrinter?,
    filaments: Map<String, world.yemyat.slicepilot.cloud.FilamentInfo>,
    onDismiss: () -> Unit,
) {
    val ctx = LocalContext.current
    val scanning by vm.lanScanning.collectAsState()
    val lanPrinters by vm.lanPrinters.collectAsState()

    LaunchedEffect(Unit) {
        if (lanPrinters.isEmpty()) {
            vm.scanLan(ctx)
        }
    }

    Text(
        "Print without signing in when your phone and printer are on the same Wi-Fi.",
        fontSize = 13.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Button(
        onClick = { vm.scanLan(ctx) },
        enabled = !scanning,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        if (scanning) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
            Spacer(Modifier.width(10.dp))
            Text("Scanning Wi-Fi…", fontSize = 16.sp)
        } else {
            Icon(Icons.Filled.Wifi, null)
            Spacer(Modifier.width(8.dp))
            Text("Scan for printers", fontSize = 16.sp)
        }
    }

    if (lanPrinters.isNotEmpty()) {
        Text("Found printers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var showFilamentPicker by remember { mutableStateOf(false) }
            val curLanFilament = selectedPrinter?.let {
                filaments[it.serial ?: ""] ?: filaments[it.ip ?: ""] ?: filaments[it.id]
            }

            lanPrinters.forEach { p ->
                val isSelected = selectedPrinter?.let { it.isLocal && (it.serial == p.serial || it.ip == p.ip) } == true
                val filament = filaments[p.serial] ?: filaments[p.ip] ?: (if (isSelected) curLanFilament else null)
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant,
                    border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(if (p.lanMode) 1f else 0.55f)
                        .clickable(enabled = p.lanMode) { vm.selectLanPrinter(p) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(p.name.ifBlank { p.model }, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(
                                    "${p.model} · ${p.ip}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (!p.lanMode) {
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        "Cloud mode — switch the printer to LAN-only mode to connect",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                            if (isSelected) {
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    contentColor = MaterialTheme.colorScheme.onPrimary,
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("Connected", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        if (filament != null && filament.present) {
                            Spacer(Modifier.height(10.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surface)
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            ) {
                                val trays = filament.trays.ifEmpty {
                                    listOf(world.yemyat.slicepilot.cloud.LoadedTray(-1, filament.name, filament.name, filament.colorHex))
                                }
                                trays.forEachIndexed { idx, tray ->
                                    if (idx > 0) Spacer(Modifier.width(12.dp))
                                    val swatchColor = remember(tray.colorHex) {
                                        tray.colorHex?.let {
                                            try { android.graphics.Color.parseColor(it) } catch (_: Exception) { null }
                                        }
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            Modifier
                                                .size(16.dp)
                                                .background(
                                                    swatchColor?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
                                                    CircleShape,
                                                )
                                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                        )
                                        Spacer(Modifier.width(6.dp))
                                        Text(
                                            tray.type.ifBlank { tray.name }.ifBlank { "Filament" },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (selectedPrinter?.isLocal == true) {
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { vm.requestAccessCodeEntry() },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Icon(Icons.Filled.Visibility, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Access code", fontSize = 14.sp)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedButton(
                    onClick = { showFilamentPicker = true },
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(46.dp),
                ) {
                    Icon(Icons.Filled.Tune, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Change loaded filament", fontSize = 14.sp)
                }
            }

            if (showFilamentPicker) {
                FilamentPickerDialog(
                    currentMaterial = curLanFilament?.name ?: "PLA",
                    currentColorHex = curLanFilament?.colorHex ?: "#E0E0E0",
                    spools = vm.lanSpools().map { it.trayId to it.colorHex },
                    currentTrayId = curLanFilament?.trays?.firstOrNull()?.trayId ?: 0,
                    onDismiss = { showFilamentPicker = false },
                    onConfirm = { mat, col, tray ->
                        vm.setLanPrinterFilament(mat, col, tray)
                        showFilamentPicker = false
                    },
                )
            }
        }
    } else if (!scanning) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.Filled.Info, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "No local printers detected yet. Make sure your printer and phone are connected to the same Wi-Fi, then tap Scan.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (selectedPrinter?.isLocal == true) {
        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onDismiss,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) {
            Icon(Icons.Filled.Check, null)
            Spacer(Modifier.width(8.dp))
            Text("Done", fontSize = 16.sp)
        }
    }
}

@Composable
private fun CloudSection(
    vm: AppViewModel,
    printers: List<CloudPrinter>,
    filaments: Map<String, world.yemyat.slicepilot.cloud.FilamentInfo>,
    selectedPrinter: ConnectedPrinter?,
    onDismiss: () -> Unit,
) {
    val demoMode by vm.demoModeState.collectAsState()
    if (!vm.cloudLoggedIn) {
        var email by rememberSaveable { mutableStateOf(vm.savedCloudEmail) }
        var password by rememberSaveable { mutableStateOf(vm.savedCloudPassword) }
        var rememberPassword by rememberSaveable { mutableStateOf(vm.rememberCloudPassword) }
        var agreedToElegooTerms by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        val uriHandler = LocalUriHandler.current

        fun doSignIn() {
            if (busy || email.isBlank() || password.isBlank() || !agreedToElegooTerms) return
            busy = true
            vm.nativeLogin(email, password) { ok ->
                busy = false
                if (ok) vm.saveCloudCredentials(email, password, rememberPassword)
            }
        }

        Text(
            "Sign in with your Elegoo account to print over the cloud.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        var showPassword by rememberSaveable { mutableStateOf(false) }
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { doSignIn() }),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        imageVector = if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { rememberPassword = !rememberPassword },
        ) {
            Checkbox(
                checked = rememberPassword,
                onCheckedChange = { rememberPassword = it },
            )
            Spacer(Modifier.width(8.dp))
            Text("Remember password", fontSize = 14.sp)
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { agreedToElegooTerms = !agreedToElegooTerms },
        ) {
            Checkbox(
                checked = agreedToElegooTerms,
                onCheckedChange = { agreedToElegooTerms = it },
            )
            Spacer(Modifier.width(8.dp))
            val linkStyle = SpanStyle(
                color = LanternGold,
                textDecoration = TextDecoration.Underline,
            )
            val termsText = buildAnnotatedString {
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append("I agree to the ")
                }
                pushStringAnnotation(tag = "URL", annotation = ELEGOO_TERMS_URL)
                withStyle(linkStyle) { append("ELEGOO Terms of Service") }
                pop()
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(" and ")
                }
                pushStringAnnotation(tag = "URL", annotation = ELEGOO_PRIVACY_URL)
                withStyle(linkStyle) { append("Privacy Policy") }
                pop()
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) {
                    append(".")
                }
            }
            ClickableText(
                text = termsText,
                style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
                onClick = { offset ->
                    termsText.getStringAnnotations(tag = "URL", start = offset, end = offset)
                        .firstOrNull()
                        ?.let { uriHandler.openUri(it.item) }
                },
            )
        }
        Button(
            onClick = { doSignIn() },
            enabled = !busy && email.isNotBlank() && password.isNotBlank() && agreedToElegooTerms,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            } else {
                Text("Sign in", fontSize = 17.sp)
            }
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(vm.cloudUser.ifEmpty { "Signed in" }, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(
                    if (demoMode) "Demo mode — printing is simulated" else vm.loginPageUrl,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { vm.refreshPrinters() }) {
                Icon(Icons.Filled.Refresh, "Refresh")
            }
        }
        if (printers.isNotEmpty()) {
            Text("Cloud printers", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                printers.forEach { p ->
                    val isSelected = selectedPrinter?.let { !it.isLocal && it.id == p.id } == true
                    PrinterRow(
                        p = p,
                        filament = filaments[p.id],
                        isSelected = isSelected,
                        onClick = { vm.selectCloudPrinter(p) },
                    )
                }
            }
        } else {
            Text("No printers found yet in your Elegoo account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { vm.signOut() }) {
                Icon(Icons.Filled.CloudOff, null)
                Spacer(Modifier.width(6.dp))
                Text(if (demoMode) "Exit demo" else "Sign out")
            }
            if (selectedPrinter?.isLocal == false) {
                Button(
                    onClick = onDismiss,
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Done")
                }
            }
        }
    }
}

@Composable
private fun PrinterRow(
    p: CloudPrinter,
    filament: world.yemyat.slicepilot.cloud.FilamentInfo?,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
        border = if (isSelected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontWeight = FontWeight.SemiBold)
                    if (p.model.isNotEmpty()) Text(p.model, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (isSelected) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.padding(end = 6.dp),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(Icons.Filled.Check, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Connected", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                AssistChip(
                    onClick = onClick,
                    label = { Text(if (p.online) "Online" else "Offline") },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(
                                    if (p.online) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                )
                        )
                    },
                )
            }
            if (p.online && filament != null && filament.present) {
                Spacer(Modifier.height(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                ) {
                    val trays = filament.trays.ifEmpty {
                        listOf(world.yemyat.slicepilot.cloud.LoadedTray(-1, filament.name, filament.name, filament.colorHex))
                    }
                    trays.forEachIndexed { idx, tray ->
                        if (idx > 0) Spacer(Modifier.width(12.dp))
                        val swatchColor = remember(tray.colorHex) {
                            tray.colorHex?.let {
                                try { android.graphics.Color.parseColor(it) } catch (_: Exception) { null }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(16.dp)
                                    .background(
                                        swatchColor?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
                                        CircleShape,
                                    )
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tray.type.ifBlank { tray.name }.ifBlank { "Filament" },
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AccessCodeDialog(
    printerName: String,
    initialCode: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var code by rememberSaveable { mutableStateOf(initialCode) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Access code", fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Enter the access code shown on $printerName's screen.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = code,
                    onValueChange = { code = it.filter(Char::isLetterOrDigit).take(16) },
                    label = { Text("Access code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { if (code.isNotBlank()) onSave(code.trim()) },
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "No code on the screen? Update the printer's firmware, then turn on " +
                        "LAN Only mode in its network settings.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(code.trim()) },
                enabled = code.isNotBlank(),
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilamentPickerDialog(
    currentMaterial: String,
    currentColorHex: String?,
    spools: List<Pair<Int, String?>>,
    currentTrayId: Int,
    onDismiss: () -> Unit,
    onConfirm: (String, String, Int) -> Unit,
) {
    val materials = listOf("PLA", "PETG", "ABS", "TPU", "PLA+", "PLA-CF", "PETG-CF", "ASA", "PC", "PA")
    val colors = listOf(
        "#FFFFFF" to "White",
        "#212121" to "Black",
        "#9E9E9E" to "Grey",
        "#E53935" to "Red",
        "#1E88E5" to "Blue",
        "#43A047" to "Green",
        "#FB8C00" to "Orange",
        "#FDD835" to "Yellow",
        "#8E24AA" to "Purple",
        "#00ACC1" to "Cyan",
        "#D4AF37" to "Gold",
        "#A1887F" to "Brown",
    )
    var selectedMat by remember { mutableStateOf(currentMaterial.ifBlank { "PLA" }) }
    var selectedCol by remember { mutableStateOf(currentColorHex ?: "#FFFFFF") }
    var selectedTray by remember { mutableStateOf(currentTrayId) }
    // The printer's own slots when known; the CC2 has two when it never reported.
    val slotOptions = spools.ifEmpty { listOf(0 to null as String?, 1 to null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set Loaded Filament", fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text("Material type", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    materials.forEach { m ->
                        FilterChip(
                            selected = m.equals(selectedMat, ignoreCase = true),
                            onClick = { selectedMat = m },
                            label = { Text(m) },
                        )
                    }
                }

                Text("Spool color", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    colors.forEach { (hex, name) ->
                        val isColSelected = selectedCol.equals(hex, ignoreCase = true)
                        val parsed = try { Color(android.graphics.Color.parseColor(hex)) } catch (_: Exception) { Color.Gray }
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable { selectedCol = hex }
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(parsed, CircleShape)
                                    .border(
                                        if (isColSelected) 3.dp else 1.dp,
                                        if (isColSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                        CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isColSelected) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = if (hex == "#FFFFFF" || hex == "#FDD835") Color.Black else Color.White,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(name, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }

                Text("Printer spool", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    slotOptions.forEach { (trayId, trayColorHex) ->
                        FilterChip(
                            selected = selectedTray == trayId,
                            onClick = { selectedTray = trayId },
                            leadingIcon = {
                                val parsed = remember(trayColorHex) {
                                    trayColorHex?.let {
                                        try { android.graphics.Color.parseColor(it) } catch (_: Exception) { null }
                                    }
                                }
                                Box(
                                    Modifier
                                        .size(14.dp)
                                        .background(
                                            parsed?.let { Color(it) } ?: MaterialTheme.colorScheme.outline,
                                            CircleShape,
                                        )
                                        .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape)
                                )
                            },
                            label = { Text("Slot ${trayId + 1}") },
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(selectedMat, selectedCol, selectedTray) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

