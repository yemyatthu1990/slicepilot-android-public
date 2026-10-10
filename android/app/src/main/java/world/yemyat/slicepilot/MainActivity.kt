package world.yemyat.slicepilot

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import world.yemyat.slicepilot.cloud.ElegooCloudClient
import world.yemyat.slicepilot.tracking.EventNotifier
import world.yemyat.slicepilot.tracking.TrackingBootstrap
import world.yemyat.slicepilot.ui.*
import world.yemyat.slicepilot.ui.components.SpiritedBackground
import world.yemyat.slicepilot.ui.theme.ElegooSlicerTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.lang.ref.WeakReference
import world.yemyat.slicepilot.model.ModelParser

class MainActivity : ComponentActivity() {

    companion object {
        private var activeInstance: WeakReference<MainActivity>? = null
    }

    private lateinit var cloud: ElegooCloudClient
    private lateinit var vm: AppViewModel

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val existing = activeInstance?.get()?.takeIf { !it.isFinishing && it !== this }
        if (existing != null) {
            existing.handleExternalIntent(intent)
            try {
                if (checkSelfPermission(Manifest.permission.REORDER_TASKS) == PackageManager.PERMISSION_GRANTED) {
                    val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                    am.moveTaskToFront(existing.taskId, 0)
                } else {
                    startActivity(
                        android.content.Intent(this, MainActivity::class.java)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    )
                }
            } catch (_: SecurityException) {
                startActivity(
                    android.content.Intent(this, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
            finish()
            return
        }
        activeInstance = WeakReference(this)
        NativeLib.init(this)
        TrackingBootstrap.ensure(this)
        EventNotifier.trackEvent("app_opened")
        requestNotificationPermissionIfNeeded()
        cloud = ElegooCloudClient(this)
        val openPreview = intent?.getBooleanExtra("open_preview", false) == true
        vm = AppViewModel(
            cloud = cloud,
            cacheDir = cacheDir,
            prefs = getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE),
            context = this,
            initialOpenPreviewGcodePath = if (openPreview) intent?.getStringExtra("gcode_path") else null,
            initialOpenPreviewModelPath = if (openPreview) intent?.getStringExtra("model_path") else null,
        )
        handleIncomingModel(intent)
        maybeRunHeadlessSlice()
        setContent {
            ElegooSlicerTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    SpiritedBackground(Modifier.fillMaxSize())
                    Surface(Modifier.fillMaxSize(), color = Color.Transparent) {
                        AppRoot(vm)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_preview", false)) {
            vm.openPreviewFromPaths(intent.getStringExtra("gcode_path"), intent.getStringExtra("model_path"))
        }
        val data = intent.data
        android.util.Log.i("AuthCallback", "onNewIntent scheme=${intent.scheme} data=$data extras=${intent.extras?.keySet()?.joinToString()}")
        if (data != null) {
            EventNotifier.trackEvent(
                "deep_link_received",
                mapOf(
                    "scheme" to data.scheme.orEmpty(),
                    "host" to data.host.orEmpty(),
                    "path" to data.path.orEmpty(),
                ),
            )
        }
        if (data != null && data.scheme != null && data.scheme != "http" && data.scheme != "https" && data.scheme != "content" && data.scheme != "file") {
            val token = listOf("token", "access_token", "accessToken", "code")
                .firstNotNullOfOrNull { data.getQueryParameter(it) }
            android.util.Log.i("AuthCallback", "deep-link token=$token")
            if (!token.isNullOrBlank()) {
                cloud.auth.saveSession(token, intent.getStringExtra("refresh_token"), cloud.auth.nickname, cloud.auth.email)
                vm.checkSession()
                return
            }
        }
        handleIncomingModel(intent)
    }

    override fun onDestroy() {
        if (activeInstance?.get() === this) {
            activeInstance = null
        }
        super.onDestroy()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun handleIncomingModel(intent: android.content.Intent?) {
        if (intent == null) return
        val openPreview = intent.getBooleanExtra("open_preview", false)
        val path = if (openPreview) {
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

        val uri = when (intent.action) {
            android.content.Intent.ACTION_VIEW -> intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            android.content.Intent.ACTION_SEND -> {
                val stream = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(android.content.Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<Uri>(android.content.Intent.EXTRA_STREAM)
                }
                stream ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
            }
            else -> intent.data ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri
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

    fun handleExternalIntent(intent: android.content.Intent?) {
        setIntent(intent)
        if (intent?.getBooleanExtra("open_preview", false) == true) {
            vm.openPreviewFromPaths(intent.getStringExtra("gcode_path"), intent.getStringExtra("model_path"))
        }
        handleIncomingModel(intent)
    }

    @Composable
    private fun AppRoot(vm: AppViewModel) {
        val screen by vm.screen.collectAsState()
        val cloudMessage by vm.cloudMessage.collectAsState()
        val slicing by vm.slicing.collectAsState()
        val pendingModelPath by vm.pendingModelPath.collectAsState()
        androidx.activity.compose.BackHandler(enabled = screen != Screen.Home) { vm.goBack() }
        var showConnect by remember { mutableStateOf(false) }
        var showConfigure by remember { mutableStateOf(false) }
        var fullscreen by remember { mutableStateOf(false) }

        val scope = remember { CoroutineScope(Dispatchers.Main) }

        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, event ->
                if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    EventNotifier.trackEvent("app_resumed")
                    vm.checkSession()
                } else if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) {
                    EventNotifier.trackEvent("app_backgrounded")
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

        val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
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

        val gcodeSaver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/x-gcode")) { uri: Uri? ->
            val path = vm.gcodePath
            if (uri != null && path != null) {
                scope.launch {
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

        androidx.compose.material3.Scaffold(
            snackbarHost = { androidx.compose.material3.SnackbarHost(snackbarHostState) },
        ) { _ ->
            when (screen) {
                is Screen.Home -> HomeScreen(
                    vm = vm,
                    onPickModel = {
                        modelPicker.launch(arrayOf("*/*"))
                    },
                    onFullscreen = { fullscreen = true },
                    onOpenConnect = { showConnect = true },
                    onOpenConfigure = { showConfigure = true },
                )
                is Screen.PrintSetup -> PrintSetupScreen(vm)
                is Screen.Slice -> SliceScreen(vm)
                is Screen.Slicing -> SlicingScreen(vm)
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
                is Screen.Printing -> PrintingScreen(
                    vm = vm,
                    onBack = { vm.cancelPrint() },
                )
            }
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
                        }
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

        if (fullscreen) {
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { fullscreen = false },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                FullscreenViewer(vm, onClose = { fullscreen = false })
            }
        }
        if (showConnect) ConnectSheet(
            vm,
            onDismiss = { showConnect = false },
        )
        if (showConfigure) ConfigureSheet(vm, onDismiss = { showConfigure = false })

        LaunchedEffect(cloudMessage) {
            cloudMessage?.let {
                snackbarHostState.showSnackbar(it)
                vm.consumeCloudMessage()
            }
        }
    }

    private fun sanitizeName(name: String): String {
        val decoded = try { Uri.decode(name) } catch (_: Exception) { name }
        val base = decoded.substringAfterLast('/').substringAfterLast('\\').trim()
        val safe = base.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return safe.ifBlank { "model" }
    }

    private fun copyToCache(context: Context, uri: Uri): File {
        val rawName = queryName(context, uri) ?: uri.lastPathSegment ?: "model"
        val safeName = sanitizeName(rawName)
        val temp = File(context.cacheDir, "temp_${System.currentTimeMillis()}_${safeName}")
        context.contentResolver.openInputStream(uri)?.use { input ->
            temp.outputStream().use { input.copyTo(it) }
        } ?: throw IllegalStateException("Cannot open $uri")

        val isZip = ModelParser.isZip(temp)

        val cleanBase = safeName.substringBeforeLast('.').ifBlank { "model" }
        val finalName = if (isZip || safeName.endsWith(".3mf", ignoreCase = true)) {
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

    private fun queryName(context: Context, uri: Uri): String? {
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
        val overrides = filamentColor?.let { c ->
            val color = if (c.startsWith("#")) c else "#$c"
            "filament_colour=$color\nfilament_multi_colour=$color\n"
        } ?: ""
        android.util.Log.i("SliceCore", "Headless slice start: printer=$printer filament=$filament process=$process")
        val ok = NativeLib.nativeSliceWithOverrides(
            model, printer, filament, process, overrides, out,
            object : NativeLib.SliceCallback {
                override fun onProgress(step: Int, text: String, percent: Int) {
                    android.util.Log.i("SliceCore", "Headless progress $percent%: $text")
                }
            })
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
