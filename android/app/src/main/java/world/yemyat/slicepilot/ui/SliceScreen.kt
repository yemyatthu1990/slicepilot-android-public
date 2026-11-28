package world.yemyat.slicepilot.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.DetailRow
import world.yemyat.slicepilot.ui.theme.SettingsRow
import world.yemyat.slicepilot.ui.theme.Space

/**
 * The slice step: what was picked, and the only place the slicer's own settings live.
 *
 * This screen used to draw those settings as three hand-built cards - Support, Advanced, Speed -
 * with a "Custom slice settings" switch gating a wall of sliders, while Print setup offered the
 * quality preset and Home offered presets. Same `SliceConfig`, three UIs, three looks. The cards
 * and the switch are gone: the summary stays (as a [BrandCard] of [DetailRow]s), and the settings
 * open into the one [AdvancedSettingsSheet]. The switch had nothing left to say - clearing every
 * override, which the sheet's Reset does, *is* "follow the quality preset".
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SliceScreen(vm: AppViewModel) {
    val config by vm.config.collectAsState()
    val mesh by vm.mesh.collectAsState()
    val quality by vm.setupQuality.collectAsState()
    val plate by vm.setupPlate.collectAsState()
    val material by vm.setupMaterial.collectAsState()
    val printer by vm.selectedPrinterState.collectAsState()
    val nozzle by vm.selectedNozzle.collectAsState()
    var showAdvanced by remember { mutableStateOf(false) }

    val multiAssignments = remember(config.printer) { vm.activeMultiColourAssignments() }
    val materialLabel =
        remember(material, multiAssignments) {
            if (multiAssignments.size > 1) {
                "Multi-colour (${multiAssignments.count { it.used }.coerceAtLeast(multiAssignments.size)} AMS spools)"
            } else {
                material?.let { key ->
                    vm.printSetupMaterials().firstOrNull { it.key == key }?.label
                } ?: "—"
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Slice", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { vm.goBack() }) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier =
                Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            BrandCard {
                Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                    DetailRow(
                        label = "Printer",
                        value =
                            printer?.let {
                                "${it.name} · ${it.model}${if (it.isLocal) " (Local)" else ""}"
                            } ?: "—",
                    )
                    DetailRow(label = "Nozzle", value = "$nozzle mm")
                    DetailRow(label = "Material", value = materialLabel)
                    DetailRow(label = "Build plate", value = plate)
                    DetailRow(label = "Quality", value = quality)
                }
            }

            SettingsRow(
                icon = Icons.Filled.Tune,
                title = "Advanced settings",
                subtitle = advancedSummary(config),
                onClick = { showAdvanced = true },
            )

            BrandButton(
                text = "Slice",
                onClick = { vm.startSlice(bedType = vm.bedTypeForPlate(plate), autoPrint = false) },
                enabled = vm.modelPath != null && printer != null && material != null && mesh != null,
                icon = {
                    Icon(
                        Icons.Filled.ContentCut,
                        contentDescription = null,
                        tint = Color.White,
                    )
                },
                modifier = Modifier.fillMaxWidth(),
                height = ButtonSize.Large,
            )
        }
    }

    if (showAdvanced) {
        AdvancedSettingsSheet(
            vm = vm,
            onDismiss = { showAdvanced = false },
        )
    }
}
