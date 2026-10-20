package world.yemyat.slicepilot.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CollectionsBookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.graphics.BitmapFactory
import world.yemyat.slicepilot.repo.FavoriteModel
import world.yemyat.slicepilot.repo.FavoritesStore
import java.io.File
import java.util.Date

/**
 * "My models": starred models kept in private storage (survives cache eviction).
 * Tapping an entry loads it into the slicer; the trash icon un-favorites it.
 */
@Composable
fun LibraryScreen(vm: AppViewModel, favorites: FavoritesStore, onBrowse: () -> Unit) {
    val entries by favorites.entries.collectAsState()
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.backHome() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
            }
            Spacer(Modifier.width(4.dp))
            Text(
                "My models",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    Icons.Filled.CollectionsBookmark,
                    null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "Nothing kept yet.\nStar a model's download button while browsing to save it here.",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(16.dp))
                TextButton(onClick = onBrowse) { Text("Find models") }
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(entries, key = { it.id }) { entry ->
                    LibraryEntryCard(
                        entry = entry,
                        onOpen = { vm.requestLoadModel(entry.path) },
                        onRemove = { confirmRemove = entry },
                    )
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LibraryEntryCard(
    entry: FavoriteModel,
    onOpen: () -> Unit,
    onRemove: () -> Unit,
) {
    val missing = !File(entry.path).exists()
    val thumb = remember(entry.thumbPath) {
        entry.thumbPath?.takeIf { File(it).exists() }
            ?.let { BitmapFactory.decodeFile(it)?.asImageBitmap() }
    }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier
                .padding(10.dp)
                .clickable(enabled = !missing, onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(10.dp))
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
                        Icons.Filled.CollectionsBookmark,
                        null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = buildString {
                    append(entry.source.replaceFirstChar { it.uppercase() })
                    append(" · ")
                    append(String.format("%.1f MB", entry.sizeBytes / 1024.0 / 1024.0))
                    append(" · ")
                    append(java.text.DateFormat.getDateInstance().format(Date(entry.addedAt)))
                }
                Text(
                    if (missing) "File missing — star it again" else meta,
                    fontSize = 12.sp,
                    color = if (missing) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Delete, "Remove", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
