package world.yemyat.slicepilot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.BrandSectionLabel
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.DropdownRow
import world.yemyat.slicepilot.ui.theme.MenuOption
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.SegmentedControl
import world.yemyat.slicepilot.ui.theme.Space
import world.yemyat.slicepilot.ui.theme.SwitchRow
import world.yemyat.slicepilot.ui.theme.filamentColor
import java.util.Locale

/**
 * The per-print slicer settings, in one sheet.
 *
 * `SliceConfig` carries ~30 settings and `startSlice` turns almost all of them into native
 * overrides (`layer_height`, `wall_loops`, `top_shell_layers`, `sparse_infill_density`,
 * `brim_width`, `seam_position`, `ironing_type`, the speeds, the support block). The slice step
 * used to render them as three hand-built cards behind a "Custom slice settings" switch, which
 * meant the settings were only reachable once you had already left Print setup - while Print
 * setup itself offered only the Draft/Standard/Quality tier, and Home offered a third copy in
 * "Configure print". One sheet now holds them all, and both screens open it:
 * Print setup before you slice, the slice step instead of its old cards.
 *
 * The preset rows from Home's sheet are deliberately not repeated here - printer, process and
 * filament are already on the Print setup screen above it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsSheet(
    vm: AppViewModel,
    onDismiss: () -> Unit,
) {
    val config by vm.config.collectAsState()
    val quality by vm.setupQuality.collectAsState()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val supportModified = config.supportEnabled
    val strengthModified =
        config.wallLoops != null || config.infillDensity != null || config.infillPattern != null
    val surfacesModified =
        config.layerHeight != null ||
            config.topShellLayers != null ||
            config.bottomShellLayers != null ||
            config.ironing
    val adhesionModified = config.adhesion != "auto" || config.seamPosition != "aligned"
    val speedsModified =
        config.wallSpeed != null || config.infillSpeed != null || config.travelSpeed != null

    val supportSummary =
        if (config.supportEnabled) {
            "On · ${labelOf(config.supportStyle)}" +
                if (config.supportOnPlateOnly) " · plate only" else ""
        } else {
            "Off"
        }
    val strengthSummary =
        listOfNotNull(
            config.wallLoops?.let { "$it walls" },
            config.infillDensity?.let { "$it% infill" },
            config.infillPattern?.let(::labelOf),
        ).ifEmpty { listOf("Preset defaults") }.joinToString(" · ")
    val surfacesSummary =
        listOfNotNull(
            config.layerHeight?.let(::mm),
            config.topShellLayers?.let { "$it top" },
            config.bottomShellLayers?.let { "$it bottom" },
            if (config.ironing) "Ironing" else null,
        ).ifEmpty { listOf("Preset defaults") }.joinToString(" · ")
    val adhesionSummary =
        listOf(
            when (config.adhesion) {
                "brim" -> "Brim ${config.brimWidth ?: 5} mm"
                "skirt" -> "Skirt"
                "raft" -> "Raft"
                "none" -> "No adhesion"
                else -> "Auto"
            },
            SeamOptions[config.seamPosition] ?: SeamDefault,
        ).joinToString(" · ")
    val speedsSummary =
        listOfNotNull(
            config.wallSpeed?.let { "Wall $it" },
            config.infillSpeed?.let { "Infill $it" },
            config.travelSpeed?.let { "Travel $it" },
        ).ifEmpty { listOf("Preset defaults") }.joinToString(" · ")

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
                    .padding(horizontal = Space.lg, vertical = Space.sm)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Advanced settings", style = MaterialTheme.typography.titleLarge)
                    val preset = vm.printSetupProcess(quality)
                    Text(
                        text =
                            "Overrides the $quality preset" +
                                (if (preset == null) "" else " ($preset)") +
                                ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { resetOverrides(vm) }) {
                    Text("Reset")
                }
            }

            ExpandableFieldCard(
                label = "Support",
                summary = supportSummary,
                modified = supportModified,
                initiallyExpanded = supportModified,
            ) {
                SwitchRow(
                    icon = Icons.Filled.Build,
                    title = "Support",
                    subtitle = supportSummary,
                    checked = config.supportEnabled,
                    onCheckedChange = { vm.setSupportEnabled(it) },
                )
                if (config.supportEnabled) {
                    OptionRow(
                        label = "Style",
                        options = SupportStyles.map(::labelOf),
                        current = labelOf(config.supportStyle),
                        onSelect = { vm.setSupportStyle(it.lowercase()) },
                    )
                    // CC2 prints supports from the second nozzle's spool, which also swaps the
                    // machine profile to the multi-material variant. The list only exists when the
                    // printer reported one, so the field appears and disappears with it.
                    if (vm.supportMaterialAvailable()) {
                        val materials = remember(config.printer) { vm.printSetupMaterials() }
                        val options = listOf<MaterialOption?>(null) + materials
                        DropdownRow(
                            label = "Support material",
                            options = options.map { MenuOption(materialLabel(it), it, filamentColor(it?.colorHex)) },
                            selected =
                                options.firstOrNull {
                                    it?.profile == config.supportFilamentPreset &&
                                        it?.trayId == config.supportFilamentTrayId
                                },
                            onSelect = { vm.setSupportMaterial(it) },
                        )
                    }
                    StepperRow(
                        label = "Threshold angle",
                        options = SupportAngles,
                        current = config.supportThresholdAngle,
                        format = { "$it\u00b0" },
                    ) { vm.setSupportThresholdAngle(it) }
                    StepperRow(
                        label = "Nozzle Z distance",
                        options = SupportZDistances,
                        current = config.supportZDistance,
                        format = { mm(it) },
                    ) { vm.setSupportZDistance(it) }
                    SwitchRow(
                        icon = Icons.Filled.Build,
                        title = "On build plate only",
                        subtitle =
                            if (config.supportOnPlateOnly) {
                                "Supports the bed, not the model"
                            } else {
                                "Supports overhangs anywhere"
                            },
                        checked = config.supportOnPlateOnly,
                        onCheckedChange = { vm.setSupportOnPlateOnly(it) },
                    )
                }
            }

            ExpandableFieldCard(
                label = "Strength",
                summary = strengthSummary,
                modified = strengthModified,
                initiallyExpanded = strengthModified,
            ) {
                StepperRow(
                    label = "Wall loops",
                    options = WallLoops,
                    current = config.wallLoops,
                    format = { it.toString() },
                ) { vm.setWallLoops(it) }
                StepperRow(
                    label = "Infill density",
                    options = InfillPercents,
                    current = config.infillDensity,
                    format = { "$it%" },
                ) { vm.setInfillDensity(it) }
                DropdownRow(
                    label = "Infill pattern",
                    options = InfillPatterns.map { MenuOption(labelOf(it), it) },
                    selected = config.infillPattern,
                    onSelect = { vm.setInfillPattern(it) },
                    placeholder = "Preset",
                )
            }

            ExpandableFieldCard(
                label = "Surfaces",
                summary = surfacesSummary,
                modified = surfacesModified,
                initiallyExpanded = surfacesModified,
            ) {
                StepperRow(
                    label = "Layer height",
                    options = LayerHeights,
                    current = config.layerHeight,
                    format = { mm(it) },
                ) { vm.setLayerHeight(it) }
                StepperRow(
                    label = "Top shells",
                    options = ShellLayers,
                    current = config.topShellLayers,
                    format = { it.toString() },
                ) { vm.setTopShellLayers(it) }
                StepperRow(
                    label = "Bottom shells",
                    options = ShellLayers,
                    current = config.bottomShellLayers,
                    format = { it.toString() },
                ) { vm.setBottomShellLayers(it) }
                SwitchRow(
                    icon = Icons.Filled.Build,
                    title = "Top surface ironing",
                    subtitle = if (config.ironing) "On" else "Off",
                    checked = config.ironing,
                    onCheckedChange = { vm.setIroning(it) },
                )
            }

            ExpandableFieldCard(
                label = "Adhesion & seam",
                summary = adhesionSummary,
                modified = adhesionModified,
                initiallyExpanded = adhesionModified,
            ) {
                DropdownRow(
                    label = "Adhesion",
                    options = AdhesionOptions.map { MenuOption(labelOf(it), it) },
                    selected = config.adhesion,
                    onSelect = { vm.setAdhesion(it) },
                )
                if (config.adhesion == "brim") {
                    StepperRow(
                        label = "Brim width",
                        options = BrimWidths,
                        current = config.brimWidth,
                        format = { "$it mm" },
                    ) { vm.setBrimWidth(it) }
                }
                DropdownRow(
                    label = "Seam position",
                    options = SeamOptions.entries.map { (key, label) -> MenuOption(label, key) },
                    selected = config.seamPosition,
                    onSelect = { vm.setSeamPosition(it) },
                    placeholder = SeamDefault,
                )
            }

            ExpandableFieldCard(
                label = "Speeds",
                summary = speedsSummary,
                modified = speedsModified,
                initiallyExpanded = speedsModified,
            ) {
                StepperRow(
                    label = "Wall speed",
                    options = WallSpeeds,
                    current = config.wallSpeed,
                    format = { "$it mm/s" },
                ) { vm.setWallSpeed(it) }
                StepperRow(
                    label = "Infill speed",
                    options = InfillSpeeds,
                    current = config.infillSpeed,
                    format = { "$it mm/s" },
                ) { vm.setInfillSpeed(it) }
                StepperRow(
                    label = "Travel speed",
                    options = TravelSpeeds,
                    current = config.travelSpeed,
                    format = { "$it mm/s" },
                ) { vm.setTravelSpeed(it) }
            }

            Spacer(Modifier.height(Space.xs))
            BrandButton(
                text = "Done",
                onClick = onDismiss,
                role = ButtonRole.Primary,
                height = ButtonSize.Large,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Space.sm))
        }
    }
}

/**
 * Concise summary of active overrides shown under the "Advanced settings" row on Print setup.
 * Reports only what actually diverges from the preset (instead of repeating "Preset …" five times).
 */
fun advancedSummary(config: SliceConfig): String {
    val overrides =
        listOfNotNull(
            config.layerHeight?.let(::mm),
            config.wallLoops?.let { "$it walls" },
            config.infillDensity?.let { "$it% infill" },
            config.infillPattern?.let(::labelOf),
            if (config.ironing) "Ironing" else null,
            when (config.adhesion) {
                "brim" -> "Brim ${config.brimWidth ?: 5} mm"
                "skirt" -> "Skirt"
                "raft" -> "Raft"
                "none" -> "No adhesion"
                else -> null
            },
            if (config.seamPosition != "aligned") SeamOptions[config.seamPosition] else null,
            if (config.wallSpeed != null || config.infillSpeed != null || config.travelSpeed != null) {
                "Custom speeds"
            } else {
                null
            },
            if (config.supportEnabled) "Support ${labelOf(config.supportStyle)}" else null,
        )
    return if (overrides.isEmpty()) {
        "Using preset defaults"
    } else {
        "${overrides.size} ${if (overrides.size == 1) "override" else "overrides"} · ${overrides.joinToString(" · ")}"
    }
}

/** Back to "the preset decides everything": nulls are dropped from prefs and the override list. */
private fun resetOverrides(vm: AppViewModel) {
    val config = vm.config.value
    vm.setLayerHeight(null)
    vm.setWallLoops(null)
    vm.setTopShellLayers(null)
    vm.setBottomShellLayers(null)
    vm.setInfillDensity(null)
    vm.setInfillPattern(null)
    vm.setAdhesion("auto")
    vm.setBrimWidth(null)
    vm.setSeamPosition("aligned")
    vm.setIroning(false)
    vm.setWallSpeed(null)
    vm.setInfillSpeed(null)
    vm.setTravelSpeed(null)
    // Support stays as it was: switching it is a decision, not a default to clear.
    vm.setSupportStyle(config.supportStyle)
}

// The value lists the slicer presets actually use. A stepper walks these instead of a free-text
// field, because the accepted values are a fixed ladder and a typed "0.175" is a typo generator.
// The ranges are the ones the slice step used to draw with sliders (walls 1-6, shells 1-8,
// speeds 20-300/20-400/50-600, support angle 10-80, Z 0.05-0.40), kept as rungs.
private val LayerHeights = listOf(0.08f, 0.10f, 0.12f, 0.16f, 0.20f, 0.24f, 0.28f, 0.32f)
private val ShellLayers = listOf(1, 2, 3, 4, 5, 6, 7, 8)
private val WallLoops = listOf(1, 2, 3, 4, 5, 6)
private val InfillPercents = listOf(3, 5, 8, 10, 12, 15, 20, 25, 30, 40, 50, 60, 80, 100)
private val WallSpeeds = listOf(20, 40, 60, 80, 100, 120, 150, 180, 200, 250, 300)
private val InfillSpeeds = listOf(20, 40, 60, 80, 100, 120, 150, 180, 200, 250, 300, 350, 400)
private val TravelSpeeds = listOf(50, 80, 100, 120, 150, 200, 250, 300, 350, 400, 500, 600)
private val BrimWidths = listOf(2, 3, 4, 5, 6, 8, 10, 12, 15)
private val SupportAngles = listOf(10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80)
private val SupportZDistances = listOf(0.05f, 0.10f, 0.15f, 0.20f, 0.25f, 0.30f, 0.35f, 0.40f)

private val InfillPatterns =
    listOf(
        "grid",
        "gyroid",
        "lines",
        "triangles",
        "honeycomb",
        "cubic",
        "concentric",
        "lightning",
    )

private val AdhesionOptions = listOf("auto", "brim", "skirt", "raft", "none")

// The native names, and the words shown for them - "aligned_back" cannot go on screen as-is.
private val SeamOptions =
    linkedMapOf(
        "aligned" to "Aligned",
        "aligned_back" to "Aligned back",
        "back" to "Back",
        "nearest" to "Nearest",
        "random" to "Random",
    )

private val SeamDefault = "Aligned"
private val SupportStyles = listOf("grid", "snug", "trees")

private fun mm(v: Float): String = String.format(Locale.US, "%.2f mm", v)

private fun labelOf(value: String): String = value.replaceFirstChar { it.uppercaseChar() }

/** A spool as the app writes it everywhere: "PLA · Tray 2", or "Same as model" for no spool. */
private fun materialLabel(option: MaterialOption?): String =
    when {
        option == null -> "Same as model"
        option.trayId != null -> "${option.label} \u00b7 Tray ${option.trayId + 1}"
        else -> option.label
    }

/** Collapsible category card inside [AdvancedSettingsSheet] with an active-override dot + summary. */
@Composable
private fun ExpandableFieldCard(
    label: String,
    summary: String,
    modified: Boolean,
    initiallyExpanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    val interactionSource = remember { MutableInteractionSource() }
    BrandCard(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        contentPadding = 0.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = interactionSource,
                            indication = LocalIndication.current,
                            onClick = { expanded = !expanded },
                        ).padding(horizontal = Space.md, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        BrandSectionLabel(label)
                        if (modified) {
                            Box(
                                Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.primary),
                            )
                        }
                    }
                    Text(
                        summary,
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Collapse $label" else "Expand $label",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(start = Space.md, end = Space.md, bottom = Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.sm),
                    content = content,
                )
            }
        }
    }
}

@Composable
private fun <T> StepperRow(
    label: String,
    options: List<T>,
    current: T?,
    format: (T) -> String,
    onPick: (T) -> Unit,
) {
    val index = if (current != null && current in options) options.indexOf(current) else -1
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(
            label,
            modifier = Modifier.weight(1f),
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Row(
            modifier =
                Modifier
                    .clip(RoundedCornerShape(Radius.md))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                    .padding(horizontal = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StepButton(Icons.Filled.Remove) { onPick(options[stepIndex(index, -1, options.size)]) }
            Text(
                text = current?.let(format) ?: "Preset",
                modifier = Modifier.widthIn(min = 62.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            StepButton(Icons.Filled.Add) { onPick(options[stepIndex(index, +1, options.size)]) }
        }
    }
}

/** Next index on the ladder; an unset field (-1) lands on the middle value. */
private fun stepIndex(
    index: Int,
    delta: Int,
    size: Int,
): Int = if (index < 0) size / 2 else (index + delta).coerceIn(0, size - 1)

@Composable
private fun StepButton(
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = null,
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun OptionRow(
    label: String,
    options: List<String>,
    current: String?,
    onSelect: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        BrandSectionLabel(label)
        SegmentedControl(
            options = options,
            selectedIndex = options.indexOf(current).coerceAtLeast(0),
            onSelect = { onSelect(options[it]) },
        )
    }
}
