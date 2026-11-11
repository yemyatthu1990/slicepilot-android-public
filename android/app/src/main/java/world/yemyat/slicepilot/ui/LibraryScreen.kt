package world.yemyat.slicepilot.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import world.yemyat.slicepilot.repo.FavoriteModel
import world.yemyat.slicepilot.repo.FavoritesStore
import world.yemyat.slicepilot.ui.theme.BrandButton
import world.yemyat.slicepilot.ui.theme.BrandCard
import world.yemyat.slicepilot.ui.theme.BrandIconButton
import world.yemyat.slicepilot.ui.theme.ButtonRole
import world.yemyat.slicepilot.ui.theme.ButtonSize
import world.yemyat.slicepilot.ui.theme.LanternGold
import world.yemyat.slicepilot.ui.theme.Radius
import world.yemyat.slicepilot.ui.theme.SegmentedControl
import java.io.File
import java.util.Date

/**
 * The Files tab: everything the app keeps on this device, in two shelves —
 * the models you saved and the pictures the printer's camera took.
 *
 * The shelves are a segmented control rather than two chips because the tab has
 * exactly two states-worth of content and nothing else to navigate. Each segment
 * carries its own count, so the tab says what is inside — before and after —
 * without a second heading repeating it per shelf.
 */
@Composable
fun LibraryScreen(
    vm: AppViewModel,
    favorites: FavoritesStore,
    onFindModels: () -> Unit,
) {
    val tab by vm.libraryTab.collectAsState()
    val entries by favorites.entries.collectAsState()
    val photos by vm.photos.collectAsState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(6.dp))
        Text(
            "Files",
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )

        SegmentedShelf(
            selected = tab,
            onSelect = { vm.selectLibraryTab(it) },
            counts =
                mapOf(
                    LibraryTab.MODELS to entries.size,
                    LibraryTab.PHOTOS to photos.size,
                ),
        )

        when (tab) {
            LibraryTab.MODELS -> {
                SavedModels(
                    vm = vm,
                    favorites = favorites,
                    entries = entries,
                    onFindModels = onFindModels,
                )
            }

            LibraryTab.PHOTOS -> {
                CameraPhotos(vm = vm)
            }
        }
    }
}

/**
 * Models | Photos as one control. Each half carries its own count, so the tab says
 * what is inside without opening either shelf.
 */
@Composable
private fun SegmentedShelf(
    selected: LibraryTab,
    onSelect: (LibraryTab) -> Unit,
    counts: Map<LibraryTab, Int>,
) {
    val shelves = LibraryTab.values().toList()
    SegmentedControl(
        options = shelves.map { "${it.label} · ${counts[it] ?: 0}" },
        selectedIndex = shelves.indexOf(selected),
        onSelect = { onSelect(shelves[it]) },
    )
}

/** The "Models" shelf: the saved models list, or the reason it is empty. */
@Composable
private fun SavedModels(
    vm: AppViewModel,
    favorites: FavoritesStore,
    entries: List<FavoriteModel>,
    onFindModels: () -> Unit,
) {
    var confirmRemove by remember { mutableStateOf<FavoriteModel?>(null) }

    val removing = confirmRemove
    if (removing != null) {
        AlertDialog(
            onDismissRequest = { confirmRemove = null },
            title = { Text("Remove from My models?") },
            text = { Text("\"${removing.name}\" will be deleted from the app.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = null
                    favorites.remove(removing.id)
                }) { Text("Remove") }
            },
            dismissButton = {
                TextButton(onClick = { confirmRemove = null }) { Text("Keep") }
            },
        )
    }

    if (entries.isEmpty()) {
        EmptyShelf(
            icon = Icons.Filled.CollectionsBookmark,
            title = "Nothing kept yet",
            body = "Star a model's download button in the feed to save it here.",
            action = "Find models" to onFindModels,
        )
    } else {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(entries, key = { it.id }) { entry ->
                ModelRow(
                    entry = entry,
                    onOpen = { vm.requestLoadModel(entry.path) },
                    onRemove = { confirmRemove = entry },
                )
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
    }
}

/** One saved model: thumbnail, name, where it came from, and its size and date. */
@Composable
private fun ModelRow(
    entry: FavoriteModel,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val missing = !File(entry.path).exists()
    val shape = RoundedCornerShape(Radius.md)
    val thumb =
        remember(entry.thumbPath) {
            entry.thumbPath
                ?.takeIf { File(it).exists() }
                ?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
                .border(
                    BorderStroke(1.dp, LanternGold.copy(alpha = if (missing) 0.12f else 0.22f)),
                    shape,
                ).clickable(enabled = !missing, onClick = onOpen)
                .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(Radius.sm))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            val img: ImageBitmap? = thumb
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    Icons.Filled.FolderOpen,
                    null,
                    modifier = Modifier.size(22.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }

        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                entry.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (missing) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                if (missing) {
                    "File missing — download it again"
                } else {
                    "${entry.source.replaceFirstChar { it.uppercase() }} · " +
                        "${fileSize(entry.sizeBytes)} · ${fileDate(entry.addedAt)}"
                },
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color =
                    if (missing) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }

        // Destructive rather than a plain grey circle: the row's own tap opens the
        // model, so the second tap has to say out loud that it deletes.
        BrandIconButton(
            icon = Icons.Filled.Delete,
            contentDescription = "Remove",
            onClick = onRemove,
            size = 34.dp,
            role = ButtonRole.Destructive,
        )
    }
}

/** The "Photos" shelf: the camera grid, or the reason it is empty. */
@Composable
private fun CameraPhotos(vm: AppViewModel) {
    val photos by vm.photos.collectAsState()

    if (photos.isEmpty()) {
        EmptyShelf(
            icon = Icons.Filled.PhotoCamera,
            title = "No photos yet",
            body = "Take one with the shutter on the printer camera.",
            action = null,
        )
    } else {
        // The grid itself (and the full-size viewer it opens) lives with the print
        // screens, where the photos come from.
        PhotosTab(vm = vm, modifier = Modifier.fillMaxSize())
    }
}

/** An empty shelf: icon, what would fill it, and the one tap that does. */
@Composable
private fun EmptyShelf(
    icon: ImageVector,
    title: String,
    body: String,
    action: Pair<String, () -> Unit>?,
) {
    BrandCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                modifier =
                    Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    null,
                    modifier = Modifier.size(26.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                title,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                body,
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.let { (label, onClick) ->
                BrandButton(
                    text = label,
                    onClick = onClick,
                    modifier = Modifier.fillMaxWidth(),
                    height = ButtonSize.Medium,
                )
            }
        }
    }
}

/** "1.4 MB" / "820 KB" — the size the file on this device actually is. */
private fun fileSize(bytes: Long): String =
    when {
        bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576.0)
        bytes >= 1024 -> "${bytes / 1024} KB"
        else -> "$bytes B"
    }

/** "24 Oct 2026" — when the app took or downloaded it. */
private fun fileDate(millis: Long): String =
    java.text.DateFormat
        .getDateInstance(java.text.DateFormat.MEDIUM)
        .format(Date(millis))
