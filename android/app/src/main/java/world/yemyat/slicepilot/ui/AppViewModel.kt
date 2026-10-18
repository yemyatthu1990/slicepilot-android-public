package world.yemyat.slicepilot.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import world.yemyat.slicepilot.NativeLib
import world.yemyat.slicepilot.SliceForegroundService
import world.yemyat.slicepilot.SliceTransform
import world.yemyat.slicepilot.cloud.CloudPrinter
import world.yemyat.slicepilot.cloud.CloudResult
import world.yemyat.slicepilot.cloud.ElegooCloudClient
import world.yemyat.slicepilot.cloud.auth.CloudAuthResult
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.GcodePreviewParser
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.model.MeshDecimator
import world.yemyat.slicepilot.model.ModelParser
import world.yemyat.slicepilot.model.ProjectPrintSettings
import world.yemyat.slicepilot.model.ProjectSettingsReader
import world.yemyat.slicepilot.model.StlParser
import world.yemyat.slicepilot.tracking.EventNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private const val DEMO_EMAIL = "demo@demo.com"
private const val DEMO_PASSWORD = "demo"
private const val DEMO_PRINTER_ID = "DEMO-CC2-001"
private const val DEMO_MODEL_ASSET = "demo/test_cube.stl"
private const val DEMO_MODEL_FILE = "test_cube.stl"

data class SliceConfig(
    val printer: String? = null,
    val process: String? = null,
    val filament: String? = null,
    val filamentColor: String? = null,
    val filamentTrayId: Int? = null,
    val filamentCanvasId: Int? = null,
    val supportEnabled: Boolean = false,
    val supportStyle: String = "grid",
    val supportFilament: String? = null,
    val layerHeight: Float? = null,
    val wallLoops: Int? = null,
    val topShellLayers: Int? = null,
    val bottomShellLayers: Int? = null,
    val infillDensity: Int? = null,
    val infillPattern: String? = null,
    val adhesion: String = "auto",
    val brimWidth: Int? = null,
    val seamPosition: String = "aligned",
    val ironing: Boolean = false,
    val supportOnPlateOnly: Boolean = false,
    val supportThresholdAngle: Int? = null,
    val supportZDistance: Float? = null,
    val wallSpeed: Int? = null,
    val infillSpeed: Int? = null,
    val travelSpeed: Int? = null,
)

data class MaterialOption(
    val label: String,
    val profile: String,
    val colorHex: String?,
    val trayId: Int? = null,
    val canvasId: Int? = null,
) {
    val key: String
        get() = if (trayId != null) "$profile|tray:$trayId|canvas:$canvasId"
        else "$profile|" + (colorHex ?: "")
}

data class ConnectedPrinter(
    val id: String,
    val name: String,
    val model: String,
    val online: Boolean = true,
    val isLocal: Boolean = false,
    val ip: String? = null,
    val serial: String? = null,
    val cloudPrinter: CloudPrinter? = null,
    val lanPrinter: world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter? = null,
)

fun CloudPrinter.toConnectedPrinter(): ConnectedPrinter = ConnectedPrinter(
    id = this.id,
    name = this.name,
    model = this.model,
    online = this.online,
    isLocal = false,
    cloudPrinter = this,
)

fun world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter.toConnectedPrinter(): ConnectedPrinter = ConnectedPrinter(
    id = this.serial.ifBlank { this.ip },
    name = this.name.ifBlank { this.model.ifBlank { "Centauri Carbon 2" } },
    model = this.model.ifBlank { "Centauri Carbon 2" },
    online = true,
    isLocal = true,
    ip = this.ip,
    serial = this.serial,
    lanPrinter = this,
)

sealed class Screen {
    data object Home : Screen()
    data object Browse : Screen()
    data object PrintSetup : Screen()
    data object Slice : Screen()
    data object Slicing : Screen()
    data object Preview : Screen()
    data object Printing : Screen()
}

class AppViewModel(
    private val cloud: ElegooCloudClient,
    private val cacheDir: File,
    private val prefs: android.content.SharedPreferences,
    private val context: Context,
    private val initialOpenPreviewGcodePath: String? = null,
    private val initialOpenPreviewModelPath: String? = null,
) : ViewModel() {

    private val _screen = MutableStateFlow<Screen>(Screen.Home)
    val screen: StateFlow<Screen> = _screen.asStateFlow()

    var modelPath: String? = null
        private set
    var gcodePath: String? = null
        private set

    private val _mesh = MutableStateFlow<MeshData?>(null)
    val mesh: StateFlow<MeshData?> = _mesh.asStateFlow()
    private val _gcodePreview = MutableStateFlow<GcodePreview?>(null)
    val gcodePreview: StateFlow<GcodePreview?> = _gcodePreview.asStateFlow()
    private val _gcodeStats = MutableStateFlow<world.yemyat.slicepilot.model.GcodeStats?>(null)
    val gcodeStats: StateFlow<world.yemyat.slicepilot.model.GcodeStats?> = _gcodeStats.asStateFlow()

    private val _modelLoading = MutableStateFlow(false)
    val modelLoading: StateFlow<Boolean> = _modelLoading.asStateFlow()

    /** 0..100 parse progress for the current model load, null before the first update. */
    private val _modelLoadProgress = MutableStateFlow<Int?>(null)
    val modelLoadProgress: StateFlow<Int?> = _modelLoadProgress.asStateFlow()

    data class ModelError(val code: String, val message: String)
    private val _modelError = MutableStateFlow<ModelError?>(null)
    val modelError: StateFlow<ModelError?> = _modelError.asStateFlow()

    fun reportModelLoadError(stage: String, t: Throwable) {
        val oom = t is OutOfMemoryError
        val code = when (stage) {
            "parse" -> if (oom) "E101" else "E100"
            "render" -> if (oom) "E201" else "E200"
            else -> "E999"
        }
        val msg = t.javaClass.simpleName + (t.message?.let { ": $it" } ?: "")
        EventNotifier.trackError(
            "model_load_failed",
            t,
            mapOf(
                "stage" to stage,
                "code" to code,
                "oom" to oom,
                "model" to modelPath.orEmpty(),
            ),
        )
        _modelError.value = ModelError(code, msg)
        _mesh.value = null
        _gcodePreview.value = null
        modelPath = null
        gcodePath = null
    }

    fun dismissModelError() { _modelError.value = null }

    fun reportUnsupportedFormat(name: String) {
        EventNotifier.trackEvent(
            "model_rejected",
            mapOf(
                "name" to name,
                "code" to "E102",
                "reason" to "unsupported_format",
            ),
        )
        _modelError.value = ModelError("E102", "Unsupported file format: $name")
        _mesh.value = null
        _gcodePreview.value = null
        modelPath = null
        gcodePath = null
    }

    private val _sliceProgress = MutableStateFlow(0f)
    val sliceProgress: StateFlow<Float> = _sliceProgress.asStateFlow()
    private val _sliceStatus = MutableStateFlow("")
    val sliceStatus: StateFlow<String> = _sliceStatus.asStateFlow()
    private val _slicing = MutableStateFlow(false)
    val slicing: StateFlow<Boolean> = _slicing.asStateFlow()

    @Volatile private var sliceGeneration = 0
    @Volatile private var handledSliceJobId = 0L
    @Volatile private var handledSliceErrorJobId = 0L

    init {
        observeSliceService()
    }

    private val _liveSliceLayers = MutableStateFlow<List<world.yemyat.slicepilot.model.GcodeLayer>>(emptyList())
    val liveSliceLayers: StateFlow<List<world.yemyat.slicepilot.model.GcodeLayer>> = _liveSliceLayers.asStateFlow()
    private val _liveSliceBounds = MutableStateFlow(floatArrayOf(0f, 0f, 1f, 1f, 0f))
    val liveSliceBounds: StateFlow<FloatArray> = _liveSliceBounds.asStateFlow()

    data class ModelTransform(
        val rotX: Float = 0f,
        val rotY: Float = 0f,
        val rotZ: Float = 0f,
        val mirrorX: Boolean = false,
        val mirrorY: Boolean = false,
        val mirrorZ: Boolean = false,
        val offsetX: Float = 0f,
        val offsetY: Float = 0f,
    )

    private val _modelTransform = MutableStateFlow(ModelTransform())
    val modelTransform: StateFlow<ModelTransform> = _modelTransform.asStateFlow()

    fun setModelTransform(t: FloatArray) {
        if (t.size < 8) return
        _modelTransform.value = ModelTransform(
            rotX = t[0], rotY = t[1], rotZ = t[2],
            mirrorX = t[3] != 0f, mirrorY = t[4] != 0f, mirrorZ = t[5] != 0f,
            offsetX = t[6], offsetY = t[7],
        )
    }

    fun resetModelTransform() {
        _modelTransform.value = ModelTransform()
    }

    private val _config = MutableStateFlow(
        SliceConfig(
            printer = prefs.getString("cfg_printer", null),
            process = prefs.getString("cfg_process", null),
            filament = prefs.getString("cfg_filament", null),
            filamentColor = prefs.getString("cfg_filament_color", null),
            filamentTrayId = if (prefs.contains("cfg_filament_tray")) prefs.getInt("cfg_filament_tray", 0) else null,
            filamentCanvasId = if (prefs.contains("cfg_filament_canvas")) prefs.getInt("cfg_filament_canvas", 0) else null,
            supportEnabled = prefs.getBoolean("cfg_support", false),
            supportStyle = prefs.getString("cfg_support_style", "grid") ?: "grid",
            supportFilament = prefs.getString("cfg_support_filament", null),
            layerHeight = if (prefs.contains("cfg_layer_height")) prefs.getFloat("cfg_layer_height", 0.2f) else null,
            wallLoops = if (prefs.contains("cfg_wall_loops")) prefs.getInt("cfg_wall_loops", 2) else null,
            topShellLayers = if (prefs.contains("cfg_top_shells")) prefs.getInt("cfg_top_shells", 4) else null,
            bottomShellLayers = if (prefs.contains("cfg_bottom_shells")) prefs.getInt("cfg_bottom_shells", 4) else null,
            infillDensity = if (prefs.contains("cfg_infill_density")) prefs.getInt("cfg_infill_density", 15) else null,
            infillPattern = prefs.getString("cfg_infill_pattern", null),
            adhesion = prefs.getString("cfg_adhesion", "auto") ?: "auto",
            brimWidth = if (prefs.contains("cfg_brim_width")) prefs.getInt("cfg_brim_width", 5) else null,
            seamPosition = prefs.getString("cfg_seam_position", "aligned") ?: "aligned",
            ironing = prefs.getBoolean("cfg_ironing", false),
            supportOnPlateOnly = prefs.getBoolean("cfg_support_plate_only", false),
            supportThresholdAngle = if (prefs.contains("cfg_support_angle")) prefs.getInt("cfg_support_angle", 30) else null,
            supportZDistance = if (prefs.contains("cfg_support_z_distance")) prefs.getFloat("cfg_support_z_distance", 0.2f) else null,
            wallSpeed = if (prefs.contains("cfg_wall_speed")) prefs.getInt("cfg_wall_speed", 120) else null,
            infillSpeed = if (prefs.contains("cfg_infill_speed")) prefs.getInt("cfg_infill_speed", 150) else null,
            travelSpeed = if (prefs.contains("cfg_travel_speed")) prefs.getInt("cfg_travel_speed", 300) else null,
        )
    )
    val config: StateFlow<SliceConfig> = _config.asStateFlow()

    private val _printers = MutableStateFlow<List<CloudPrinter>>(emptyList())
    val printers: StateFlow<List<CloudPrinter>> = _printers.asStateFlow()
    private val _cloudMessage = MutableStateFlow<String?>(null)
    val cloudMessage: StateFlow<String?> = _cloudMessage.asStateFlow()
    private val _pendingModelPath = MutableStateFlow<String?>(null)
    val pendingModelPath: StateFlow<String?> = _pendingModelPath.asStateFlow()
    private val _demoMode = MutableStateFlow(prefs.getBoolean("demo_mode", false))
    val demoModeState: StateFlow<Boolean> = _demoMode.asStateFlow()
    val isDemoMode: Boolean get() = _demoMode.value

    data class PrintSimStep(val label: String, val targetProgress: Float, val durationMs: Long)
    data class PrintSimState(
        val stepIndex: Int = 0,
        val progress: Float = 0f,
        val message: String = "Preparing…",
        val complete: Boolean = false,
        val failed: Boolean = false,
    )

    private val _printSim = MutableStateFlow(PrintSimState())
    val printSim: StateFlow<PrintSimState> = _printSim.asStateFlow()
    private var printSimJob: Job? = null

    val printSimSteps = listOf(
        PrintSimStep("Preparing G-code", 0.16f, 700L),
        PrintSimStep("Uploading to printer", 0.58f, 1500L),
        PrintSimStep("Sending print job", 0.84f, 900L),
        PrintSimStep("Starting print", 1f, 800L),
    )

    private fun currentPrintSlot(): world.yemyat.slicepilot.cloud.PrintSlot? {
        val cfg = _config.value
        val tray = cfg.filamentTrayId ?: return null
        return world.yemyat.slicepilot.cloud.PrintSlot(
            t = 0,
            canvasId = cfg.filamentCanvasId ?: 0,
            trayId = tray,
        )
    }

    fun startPrint(context: android.content.Context) {
        _screen.value = Screen.Printing
        _printSim.value = PrintSimState(message = "Preparing print…")
        printSimJob?.cancel()
        printSimJob = viewModelScope.launch {
            val gp = gcodePath
            if (gp == null) {
                failPrint("No sliced file to print")
                return@launch
            }
            val sp = selectedPrinter
            if (sp == null) {
                failPrint("No printer selected")
                return@launch
            }
            val slot = currentPrintSlot()

            if (_demoMode.value) {
                simulateDemoPrint(sp)
                return@launch
            }

            val ticker = launch {
                while (true) {
                    delay(120L)
                    _printSim.update {
                        if (it.complete || it.failed) it
                        else it.copy(progress = (it.progress + 0.012f).coerceAtMost(0.92f))
                    }
                }
            }

            val result = try {
                if (sp.isLocal) {
                    val lanTarget = sp.lanPrinter ?: _selectedLanPrinter.value
                    var local = lanPrint.print(context.applicationContext, File(gp), lanTarget, slot) { msg -> applyPrintStatus(msg) }
                    if (local is CloudResult.Err && isConnectionFailure(local.message)) {
                        if (cloudLoggedIn) {
                            applyPrintStatus("Local upload failed — trying cloud…")
                            val cloudMatch = when (val lr = cloud.listPrinters()) {
                                is CloudResult.Ok -> lr.value.firstOrNull {
                                    it.id == sp.serial || it.id == sp.ip || it.name == sp.name
                                }
                                is CloudResult.Err -> null
                            }
                            if (cloudMatch != null) {
                                local = cloudPrint.print(cloudMatch, File(gp), slot) { msg -> applyPrintStatus(msg) }
                            }
                        } else if (sp.lanPrinter?.lanMode == false) {
                            local = CloudResult.Err(
                                "Printer is in cloud (WAN) mode. Enable LAN-only mode on the printer or sign in to cloud.",
                            )
                        }
                    }
                    local
                } else {
                    val cp = sp.cloudPrinter ?: _printers.value.firstOrNull { it.id == sp.id }
                    if (cp == null || !cloudLoggedIn) {
                        ticker.cancel()
                        failPrint("Sign in to cloud to print on ${sp.name}")
                        return@launch
                    }
                    if (!cp.online) {
                        ticker.cancel()
                        EventNotifier.trackEvent(
                            "print_skipped_printer_offline",
                            mapOf("printer" to sp.name),
                        )
                        _printSim.update {
                            it.copy(
                                message = "${sp.name} is offline. Wake it up and try again.",
                                failed = true,
                                complete = false,
                            )
                        }
                        return@launch
                    }
                    cloudPrint.print(cp, File(gp), slot) { msg -> applyPrintStatus(msg) }
                }
            } catch (e: Exception) {
                EventNotifier.trackError(
                    "print_exception",
                    e,
                    mapOf(
                        "printer" to sp.name,
                        "local" to sp.isLocal,
                        "gcode" to gp,
                    ),
                )
                CloudResult.Err(e.message ?: "Print failed")
            }

            ticker.cancel()
            when (result) {
                is CloudResult.Ok -> {
                    EventNotifier.trackEvent(
                        "print_started",
                        mapOf(
                            "printer" to sp.name,
                            "local" to sp.isLocal,
                            "gcode" to gp,
                        ),
                    )
                    _printSim.update {
                        it.copy(
                            progress = 1f,
                            message = "Print started on ${sp.name}",
                            complete = true,
                            failed = false,
                        )
                    }
                }
                is CloudResult.Err -> failPrint(result.message)
            }
        }
    }

    private fun applyPrintStatus(msg: String) {
        val lower = msg.lowercase()
        val step = when {
            lower.contains("search") || lower.contains("prepare") -> 0
            lower.contains("upload") -> 1
            lower.contains("send") || lower.contains("download") -> 2
            lower.contains("start") -> 3
            else -> _printSim.value.stepIndex
        }
        val target = when (step) {
            0 -> 0.18f
            1 -> 0.55f
            2 -> 0.78f
            3 -> 0.92f
            else -> _printSim.value.progress
        }
        if (step > _printSim.value.stepIndex) {
            EventNotifier.trackEvent(
                "print_status_changed",
                mapOf(
                    "step" to step,
                    "message" to msg,
                    "printer" to selectedPrinter?.name.orEmpty(),
                ),
            )
        }
        _printSim.update {
            it.copy(
                stepIndex = it.stepIndex.coerceAtLeast(step),
                message = msg,
                progress = it.progress.coerceAtLeast(target),
                failed = false,
            )
        }
    }

    private suspend fun simulateDemoPrint(sp: ConnectedPrinter) {
        var progress = 0f
        printSimSteps.forEachIndexed { index, step ->
            _printSim.value = PrintSimState(
                stepIndex = index,
                progress = progress,
                message = "Demo: ${step.label}",
            )
            delay(step.durationMs)
            progress = step.targetProgress
        }
        _printSim.value = PrintSimState(
            stepIndex = printSimSteps.lastIndex,
            progress = 1f,
            message = "Demo print started on ${sp.name} — no real printer was used",
            complete = true,
            failed = false,
        )
        EventNotifier.trackEvent(
            "demo_print_started",
            mapOf(
                "printer" to sp.name,
                "gcode" to gcodePath.orEmpty(),
            ),
        )
    }

    private fun failPrint(msg: String) {
        EventNotifier.trackError(
            "print_failed",
            null,
            mapOf(
                "message" to msg,
                "printer" to selectedPrinter?.name.orEmpty(),
                "local" to (selectedPrinter?.isLocal ?: false),
            ),
        )
        _printSim.update { it.copy(message = msg, failed = true, complete = false) }
    }

    private fun isConnectionFailure(message: String): Boolean {
        val m = message.lowercase()
        return m.contains("failed to connect") ||
            m.contains("connection refused") ||
            m.contains("connection timed out") ||
            m.contains("econnrefused") ||
            m.contains("etimedout") ||
            m.contains("unable to resolve host")
    }

    fun cancelPrint() {
        EventNotifier.trackEvent(
            "print_cancelled",
            mapOf("printer" to selectedPrinter?.name.orEmpty()),
        )
        printSimJob?.cancel()
        printSimJob = null
        _printSim.value = PrintSimState()
        _screen.value = Screen.Preview
    }

    fun finishPrintToHome() {
        EventNotifier.trackEvent(
            "print_finished",
            mapOf("printer" to selectedPrinter?.name.orEmpty()),
        )
        printSimJob?.cancel()
        printSimJob = null
        _printSim.value = PrintSimState()
        _modelError.value = null
        _cloudMessage.value = null
        _pendingModelPath.value = null
        clearModel()
        _screen.value = Screen.Home
    }

    private val mqtt = world.yemyat.slicepilot.cloud.MqttManager()
    private val _filaments = MutableStateFlow<Map<String, world.yemyat.slicepilot.cloud.FilamentInfo>>(emptyMap())
    val filaments: StateFlow<Map<String, world.yemyat.slicepilot.cloud.FilamentInfo>> = _filaments.asStateFlow()

    fun filamentFor(deviceCode: String): world.yemyat.slicepilot.cloud.FilamentInfo? {
        _filaments.value[deviceCode]?.let { return it }
        val p = selectedPrinter
        if (p != null && (p.id == deviceCode || p.serial == deviceCode || p.ip == deviceCode)) {
            p.serial?.let { _filaments.value[it]?.let { f -> return f } }
            p.ip?.let { _filaments.value[it]?.let { f -> return f } }
            _filaments.value[p.id]?.let { return it }
        }
        return null
    }

    fun applyFilament(serial: String?, ip: String?, info: world.yemyat.slicepilot.cloud.FilamentInfo) {
        val map = mutableMapOf<String, world.yemyat.slicepilot.cloud.FilamentInfo>()
        if (!serial.isNullOrBlank()) map[serial] = info
        if (!ip.isNullOrBlank()) map[ip] = info
        val sel = _selectedPrinter.value
        if (sel != null && (sel.serial == serial || sel.ip == ip)) {
            map[sel.id] = info
        }
        _filaments.value = _filaments.value + map

        val e = prefs.edit()
        val jsonStr = filamentToJson(info)
        if (!serial.isNullOrBlank()) e.putString("filament_$serial", jsonStr)
        if (!ip.isNullOrBlank()) e.putString("filament_$ip", jsonStr)
        e.apply()
        EventNotifier.trackEvent(
            "filament_synced",
            mapOf(
                "serial" to serial.orEmpty(),
                "ip" to ip.orEmpty(),
                "present" to info.present,
                "name" to info.name,
                "tray_count" to info.trays.size,
            ),
        )

        // Sync setup material if needed
        val opts = printSetupMaterials()
        if (_setupMaterial.value == null || opts.none { it.key == _setupMaterial.value }) {
            _setupMaterial.value = opts.firstOrNull()?.key
        }
    }

    fun refreshLanFilament(printer: world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter? = _selectedLanPrinter.value) {
        val p = printer ?: return
        viewModelScope.launch {
            var info: world.yemyat.slicepilot.cloud.FilamentInfo? = lanPrint.getFilament(p.ip, p.serial)
            if ((info == null || (!info.present && info.trays.isEmpty())) && cloud.auth.isLoggedIn && p.serial.isNotBlank()) {
                info = cloud.getFilament(p.serial)
            }
            if (info != null && (info.present || info.trays.isNotEmpty())) {
                applyFilament(p.serial, p.ip, info)
            }
        }
    }

    fun setLanPrinterFilament(material: String, colorHex: String?) {
        val p = _selectedLanPrinter.value ?: return
        val normColor = colorHex?.let { if (it.startsWith("#")) it else "#$it" }
        val info = world.yemyat.slicepilot.cloud.FilamentInfo(
            present = true,
            name = material,
            colorHex = normColor,
            trays = listOf(world.yemyat.slicepilot.cloud.LoadedTray(0, material, material, normColor)),
        )
        EventNotifier.trackEvent(
            "filament_selected",
            mapOf(
                "source" to "lan",
                "material" to material,
                "color" to normColor.orEmpty(),
            ),
        )
        applyFilament(p.serial, p.ip, info)
    }

    private fun filamentToJson(f: world.yemyat.slicepilot.cloud.FilamentInfo): String {
        val root = org.json.JSONObject()
        root.put("present", f.present)
        root.put("name", f.name)
        root.put("colorHex", f.colorHex ?: "")
        f.remaining?.let { root.put("remaining", it) }
        val arr = org.json.JSONArray()
        for (tray in f.trays) {
            val t = org.json.JSONObject()
            t.put("trayId", tray.trayId)
            t.put("canvasId", tray.canvasId)
            t.put("name", tray.name)
            t.put("type", tray.type)
            t.put("colorHex", tray.colorHex ?: "")
            arr.put(t)
        }
        root.put("trays", arr)
        return root.toString()
    }

    private fun parseFilamentJson(jsonStr: String): world.yemyat.slicepilot.cloud.FilamentInfo? {
        return try {
            val root = org.json.JSONObject(jsonStr)
            val present = root.optBoolean("present", true)
            val name = root.optString("name", "")
            val colorHex = root.optString("colorHex", "").ifBlank { null }
            val remaining = if (root.has("remaining")) root.optInt("remaining") else null
            val trays = mutableListOf<world.yemyat.slicepilot.cloud.LoadedTray>()
            val arr = root.optJSONArray("trays")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val t = arr.getJSONObject(i)
                    trays.add(
                        world.yemyat.slicepilot.cloud.LoadedTray(
                            trayId = t.optInt("trayId", i),
                            name = t.optString("name", ""),
                            type = t.optString("type", ""),
                            colorHex = t.optString("colorHex", "").ifBlank { null },
                            canvasId = t.optInt("canvasId", 0),
                        )
                    )
                }
            }
            world.yemyat.slicepilot.cloud.FilamentInfo(
                present = present,
                name = name,
                colorHex = colorHex,
                remaining = remaining,
                trays = trays,
            )
        } catch (_: Exception) {
            null
        }
    }

    private val _selectedPrinterId = MutableStateFlow<String?>(prefs.getString("cloud_printer_id", null))
    val selectedPrinterId: StateFlow<String?> = _selectedPrinterId.asStateFlow()
    private val _selectedNozzle = MutableStateFlow<String>(prefs.getString("nozzle_size", "0.4") ?: "0.4")
    val selectedNozzle: StateFlow<String> = _selectedNozzle.asStateFlow()

    private val _selectedPrinter = MutableStateFlow<ConnectedPrinter?>(null)
    val selectedPrinterState: StateFlow<ConnectedPrinter?> = _selectedPrinter.asStateFlow()
    val selectedPrinter: ConnectedPrinter? get() = _selectedPrinter.value

    private val _availablePrinters = MutableStateFlow<List<ConnectedPrinter>>(emptyList())
    val availablePrinters: StateFlow<List<ConnectedPrinter>> = _availablePrinters.asStateFlow()

    private val cloudPrint = world.yemyat.slicepilot.cloud.CloudPrintManager(cloud)
    private val lanPrint = world.yemyat.slicepilot.cloud.LanPrintManager()

    private val _lanScanning = MutableStateFlow(false)
    val lanScanning: StateFlow<Boolean> = _lanScanning.asStateFlow()
    private val _lanPrinters = MutableStateFlow<List<world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter>>(emptyList())
    val lanPrinters: StateFlow<List<world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter>> = _lanPrinters.asStateFlow()
    private val _selectedLanPrinter = MutableStateFlow<world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter?>(null)
    val selectedLanPrinter: StateFlow<world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter?> = _selectedLanPrinter.asStateFlow()

    init {
        val restoredPreview = if (!initialOpenPreviewGcodePath.isNullOrBlank() || !initialOpenPreviewModelPath.isNullOrBlank()) {
            openPreviewFromPaths(initialOpenPreviewGcodePath, initialOpenPreviewModelPath)
        } else {
            false
        }
        if (!restoredPreview && _demoMode.value) {
            refreshPrinters()
            ensureDemoModel()
        }
    }

    fun nozzleSizesFor(model: String): List<String> {
        if (model.isBlank()) return listOf("0.4")
        val local = parseJsonStringArray(NativeLib.nativeGetPrinters())
        val norm = model.replace("_", " ").replace("-", " ")
        val keywords = norm.split(" ").filter { it.isNotBlank() }
        var matching = local.filter { p ->
            p.contains(norm, ignoreCase = true) || (keywords.isNotEmpty() && keywords.all { kw -> p.contains(kw, ignoreCase = true) })
        }
        if (matching.isEmpty() && (norm.contains("CC2", true) || (norm.contains("Centauri", true) && norm.contains("2", true)))) {
            matching = local.filter { it.contains("Centauri Carbon 2", true) }
        }
        val sizes = matching
            .mapNotNull { Regex("(\\d\\.\\d)\\s+nozzle", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            .distinct()
            .sorted()
        return sizes.ifEmpty { listOf("0.4") }
    }

    fun selectCloudPrinter(p: CloudPrinter) {
        _selectedPrinterId.value = p.id
        _selectedPrinter.value = p.toConnectedPrinter()
        prefs.edit()
            .putString("selected_printer_type", "cloud")
            .putString("cloud_printer_id", p.id)
            .apply()
        recomputeAvailablePrinters()
        syncPrinterProfileToConfig()
        EventNotifier.trackEvent(
            "printer_selected",
            mapOf(
                "type" to "cloud",
                "printer" to p.name,
                "model" to p.model,
                "online" to p.online,
            ),
        )
        EventNotifier.trackEvent(
            "cloud_printer_selected",
            mapOf(
                "printer" to p.name,
                "model" to p.model,
                "online" to p.online,
            ),
        )
    }

    fun selectNozzle(size: String) {
        _selectedNozzle.value = size
        prefs.edit().putString("nozzle_size", size).apply()
        syncPrinterProfileToConfig()
        EventNotifier.trackEvent("nozzle_selected", mapOf("size" to size))
    }

    private fun syncPrinterProfileToConfig() {
        val p = selectedPrinter ?: return
        val local = findLocalProfile(p.model, _selectedNozzle.value) ?: return
        if (_config.value.printer != local) {
            updateConfig(_config.value.copy(printer = local))
        }
    }

    private fun findLocalProfile(model: String, nozzle: String): String? {
        if (model.isBlank()) return null
        val local = parseJsonStringArray(NativeLib.nativeGetPrinters())
        if (local.isEmpty()) return null

        val norm = model.replace("_", " ").replace("-", " ")
        val keywords = norm.split(" ").filter { it.isNotBlank() }

        // 1. Exact match with nozzle
        local.firstOrNull { it.contains(norm, true) && it.contains("$nozzle nozzle", true) }?.let { return it }

        // 2. All keywords match with nozzle
        if (keywords.isNotEmpty()) {
            local.firstOrNull { p -> keywords.all { kw -> p.contains(kw, true) } && p.contains("$nozzle nozzle", true) }?.let { return it }
        }

        // 3. Fallback for CC2 / Centauri Carbon 2 aliases
        if (norm.contains("CC2", true) || (norm.contains("Centauri", true) && norm.contains("2", true))) {
            local.firstOrNull { it.contains("Centauri Carbon 2", true) && it.contains("$nozzle nozzle", true) }?.let { return it }
        }
        if (norm.contains("CC", true) || norm.contains("Centauri Carbon", true)) {
            local.firstOrNull { it.contains("Centauri Carbon", true) && it.contains("$nozzle nozzle", true) }?.let { return it }
        }

        // 4. Any match containing norm or keywords
        return local.firstOrNull { it.contains(norm, true) }
            ?: local.firstOrNull { p -> keywords.all { kw -> p.contains(kw, true) } }
            ?: local.firstOrNull { it.contains("Centauri Carbon 2", true) }
            ?: local.firstOrNull()
    }

    // ---- Print setup ----
    private val _setupMaterial = MutableStateFlow<String?>(null)
    val setupMaterial: StateFlow<String?> = _setupMaterial.asStateFlow()
    private val _setupQuality = MutableStateFlow(prefs.getString("setup_quality", "Standard") ?: "Standard")
    val setupQuality: StateFlow<String> = _setupQuality.asStateFlow()
    private val _setupPlate = MutableStateFlow(prefs.getString("setup_plate", "Textured") ?: "Textured")
    val setupPlate: StateFlow<String> = _setupPlate.asStateFlow()

    fun friendlyMaterial(profile: String): String =
        profile.removePrefix("Elegoo ").substringBefore(" @").ifBlank { profile }

    private fun matchProfileForType(compatible: List<String>, type: String): String? {
        if (type.isBlank()) return null
        compatible.firstOrNull { friendlyMaterial(it).equals(type, true) }?.let { return it }
        compatible.firstOrNull { friendlyMaterial(it).contains(type, true) }?.let { return it }
        return null
    }

    fun printSetupMaterials(): List<MaterialOption> {
        val compatible = parseJsonStringArray(NativeLib.nativeGetCompatibleFilaments())
        val p = selectedPrinter
        val filament = p?.let {
            filamentFor(it.id)
                ?: it.serial?.let { s -> filamentFor(s) }
                ?: it.ip?.let { ip -> filamentFor(ip) }
        }
        val realTrays = filament?.trays.orEmpty()
        if (realTrays.isNotEmpty()) {
            val opts = realTrays.sortedBy { it.trayId }.mapNotNull { tray ->
                val profile = matchProfileForType(compatible, tray.type) ?: return@mapNotNull null
                val label = tray.type.ifBlank { friendlyMaterial(profile) }
                MaterialOption(label, profile, tray.colorHex, tray.trayId, tray.canvasId)
            }.distinctBy { it.key }
            if (opts.isNotEmpty()) return opts
        }
        val presentSingle = filament?.takeIf { it.present && (it.name.isNotBlank() || it.colorHex != null) }
        if (presentSingle != null) {
            val name = presentSingle.name.ifBlank { "Filament" }
            val profile = matchProfileForType(compatible, name) ?: compatible.firstOrNull()
            if (profile != null) {
                return listOf(MaterialOption(name, profile, presentSingle.colorHex))
            }
        }
        return compatible.map { MaterialOption(friendlyMaterial(it), it, null) }
    }

    fun printSetupProcess(tier: String): String? {
        val procs = parseJsonStringArray(NativeLib.nativeGetCompatibleProcesses())
        val word = when (tier) {
            "Draft" -> "Draft"
            "Quality" -> "Optimal"
            else -> "Standard"
        }
        return procs.firstOrNull { it.contains(word, true) } ?: procs.firstOrNull()
    }

    fun bedTypeForPlate(plate: String): String = when (plate) {
        "Smooth" -> "High Temp Plate"
        else -> "Textured PEI Plate"
    }

    fun plateColorHex(plate: String): String = when (plate) {
        "Smooth" -> "#8C93A0"
        else -> "#2A2E36"
    }

    fun setSetupMaterial(key: String) {
        _setupMaterial.value = key
        EventNotifier.trackEvent("material_selected", mapOf("key" to key))
    }

    fun setSetupQuality(tier: String) {
        _setupQuality.value = tier
        prefs.edit().putString("setup_quality", tier).apply()
    }

    fun setSetupPlate(plate: String) {
        _setupPlate.value = plate
        prefs.edit().putString("setup_plate", plate).apply()
        EventNotifier.trackEvent("bed_type_selected", mapOf("plate" to plate))
    }

    fun openBrowse() {
        _screen.value = Screen.Browse
    }

    fun openPrintSetup() {        val printer = _config.value.printer ?: return
        NativeLib.nativeSelectPrinter(printer)
        val opts = printSetupMaterials()
        if (_setupMaterial.value == null || opts.none { it.key == _setupMaterial.value }) {
            _setupMaterial.value = opts.firstOrNull()?.key
        }
        _screen.value = Screen.PrintSetup
    }

    fun openSlice() {
        val printer = _config.value.printer ?: return
        NativeLib.nativeSelectPrinter(printer)
        val opts = printSetupMaterials()
        val selected = opts.firstOrNull { it.key == _setupMaterial.value } ?: opts.firstOrNull() ?: return
        _setupMaterial.value = selected.key
        val process = printSetupProcess(_setupQuality.value) ?: return
        updateConfig(
            _config.value.copy(
                filament = selected.profile,
                filamentColor = selected.colorHex,
                filamentTrayId = selected.trayId,
                filamentCanvasId = selected.canvasId,
                process = process,
            )
        )
        _screen.value = Screen.Slice
    }

    fun setSupportEnabled(v: Boolean) { updateConfig(_config.value.copy(supportEnabled = v)) }
    fun setSupportStyle(v: String) { updateConfig(_config.value.copy(supportStyle = v)) }
    fun setSupportFilament(v: String?) { updateConfig(_config.value.copy(supportFilament = v)) }
    fun setLayerHeight(v: Float?) { updateConfig(_config.value.copy(layerHeight = v)) }
    fun setWallLoops(v: Int?) { updateConfig(_config.value.copy(wallLoops = v)) }
    fun setTopShellLayers(v: Int?) { updateConfig(_config.value.copy(topShellLayers = v)) }
    fun setBottomShellLayers(v: Int?) { updateConfig(_config.value.copy(bottomShellLayers = v)) }
    fun setInfillDensity(v: Int?) { updateConfig(_config.value.copy(infillDensity = v)) }
    fun setInfillPattern(v: String?) { updateConfig(_config.value.copy(infillPattern = v)) }
    fun setAdhesion(v: String) { updateConfig(_config.value.copy(adhesion = v)) }
    fun setBrimWidth(v: Int?) { updateConfig(_config.value.copy(brimWidth = v)) }
    fun setSeamPosition(v: String) { updateConfig(_config.value.copy(seamPosition = v)) }
    fun setIroning(v: Boolean) { updateConfig(_config.value.copy(ironing = v)) }
    fun setSupportOnPlateOnly(v: Boolean) { updateConfig(_config.value.copy(supportOnPlateOnly = v)) }
    fun setSupportThresholdAngle(v: Int?) { updateConfig(_config.value.copy(supportThresholdAngle = v)) }
    fun setSupportZDistance(v: Float?) { updateConfig(_config.value.copy(supportZDistance = v)) }
    fun setWallSpeed(v: Int?) { updateConfig(_config.value.copy(wallSpeed = v)) }
    fun setInfillSpeed(v: Int?) { updateConfig(_config.value.copy(infillSpeed = v)) }
    fun setTravelSpeed(v: Int?) { updateConfig(_config.value.copy(travelSpeed = v)) }

    fun printSetupSliceAndPrint() {
        val opts = printSetupMaterials()
        val selected = opts.firstOrNull { it.key == _setupMaterial.value } ?: opts.firstOrNull() ?: return
        _setupMaterial.value = selected.key
        val process = printSetupProcess(_setupQuality.value) ?: return
        updateConfig(
            _config.value.copy(
                filament = selected.profile,
                filamentColor = selected.colorHex,
                filamentTrayId = selected.trayId,
                filamentCanvasId = selected.canvasId,
                process = process,
            )
        )
        startSlice(bedType = bedTypeForPlate(_setupPlate.value), autoPrint = true)
    }

    val cloudUser get() = if (_demoMode.value) "Demo user" else cloud.auth.nickname
    val cloudLoggedIn get() = cloud.auth.isLoggedIn || _demoMode.value
    val loginPageUrl get() = if (_demoMode.value) "Demo mode" else cloud.auth.loginPageUrl
    var cloudRegion: String
        get() = cloud.auth.region
        set(v) { cloud.auth.region = v }

    val savedCloudEmail: String
        get() = if (_demoMode.value) DEMO_EMAIL else prefs.getString("cloud_email", cloud.auth.email).orEmpty()
    val savedCloudPassword: String
        get() = if (prefs.getBoolean("cloud_remember_password", false)) {
            prefs.getString("cloud_password", "").orEmpty()
        } else {
            ""
        }
    val rememberCloudPassword: Boolean
        get() = prefs.getBoolean("cloud_remember_password", false)

    fun saveCloudCredentials(email: String, password: String, remember: Boolean) {
        prefs.edit()
            .putString("cloud_email", email)
            .putBoolean("cloud_remember_password", remember)
            .apply()
        if (remember) {
            prefs.edit().putString("cloud_password", password).apply()
        } else {
            prefs.edit().remove("cloud_password").apply()
        }
    }

    init {
        val printerType = prefs.getString("selected_printer_type", null)
        if (printerType == "lan") {
            val serial = prefs.getString("lan_printer_serial", "") ?: ""
            val ip = prefs.getString("lan_printer_ip", "") ?: ""
            val name = prefs.getString("lan_printer_name", "") ?: ""
            val model = prefs.getString("lan_printer_model", "") ?: ""
            val lanMode = prefs.getBoolean("lan_printer_lan_mode", true)
            if ((ip.isNotBlank() || serial.isNotBlank()) && lanMode) {
                val lp = world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter(
                    ip = ip,
                    serial = serial,
                    model = model.ifBlank { "Centauri Carbon 2" },
                    name = name.ifBlank { model.ifBlank { "Centauri Carbon 2" } },
                    lanMode = true,
                )
                _selectedLanPrinter.value = lp
                _selectedPrinter.value = lp.toConnectedPrinter()
                syncPrinterProfileToConfig()

                // Restore cached filament or default
                val cached = serial.takeIf { it.isNotBlank() }?.let { prefs.getString("filament_$it", null) }
                    ?: ip.takeIf { it.isNotBlank() }?.let { prefs.getString("filament_$it", null) }
                val info = cached?.let { parseFilamentJson(it) }
                    ?: world.yemyat.slicepilot.cloud.FilamentInfo(present = false)
                applyFilament(serial, ip, info)
                refreshLanFilament(lp)
            } else if (!lanMode) {
                prefs.edit()
                    .remove("selected_printer_type")
                    .remove("lan_printer_serial")
                    .remove("lan_printer_ip")
                    .remove("lan_printer_name")
                    .remove("lan_printer_model")
                    .remove("lan_printer_lan_mode")
                    .apply()
            }
        }
        recomputeAvailablePrinters()
        if (cloudLoggedIn) {
            refreshPrinters()
        } else {
            tryAutoLogin()
        }
    }

    fun requestLoadModel(path: String) {
        if (_screen.value == Screen.Home) {
            onModelPicked(path)
        } else {
            _pendingModelPath.value = path
        }
    }

    fun confirmPendingModel() {
        val path = _pendingModelPath.value ?: return
        _pendingModelPath.value = null
        if (_screen.value == Screen.Printing) {
            cancelPrint()
        }
        onModelPicked(path)
    }

    fun dismissPendingModel() {
        _pendingModelPath.value = null
    }

    fun onModelPicked(path: String) {
        abandonSlice()
        resetModelTransform()
        _modelError.value = null
        _screen.value = Screen.Home
        var file = File(path)
        if (!file.exists()) {
            reportUnsupportedFormat(file.name)
            return
        }

        var ext = file.extension.lowercase()
        val isZip = ModelParser.isZip(file)
        if (isZip && ext != "3mf") {
            val target = File(file.parentFile, "${file.nameWithoutExtension}.3mf")
            if (file.renameTo(target)) file = target
            ext = "3mf"
        } else if (!isZip && ext != "stl" && ext != "3mf") {
            val target = File(file.parentFile, "${file.nameWithoutExtension}.stl")
            if (file.renameTo(target)) file = target
            ext = "stl"
        }

        if (ext != "stl" && ext != "3mf") {
            reportUnsupportedFormat(file.name)
            return
        }
        EventNotifier.trackEvent(
            "model_selected",
            mapOf(
                "name" to file.name,
                "format" to ext,
                "size_bytes" to file.length(),
            ),
        )
        modelPath = file.absolutePath
        viewModelScope.launch {
            _modelLoading.value = true
            _modelLoadProgress.value = null
            try {
                val t0 = android.os.SystemClock.elapsedRealtime()
                val m = withContext(Dispatchers.Default) {
                    // Native libslic3r readers + quadric decimation; Kotlin fallback.
                    NativeLib.parsePreviewMesh(
                        file.absolutePath,
                        MeshDecimator.MAX_PREVIEW_TRIANGLES,
                        onProgress = { _modelLoadProgress.value = it },
                    ) ?: ModelParser.parse(file)
                }
                android.util.Log.i(
                    "ModelPreview",
                    "parse ${file.name}: ${android.os.SystemClock.elapsedRealtime() - t0}ms " +
                        "tris=${m.indices.size / 3} verts=${m.positions.size / 3}",
                )
                if (m.positions.isEmpty() || !m.maxX.isFinite() || m.maxX < m.minX) {
                    reportUnsupportedFormat(file.name)
                } else {
                    EventNotifier.trackEvent(
                        "model_loaded",
                        mapOf(
                            "name" to file.name,
                            "format" to ext,
                            "size_bytes" to file.length(),
                            "vertices" to (m.positions.size / 3),
                            "triangles" to (m.indices.size / 3),
                        ),
                    )
                    _mesh.value = m
                    val settings = withContext(Dispatchers.Default) { ProjectSettingsReader.read(file) }
                    applyProjectPrintSettings(settings)
                }
            } catch (t: Throwable) {
                reportModelLoadError("parse", t)
            }
            _modelLoading.value = false
            _modelLoadProgress.value = null
        }
    }

    @Volatile private var demoModelLoading = false

    fun ensureDemoModel() {
        if (!_demoMode.value) return
        if (modelPath != null && _mesh.value != null) return
        if (demoModelLoading) return
        demoModelLoading = true
        viewModelScope.launch {
            try {
                val file = File(cacheDir, DEMO_MODEL_FILE)
                if (!file.exists() || file.length() == 0L) {
                    withContext(Dispatchers.IO) {
                        context.assets.open(DEMO_MODEL_ASSET).use { input ->
                            file.outputStream().use { output -> input.copyTo(output) }
                        }
                    }
                }
                if (file.exists() && file.length() > 0L) {
                    onModelPicked(file.absolutePath)
                }
            } catch (t: Throwable) {
                android.util.Log.w("AppViewModel", "demo model load failed", t)
            } finally {
                demoModelLoading = false
            }
        }
    }

    fun clearModel() {
        modelPath = null
        _mesh.value = null
        _gcodePreview.value = null
        _gcodeStats.value = null
        gcodePath = null
        resetModelTransform()
        if (_demoMode.value) ensureDemoModel()
    }

    private fun applyProjectPrintSettings(ps: ProjectPrintSettings?) {
        if (ps == null) return
        val current = _config.value
        _config.value = current.copy(
            supportEnabled = ps.supportEnabled ?: false,
            supportStyle = ps.supportStyle ?: "grid",
            layerHeight = ps.layerHeight,
            wallLoops = ps.wallLoops,
            topShellLayers = ps.topShellLayers,
            bottomShellLayers = ps.bottomShellLayers,
            infillDensity = ps.infillDensity,
            infillPattern = ps.infillPattern,
            adhesion = ps.adhesion ?: "auto",
            brimWidth = ps.brimWidth?.toInt(),
            seamPosition = ps.seamPosition ?: "aligned",
            ironing = ps.ironing ?: false,
            supportOnPlateOnly = ps.supportOnPlateOnly ?: false,
            supportThresholdAngle = ps.supportThresholdAngle,
            supportZDistance = ps.supportZDistance,
            wallSpeed = ps.wallSpeed,
            infillSpeed = ps.infillSpeed,
            travelSpeed = ps.travelSpeed,
        )
        ps.bedType?.let { raw ->
            val plate = when {
                raw.contains("textured", ignoreCase = true) -> "Textured"
                raw.contains("smooth", ignoreCase = true) || raw.contains("high temp", ignoreCase = true) ||
                    raw.contains("engineering", ignoreCase = true) || raw.contains("cool", ignoreCase = true) -> "Smooth"
                else -> null
            }
            if (plate != null) setSetupPlate(plate)
        }
    }

    fun updateConfig(newConfig: SliceConfig) {
        _config.value = newConfig
        prefs.edit()
            .putString("cfg_printer", newConfig.printer)
            .putString("cfg_process", newConfig.process)
            .putString("cfg_filament", newConfig.filament)
            .putString("cfg_filament_color", newConfig.filamentColor)
            .putBoolean("cfg_support", newConfig.supportEnabled)
            .putString("cfg_support_style", newConfig.supportStyle)
            .putString("cfg_support_filament", newConfig.supportFilament)
            .apply()
        val e = prefs.edit()
        newConfig.layerHeight?.let { e.putFloat("cfg_layer_height", it) } ?: e.remove("cfg_layer_height")
        newConfig.wallLoops?.let { e.putInt("cfg_wall_loops", it) } ?: e.remove("cfg_wall_loops")
        newConfig.topShellLayers?.let { e.putInt("cfg_top_shells", it) } ?: e.remove("cfg_top_shells")
        newConfig.bottomShellLayers?.let { e.putInt("cfg_bottom_shells", it) } ?: e.remove("cfg_bottom_shells")
        newConfig.infillDensity?.let { e.putInt("cfg_infill_density", it) } ?: e.remove("cfg_infill_density")
        newConfig.filamentTrayId?.let { e.putInt("cfg_filament_tray", it) } ?: e.remove("cfg_filament_tray")
        newConfig.filamentCanvasId?.let { e.putInt("cfg_filament_canvas", it) } ?: e.remove("cfg_filament_canvas")
        e.putString("cfg_infill_pattern", newConfig.infillPattern)
        e.putString("cfg_adhesion", newConfig.adhesion)
        newConfig.brimWidth?.let { e.putInt("cfg_brim_width", it) } ?: e.remove("cfg_brim_width")
        e.putString("cfg_seam_position", newConfig.seamPosition)
        e.putBoolean("cfg_ironing", newConfig.ironing)
        e.putBoolean("cfg_support_plate_only", newConfig.supportOnPlateOnly)
        newConfig.supportThresholdAngle?.let { e.putInt("cfg_support_angle", it) } ?: e.remove("cfg_support_angle")
        newConfig.supportZDistance?.let { e.putFloat("cfg_support_z_distance", it) } ?: e.remove("cfg_support_z_distance")
        newConfig.wallSpeed?.let { e.putInt("cfg_wall_speed", it) } ?: e.remove("cfg_wall_speed")
        newConfig.infillSpeed?.let { e.putInt("cfg_infill_speed", it) } ?: e.remove("cfg_infill_speed")
        newConfig.travelSpeed?.let { e.putInt("cfg_travel_speed", it) } ?: e.remove("cfg_travel_speed")
        e.apply()
    }

    private fun safeGcodeName(modelPath: String?): String {
        val base = modelPath?.let { File(it).nameWithoutExtension }?.trim().orEmpty()
        val cleaned = base.ifBlank { "model" }
            .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
            .trim()
            .ifBlank { "model" }
        return if (cleaned.endsWith(".gcode", ignoreCase = true)) cleaned else "$cleaned.gcode"
    }

    val suggestedGcodeName: String
        get() = safeGcodeName(modelPath)

    fun startSlice(bedType: String? = null, autoPrint: Boolean = false) {
        val mp = modelPath ?: return
        val cfg = _config.value
        val printer = cfg.printer ?: return
        val process = cfg.process ?: return
        val filament = cfg.filament ?: return
        val out = File(cacheDir, suggestedGcodeName).absolutePath
        val jobId = (++sliceGeneration).toLong()
        _gcodePreview.value = null
        _gcodeStats.value = null
        gcodePath = null
        _slicing.value = true
        _sliceProgress.value = 0f
        _sliceStatus.value = "Starting…"
        _screen.value = Screen.Slicing
        EventNotifier.trackEvent(
            "slice_started",
            mapOf(
                "printer" to printer,
                "filament" to filament,
                "process" to process,
                "bed_type" to bedType.orEmpty(),
                "auto_print" to autoPrint,
                "output" to out,
            ),
        )
        val overrides = buildString {
            if (cfg.supportEnabled) {
                appendLine("enable_support=1")
                if (cfg.supportStyle == "trees") {
                    appendLine("support_type=tree(auto)")
                    appendLine("support_style=organic")
                } else {
                    appendLine("support_type=normal(auto)")
                    if (cfg.supportStyle == "snug") appendLine("support_style=snug")
                }
                if (cfg.supportFilament != null) appendLine("support_filament=${cfg.supportFilament}")
                cfg.supportThresholdAngle?.let { appendLine("support_threshold_angle=$it") }
                cfg.supportZDistance?.let {
                    appendLine("support_top_z_distance=$it")
                    appendLine("support_bottom_z_distance=$it")
                }
                if (cfg.supportOnPlateOnly) appendLine("support_on_build_plate_only=1")
            } else {
                appendLine("enable_support=0")
            }
            cfg.filamentColor?.let { c ->
                val color = if (c.startsWith("#")) c else "#$c"
                appendLine("filament_colour=$color")
                appendLine("filament_multi_colour=$color")
            }
            cfg.layerHeight?.let { appendLine("layer_height=$it") }
            cfg.wallLoops?.let { appendLine("wall_loops=$it") }
            cfg.topShellLayers?.let { appendLine("top_shell_layers=$it") }
            cfg.bottomShellLayers?.let { appendLine("bottom_shell_layers=$it") }
            cfg.infillDensity?.let { appendLine("sparse_infill_density=$it%") }
            cfg.infillPattern?.let { appendLine("sparse_infill_pattern=$it") }
            when (cfg.adhesion) {
                "none" -> { appendLine("brim_width=0"); appendLine("raft_layers=0"); appendLine("skirt_height=0") }
                "skirt" -> { appendLine("brim_width=0"); appendLine("raft_layers=0"); appendLine("skirt_height=2") }
                "brim" -> { appendLine("brim_width=${cfg.brimWidth ?: 5}"); appendLine("raft_layers=0") }
                "raft" -> { appendLine("raft_layers=2") }
            }
            appendLine("seam_position=${cfg.seamPosition}")
            appendLine("ironing_type=${if (cfg.ironing) "top" else "no ironing"}")
            cfg.wallSpeed?.let {
                appendLine("outer_wall_speed=$it")
                appendLine("inner_wall_speed=$it")
            }
            cfg.infillSpeed?.let { appendLine("sparse_infill_speed=$it") }
            cfg.travelSpeed?.let { appendLine("travel_speed=$it") }
            if (bedType != null) appendLine("curr_bed_type=$bedType")
        }
        val t = _modelTransform.value
        try {
            SliceForegroundService.start(
                context = context,
                jobId = jobId,
                modelPath = mp,
                printer = printer,
                filament = filament,
                process = process,
                overrides = overrides,
                outPath = out,
                autoPrint = autoPrint,
                transform = SliceTransform(
                    rotX = t.rotX,
                    rotY = t.rotY,
                    rotZ = t.rotZ,
                    mirrorX = t.mirrorX,
                    mirrorY = t.mirrorY,
                    mirrorZ = t.mirrorZ,
                    offsetX = t.offsetX,
                    offsetY = t.offsetY,
                ),
            )
        } catch (e: Exception) {
            _slicing.value = false
            _sliceProgress.value = 0f
            _sliceStatus.value = "Could not start background slicing"
            _cloudMessage.value = _sliceStatus.value
            _screen.value = Screen.Slice
            EventNotifier.trackError(
                "slice_service_start_failed",
                e,
                mapOf(
                    "printer" to printer,
                    "filament" to filament,
                    "process" to process,
                    "output" to out,
                ),
            )
        }
    }

    private fun abandonSlice() {
        sliceGeneration++
        val serviceState = SliceForegroundService.state.value
        if (serviceState.isRunning) {
            SliceForegroundService.cancel(context, serviceState.jobId)
        }
        _slicing.value = false
    }

    fun cancelSlice() {
        EventNotifier.trackEvent("slice_cancelled")
        abandonSlice()
        _screen.value = Screen.Slice
    }

    fun backHome() {
        abandonSlice()
        _screen.value = Screen.Home
    }

    fun goBack() {
        when (_screen.value) {
            Screen.Home -> {}
            Screen.Browse -> _screen.value = Screen.Home
            Screen.PrintSetup -> _screen.value = Screen.Home
            Screen.Slice -> _screen.value = Screen.PrintSetup
            Screen.Slicing -> {
                abandonSlice()
                _screen.value = Screen.Slice
            }
            Screen.Preview -> _screen.value = Screen.Slice
            Screen.Printing -> if (_printSim.value.complete) finishPrintToHome() else cancelPrint()
        }
    }

    fun openPreviewIfReady() {
        if (gcodePath != null && _gcodePreview.value != null) {
            _screen.value = Screen.Preview
            return
        }
        openPreviewFromPaths(
            gcodePath ?: prefs.getString("last_gcode_path", null),
            modelPath ?: prefs.getString("last_model_path", null),
        )
    }

    fun openPreviewFromPaths(gcodePath: String?, modelPath: String?): Boolean {
        val gcodeFile = gcodePath?.let(::File)?.takeIf { it.exists() && it.length() > 0L } ?: return false
        if (this.gcodePath == gcodeFile.absolutePath && _gcodePreview.value != null) {
            _screen.value = Screen.Preview
            return true
        }

        this.gcodePath = gcodeFile.absolutePath
        val modelFile = modelPath?.let(::File)?.takeIf { it.exists() && it.length() > 0L }
        if (modelFile != null) {
            this.modelPath = modelFile.absolutePath
        }
        persistSliceResult(this.gcodePath, this.modelPath)

        viewModelScope.launch {
            _modelLoading.value = true
            try {
                val stats = withContext(Dispatchers.Default) { GcodePreviewParser.parseStats(gcodeFile) }
                _gcodeStats.value = stats
                val preview = withContext(Dispatchers.Default) { GcodePreviewParser.parse(gcodeFile) }
                _gcodePreview.value = preview
                _screen.value = Screen.Preview
            } catch (t: Throwable) {
                reportModelLoadError("parse", t)
            } finally {
                _modelLoading.value = false
            }
        }
        return true
    }

    private suspend fun injectGcodeThumbnail(path: String) {
        val mesh = _mesh.value ?: return
        try {
            withContext(Dispatchers.IO) {
                val file = File(path)
                if (!file.exists() || file.length() == 0L) return@withContext
                if (world.yemyat.slicepilot.model.GcodeThumbnail.hasThumbnail(file)) return@withContext
                val t = _modelTransform.value
                val previewTransform = world.yemyat.slicepilot.preview.ModelTransform(
                    rotX = t.rotX,
                    rotY = t.rotY,
                    rotZ = t.rotZ,
                    mirrorX = t.mirrorX,
                    mirrorY = t.mirrorY,
                    mirrorZ = t.mirrorZ,
                    offsetX = t.offsetX,
                    offsetY = t.offsetY,
                )
                val bmp = world.yemyat.slicepilot.preview.ThumbnailRenderer.render(
                    mesh,
                    144,
                    transform = previewTransform,
                    modelColorHex = _config.value.filamentColor,
                ) ?: return@withContext
                val png = java.io.ByteArrayOutputStream().use { out ->
                    bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                    out.toByteArray()
                }
                world.yemyat.slicepilot.model.GcodeThumbnail.inject(file, png, bmp.width, bmp.height)
                bmp.recycle()
            }
        } catch (t: Throwable) {
            android.util.Log.w("AppViewModel", "gcode thumbnail failed", t)
        }
    }

    private fun persistSliceResult(gcodePath: String?, modelPath: String?) {
        prefs.edit()
            .putString("last_gcode_path", gcodePath)
            .putString("last_model_path", modelPath)
            .apply()
    }

    private fun observeSliceService() {
        viewModelScope.launch {
            SliceForegroundService.state.collect { s ->
                if (s.jobId == 0L) {
                    _slicing.value = false
                    _sliceProgress.value = 0f
                    if (_screen.value == Screen.Slicing) {
                        _screen.value = Screen.Slice
                    }
                    return@collect
                }

                _sliceProgress.value = s.progress
                _sliceStatus.value = s.status
                _slicing.value = s.isRunning
                s.gcodePath?.let { gcodePath = it }
                s.preview?.let { _gcodePreview.value = it }
                s.stats?.let { _gcodeStats.value = it }

                if (!s.isRunning && s.error != null && s.jobId != handledSliceErrorJobId) {
                    handledSliceErrorJobId = s.jobId
                    _sliceStatus.value = "Error: ${s.error}"
                    _cloudMessage.value = _sliceStatus.value
                    if (_screen.value == Screen.Slicing) {
                        _screen.value = Screen.Slice
                    }
                    SliceForegroundService.clearFinishedJob()
                }

                if (!s.isRunning && s.error == null && s.gcodePath != null && s.jobId != handledSliceJobId) {
                    handledSliceJobId = s.jobId
                    injectGcodeThumbnail(s.gcodePath)
                    persistSliceResult(s.gcodePath, modelPath)
                    if (s.autoPrint) {
                        val p = selectedPrinter
                        if (p?.cloudPrinter != null && cloudLoggedIn) {
                            sendToPrinter(p.cloudPrinter)
                        } else {
                            _cloudMessage.value = "Sliced. Connect a printer to print."
                        }
                    }
                    if (_screen.value == Screen.Slicing) {
                        _screen.value = Screen.Preview
                    }
                    SliceForegroundService.clearFinishedJob()
                }
            }
        }
    }

    fun signIn() {
        // Opens the Elegoo web login; token handoff completes via checkSession when the user returns.
    }

    fun nativeLogin(email: String, password: String, done: (Boolean) -> Unit = {}) {
        if (email.trim().lowercase() == DEMO_EMAIL && password == DEMO_PASSWORD) {
            enableDemo()
            done(true)
            return
        }
        viewModelScope.launch {
            when (val r = cloud.auth.login(email, password)) {
                is CloudAuthResult.Ok -> {
                    disableDemo()
                    EventNotifier.trackEvent("cloud_login_success", mapOf("user" to cloud.auth.nickname))
                    _cloudMessage.value = "Signed in as ${cloud.auth.nickname}"
                    refreshPrinters()
                    done(true)
                }
                is CloudAuthResult.Err -> {
                    EventNotifier.trackError("cloud_login_failed", null, mapOf("message" to r.message))
                    _cloudMessage.value = r.message
                    done(false)
                }
            }
        }
    }

    private var autoLoginJob: Job? = null

    /**
     * Silently signs back in at app start when the user checked "Remember password".
     * The Elegoo cloud only allows one session per account, so being signed out on
     * another device leaves the saved tokens dead; a fresh login restores the session.
     */
    private fun tryAutoLogin() {
        if (_demoMode.value) return
        if (autoLoginJob?.isActive == true) return
        val email = savedCloudEmail
        val password = savedCloudPassword // non-blank only when "Remember password" is set
        if (email.isBlank() || password.isBlank()) return
        autoLoginJob = viewModelScope.launch {
            when (val r = cloud.auth.login(email, password)) {
                is CloudAuthResult.Ok -> {
                    android.util.Log.i("AppViewModel", "auto-login succeeded for $email")
                    EventNotifier.trackEvent("cloud_auto_login", mapOf("user" to cloud.auth.nickname))
                    refreshPrinters()
                }
                is CloudAuthResult.Err -> {
                    android.util.Log.w("AppViewModel", "auto-login failed: ${r.message}")
                    EventNotifier.trackEvent("cloud_auto_login_failed", mapOf("message" to r.message))
                }
            }
        }
    }

    fun checkSession() {
        if (_demoMode.value) return
        if (!cloud.auth.isLoggedIn) {
            // A sign-in may be in flight from app start; don't clobber its new tokens.
            tryAutoLogin()
            return
        }
        val wasLoggedIn = cloud.auth.isLoggedIn
        viewModelScope.launch {
            when (val r = cloud.validateSession()) {
                is CloudResult.Ok -> {
                    EventNotifier.trackEvent(
                        "cloud_session_validated",
                        mapOf("user" to r.value.email.orEmpty()),
                    )
                    cloud.auth.saveSession(
                        cloud.auth.accessToken ?: "",
                        cloud.auth.refreshToken,
                        r.value.nickname,
                        r.value.email,
                    )
                    refreshPrinters()
                }
                is CloudResult.Err -> {
                    android.util.Log.w("AppViewModel", "checkSession failed: ${r.message}")
                    if (cloud.auth.isAuthFailure(r.message)) {
                        EventNotifier.trackEvent(
                            "cloud_session_expired",
                            mapOf("was_logged_in" to wasLoggedIn, "message" to r.message),
                        )
                        handleCloudSignedOut(
                            if (wasLoggedIn) "Signed out on another device" else null,
                        )
                    } else if (wasLoggedIn) {
                        _cloudMessage.value = r.message
                    }
                }
            }
        }
    }

    private fun handleCloudSignedOut(message: String? = null) {
        val wasCloudSelected = _selectedPrinter.value?.isLocal == false
        val hadCloudState = wasCloudSelected || _printers.value.isNotEmpty() || cloud.auth.isLoggedIn
        if (!hadCloudState && message.isNullOrBlank()) return

        EventNotifier.trackEvent(
            "cloud_signed_out",
            mapOf("message" to message.orEmpty(), "was_cloud_selected" to wasCloudSelected),
        )
        mqtt.stop()
        cloud.auth.logout()
        _printers.value = emptyList()
        if (wasCloudSelected) {
            _selectedPrinter.value = null
            _selectedPrinterId.value = null
            prefs.edit()
                .remove("cloud_printer_id")
                .remove("selected_printer_type")
                .apply()
        }
        recomputeAvailablePrinters()
        if (!message.isNullOrBlank()) _cloudMessage.value = message
    }

    suspend fun tryLoginWithWebViewCookies(): Boolean {
        if (_demoMode.value) return false
        val cookie = cloud.auth.captureWebViewCookie()
        if (cookie.isBlank()) return false
        cloud.auth.sessionCookie = cookie
        return when (val r = cloud.auth.checkSession()) {
            is CloudAuthResult.Ok -> {
                cloud.auth.saveSession(
                    cloud.auth.accessToken ?: "",
                    cloud.auth.refreshToken,
                    r.value.nickname,
                    r.value.email,
                )
                cloud.auth.sessionCookie = cookie
                refreshPrinters()
                true
            }
            is CloudAuthResult.Err -> {
                android.util.Log.i("CloudClient", "tryLogin not yet: ${r.message}")
                false
            }
        }
    }

    private fun recomputeAvailablePrinters() {
        val list = mutableListOf<ConnectedPrinter>()
        val seen = mutableSetOf<String>()

        for (lp in _lanPrinters.value) {
            if (!lp.lanMode) continue
            val key = "lan:${lp.serial.ifBlank { lp.ip }}"
            if (seen.add(key)) {
                list.add(lp.toConnectedPrinter())
            }
        }
        val selLan = _selectedLanPrinter.value
        if (selLan != null && selLan.lanMode) {
            val key = "lan:${selLan.serial.ifBlank { selLan.ip }}"
            if (seen.add(key)) {
                list.add(selLan.toConnectedPrinter())
            }
        }
        for (cp in _printers.value) {
            val key = "cloud:${cp.id}"
            if (seen.add(key)) {
                list.add(cp.toConnectedPrinter())
            }
        }
        _availablePrinters.value = list
    }

    fun refreshPrinters() {
        if (_demoMode.value) {
            val demoPrinter = CloudPrinter(
                id = DEMO_PRINTER_ID,
                name = "Demo Centauri Carbon 2",
                model = "Centauri Carbon 2",
                online = true,
            )
            _printers.value = listOf(demoPrinter)
            recomputeAvailablePrinters()
            val printerType = prefs.getString("selected_printer_type", null)
            if (_selectedPrinter.value == null || printerType == "cloud") {
                selectCloudPrinter(demoPrinter)
            }
            _filaments.value = _filaments.value + (
                DEMO_PRINTER_ID to world.yemyat.slicepilot.cloud.FilamentInfo(
                    present = true,
                    name = "Demo PLA",
                    colorHex = "#2E7D74",
                    remaining = 80,
                    trays = listOf(
                        world.yemyat.slicepilot.cloud.LoadedTray(
                            trayId = 1,
                            name = "Demo PLA",
                            type = "PLA",
                            colorHex = "#2E7D74",
                        ),
                    ),
                )
            )
            _cloudMessage.value = "Demo printer ready — printing is simulated."
            return
        }
        val wasLoggedIn = cloud.auth.isLoggedIn
        viewModelScope.launch {
            when (val r = cloud.listPrinters()) {
                is CloudResult.Ok -> {
                    _printers.value = r.value
                    EventNotifier.trackEvent(
                        "cloud_printers_refreshed",
                        mapOf(
                            "count" to r.value.size,
                            "online_count" to r.value.count { it.online },
                        ),
                    )
                    recomputeAvailablePrinters()
                    val printerType = prefs.getString("selected_printer_type", null)
                    if (_selectedPrinter.value == null || printerType == "cloud") {
                        val savedId = _selectedPrinterId.value
                        val match = r.value.firstOrNull { it.id == savedId }
                            ?: r.value.firstOrNull { it.online }
                            ?: r.value.firstOrNull()
                        if (match != null) selectCloudPrinter(match)
                    }
                    startMqtt()
                    refreshFilaments(r.value)
                }
                is CloudResult.Err -> {
                    android.util.Log.w("AppViewModel", "refreshPrinters failed: ${r.message}")
                    if (cloud.auth.isAuthFailure(r.message)) {
                        handleCloudSignedOut(
                            if (wasLoggedIn) "Signed out on another device" else null,
                        )
                    } else if (wasLoggedIn) {
                        _cloudMessage.value = r.message
                    }
                }
            }
        }
    }

    private fun refreshFilaments(printers: List<CloudPrinter>) {
        viewModelScope.launch {
            for (p in printers.filter { it.online }) {
                val f = cloud.getFilament(p.id)
                if (f != null) {
                    _filaments.value = _filaments.value + (p.id to f)
                }
            }
        }
    }

    private fun startMqtt() {
        if (mqtt.connected) return
        viewModelScope.launch {
            when (val r = cloud.getMqttConfig()) {
                is CloudResult.Ok -> {
                    val c = r.value
                    mqtt.start(c.host, c.port, c.path, c.clientId, c.username, c.password, c.topicFilter)
                }
                is CloudResult.Err -> android.util.Log.w("AppViewModel", "mqtt config: ${r.message}")
            }
        }
        viewModelScope.launch {
            mqtt.filaments.collect { live ->
                if (live.isNotEmpty()) {
                    _filaments.value = _filaments.value + live
                }
            }
        }
    }

    fun enableDemo() {
        _demoMode.value = true
        prefs.edit().putBoolean("demo_mode", true).apply()
        EventNotifier.trackEvent("demo_mode_enabled")
        _cloudMessage.value = "Demo mode enabled — no real printer is connected."
        refreshPrinters()
        ensureDemoModel()
    }

    fun disableDemo() {
        val wasDemo = _demoMode.value
        _demoMode.value = false
        prefs.edit().remove("demo_mode").apply()
        if (wasDemo) {
            EventNotifier.trackEvent("demo_mode_disabled")
            if (modelPath == File(cacheDir, DEMO_MODEL_FILE).absolutePath) {
                clearModel()
            }
            if (_selectedPrinterId.value == DEMO_PRINTER_ID || _selectedPrinter.value?.cloudPrinter?.id == DEMO_PRINTER_ID) {
                _selectedPrinter.value = null
                _selectedPrinterId.value = null
                prefs.edit()
                    .remove("cloud_printer_id")
                    .remove("selected_printer_type")
                    .apply()
            }
            _printers.value = emptyList()
            recomputeAvailablePrinters()
            if (cloud.auth.isLoggedIn) refreshPrinters()
        }
    }

    fun signOut() {
        if (_demoMode.value) {
            disableDemo()
            _cloudMessage.value = "Demo mode ended"
            return
        }
        EventNotifier.trackEvent("cloud_sign_out")
        handleCloudSignedOut("Signed out")
    }

    fun scanLan(context: android.content.Context) {
        if (_lanScanning.value) return
        viewModelScope.launch {
            _lanScanning.value = true
            val scanStartedAt = System.currentTimeMillis()
            EventNotifier.trackEvent("lan_scan_started")
            try {
                val found = lanPrint.discoverAll(context.applicationContext, 5000)
                _lanPrinters.value = found
                recomputeAvailablePrinters()
                for (p in found) {
                    EventNotifier.trackEvent(
                        "lan_printer_found",
                        mapOf(
                            "name" to p.name,
                            "model" to p.model,
                            "ip" to p.ip,
                            "lan_mode" to p.lanMode,
                        ),
                    )
                    refreshLanFilament(p)
                }

                val selectable = found.filter { it.lanMode }
                EventNotifier.trackEvent(
                    "lan_scan_completed",
                    mapOf(
                        "found" to found.size,
                        "selectable" to selectable.size,
                        "cloud_only" to (found.size - selectable.size),
                        "duration_ms" to (System.currentTimeMillis() - scanStartedAt),
                    ),
                )
                val current = _selectedLanPrinter.value
                if (current != null) {
                    val updated = found.firstOrNull { it.serial == current.serial || it.ip == current.ip }
                    if (updated != null) {
                        if (updated.lanMode) {
                            selectLanPrinter(updated)
                        } else {
                            clearSelectedPrinter()
                            _cloudMessage.value =
                                "This printer is in cloud mode. Switch it to LAN-only mode to connect locally."
                        }
                    }
                } else if (_selectedPrinter.value == null && selectable.isNotEmpty()) {
                    selectLanPrinter(selectable.first())
                } else if (found.isNotEmpty() && selectable.isEmpty()) {
                    val noun = if (found.size == 1) "printer" else "printers"
                    _cloudMessage.value =
                        "Found ${found.size} $noun in cloud mode. Switch one to LAN-only mode to connect locally."
                }
            } finally {
                _lanScanning.value = false
            }
        }
    }

    fun selectLanPrinter(printer: world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter) {
        if (!printer.lanMode) {
            _cloudMessage.value =
                "This printer is in cloud mode. Switch it to LAN-only mode to connect locally."
            return
        }
        _selectedLanPrinter.value = printer
        _selectedPrinter.value = printer.toConnectedPrinter()
        prefs.edit()
            .putString("selected_printer_type", "lan")
            .putString("lan_printer_serial", printer.serial)
            .putString("lan_printer_ip", printer.ip)
            .putString("lan_printer_name", printer.name)
            .putString("lan_printer_model", printer.model)
            .putBoolean("lan_printer_lan_mode", true)
            .apply()
        recomputeAvailablePrinters()
        syncPrinterProfileToConfig()

        // Check if we have cached filament for this printer or use default
        val cached = printer.serial.takeIf { it.isNotBlank() }?.let { prefs.getString("filament_$it", null) }
            ?: printer.ip.takeIf { it.isNotBlank() }?.let { prefs.getString("filament_$it", null) }
        val info = cached?.let { parseFilamentJson(it) }
            ?: world.yemyat.slicepilot.cloud.FilamentInfo(present = false)
        applyFilament(printer.serial, printer.ip, info)
        refreshLanFilament(printer)
        EventNotifier.trackEvent(
            "printer_selected",
            mapOf(
                "type" to "local",
                "printer" to printer.name,
                "model" to printer.model,
                "ip" to printer.ip,
            ),
        )
    }

    fun selectPrinter(printer: ConnectedPrinter) {
        if (printer.isLocal) {
            printer.lanPrinter?.let { selectLanPrinter(it) } ?: run {
                val lp = world.yemyat.slicepilot.cloud.LanPrintManager.DiscoveredPrinter(
                    ip = printer.ip ?: "",
                    serial = printer.serial ?: "",
                    model = printer.model,
                    name = printer.name,
                )
                selectLanPrinter(lp)
            }
        } else {
            printer.cloudPrinter?.let { selectCloudPrinter(it) } ?: run {
                val cp = CloudPrinter(
                    id = printer.id,
                    name = printer.name,
                    model = printer.model,
                    online = printer.online,
                )
                selectCloudPrinter(cp)
            }
        }
    }

    fun clearSelectedPrinter() {
        val previous = _selectedPrinter.value
        EventNotifier.trackEvent(
            "printer_disconnected",
            mapOf(
                "printer" to previous?.name.orEmpty(),
                "type" to if (previous?.isLocal == true) "local" else "cloud",
            ),
        )
        _selectedPrinter.value = null
        _selectedLanPrinter.value = null
        _selectedPrinterId.value = null
        prefs.edit()
            .remove("selected_printer_type")
            .remove("lan_printer_serial")
            .remove("lan_printer_ip")
            .remove("lan_printer_name")
            .remove("lan_printer_model")
            .remove("lan_printer_lan_mode")
            .remove("cloud_printer_id")
            .apply()
        recomputeAvailablePrinters()
    }

    fun sendToPrinter(printer: CloudPrinter) {
        val gp = gcodePath
        if (gp == null) {
            _cloudMessage.value = "No sliced file to print"
            return
        }
        if (_demoMode.value) {
            viewModelScope.launch { simulateDemoPrint(printer.toConnectedPrinter()) }
            return
        }
        viewModelScope.launch {
            _cloudMessage.value = "Preparing print…"
            when (val r = cloudPrint.print(printer, File(gp), currentPrintSlot()) { msg -> _cloudMessage.value = msg }) {
                is CloudResult.Ok -> _cloudMessage.value = "Print started on ${printer.name}"
                is CloudResult.Err -> _cloudMessage.value = r.message
            }
        }
    }

    /** Print the sliced file: cloud when signed in, otherwise local network. */
    fun printSliced(context: android.content.Context) {
        val gp = gcodePath
        if (gp == null) {
            _cloudMessage.value = "No sliced file to print"
            return
        }
        val sp = selectedPrinter
        if (sp == null) {
            _cloudMessage.value = "No printer selected"
            return
        }
        if (_demoMode.value) {
            startPrint(context)
            return
        }
        if (sp.isLocal) {
            val lanTarget = sp.lanPrinter ?: _selectedLanPrinter.value
            viewModelScope.launch {
                _cloudMessage.value = "Preparing local print…"
                when (val r = lanPrint.print(context.applicationContext, File(gp), lanTarget, currentPrintSlot()) { msg -> _cloudMessage.value = msg }) {
                    is CloudResult.Ok -> _cloudMessage.value = "Print started on ${sp.name}"
                    is CloudResult.Err -> _cloudMessage.value = r.message
                }
            }
        } else {
            val cp = sp.cloudPrinter ?: _printers.value.firstOrNull { it.id == sp.id }
            if (cp == null || !cloudLoggedIn) {
                _cloudMessage.value = "Sign in to cloud to print on ${sp.name}"
                return
            }
            sendToPrinter(cp)
        }
    }

    override fun onCleared() {
        cloudPrint.release()
        super.onCleared()
    }

    fun consumeCloudMessage() { _cloudMessage.value = null }
}
