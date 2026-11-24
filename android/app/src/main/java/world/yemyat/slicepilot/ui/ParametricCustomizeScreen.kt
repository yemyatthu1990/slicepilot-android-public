package world.yemyat.slicepilot.ui

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import world.yemyat.slicepilot.NativeLib
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.model.MeshDecimator
import world.yemyat.slicepilot.model.ModelParser
import world.yemyat.slicepilot.preview.ModelPreviewView
import world.yemyat.slicepilot.preview.PreviewGeometry
import world.yemyat.slicepilot.repo.MakerworldCustomizer
import world.yemyat.slicepilot.repo.ParametricInfo
import world.yemyat.slicepilot.repo.ScadCustomizer
import world.yemyat.slicepilot.ui.components.WavyDots
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.BrandSectionLabel
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.DropdownRow
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.MenuOption
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.SettingsRow
import world.yemyat.slicepilot.ui.theme.Space
import world.yemyat.slicepilot.ui.theme.SwatchDot
import world.yemyat.slicepilot.ui.theme.filamentColor
import java.io.File

private const val TAG = "ParametricCustom"

private data class MakerFont(
    val name: String,
    val style: String,
)

/** Curated MakerWorld / Google Fonts popular for 3D-printed keychains, signs, and labels. */
private val MAKERWORLD_FONTS =
    listOf(
        // Script & Cursive
        MakerFont("Lobster", "Script"),
        MakerFont("Pacifico", "Script"),
        MakerFont("Dancing Script", "Script"),
        MakerFont("Satisfy", "Script"),
        MakerFont("Kaushan Script", "Script"),
        MakerFont("Great Vibes", "Script"),
        MakerFont("Sacramento", "Script"),
        MakerFont("Cookie", "Script"),
        MakerFont("Yellowtail", "Script"),
        MakerFont("Courgette", "Script"),
        MakerFont("Allura", "Script"),
        MakerFont("Caveat", "Script"),
        // Bold & Display
        MakerFont("Fredoka", "Bold"),
        MakerFont("Bangers", "Bold"),
        MakerFont("Bungee", "Bold"),
        MakerFont("Righteous", "Bold"),
        MakerFont("Lilita One", "Bold"),
        MakerFont("Chewy", "Bold"),
        MakerFont("Titan One", "Bold"),
        MakerFont("Luckiest Guy", "Bold"),
        MakerFont("Rubik Bubbles", "Bold"),
        MakerFont("Bebas Neue", "Bold"),
        MakerFont("Anton", "Bold"),
        MakerFont("Alfa Slab One", "Bold"),
        // Rounded & Cute
        MakerFont("Baloo 2", "Rounded"),
        MakerFont("Comfortaa", "Rounded"),
        MakerFont("Quicksand", "Rounded"),
        MakerFont("Nunito", "Rounded"),
        MakerFont("Varela Round", "Rounded"),
        MakerFont("Comic Neue", "Rounded"),
        // Clean & Modern
        MakerFont("Roboto", "Modern"),
        MakerFont("Montserrat", "Modern"),
        MakerFont("Poppins", "Modern"),
        MakerFont("Oswald", "Modern"),
        MakerFont("Raleway", "Modern"),
        MakerFont("Open Sans", "Modern"),
        MakerFont("Lato", "Modern"),
        MakerFont("Ubuntu", "Modern"),
        // Classic & Serif
        MakerFont("Playfair Display", "Classic"),
        MakerFont("Cinzel", "Classic"),
        MakerFont("Merriweather", "Classic"),
        MakerFont("Abril Fatface", "Classic"),
        MakerFont("Lora", "Classic"),
        // Tech & Mono
        MakerFont("Orbitron", "Tech"),
        MakerFont("Press Start 2P", "Tech"),
        MakerFont("Audiowide", "Tech"),
        MakerFont("JetBrains Mono", "Tech"),
        MakerFont("Space Mono", "Tech"),
    )

private val QUICK_FONTS =
    listOf("Lobster", "Pacifico", "Fredoka", "Dancing Script", "Bangers", "Montserrat", "Comfortaa", "Orbitron")

private val FONT_STYLES = listOf("All", "Script", "Bold", "Rounded", "Modern", "Classic", "Tech")

private data class PresetColor(
    val name: String,
    val hex: String,
)

/** Popular 3D-printing filament colours for one-tap selection on parametric colour fields. */
private val FILAMENT_PRESET_COLORS =
    listOf(
        PresetColor("White", "#FFFFFF"),
        PresetColor("Black", "#1A1A1A"),
        PresetColor("Silver", "#9E9E9E"),
        PresetColor("Red", "#E53935"),
        PresetColor("Hot Pink", "#FF1493"),
        PresetColor("Coral", "#FF6F61"),
        PresetColor("Orange", "#FB8C00"),
        PresetColor("Gold", "#FBC02D"),
        PresetColor("Yellow", "#FFEB3B"),
        PresetColor("Lime", "#8BC34A"),
        PresetColor("Green", "#2E7D32"),
        PresetColor("Mint", "#26A69A"),
        PresetColor("Cyan", "#00ACC1"),
        PresetColor("Sky Blue", "#29B6F6"),
        PresetColor("Blue", "#1E88E5"),
        PresetColor("Navy", "#283593"),
        PresetColor("Purple", "#8E24AA"),
        PresetColor("Lavender", "#CE93D8"),
        PresetColor("Brown", "#6D4C41"),
        PresetColor("Cream", "#F5E6CA"),
    )

/**
 * Snapshot-backed wrapper around a parsed [ScadCustomizer.Param] so Compose
 * controls stay live while editing and commit back cleanly before generation.
 */
private class ParamEdit(
    val param: ScadCustomizer.Param,
) {
    var value by mutableStateOf(param.value)
    val name: String get() = param.name
    val section: String get() = param.section

    fun commit() {
        param.value = value
    }

    companion object {
        fun of(params: List<ScadCustomizer.Param>): List<ParamEdit> =
            params.filter { it !is ScadCustomizer.Param.Raw }.map(::ParamEdit)
    }
}

private data class GeneratedPreview(
    val filePath: String,
    val mesh: MeshData,
    val filamentColors: List<String>,
    val scadSnapshot: String,
)

/**
 * Customizes a MakerWorld parametric (MakerLab) model — such as a personalized
 * name keychain — with an inline interactive 3D preview, one-tap font picker,
 * and automatic multi-colour 3MF packing into SlicePilot's slicer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ParametricCustomizeScreen(
    modelTitle: String,
    coverImageUrl: String? = null,
    parametric: ParametricInfo,
    customizer: MakerworldCustomizer,
    onBack: () -> Unit,
    onModel: (String) -> Unit,
    onSession: (access: String, refresh: String, uid: String) -> Unit = { _, _, _ -> },
) {
    var picked by remember(parametric) {
        mutableStateOf(if (parametric.sourceFiles.size == 1) parametric.sourceFiles.first() else null)
    }
    val info =
        remember(parametric, picked) {
            if (picked == null) parametric else parametric.forFile(picked!!)
        }

    var scad by remember(info) { mutableStateOf<String?>(null) }
    var items by remember(info) { mutableStateOf(listOf<ParamEdit>()) }
    var labels by remember(info) { mutableStateOf(mapOf<String, String>()) }
    var loadError by remember(info) { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var previewBusy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var showSource by remember { mutableStateOf(false) }
    var preview by remember(info) { mutableStateOf<GeneratedPreview?>(null) }
    var openWeb by remember(info, picked) {
        val hasCached = info.modelType == "scad" && customizer.cachedScad(info) != null
        mutableStateOf(
            (parametric.sourceFiles.size <= 1 || picked != null) &&
                ((info.isProtected && !hasCached) || info.modelType != "scad"),
        )
    }
    val scope = rememberCoroutineScope()

    fun currentEditedScad(): String? {
        val src = scad ?: return null
        items.forEach { it.commit() }
        return ScadCustomizer.apply(src, items.map { it.param })
    }

    // Track whether any edited value has diverged from the current 3D preview snapshot.
    val editSignature = items.joinToString("\u0000") { "${it.name}=${it.value}" }
    val previewIsCurrent =
        remember(preview, editSignature, scad) {
            val p = preview ?: return@remember false
            val src = scad ?: return@remember false
            val snapshot =
                ScadCustomizer.apply(
                    src,
                    items.map { edit ->
                        edit.commit()
                        edit.param
                    },
                )
            snapshot == p.scadSnapshot && File(p.filePath).exists()
        }

    fun adoptSource(
        text: String,
        closeWeb: Boolean,
    ) {
        scad = text
        loadError = null
        val parsed = ScadCustomizer.parse(text)
        items = ParamEdit.of(parsed)
        labels = items.associate { it.name to ScadCustomizer.label(text, it.name) }
        if (closeWeb) openWeb = false
    }

    suspend fun generateAndParsePreview(edited: String): GeneratedPreview =
        withContext(Dispatchers.IO) {
            val job = customizer.render(edited, info) { status = it }
            status = "Downloading preview…"
            val file = customizer.downloadMesh(job, "custom_${info.designId}.stl")
            val mesh =
                NativeLib.parsePreviewMesh(
                    file.absolutePath,
                    MeshDecimator.MAX_PREVIEW_TRIANGLES,
                ) ?: ModelParser.parse(file)
            val colors =
                if (file.extension.equals("3mf", ignoreCase = true) && NativeLib.isLoaded) {
                    val rawJson = runCatching { NativeLib.nativeInspectModel(file.absolutePath) }.getOrNull()
                    val inspected = MultiColourMapping.parseInspectJson(rawJson, mesh.triExtruders)
                    if (inspected?.isMultiColour == true) {
                        inspected.filaments.map { it.colorHex }
                    } else {
                        emptyList()
                    }
                } else {
                    emptyList()
                }
            GeneratedPreview(
                filePath = file.absolutePath,
                mesh = mesh,
                filamentColors = colors,
                scadSnapshot = edited,
            )
        }

    fun requestPreview() {
        if (busy || previewBusy) return
        val edited = currentEditedScad() ?: return
        previewBusy = true
        error = null
        status = "Generating 3D preview…"
        scope.launch {
            val res = runCatching { generateAndParsePreview(edited) }
            previewBusy = false
            res
                .onSuccess { preview = it }
                .onFailure { error = it.message ?: "Could not generate preview." }
        }
    }

    fun importJob(job: MakerworldCustomizer.Job) {
        if (busy) return
        busy = true
        error = null
        status = "Downloading custom model…"
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching { customizer.downloadMesh(job, "custom_${info.designId}.stl") }
                }
            busy = false
            result
                .onSuccess { file: File -> onModel(file.absolutePath) }
                .onFailure { error = it.message ?: "Could not download the generated model." }
        }
    }

    fun importUrl(
        dlUrl: String,
        filename: String,
    ) {
        if (busy) return
        busy = true
        error = null
        status = "Downloading custom model…"
        scope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    runCatching {
                        customizer.downloadFromUrl(dlUrl, filename.ifBlank { "custom_${info.designId}.stl" })
                    }
                }
            busy = false
            result
                .onSuccess { file: File -> onModel(file.absolutePath) }
                .onFailure { error = it.message ?: "Could not download the customized file." }
        }
    }

    // Multi-file picker (e.g. Single-line vs Multi-line keychain).
    if (parametric.sourceFiles.size > 1 && picked == null) {
        BackHandler(onBack = onBack)
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Choose version", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                )
            },
        ) { pad ->
            Column(
                Modifier
                    .padding(pad)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    "This design includes multiple customizable templates. Pick the one you want to personalize.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                parametric.sourceFiles.forEach { file ->
                    val displayName =
                        file.modelFileName
                            .ifBlank { file.modelName }
                            .substringBeforeLast('.')
                            .replace('_', ' ')
                            .replace('-', ' ')
                            .trim()
                            .ifBlank { file.modelName }
                    val subtitle =
                        buildString {
                            append(if (file.modelType == "f3d") "Fusion parametric" else "OpenSCAD parametric")
                            if (file.isProtected) append(" · MakerWorld editor")
                        }
                    SettingsRow(
                        icon = Icons.Filled.Tune,
                        title = displayName,
                        subtitle = subtitle,
                        onClick = { picked = file },
                    )
                }
            }
        }
        return
    }

    // In-app MakerWorld mobile customizer (used for protected designs, Fusion .f3d, or fallback).
    if (openWeb) {
        MakerWorldWebViewScreen(
            url = info.mobileUrl.ifBlank { info.customizerUrl },
            onBack = {
                if (scad != null) {
                    openWeb = false
                } else if (parametric.sourceFiles.size > 1) {
                    picked = null
                } else {
                    onBack()
                }
            },
            onCookies = { cookie -> customizer.setWebCookies(cookie) },
            onSession = { access, refresh, uid -> onSession(access, refresh, uid) },
            accessToken = customizer.accessToken,
            tokenPayload = { if (customizer.signedIn) customizer.appBridgePayload() else null },
            onSource = { text ->
                if (info.modelType == "scad") {
                    customizer.cacheScad(info, text)
                    adoptSource(text, closeWeb = !info.isProtected)
                }
            },
            onRequest = { body ->
                val code = runCatching { JSONObject(body).optString("code") }.getOrNull().orEmpty()
                val decoded =
                    runCatching {
                        String(
                            android.util.Base64.decode(code, android.util.Base64.DEFAULT),
                            Charsets.UTF_8,
                        )
                    }.getOrNull()
                if (decoded != null && decoded.length > 40 && decoded.contains("=")) {
                    Log.i(TAG, "harvested editor source (${decoded.length} chars)")
                    customizer.cacheScad(info, decoded)
                    customizer.cachePayload(info, body)
                    adoptSource(decoded, closeWeb = info.modelType == "scad")
                }
            },
            onGeneratedJob = { job -> importJob(job) },
            onDownloadUrl = { dlUrl, filename -> importUrl(dlUrl, filename) },
            onSwitchToNative =
                if (info.modelType == "scad" && scad != null && items.isNotEmpty()) {
                    { openWeb = false }
                } else {
                    null
                },
            importing = busy,
            bannerHint =
                if (info.modelType == "f3d") {
                    "Customize your model below, generate it, and tap Download to load into SlicePilot."
                } else {
                    "Edit your name or options below and tap Generate — then load it straight into SlicePilot."
                },
        )
        return
    }

    BackHandler {
        if (parametric.sourceFiles.size > 1) picked = null else onBack()
    }

    LaunchedEffect(info) {
        if (scad != null) return@LaunchedEffect
        val loaded =
            withContext(Dispatchers.IO) {
                runCatching { customizer.loadScad(info) }
            }
        loaded
            .onSuccess { src ->
                if (src == null) {
                    loadError =
                        if (info.isProtected) {
                            "This designer keeps the source private on MakerWorld. Open the in-app MakerWorld editor to customize it."
                        } else {
                            "Could not load the parameters directly — open the in-app MakerWorld editor instead."
                        }
                } else {
                    adoptSource(src, closeWeb = false)
                }
            }.onFailure {
                loadError = it.message ?: "Loading the parameter list failed."
            }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Customize", fontWeight = FontWeight.Bold, maxLines = 1)
                        if (modelTitle.isNotBlank()) {
                            Text(
                                modelTitle,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (parametric.sourceFiles.size > 1) picked = null else onBack()
                        },
                    ) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (items.isNotEmpty()) {
                        IconButton(
                            onClick = {
                                val src = scad ?: return@IconButton
                                items = ParamEdit.of(ScadCustomizer.parse(src))
                            },
                        ) {
                            Icon(Icons.Filled.Refresh, "Reset parameters")
                        }
                    }
                    IconButton(onClick = { openWeb = true }) {
                        Icon(Icons.Filled.OpenInBrowser, "Open MakerWorld editor")
                    }
                },
            )
        },
        bottomBar = {
            val src = scad
            if (src != null) {
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .navigationBarsPadding()
                                .padding(horizontal = Space.lg, vertical = Space.sm),
                        verticalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        error?.let {
                            Text(
                                it,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        BrandButton(
                            text =
                                when {
                                    busy -> status.ifBlank { "Generating…" }
                                    previewIsCurrent -> "Use preview & slice"
                                    else -> "Generate & slice"
                                },
                            onClick = {
                                val existing = preview
                                if (previewIsCurrent && existing != null) {
                                    onModel(existing.filePath)
                                    return@BrandButton
                                }
                                busy = true
                                error = null
                                status = "Sending to MakerWorld…"
                                scope.launch {
                                    val res =
                                        withContext(Dispatchers.IO) {
                                            runCatching {
                                                val edited = currentEditedScad() ?: src
                                                val job = customizer.render(edited, info) { status = it }
                                                status = "Downloading model…"
                                                customizer.downloadMesh(job, "custom_${info.designId}.stl")
                                            }
                                        }
                                    busy = false
                                    res
                                        .onSuccess { file: File -> onModel(file.absolutePath) }
                                        .onFailure {
                                            error = it.message ?: "MakerWorld could not generate the model."
                                        }
                                }
                            },
                            enabled = !busy && !previewBusy && items.isNotEmpty(),
                            loading = busy,
                            icon = { Icon(Icons.Filled.Build, null, Modifier.size(18.dp)) },
                            modifier = Modifier.fillMaxWidth(),
                            height = ButtonSize.Large,
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            // Pinned compact preview card above the scrollable form so the user can rotate
            // the live 3D model without scroll conflict while tweaking name, font, and size.
            if (scad != null && items.isNotEmpty()) {
                CustomizerPreviewCard(
                    coverImageUrl = coverImageUrl,
                    preview = preview,
                    previewIsCurrent = previewIsCurrent,
                    previewBusy = previewBusy,
                    status = status,
                    onGeneratePreview = { requestPreview() },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Space.md, vertical = Space.xs),
                )
            }

            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.md, vertical = Space.sm),
                verticalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                loadError?.let { msg ->
                    BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.md) {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                            Text(msg, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            BrandButton(
                                text = "Open MakerWorld editor",
                                onClick = { openWeb = true },
                                role = ButtonRole.Primary,
                                icon = { Icon(Icons.Filled.OpenInBrowser, null, Modifier.size(18.dp)) },
                                modifier = Modifier.fillMaxWidth(),
                                height = ButtonSize.Medium,
                            )
                        }
                    }
                }

                if (scad == null && loadError == null) {
                    BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.lg) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        ) {
                            WavyDots()
                            Spacer(Modifier.width(Space.xs))
                            Text(
                                "Loading customizable parameters…",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                val src = scad
                if (src != null) {
                    if (items.isEmpty()) {
                        BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.md) {
                            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                                Text(
                                    "No editable OpenSCAD parameters were found in this file. You can still customize it in MakerWorld's editor.",
                                    fontSize = 13.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                BrandButton(
                                    text = "Open MakerWorld editor",
                                    onClick = { openWeb = true },
                                    role = ButtonRole.Secondary,
                                    modifier = Modifier.fillMaxWidth(),
                                    height = ButtonSize.Medium,
                                )
                            }
                        }
                    } else {
                        val customFontToggle =
                            items.firstOrNull {
                                it.param is ScadCustomizer.Param.Bool &&
                                    it.name.lowercase().let { n ->
                                        n == "use_custom_font" ||
                                            n == "use_custom_fonts" ||
                                            n == "enable_custom_font"
                                    }
                            }
                        val hideCustomFontFields =
                            customFontToggle != null && !customFontToggle.value.equals("true", true)
                        val visibleItems =
                            items.filterNot { edit ->
                                hideCustomFontFields &&
                                    edit.param is ScadCustomizer.Param.Text &&
                                    edit.name.lowercase().let { n ->
                                        n.contains("custom_font") || n.contains("customfont")
                                    }
                            }
                        val primaryFontName =
                            visibleItems
                                .firstOrNull { (it.param as? ScadCustomizer.Param.Text)?.isFont == true }
                                ?.name
                        val sections = visibleItems.groupBy { it.section }
                        sections.forEach { (sectionName, sectionItems) ->
                            BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.md) {
                                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                                    if (sectionName.isNotBlank()) {
                                        BrandSectionLabel(sectionName)
                                    }
                                    sectionItems.forEach { edit ->
                                        ParameterControl(
                                            edit = edit,
                                            label = labels[edit.name] ?: ScadCustomizer.humanizeName(edit.name),
                                            showQuickFontChips = edit.name == primaryFontName,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    TextButton(onClick = { showSource = !showSource }) {
                        Icon(Icons.Filled.Code, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (showSource) "Hide OpenSCAD source" else "Inspect OpenSCAD source")
                    }
                    if (showSource) {
                        BrandCard(modifier = Modifier.fillMaxWidth(), contentPadding = Space.md) {
                            Text(
                                src.take(2500),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Space.lg))
            }
        }
    }
}

/**
 * Compact 180 dp live preview panel at the top of the Customize screen.
 * Shows the model's cover photo initially and switches to an interactive,
 * orbitable multi-colour 3D [ModelPreviewView] once the user taps Preview.
 */
@Composable
private fun CustomizerPreviewCard(
    coverImageUrl: String?,
    preview: GeneratedPreview?,
    previewIsCurrent: Boolean,
    previewBusy: Boolean,
    status: String,
    onGeneratePreview: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val glView =
        remember(preview != null) {
            if (preview != null) ModelPreviewView(context) else null
        }

    DisposableEffect(glView) {
        if (glView == null) return@DisposableEffect onDispose {}
        val obs =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> glView.onResume()
                    Lifecycle.Event.ON_PAUSE -> glView.onPause()
                    else -> Unit
                }
            }
        lifecycleOwner.lifecycle.addObserver(obs)
        glView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(obs)
            glView.destroy()
        }
    }

    LaunchedEffect(glView, preview) {
        val v = glView ?: return@LaunchedEffect
        val p = preview ?: return@LaunchedEffect
        v.setModel(p.mesh)
        if (p.filamentColors.size > 1) {
            v.setModelFilamentColors(
                p.filamentColors.map { hex ->
                    PreviewGeometry.parseColor(hex) ?: PreviewGeometry.DEFAULT_MODEL_COLOR
                },
            )
        } else {
            v.setModelFilamentColors(null)
        }
    }

    BrandCard(modifier = modifier, contentPadding = 8.dp) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        ) {
            if (preview != null && glView != null) {
                AndroidView(
                    factory = { glView },
                    modifier = Modifier.fillMaxSize(),
                )
                // Multi-colour swatch indicator when the preview carries 2+ colours.
                if (preview.filamentColors.size > 1) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.Black.copy(alpha = 0.62f))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                    ) {
                        preview.filamentColors.take(4).forEach { hex ->
                            SwatchDot(filamentColor(hex), modifier = Modifier.size(12.dp))
                        }
                        Text(
                            "${preview.filamentColors.size} colours",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                    }
                } else {
                    Text(
                        "Drag to rotate",
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier =
                            Modifier
                                .align(Alignment.TopStart)
                                .padding(8.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color.Black.copy(alpha = 0.55f))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                    )
                }
            } else if (!coverImageUrl.isNullOrBlank()) {
                RemoteThumbnail(
                    url = coverImageUrl,
                    modifier = Modifier.fillMaxSize(),
                    maxDimPx = 720,
                )
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.28f)),
                )
            }

            if (previewBusy) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.58f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        WavyDots()
                        Text(
                            status.ifBlank { "Generating 3D preview…" },
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White,
                        )
                    }
                }
            } else {
                val label =
                    when {
                        preview == null -> "Preview 3D"
                        !previewIsCurrent -> "Update preview"
                        else -> "Refresh"
                    }
                val highlighted = preview == null || !previewIsCurrent
                Surface(
                    onClick = onGeneratePreview,
                    shape = RoundedCornerShape(999.dp),
                    color =
                        if (highlighted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            Color.Black.copy(alpha = 0.65f)
                        },
                    contentColor =
                        if (highlighted) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            Color.White
                        },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                    ) {
                        Icon(
                            if (preview == null) Icons.Filled.Visibility else Icons.Filled.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(15.dp),
                        )
                        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterControl(
    edit: ParamEdit,
    label: String,
    showQuickFontChips: Boolean = false,
) {
    when (val p = edit.param) {
        is ScadCustomizer.Param.Text -> {
            val unquoted =
                if (p.isQuoted) ScadCustomizer.unquoteScadString(edit.value) else edit.value
            when {
                p.isFont -> {
                    FontPickerControl(
                        label = label,
                        selectedFont = unquoted,
                        showQuickChips = showQuickFontChips,
                        onSelectFont = { chosen ->
                            edit.value = if (p.isQuoted) ScadCustomizer.quoteScadString(chosen) else chosen
                        },
                    )
                }

                p.isColor -> {
                    ColorPickerControl(
                        label = label,
                        selectedHex = unquoted,
                        onSelectHex = { hex ->
                            edit.value = if (p.isQuoted) ScadCustomizer.quoteScadString(hex) else hex
                        },
                    )
                }

                else -> {
                    OutlinedTextField(
                        value = unquoted,
                        onValueChange = { nv ->
                            edit.value = if (p.isQuoted) ScadCustomizer.quoteScadString(nv) else nv
                        },
                        label = { Text(label) },
                        singleLine = p.isQuoted,
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        is ScadCustomizer.Param.Choice -> {
            val options =
                remember(p.options) {
                    p.options.map { opt ->
                        MenuOption(
                            label = opt.label,
                            value = opt.value,
                            swatch = filamentColor(opt.value.removeSurrounding("\"")),
                        )
                    }
                }
            DropdownRow(
                label = label,
                options = options,
                selected = edit.value,
                onSelect = { edit.value = it },
            )
        }

        is ScadCustomizer.Param.Slider -> {
            val numeric = edit.value.toDoubleOrNull() ?: p.min
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Text(
                        label,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = edit.value,
                        onValueChange = { nv ->
                            edit.value = nv.filter { it.isDigit() || it == '.' || it == '-' }
                        },
                        modifier = Modifier.width(96.dp),
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.sm),
                        keyboardOptions =
                            KeyboardOptions(
                                keyboardType = if (p.isInt) KeyboardType.Number else KeyboardType.Decimal,
                            ),
                        textStyle = MaterialTheme.typography.bodyMedium,
                    )
                }
                Slider(
                    value = numeric.toFloat().coerceIn(p.min.toFloat(), p.max.toFloat()),
                    onValueChange = { fv ->
                        edit.value = ScadCustomizer.formatSliderValue(fv.toDouble(), p.step, p.isInt)
                    },
                    valueRange = p.min.toFloat()..p.max.toFloat(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        is ScadCustomizer.Param.Number -> {
            OutlinedTextField(
                value = edit.value,
                onValueChange = { nv ->
                    edit.value = nv.filter { it.isDigit() || it == '.' || it == '-' }
                },
                label = { Text(label) },
                singleLine = true,
                shape = RoundedCornerShape(Radius.sm),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = if (p.isInt) KeyboardType.Number else KeyboardType.Decimal,
                    ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        is ScadCustomizer.Param.Bool -> {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Text(
                    label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = edit.value.equals("true", true),
                    onCheckedChange = { on -> edit.value = if (on) "true" else "false" },
                )
            }
        }

        is ScadCustomizer.Param.Raw -> Unit
    }
}

/**
 * Easy font selector: one-tap quick-pick chips for popular MakerWorld keychain fonts
 * plus a tappable row that opens a categorized, searchable [FontPickerDialog] (with custom entry).
 */
@Composable
private fun FontPickerControl(
    label: String,
    selectedFont: String,
    showQuickChips: Boolean = true,
    onSelectFont: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(Radius.sm)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.25f)), shape)
                    .clickable { showDialog = true }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.TextFields,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    selectedFont.ifBlank { "Choose a font…" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "Tap to browse all ${MAKERWORLD_FONTS.size}+ fonts",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Browse fonts",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        if (showQuickChips) {
            // One-tap popular keychain font chips right underneath the primary font selector.
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QUICK_FONTS.forEach { font ->
                    FilterChip(
                        selected = selectedFont.equals(font, ignoreCase = true),
                        onClick = { onSelectFont(font) },
                        label = { Text(font, fontSize = 12.sp) },
                    )
                }
            }
        }
    }

    if (showDialog) {
        FontPickerDialog(
            currentFont = selectedFont,
            onPick = { chosen ->
                onSelectFont(chosen)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun FontPickerDialog(
    currentFont: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var selectedStyle by remember { mutableStateOf("All") }

    val filtered =
        remember(query, selectedStyle) {
            val q = query.trim()
            MAKERWORLD_FONTS.filter { font ->
                (selectedStyle == "All" || font.style == selectedStyle) &&
                    (q.isEmpty() || font.name.contains(q, ignoreCase = true) || font.style.contains(q, ignoreCase = true))
            }
        }
    val trimmedQuery = query.trim()
    val exactMatch = trimmedQuery.isNotEmpty() && MAKERWORLD_FONTS.any { it.name.equals(trimmedQuery, ignoreCase = true) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = Space.md, vertical = Space.lg),
            contentAlignment = Alignment.Center,
        ) {
            BrandCard(
                modifier = Modifier.fillMaxWidth().fillMaxHeight(0.82f),
                contentPadding = Space.md,
                background = MaterialTheme.colorScheme.surface,
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Choose font",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search or type any Google Font…") },
                        leadingIcon = { Icon(Icons.Filled.Search, null) },
                        trailingIcon =
                            if (query.isNotEmpty()) {
                                {
                                    IconButton(onClick = { query = "" }) {
                                        Icon(Icons.Filled.Close, "Clear")
                                    }
                                }
                            } else {
                                null
                            },
                        singleLine = true,
                        shape = RoundedCornerShape(Radius.sm),
                        modifier = Modifier.fillMaxWidth(),
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        FONT_STYLES.forEach { style ->
                            FilterChip(
                                selected = selectedStyle == style,
                                onClick = { selectedStyle = style },
                                label = { Text(style, fontSize = 12.sp) },
                            )
                        }
                    }

                    if (trimmedQuery.isNotEmpty() && !exactMatch) {
                        BrandButton(
                            text = "Use custom font \"$trimmedQuery\"",
                            onClick = { onPick(trimmedQuery) },
                            role = ButtonRole.Tonal,
                            modifier = Modifier.fillMaxWidth(),
                            height = ButtonSize.Small,
                        )
                    }

                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        items(filtered, key = { it.name }) { font ->
                            val active = currentFont.equals(font.name, ignoreCase = true)
                            Row(
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(Radius.sm))
                                        .background(
                                            if (active) {
                                                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                                            } else {
                                                Color.Transparent
                                            },
                                        ).clickable { onPick(font.name) }
                                        .padding(horizontal = 12.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        font.name,
                                        fontSize = 15.sp,
                                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        font.style,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                if (active) {
                                    Icon(
                                        Icons.Filled.Check,
                                        contentDescription = "Selected",
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Tactile colour picker for OpenSCAD `// color` and `#RRGGBB` fields:
 * one-tap filament colour swatches inline plus a custom RGB/palette dialog on tap.
 */
@Composable
private fun ColorPickerControl(
    label: String,
    selectedHex: String,
    onSelectHex: (String) -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }
    val normalized = selectedHex.trim().uppercase()
    val presetName = FILAMENT_PRESET_COLORS.firstOrNull { it.hex.equals(normalized, ignoreCase = true) }?.name
    val activeColor = filamentColor(normalized) ?: MaterialTheme.colorScheme.outline
    val shape = RoundedCornerShape(Radius.sm)

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(shape)
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                    .border(BorderStroke(1.dp, LanternGold.copy(alpha = 0.25f)), shape)
                    .clickable { showDialog = true }
                    .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SwatchDot(activeColor, modifier = Modifier.size(28.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    presetName ?: normalized.ifBlank { "Pick colour" },
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Text(
                    if (presetName != null) "$normalized · Tap to customize" else "Tap for custom colour",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = "Choose colour",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp),
            )
        }

        // One-tap filament swatch strip right below the row.
        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FILAMENT_PRESET_COLORS.forEach { preset ->
                val isSelected = normalized.equals(preset.hex, ignoreCase = true)
                val c = filamentColor(preset.hex) ?: Color.Gray
                ColorSwatchButton(
                    color = c,
                    selected = isSelected,
                    contentDescription = preset.name,
                    onClick = { onSelectHex(preset.hex) },
                )
            }
        }
    }

    if (showDialog) {
        ColorPickerDialog(
            initialHex = normalized.ifBlank { "#FFFFFF" },
            onPick = { hex ->
                onSelectHex(hex)
                showDialog = false
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun ColorSwatchButton(
    color: Color,
    selected: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    sizeDp: Int = 34,
) {
    val luminance = 0.299f * color.red + 0.587f * color.green + 0.114f * color.blue
    val checkTint = if (luminance > 0.62f) Color.Black else Color.White
    Box(
        modifier =
            Modifier
                .size(sizeDp.dp)
                .clip(CircleShape)
                .background(color)
                .border(
                    width = if (selected) 2.5.dp else 1.dp,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    shape = CircleShape,
                ).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = contentDescription,
                tint = checkTint,
                modifier = Modifier.size((sizeDp * 0.52f).dp),
            )
        }
    }
}

@Composable
private fun ColorPickerDialog(
    initialHex: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val startColor = remember(initialHex) { filamentColor(initialHex) ?: Color.White }
    var red by remember { mutableIntStateOf((startColor.red * 255f).toInt().coerceIn(0, 255)) }
    var green by remember { mutableIntStateOf((startColor.green * 255f).toInt().coerceIn(0, 255)) }
    var blue by remember { mutableIntStateOf((startColor.blue * 255f).toInt().coerceIn(0, 255)) }
    val currentHex = String.format(java.util.Locale.US, "#%02X%02X%02X", red, green, blue)
    val currentColor = Color(red, green, blue)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Space.md, vertical = Space.lg),
            contentAlignment = Alignment.Center,
        ) {
            BrandCard(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = Space.md,
                background = MaterialTheme.colorScheme.surface,
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.md)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "Choose colour",
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = "Close",
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    // Live preview banner of the chosen colour.
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(Radius.sm))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                                .padding(12.dp),
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(currentColor)
                                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(12.dp)),
                        )
                        Column(Modifier.weight(1f)) {
                            val matchedName =
                                FILAMENT_PRESET_COLORS.firstOrNull { it.hex.equals(currentHex, true) }?.name
                            Text(
                                matchedName ?: "Custom colour",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                            Text(
                                currentHex,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    // Preset filament palette grid (5 per row).
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        FILAMENT_PRESET_COLORS.chunked(5).forEach { rowColors ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                rowColors.forEach { preset ->
                                    val c = filamentColor(preset.hex) ?: Color.Gray
                                    ColorSwatchButton(
                                        color = c,
                                        selected = currentHex.equals(preset.hex, ignoreCase = true),
                                        contentDescription = preset.name,
                                        sizeDp = 42,
                                        onClick = {
                                            red = (c.red * 255f).toInt().coerceIn(0, 255)
                                            green = (c.green * 255f).toInt().coerceIn(0, 255)
                                            blue = (c.blue * 255f).toInt().coerceIn(0, 255)
                                        },
                                    )
                                }
                            }
                        }
                    }

                    // Fine-tune RGB sliders.
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        RgbSliderRow("Red", red, Color(0xFFE53935)) { red = it }
                        RgbSliderRow("Green", green, Color(0xFF43A047)) { green = it }
                        RgbSliderRow("Blue", blue, Color(0xFF1E88E5)) { blue = it }
                    }

                    BrandButton(
                        text = "Use $currentHex",
                        onClick = { onPick(currentHex) },
                        role = ButtonRole.Primary,
                        modifier = Modifier.fillMaxWidth(),
                        height = ButtonSize.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun RgbSliderRow(
    label: String,
    value: Int,
    tint: Color,
    onValueChange: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(42.dp),
        )
        Slider(
            value = value.toFloat(),
            onValueChange = { onValueChange(it.toInt().coerceIn(0, 255)) },
            valueRange = 0f..255f,
            colors = SliderDefaults.colors(thumbColor = tint, activeTrackColor = tint),
            modifier = Modifier.weight(1f),
        )
        Text(
            value.toString(),
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(28.dp),
        )
    }
}
