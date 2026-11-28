package world.yemyat.slicepilot.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.cloud.PrintObject
import world.yemyat.slicepilot.cloud.PrintStatus
import world.yemyat.slicepilot.ui.theme.BrandCyan
import world.yemyat.slicepilot.ui.theme.BrandSectionLabel
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius

/**
 * Mid-print object cancellation sheet for Klipper/COSMOS printers running `[exclude_object]`.
 * Renders a top-down bed footprint map (when polygon/center coordinates are present) alongside
 * the list of parts so the user can identify and cancel a failed part without aborting the job.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkipPartSheet(
    status: PrintStatus,
    onExclude: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var pendingSkip by remember { mutableStateOf<PrintObject?>(null) }

    val remainingCount = status.objects.count { it.name !in status.excludedObjects }

    pendingSkip?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingSkip = null },
            title = { Text("Skip this part?") },
            text = {
                Text(
                    "The printer will stop printing \"${target.name}\" and continue with the remaining parts. " +
                        "A skipped part cannot be resumed during this print.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingSkip = null
                        onExclude(target.name)
                    },
                ) {
                    Text("Skip part", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSkip = null }) { Text("Keep printing") }
            },
        )
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { androidx.compose.material3.BottomSheetDefaults.DragHandle(color = LanternGold.copy(alpha = 0.45f)) },
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    Icons.Filled.LayersClear,
                    contentDescription = null,
                    tint = LanternGold,
                    modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text("Skip a part", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "$remainingCount of ${status.objects.size} parts active",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                "Cancel a detached or failed part without stopping the rest of the build plate.",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val hasGeometry = status.objects.any { it.polygon.isNotEmpty() || it.center != null }
            if (hasGeometry) {
                BrandSectionLabel("Build plate map")
                ExcludeObjectBedMap(
                    objects = status.objects,
                    excluded = status.excludedObjects,
                    current = status.currentObject,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.35f),
                )
            }

            BrandSectionLabel("Parts")
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                status.objects.forEachIndexed { idx, obj ->
                    val isExcluded = obj.name in status.excludedObjects
                    val isCurrent = obj.name == status.currentObject
                    val canSkip = !isExcluded && remainingCount > 1

                    Surface(
                        shape = RoundedCornerShape(Radius.sm),
                        color =
                            when {
                                isExcluded -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                                isCurrent -> BrandCyan.copy(alpha = 0.12f)
                                else -> MaterialTheme.colorScheme.surfaceVariant
                            },
                        border =
                            BorderStroke(
                                1.dp,
                                when {
                                    isCurrent -> BrandCyan.copy(alpha = 0.5f)
                                    isExcluded -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
                                    else -> LanternGold.copy(alpha = 0.18f)
                                },
                            ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(26.dp)
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            when {
                                                isExcluded -> MaterialTheme.colorScheme.error.copy(alpha = 0.16f)
                                                isCurrent -> BrandCyan.copy(alpha = 0.22f)
                                                else -> LanternGold.copy(alpha = 0.2f)
                                            },
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    "${idx + 1}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color =
                                        when {
                                            isExcluded -> MaterialTheme.colorScheme.error
                                            isCurrent -> BrandCyan
                                            else -> LanternGold
                                        },
                                )
                            }
                            Column(Modifier.weight(1f)) {
                                Text(
                                    obj.name,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    textDecoration = if (isExcluded) TextDecoration.LineThrough else TextDecoration.None,
                                    color =
                                        if (isExcluded) {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        } else {
                                            MaterialTheme.colorScheme.onSurface
                                        },
                                )
                                val subtitle =
                                    when {
                                        isExcluded -> "Skipped"
                                        isCurrent -> "Printing now"
                                        obj.center != null ->
                                            "X ${obj.center.first.toInt()} mm · Y ${obj.center.second.toInt()} mm"
                                        else -> "Active"
                                    }
                                Text(
                                    subtitle,
                                    fontSize = 11.sp,
                                    color =
                                        when {
                                            isExcluded -> MaterialTheme.colorScheme.error
                                            isCurrent -> BrandCyan
                                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )
                            }
                            if (isExcluded) {
                                Icon(
                                    Icons.Filled.Block,
                                    contentDescription = "Skipped",
                                    tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f),
                                    modifier = Modifier.size(18.dp),
                                )
                            } else {
                                OutlinedButton(
                                    onClick = { pendingSkip = obj },
                                    enabled = canSkip,
                                    shape = RoundedCornerShape(Radius.sm),
                                    border =
                                        BorderStroke(
                                            1.dp,
                                            if (canSkip) {
                                                MaterialTheme.colorScheme.error.copy(alpha = 0.65f)
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            },
                                        ),
                                ) {
                                    Text(
                                        "Skip",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color =
                                            if (canSkip) {
                                                MaterialTheme.colorScheme.error
                                            } else {
                                                MaterialTheme.colorScheme.onSurfaceVariant
                                            },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (remainingCount <= 1) {
                Text(
                    "Only one active part remains — use Stop print if you want to cancel the entire job.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun ExcludeObjectBedMap(
    objects: List<PrintObject>,
    excluded: Set<String>,
    current: String?,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f)
    val errorColor = MaterialTheme.colorScheme.error
    val bgColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)

    // Compute bed bounds (at least a 256x256 mm build plate, expanded if any coordinate exceeds it).
    val allPoints =
        remember(objects) {
            buildList {
                for (o in objects) {
                    o.center?.let { add(it) }
                    addAll(o.polygon)
                }
            }
        }
    val maxCoord = remember(allPoints) {
        val m = allPoints.maxOfOrNull { maxOf(it.first, it.second) } ?: 256f
        maxOf(256f, m + 12f)
    }

    Surface(
        shape = RoundedCornerShape(Radius.md),
        color = bgColor,
        border = BorderStroke(1.dp, LanternGold.copy(alpha = 0.22f)),
        modifier = modifier,
    ) {
        Canvas(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(12.dp),
        ) {
            val side = minOf(size.width, size.height)
            val ox = (size.width - side) / 2f
            val oy = (size.height - side) / 2f

            fun mapPt(pt: Pair<Float, Float>): Offset {
                val nx = (pt.first / maxCoord).coerceIn(0f, 1f)
                val ny = 1f - (pt.second / maxCoord).coerceIn(0f, 1f)
                return Offset(ox + nx * side, oy + ny * side)
            }

            // Light 4x4 plate grid
            for (i in 0..4) {
                val f = i / 4f
                drawLine(gridColor, Offset(ox + f * side, oy), Offset(ox + f * side, oy + side), strokeWidth = 1f)
                drawLine(gridColor, Offset(ox, oy + f * side), Offset(ox + side, oy + f * side), strokeWidth = 1f)
            }

            for (obj in objects) {
                val isExcluded = obj.name in excluded
                val isCurrent = obj.name == current
                val fillColor: Color =
                    when {
                        isExcluded -> errorColor.copy(alpha = 0.16f)
                        isCurrent -> BrandCyan.copy(alpha = 0.36f)
                        else -> LanternGold.copy(alpha = 0.26f)
                    }
                val strokeColor: Color =
                    when {
                        isExcluded -> errorColor.copy(alpha = 0.7f)
                        isCurrent -> BrandCyan
                        else -> LanternGold
                    }
                if (obj.polygon.size >= 3) {
                    val path = Path().apply {
                        val first = mapPt(obj.polygon.first())
                        moveTo(first.x, first.y)
                        for (i in 1 until obj.polygon.size) {
                            val p = mapPt(obj.polygon[i])
                            lineTo(p.x, p.y)
                        }
                        close()
                    }
                    drawPath(path, color = fillColor)
                    drawPath(path, color = strokeColor, style = Stroke(width = if (isCurrent) 3f else 2f))
                } else if (obj.center != null) {
                    val c = mapPt(obj.center)
                    drawCircle(fillColor, radius = 14f, center = c)
                    drawCircle(strokeColor, radius = 14f, center = c, style = Stroke(width = 2.5f))
                }
            }
        }
    }
}
