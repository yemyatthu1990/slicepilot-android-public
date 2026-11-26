package world.yemyat.slicepilot

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.core.view.ViewCompat
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import world.yemyat.slicepilot.cloud.ElegooCloudClient
import world.yemyat.slicepilot.model.ModelParser
import world.yemyat.slicepilot.tracking.EventNotifier
import world.yemyat.slicepilot.tracking.TrackingBootstrap
import world.yemyat.slicepilot.ui.*
import world.yemyat.slicepilot.ui.components.AddPrinterDialog
import world.yemyat.slicepilot.ui.components.QrScannerDialog
import world.yemyat.slicepilot.ui.components.SpiritedBackground
import world.yemyat.slicepilot.ui.theme.ElegooSlicerTheme
import java.io.File

class MainActivity : ComponentActivity() {
    private lateinit var cloud: ElegooCloudClient
    private lateinit var vm: AppViewModel

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // The system prompt has been seen (grant or deny): stop showing the
            // rationale dialog; users who chose "Not now" keep seeing it until
            // the system prompt appears.
            getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE)
                .edit()
                .putBoolean("notifications_asked", true)
                .apply()
            if (!granted) {
                getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putBoolean("notifications_denied", true)
                    .apply()
            }
        }

    /** First launch on Android 13+ with notifications not granted and the system
     *  prompt never shown: lead with a rationale dialog before asking. */
    private fun needsNotificationRationale(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE)
                .getBoolean("notifications_asked", false)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        NativeLib.init(this)
        TrackingBootstrap.ensure(this)
        EventNotifier.trackEvent("app_opened")
        cloud = ElegooCloudClient(this)
        val openPreview = intent?.getBooleanExtra("open_preview", false) == true
        // Any print-related notification tap lands on the device status screen.
        // A swipe-killed task discards the tap's extras, so a live print session
        // itself routes there too (covers the icon tap during printing as well).
        val openPrint = intent?.getBooleanExtra("open_print_monitor", false) == true
        val prefs = getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE)
        val openStatus =
            intent?.getBooleanExtra("open_device_status", false) == true ||
                prefs.getBoolean("print_session_active", false) ||
                prefs.getBoolean("pending_device_status", false).also {
                    if (it) prefs.edit().remove("pending_device_status").apply()
                }
        vm =
            AppViewModel(
                cloud = cloud,
                cacheDir = cacheDir,
                prefs = getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE),
                context = this,
                initialOpenPreviewGcodePath = if (openPreview) intent?.getStringExtra("gcode_path") else null,
                initialOpenPreviewModelPath = if (openPreview) intent?.getStringExtra("model_path") else null,
                initialOpenPrintMonitor = openPrint,
                initialOpenDeviceStatus = openStatus,
                initialPrintAction = intent?.getStringExtra(PrintForegroundService.EXTRA_PRINT_ACTION),
            )
        if (intent?.let { handleAuthLink(it) } != true) {
            handleIncomingModel(intent)
        }
        maybeRunHeadlessSlice()
        setContent {
            val darkTheme by vm.themeDark.collectAsState()
            ElegooSlicerTheme(darkTheme = darkTheme) {
                Surface(
                    Modifier.fillMaxSize(),
                    color = Color.Transparent,
                    contentColor = androidx.compose.material3.MaterialTheme.colorScheme.onBackground,
                ) {
                    AppRoot(vm, darkTheme = darkTheme)
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Re-apply window insets after orientation changes (e.g. exiting landscape CameraFullscreen)
        // so statusBarsPadding() and navigationBarsPadding() never stay stuck at 0.
        window?.decorView?.let { decor ->
            ViewCompat.requestApplyInsets(decor)
            decor.post { ViewCompat.requestApplyInsets(decor) }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_preview", false)) {
            if (!vm.openPreviewFromPaths(intent.getStringExtra("gcode_path"), intent.getStringExtra("model_path"))) {
                vm.openPreviewIfReady()
            }
        }
        if (intent.getBooleanExtra("open_print_monitor", false)) {
            vm.openPrintMonitor()
        }
        if (intent.getBooleanExtra("open_device_status", false)) {
            vm.openDeviceStatus()
        }
        when (intent.getStringExtra(PrintForegroundService.EXTRA_PRINT_ACTION)) {
            PrintForegroundService.ACTION_PAUSE -> vm.pausePrint()
            PrintForegroundService.ACTION_RESUME -> vm.resumePrint()
            PrintForegroundService.ACTION_STOP -> vm.stopPrintJob()
        }
        if (!handleAuthLink(intent)) {
            handleIncomingModel(intent)
        }
    }

    /**
     * Every auth-shaped deep link, in one place. The `world.yemyat.slicepilot://social/google?token=…`
     * answer from a browser tab is a provider token that still has to be exchanged
     * ([AppViewModel.completeSocialSignIn]); the older web-login handoff carried the access token
     * itself, so storing it was the whole job. Returns true when the intent was an auth link.
     */
    private fun handleAuthLink(intent: android.content.Intent): Boolean {
        val data = intent.data ?: return false
        android.util.Log.i(
            "AuthCallback",
            "intent scheme=${intent.scheme} data=$data extras=${intent.extras?.keySet()?.joinToString()}",
        )
        EventNotifier.trackEvent(
            "deep_link_received",
            mapOf(
                "scheme" to data.scheme.orEmpty(),
                "host" to data.host.orEmpty(),
                "path" to data.path.orEmpty(),
            ),
        )
        if (vm.completeSocialSignIn(data)) return true
        if (data.scheme == "http" ||
            data.scheme == "https" ||
            data.scheme == "content" ||
            data.scheme == "file"
        ) {
            return false
        }
        val token =
            listOf("token", "access_token", "accessToken", "code")
                .firstNotNullOfOrNull { data.getQueryParameter(it) }
        android.util.Log.i("AuthCallback", "deep-link token=$token")
        if (token.isNullOrBlank()) return false
        cloud.auth.saveSession(
            token,
            intent.getStringExtra("refresh_token"),
            cloud.auth.nickname,
            cloud.auth.email,
        )
        vm.checkSession()
        return true
    }

    private fun handleIncomingModel(intent: android.content.Intent?) {
        if (intent == null) return
        val openPreview = intent.getBooleanExtra("open_preview", false)
        val path =
            if (openPreview) {
                null
            } else {
                intent.getStringExtra("model_path") ?: intent.getStringExtra("model")
            }
        if (path != null) {
            val f = File(path)
            if (f.exists()) {
                vm.requestLoadModel(f.absolutePath)
                return
            }
        }

        val uri =
            when (intent.action) {
                android.content.Intent.ACTION_VIEW -> {
                    intent.data ?: intent.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
                }

                android.content.Intent.ACTION_SEND -> {
                    val stream =
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
                        }
                    stream ?: intent.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
                }

                else -> {
                    intent.data ?: intent.clipData
                        ?.takeIf { it.itemCount > 0 }
                        ?.getItemAt(0)
                        ?.uri
                }
            }

        if (uri != null && (uri.scheme == "content" || uri.scheme == "file")) {
            Thread {
                try {
                    val f = copyToCache(this, uri)
                    vm.requestLoadModel(f.absolutePath)
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "Failed to load model from uri: $uri", e)
                    EventNotifier.trackError("model_load_failed", e, mapOf("uri" to uri.toString()))
                }
            }.start()
        }
    }

    @Composable
    private fun AppRoot(
        vm: AppViewModel,
        darkTheme: Boolean,
    ) {
        val screen by vm.screen.collectAsState()
        val cloudMessage by vm.cloudMessage.collectAsState()
        val cloudReconnectNeeded by vm.cloudReconnectNeeded.collectAsState()
        val slicing by vm.slicing.collectAsState()
        val pendingModelPath by vm.pendingModelPath.collectAsState()
        val askPrinterForModel by vm.askPrinterForModel.collectAsState()
        val accessCodePrompt by vm.accessCodePrompt.collectAsState()
        val pairingAttempt by vm.pairingAttempt.collectAsState()
        val discardOnExitModel by vm.discardOnExitModel.collectAsState()
        androidx.activity.compose.BackHandler(enabled = screen != Screen.Home) { vm.goBack() }
        var showConnect by remember { mutableStateOf(false) }
        var showAddPrinter by remember { mutableStateOf(false) }
        var showQrScanner by remember { mutableStateOf(false) }
        var expandedSource by remember { mutableStateOf<world.yemyat.slicepilot.repo.ModelSource?>(null) }
        // The PIN is the only thing worth typing; the machine line keeps the pcode or serial a scan
        // read, so the dialog can show which printer pairing would claim. Kept here because the
        // scanner lives outside the dialog and writes into it.
        var pairingPcode by remember { mutableStateOf("") }
        var pairingPincode by remember { mutableStateOf("") }
        var pairingVia by remember { mutableStateOf("pin") }
        var showNotifRationale by remember { mutableStateOf(needsNotificationRationale()) }
        val favorites =
            remember {
                world.yemyat.slicepilot.repo
                    .FavoritesStore(this)
            }
        val browser =
            remember {
                world.yemyat.slicepilot.repo.ModelBrowser(
                    prefs = getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE),
                    cacheDir = cacheDir,
                    cloudTokenProvider = { cloud.auth.accessToken.takeIf { cloud.auth.isLoggedIn } },
                    favorites = favorites,
                )
            }
        DisposableEffect(browser) { onDispose { browser.dispose() } }

        val scope = remember { CoroutineScope(Dispatchers.Main) }

        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val obs =
                androidx.lifecycle.LifecycleEventObserver { _, event ->
                    if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                        EventNotifier.trackEvent("app_resumed")
                        vm.checkSession()
                        vm.onAppForegrounded()
                    } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                        EventNotifier.trackEvent("app_backgrounded")
                        vm.onAppBackgrounded()
                    }
                }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

        val modelPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                if (uri != null) {
                    val name = queryName(this@MainActivity, uri) ?: uri.lastPathSegment.orEmpty()
                    if (!name.endsWith(".stl", ignoreCase = true) && !name.endsWith(".3mf", ignoreCase = true)) {
                        scope.launch {
                            snackbarHostState.showSnackbar("Only STL and 3MF files are supported")
                        }
                        return@rememberLauncherForActivityResult
                    }
                    scope.launch {
                        try {
                            val f = withContext(Dispatchers.IO) { copyToCache(this@MainActivity, uri) }
                            vm.onModelPicked(f.absolutePath)
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to import model: $uri", e)
                            EventNotifier.trackError("model_import_failed", e, mapOf("uri" to uri.toString()))
                        }
                    }
                }
            }

        val gcodeSaver =
            rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-gcode")) { uri: Uri? ->
                if (uri != null) {
                    scope.launch {
                        vm.awaitSliceFinalized()
                        val path = vm.gcodePath ?: return@launch
                        try {
                            withContext(Dispatchers.IO) {
                                contentResolver.openOutputStream(uri)?.use { out ->
                                    File(path).inputStream().use { it.copyTo(out) }
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("MainActivity", "Failed to save g-code: $uri", e)
                            EventNotifier.trackError("gcode_save_failed", e, mapOf("uri" to uri.toString()))
                        }
                    }
                }
            }

        LaunchedEffect(cloudMessage) {
            cloudMessage?.let {
                // surfaced via snackbar below
            }
        }

        val browse by browser.state.collectAsState()
        val favEntries by browser.favorites.entries.collectAsState()
        val favoritedListings =
            remember(favEntries) {
                favEntries
                    .mapNotNull { e ->
                        e.listingId.takeIf { it.isNotBlank() }?.let { "${e.source}|$it" }
                    }.toSet()
            }
        val expanded = expandedSource?.let { src -> browse.sections.firstOrNull { it.source == src } }
        LaunchedEffect(expandedSource, expanded) {
            if (expandedSource != null && expanded == null) expandedSource = null
        }

        // Pause the ambient background animation whenever an opaque full-screen canvas or pushed
        // browse overlay covers it, saving GPU/battery during 3D preview, slicing, and live video.
        val animateBackground =
            (screen == Screen.Home || screen == Screen.Printer || screen == Screen.Library) &&
                expanded == null &&
                browse.detail == null &&
                !browse.loadingDetail

        BoxWithConstraints(Modifier.fillMaxSize()) {
            val useRail = shouldUseNavigationRail(maxWidth)
            SpiritedBackground(
                modifier = Modifier.fillMaxSize(),
                darkTheme = darkTheme,
                animate = animateBackground,
            )
            Row(Modifier.fillMaxSize()) {
                if (useRail) {
                    AppNavigationRail(
                        vm = vm,
                        onPickLocalFile = { modelPicker.launch(arrayOf("*/*")) },
                    )
                }
                androidx.compose.material3.Scaffold(
                    modifier = Modifier.weight(1f),
                    containerColor = Color.Transparent,
                    snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
                    // Each screen draws its own status-bar padding; taking the inset here
                    // too would stack a second status bar of space above every tab.
                    contentWindowInsets = WindowInsets(0),
                    bottomBar = { if (!useRail) AppTabBar(vm) },
                ) { frame ->
                    Box(Modifier.padding(frame).fillMaxSize()) {
                        AnimatedContent(
                            targetState = screen,
                            transitionSpec = {
                                if (initialState == Screen.CameraFull || targetState == Screen.CameraFull) {
                                    EnterTransition.None togetherWith ExitTransition.None
                                } else {
                                    fadeIn(tween(180)) togetherWith fadeOut(tween(140))
                                }
                            },
                            label = "screenTransition",
                        ) { currentScreen ->
                            when (currentScreen) {
                                is Screen.Home -> {
                                    HomeScreen(
                                        vm = vm,
                                        onPickLocalFile = { modelPicker.launch(arrayOf("*/*")) },
                                        onOpenConnect = { showConnect = true },
                                        onSeeMore = { expandedSource = it },
                                        browser = browser,
                                        showLocalModelFab = !useRail,
                                    )
                                }

                            is Screen.Printer -> {
                                PrinterScreen(
                                    vm = vm,
                                    onOpenConnect = { showConnect = true },
                                )
                            }

                            is Screen.PrintSetup -> {
                                PrintSetupScreen(vm)
                            }

                            is Screen.Library -> {
                                LibraryScreen(
                                    vm = vm,
                                    favorites = favorites,
                                    onFindModels = { vm.openScreen(Screen.Home) },
                                )
                            }

                            is Screen.Slice -> {
                                SliceScreen(vm)
                            }

                            is Screen.Slicing -> {
                                SlicingScreen(vm)
                            }

                            is Screen.Preview -> {
                                val gp = vm.gcodePreview.collectAsState().value
                                if (gp != null) {
                                    SlicePreviewScreen(
                                        vm = vm,
                                        preview = gp,
                                        onSaveGcode = { gcodeSaver.launch(vm.suggestedGcodeName) },
                                        onPrint = { vm.startPrint(this@MainActivity) },
                                    )
                                } else {
                                    LaunchedEffect(Unit) { vm.backHome() }
                                }
                            }

                            is Screen.Printing -> {
                                PrintingScreen(
                                    vm = vm,
                                    onBack = { vm.cancelPrint() },
                                )
                            }

                            is Screen.DeviceStatus -> {
                                DeviceStatusScreen(vm = vm)
                            }

                            is Screen.CameraFull -> {
                                CameraFullscreen(
                                    vm = vm,
                                    onClose = { vm.closeCameraFull() },
                                )
                            }
                        }
                    }
                    val downloading = browse.downloadingFile
                    if (downloading != null) {
                        DownloadProgressBanner(
                            name = downloading,
                            progress = browse.downloadProgress,
                            modifier =
                                Modifier
                                    .align(Alignment.TopCenter)
                                    .fillMaxWidth(),
                        )
                    }
                }
            }
            }

            // Full-screen pushed browse overlays belong to Home and sit above Scaffold so the
            // bottom tab bar stays hidden while browsing a source list or viewing a model's
            // detail page. Gating on Screen.Home ensures PrintSetup is revealed immediately once
            // a download finishes instead of sitting hidden behind SourceListOverlay / ModelDetail.
            if (screen == Screen.Home) {
                expanded?.let { section ->
                    SourceListOverlay(
                        section = section,
                        onBack = { expandedSource = null },
                        favoritedListings = favoritedListings,
                        onLoadMore = { browser.loadMore(section.source) },
                        onOpen = { browser.openDetail(it) },
                    )
                }
                ModelDetailHost(
                    vm = vm,
                    browser = browser,
                    onModelDownloaded = { expandedSource = null },
                )
            }
        }

        // Rationale before the Android 13+ notification prompt: explain the value,
        // then ask — a bare system dialog on first launch gets reflexively denied.
        if (showNotifRationale) {
            world.yemyat.slicepilot.ui.components.NotificationRationaleDialog(
                onAllow = {
                    showNotifRationale = false
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                },
                onNotNow = { showNotifRationale = false },
            )
        }

        if (pendingModelPath != null) {
            val busy = slicing || screen == Screen.Printing
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.dismissPendingModel() },
                title = { androidx.compose.material3.Text("Load new model?") },
                text = {
                    androidx.compose.material3.Text(
                        if (busy) {
                            "A slicing or print job is active. Cancel it and load the new model?"
                        } else {
                            "Loading this model will leave the current screen. Continue?"
                        },
                    )
                },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.confirmPendingModel() }) {
                        androidx.compose.material3.Text(if (busy) "Cancel & load" else "Load")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.dismissPendingModel() }) {
                        androidx.compose.material3.Text("Keep current")
                    }
                },
            )
        }

        // Backing out of print setup ends the model's flow, so it asks before throwing the
        // model away (AppViewModel.leavePrintSetup); "Keep editing" leaves the page as it was.
        discardOnExitModel?.let { model ->
            val name = File(model).name
            val body =
                (
                    if (vm.gcodePath != null) {
                        "$name is sliced but not printed — discarding it loses that g-code."
                    } else {
                        "$name is not sliced yet."
                    }
                ) +
                    " You'd have to pick the file again to print it."
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.dismissDiscardPrompt() },
                title = { androidx.compose.material3.Text("Discard this model?") },
                text = { androidx.compose.material3.Text(body) },
                confirmButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.confirmDiscardModel() }) {
                        androidx.compose.material3.Text("Discard")
                    }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.dismissDiscardPrompt() }) {
                        androidx.compose.material3.Text("Keep editing")
                    }
                },
            )
        }

        // Leaving Home (e.g. entering PrintSetup after a download or printer selection) must
        // always dismiss any open connect sheet or browse overlays so PrintSetup is visible
        // immediately without pressing Back.
        LaunchedEffect(screen) {
            EventNotifier.trackScreen(screen.analyticsName)
            if (screen == Screen.PrintSetup) showConnect = false
            if (screen != Screen.Home) {
                expandedSource = null
                browser.closeDetail()
            }
        }

        // A model is loaded but nothing is connected to print it on: ask before the setup
        // page (which needs a printer) and send the answer to the same sheet the tabs use.
        askPrinterForModel?.let { model ->
            world.yemyat.slicepilot.ui.components.ConnectPrinterDialog(
                modelName = File(model).name,
                onConnect = {
                    vm.onConnectForPrint()
                    showConnect = true
                },
                onLater = { vm.onLaterForPrint() },
            )
        }

        if (showConnect) {
            ConnectSheet(
                vm,
                onDismiss = { showConnect = false },
                onAddPrinter = {
                    pairingVia = "pin"
                    showAddPrinter = true
                },
            )
        }
        // Pairing lives above the sheet rather than inside it: the QR scanner needs a full-screen
        // camera surface, and a dialog stacked on a bottom sheet renders badly.
        if (showAddPrinter) {
            AddPrinterDialog(
                account = vm.cloudUser,
                attempt = pairingAttempt,
                machine = pairingPcode,
                pincode = pairingPincode,
                onPincodeChange = { pairingPincode = it },
                onDismiss = {
                    vm.dismissPairing()
                    showAddPrinter = false
                },
                onSubmit = { vm.pairPrinter(pairingPincode, pairingPcode, pairingVia) },
                onScanQr = { showQrScanner = true },
            )
        }
        if (showQrScanner) {
            QrScannerDialog(
                onCode = { raw ->
                    showQrScanner = false
                    // A printer's QR carries the cloud's identifiers — pcode and serialNo, and often
                    // the model — but never the PIN, so a scan fills the machine and leaves the PIN
                    // to be typed. The older QR wrote the printer's id as `pcode`, hence the order.
                    world.yemyat.slicepilot.cloud.PairingCode.fromQr(raw)?.let { scanned ->
                        val machine = scanned.pcode.ifBlank { scanned.serialNo }
                        if (machine.isNotBlank()) pairingPcode = machine
                        if (scanned.pincode.isNotBlank()) pairingPincode = scanned.pincode
                        pairingVia = "qr"
                    }
                },
                onDismiss = { showQrScanner = false },
            )
        }
        // A successful pair selects the printer, which can move the app to Print setup while the
        // Add dialog is still up: close the dialog so what's left on screen is the page the
        // pairing promised, not a form for a printer that is already added.
        LaunchedEffect(pairingAttempt?.printerName) {
            if (pairingAttempt?.printerName != null) showAddPrinter = false
        }

        // Shown from the root so it also appears over the print screen when a print is rejected.
        accessCodePrompt?.let { printer ->
            AccessCodeDialog(
                printerName = printer.name.ifBlank { printer.model.ifBlank { "the printer" } },
                initialCode = vm.storedAccessCode(printer),
                isMoonraker = printer.protocol == world.yemyat.slicepilot.cloud.PrinterProtocol.MOONRAKER,
                onDismiss = { vm.dismissAccessCodePrompt() },
                onSave = { code -> vm.setLanAccessCode(printer, code) },
            )
        }

        LaunchedEffect(cloudMessage, cloudReconnectNeeded) {
            cloudMessage?.let { msg ->
                val result =
                    if (cloudReconnectNeeded) {
                        snackbarHostState.showSnackbar(
                            message = msg,
                            actionLabel = "Sign in",
                            duration = androidx.compose.material3.SnackbarDuration.Long,
                        )
                    } else {
                        snackbarHostState.showSnackbar(msg)
                    }
                if (result == androidx.compose.material3.SnackbarResult.ActionPerformed) {
                    showConnect = true
                }
                vm.consumeCloudMessage()
            }
        }
    }

    private fun sanitizeName(name: String): String {
        val decoded =
            try {
                Uri.decode(name)
            } catch (_: Exception) {
                name
            }
        val base = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        val safe = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return safe.ifBlank { "model" }
    }

    private fun copyToCache(
        context: Context,
        uri: Uri,
    ): File {
        val rawName = queryName(context, uri) ?: uri.lastPathSegment ?: "model"
        val safeName = sanitizeName(rawName)
        val temp = File(context.cacheDir, "temp_${System.currentTimeMillis()}_$safeName")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Cannot open $uri")

        val isZip = ModelParser.isZip(temp)

        val cleanBase = safeName.substringBeforeLast('.').ifBlank { "model" }
        val finalName =
            if (isZip || safeName.endsWith(".3mf", ignoreCase = true)) {
                if (safeName.endsWith(".3mf", ignoreCase = true)) safeName else "$cleanBase.3mf"
            } else {
                if (safeName.endsWith(".stl", ignoreCase = true)) safeName else "$cleanBase.stl"
            }

        val out = File(context.cacheDir, finalName)
        if (out.exists()) out.delete()
        if (!temp.renameTo(out)) {
            temp.copyTo(out, overwrite = true)
            temp.delete()
        }
        return out
    }

    private fun queryName(
        context: Context,
        uri: Uri,
    ): String? {
        var name: String? = null
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) name = c.getString(idx)
        }
        return name
    }

    private fun maybeRunHeadlessSlice() {
        val model = intent.getStringExtra("model") ?: return
        val printer = intent.getStringExtra("printer") ?: return
        val filament = intent.getStringExtra("filament") ?: return
        val process = intent.getStringExtra("process") ?: return
        val out = intent.getStringExtra("out") ?: File(cacheDir, "headless.gcode").absolutePath
        val filamentColor = intent.getStringExtra("filament_color")
        val supportFilament = intent.getStringExtra("support_filament") ?: ""
        val multiColour = intent.getBooleanExtra("multi_colour", false)
        val filamentsExtra = intent.getStringExtra("filaments")
        val filaments: Array<String> =
            filamentsExtra
                ?.split(';')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                ?.toTypedArray()
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull(filament.takeIf { it.isNotBlank() }, supportFilament.takeIf { it.isNotBlank() }).toTypedArray()
        val overrides =
            buildString {
                filamentColor?.let { c ->
                    val colors =
                        c.split(';', '|', ',')
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .joinToString(";") { if (it.startsWith("#")) it else "#$it" }
                    if (colors.isNotEmpty()) {
                        appendLine("filament_colour=$colors")
                        appendLine("filament_multi_colour=$colors")
                    }
                }
                if (multiColour && filaments.size > 1) {
                    appendLine("single_extruder_multi_material=1")
                    appendLine("enable_prime_tower=1")
                }
            }
        android.util.Log.i(
            "SliceCore",
            "Headless slice start: printer=$printer filaments=${filaments.size} multi=$multiColour process=$process",
        )
        val ok =
            NativeLib.nativeSliceWithOverrides(
                model,
                printer,
                filaments,
                process,
                overrides,
                multiColour,
                out,
                object : NativeLib.SliceCallback {
                    override fun onProgress(
                        step: Int,
                        text: String,
                        percent: Int,
                    ) {
                        android.util.Log.i("SliceCore", "Headless progress $percent%: $text")
                    }
                },
            )
        if (!ok) {
            android.util.Log.e("SliceCore", "Headless slice launch failed: ${NativeLib.nativeGetError()}")
            return
        }
        Thread {
            while (NativeLib.nativeIsRunning()) Thread.sleep(200)
            val err = NativeLib.nativeGetError()
            if (err.isNotEmpty()) {
                android.util.Log.e("SliceCore", "Headless slice error: $err")
            } else {
                val f = File(out)
                android.util.Log.i("SliceCore", "Headless slice done: $out (${f.length() / 1024} KB)")
            }
        }.start()
    }
}
