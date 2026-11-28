package world.yemyat.slicepilot.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

/** A model the user starred in "My models". [path] points into private storage. */
data class FavoriteModel(
    val id: String,
    val name: String,
    val source: String,
    /** "file|<name>" — dedupe key so the star is shared across screens. */
    val key: String,
    /** Source listing id when the favorite came from browse; powers card badges. */
    val listingId: String = "",
    val path: String,
    val thumbPath: String?,
    val sizeBytes: Long,
    val addedAt: Long,
)

/**
 * Favorited models ("My models"): favorites live in `filesDir/favorites` —
 * private storage that survives cache eviction, unlike the browse-download
 * cache — plus a JSON manifest with name/source/thumbnail/date for the
 * library screen.
 */
class FavoritesStore(context: Context) {

    private val dir = File(context.filesDir, "favorites").apply { mkdirs() }
    private val thumbDir = File(dir, "thumbs").apply { mkdirs() }
    private val manifest = File(dir, "favorites.json")
    private val _entries = MutableStateFlow(readManifest())
    val entries: StateFlow<List<FavoriteModel>> = _entries.asStateFlow()

    fun isFavorited(key: String): Boolean = _entries.value.any { it.key == key }

    /** Copies [sourceFile] into private storage and records it. Newest first. */
    suspend fun add(
        sourceFile: File,
        name: String,
        source: String,
        key: String,
        listingId: String = "",
    ): FavoriteModel =
        withContext(Dispatchers.IO) {
            val safe = name.replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
            val base = safe.substringBeforeLast('.', safe)
            val ext = safe.substringAfterLast('.', "")
            val target: File = if (sourceFile.parentFile?.absolutePath == dir.absolutePath) {
                // Already a library file (e.g. re-starring a model loaded from
                // the library) — adopt it instead of copying.
                sourceFile
            } else {
                var t = File(dir, safe)
                var n = 2
                while (t.exists()) {
                    t = File(dir, if (ext.isEmpty()) "$base($n)" else "$base($n).$ext")
                    n++
                }
                sourceFile.copyTo(t, overwrite = true)
                t
            }
            val entry = FavoriteModel(
                id = UUID.randomUUID().toString(),
                name = name,
                source = source,
                key = key,
                listingId = listingId,
                path = target.absolutePath,
                thumbPath = null,
                sizeBytes = target.length(),
                addedAt = System.currentTimeMillis(),
            )
            _entries.value = listOf(entry) + _entries.value
            writeManifest()
            entry
        }

    /** Best-effort thumbnail download (the library degrades to a generic icon). */
    suspend fun setThumb(entry: FavoriteModel, thumbUrl: String?) = withContext(Dispatchers.IO) {
        if (thumbUrl.isNullOrBlank() || !thumbUrl.startsWith("http")) return@withContext
        try {
            val conn = URL(thumbUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", MODEL_UA)
            try {
                if (conn.responseCode !in 200..299) return@withContext
                val thumb = File(thumbDir, "${entry.id}.img")
                conn.inputStream.use { input -> thumb.outputStream().use(input::copyTo) }
                update(entry.copy(thumbPath = thumb.absolutePath))
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.d(TAG, "thumb fetch failed: ${e.message}")
        }
    }

    /** [deleteFile] is false when the file is the currently loaded model —
     *  deleting it out from under the slicer would empty the project. */
    fun remove(id: String, deleteFile: Boolean = true) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        _entries.value = _entries.value.filterNot { it.id == id }
        writeManifest()
        if (deleteFile) runCatching { File(entry.path).delete() }
        entry.thumbPath?.let { runCatching { File(it).delete() } }
    }

    private fun update(entry: FavoriteModel) {
        _entries.value = _entries.value.map { if (it.id == entry.id) entry else it }
        writeManifest()
    }

    private fun readManifest(): List<FavoriteModel> { return try {
        if (!manifest.exists()) return emptyList()
        val arr = JSONArray(manifest.readText())
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val e = FavoriteModel(
                id = o.getString("id"),
                name = o.getString("name"),
                source = o.optString("source"),
                key = o.optString("key"),
                listingId = o.optString("listingId"),
                path = o.getString("path"),
                thumbPath = o.optString("thumbPath").takeIf { it.isNotBlank() },
                sizeBytes = o.optLong("sizeBytes"),
                addedAt = o.optLong("addedAt"),
            )
            // Drop entries whose file vanished (e.g. cleared storage).
            e.takeIf { File(e.path).exists() }
        }
    } catch (e: Exception) {
        Log.w(TAG, "manifest read failed", e)
        emptyList()
    } }

    private fun writeManifest() {
        try {
            val arr = JSONArray()
            _entries.value.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("name", e.name)
                        .put("source", e.source)
                        .put("key", e.key)
                        .put("listingId", e.listingId)
                        .put("path", e.path)
                        .put("thumbPath", e.thumbPath ?: "")
                        .put("sizeBytes", e.sizeBytes)
                        .put("addedAt", e.addedAt),
                )
            }
            manifest.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "manifest write failed", e)
        }
    }

    private companion object {
        const val TAG = "Favorites"
        const val MODEL_UA =
            "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36"
    }
}
