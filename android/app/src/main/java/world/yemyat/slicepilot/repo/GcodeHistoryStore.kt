package world.yemyat.slicepilot.repo

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import world.yemyat.slicepilot.preview.GcodePreviewImage
import java.io.File
import java.util.UUID

/**
 * One g-code the app sliced. [gcodePath] lives in private storage (`filesDir/gcodes`),
 * not in `cacheDir` where the slicer writes it: a cache file is disposable — Android
 * evicts it and discarding the model deletes it — while the g-code is the thing a print
 * is actually sent from. [modelName] names the model it was cut from, which is what
 * makes a list of g-code readable at all.
 */
data class GcodeHistoryEntry(
    val id: String,
    val modelName: String,
    val gcodePath: String,
    /** The slice's own picture (`filesDir/gcodes/thumbs`); null until one can be made. */
    val thumbPath: String?,
    /** The model file it was cut from, when that file still exists. */
    val modelPath: String?,
    /** Slicer profile the g-code was cut for ("Elegoo Centauri Carbon 2 0"). */
    val printer: String?,
    val sizeBytes: Long,
    val slicedAt: Long,
)

/**
 * The Files tab's "G-code" shelf: every slice the app finished, newest first.
 *
 * There used to be one slot — `last_gcode_path` — and it went away with the model, so a
 * sliced-but-not-printed job had to be re-sliced to be printed. Slices are filed here
 * instead, keeping the newest [MAX_ENTRIES] files within [MAX_BYTES] and deleting the
 * older ones.
 */
class GcodeHistoryStore(
    context: Context,
) {
    private val dir = File(context.filesDir, "gcodes").apply { mkdirs() }
    private val thumbDir = File(dir, "thumbs").apply { mkdirs() }
    private val manifest = File(dir, "history.json")
    private val _entries = MutableStateFlow(readManifest())
    val entries: StateFlow<List<GcodeHistoryEntry>> = _entries.asStateFlow()

    /** Where a slice of [modelPath] should be written, so it lands in the archive. */
    fun outputFileFor(modelPath: String?): File =
        File(dir, archivedName(File(modelPath.orEmpty()).nameWithoutExtension.ifBlank { "model" }))

    /** True when [path] is a file this store owns (an archived g-code). */
    fun owns(path: String): Boolean = File(path).absolutePath.startsWith(dir.absolutePath)

    /**
     * File [gcodeFile] — written by the slicer — and return the path the app should now
     * use for it. A file already in the archive is registered in place; a cache file is
     * moved in (copied if the filesystem refuses the rename), so the app ends up with one
     * canonical g-code instead of two copies. Re-registering a known path returns it as is.
     * The new entry has no picture yet: [renderMissingThumbs] draws it, because parsing a
     * g-code costs and slicing already takes long enough.
     */
    fun record(
        gcodeFile: File,
        modelPath: String?,
        printer: String?,
    ): String? {
        val absolute = gcodeFile.absolutePath
        _entries.value.firstOrNull { it.gcodePath == absolute }?.let { known ->
            return known.gcodePath
        }
        val target =
            if (owns(absolute)) {
                gcodeFile
            } else {
                val named =
                    File(
                        dir,
                        archivedName(modelPath?.let { File(it).nameWithoutExtension } ?: gcodeFile.nameWithoutExtension),
                    )
                val moved =
                    runCatching { named.takeIf { gcodeFile.renameTo(it) } }.getOrNull()
                        ?: runCatching { gcodeFile.copyTo(named, overwrite = true).takeIf { it.exists() } }.getOrNull()
                        ?: return null
                runCatching { gcodeFile.delete() }
                moved
            }
        val entry =
            GcodeHistoryEntry(
                id = UUID.randomUUID().toString(),
                modelName = modelPath?.let { File(it).name } ?: target.nameWithoutExtension,
                gcodePath = target.absolutePath,
                thumbPath = null,
                modelPath = modelPath?.takeIf { File(it).exists() },
                printer = printer,
                sizeBytes = target.length(),
                slicedAt = System.currentTimeMillis(),
            )
        _entries.value = prune(listOf(entry) + _entries.value)
        writeManifest()
        return entry.gcodePath
    }

    /** Give an entry its picture; null leaves the row to its fallback glyph. */
    fun setThumb(
        entry: GcodeHistoryEntry,
        path: String?,
    ) {
        if (entry.thumbPath == path || path != null && !File(path).exists()) return
        _entries.value = _entries.value.map { if (it.id == entry.id) it.copy(thumbPath = path) else it }
        writeManifest()
    }

    /** Entries tried and found unrenderable, so one bad file is not parsed on every start. */
    private val unrenderable = mutableSetOf<String>()

    /**
     * Draw the pictures that entries are still missing — filed before previews were stored,
     * or whose file has moved. Parsing a g-code costs, so callers run this off the main
     * thread; entries that yield nothing simply stay without a picture.
     */
    fun renderMissingThumbs() {
        entries.value
            .filter { it.thumbPath == null || !File(it.thumbPath).exists() }
            .filterNot { entry -> entry.id in unrenderable }
            .forEach { entry ->
                val path = writeThumb(entry.gcodePath, File(entry.gcodePath))
                if (path == null) unrenderable.add(entry.id) else setThumb(entry, path)
            }
    }

    /**
     * The tile image for the slice at [gcodePath], drawn from [gcodeFile]: the preview the
     * slicer embedded when the file carries one, otherwise its toolpaths flattened to the
     * same picture the 3D preview shows. Saved as `thumbs/<name>.png`.
     */
    private fun writeThumb(
        gcodePath: String,
        gcodeFile: File,
    ): String? {
        val png = GcodePreviewImage.renderPng(gcodeFile) ?: return null
        val file = File(thumbDir, "${File(gcodePath).nameWithoutExtension}.png")
        return if (runCatching { file.writeBytes(png) }.isSuccess) file.absolutePath else null
    }

    /** Forget an entry, its g-code, and its picture. */
    fun remove(id: String) {
        val entry = _entries.value.firstOrNull { it.id == id } ?: return
        _entries.value = _entries.value.filterNot { it.id == id }
        writeManifest()
        discard(entry)
    }

    private fun discard(entry: GcodeHistoryEntry) {
        runCatching { File(entry.gcodePath).delete() }
            .onFailure { Log.d(TAG, "gcode delete failed: ${it.message}") }
        entry.thumbPath?.let { path -> runCatching { File(path).delete() } }
    }

    /** `<name>.gcode`, or `<name>(2).gcode` when that slice is already filed. */
    private fun archivedName(base: String): String {
        val safe =
            base
                .substringBeforeLast('.')
                .replace(Regex("[/\\\\:*?\"<>|\\x00-\\x1F]"), "_")
                .ifBlank { "model" }
        var n = 1
        var name = "$safe.gcode"
        while (File(dir, name).exists()) {
            n++
            name = "$safe($n).gcode"
        }
        return name
    }

    /** Newest first, at most [MAX_ENTRIES] files and [MAX_BYTES] of storage. */
    private fun prune(entries: List<GcodeHistoryEntry>): List<GcodeHistoryEntry> {
        var total = 0L
        val kept =
            entries
                .filter { e ->
                    val fits = (total + e.sizeBytes <= MAX_BYTES) || total == 0L
                    if (fits) total += e.sizeBytes
                    fits
                }.take(MAX_ENTRIES)
        // Everything dropped is g-code nothing points at any more, and its listing was
        // the only place it could be reached from.
        entries.drop(kept.size).forEach(::discard)
        return kept
    }

    private fun readManifest(): List<GcodeHistoryEntry> =
        run {
            if (!manifest.exists()) {
                emptyList()
            } else {
                try {
                    val arr = JSONArray(manifest.readText())
                    (0 until arr.length())
                        .mapNotNull { i ->
                            val o = arr.optJSONObject(i) ?: return@mapNotNull null
                            GcodeHistoryEntry(
                                id = o.getString("id"),
                                modelName = o.optString("modelName"),
                                gcodePath = o.getString("gcodePath"),
                                thumbPath = o.optString("thumbPath").takeIf { it.isNotBlank() },
                                modelPath = o.optString("modelPath").takeIf { it.isNotBlank() },
                                printer = o.optString("printer").takeIf { it.isNotBlank() },
                                sizeBytes = o.optLong("sizeBytes"),
                                slicedAt = o.optLong("slicedAt"),
                            )
                        }
                        // G-code Android evicted while the app was away: forget the listing too.
                        .filter { File(it.gcodePath).exists() }
                } catch (e: Exception) {
                    Log.w(TAG, "manifest read failed", e)
                    emptyList()
                }
            }
        }

    private fun writeManifest() {
        try {
            val arr = JSONArray()
            _entries.value.forEach { e ->
                arr.put(
                    JSONObject()
                        .put("id", e.id)
                        .put("modelName", e.modelName)
                        .put("gcodePath", e.gcodePath)
                        .put("thumbPath", e.thumbPath ?: "")
                        .put("modelPath", e.modelPath ?: "")
                        .put("printer", e.printer ?: "")
                        .put("sizeBytes", e.sizeBytes)
                        .put("slicedAt", e.slicedAt),
                )
            }
            manifest.writeText(arr.toString())
        } catch (e: Exception) {
            Log.w(TAG, "manifest write failed", e)
        }
    }

    private companion object {
        const val TAG = "GcodeHistory"
        const val MAX_ENTRIES = 10
        const val MAX_BYTES = 200L * 1024 * 1024
    }
}
