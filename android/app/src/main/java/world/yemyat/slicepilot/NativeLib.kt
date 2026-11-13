package world.yemyat.slicepilot

import android.content.Context
import java.io.File

object NativeLib {
    interface SliceCallback {
        fun onProgress(
            step: Int,
            text: String,
            percent: Int,
        )
    }

    /** Receives 0..100 progress updates while [parsePreviewMesh] runs. */
    interface PreviewCallback {
        fun onProgress(percent: Int)
    }

    @Volatile
    private var initialized = false

    @Volatile
    var isLoaded = false
        private set

    /**
     * Loads the native engine. Returns false (instead of crashing) when the
     * library is missing, e.g. after a corrupted Play install shows
     * "dlopen failed: library libslice_core.so not found".
     */
    fun init(context: Context): Boolean {
        if (initialized) return true
        try {
            System.loadLibrary("slice_core")
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e("NativeLib", "slice_core load failed", e)
            world.yemyat.slicepilot.tracking.EventNotifier.trackEvent(
                "native_lib_load_failed",
                mapOf("message" to (e.message ?: "")),
            )
            return false
        }
        isLoaded = true
        val dataDir = context.getDir("slic3r", Context.MODE_PRIVATE).absolutePath
        val resDir = context.applicationInfo.dataDir + "/resources"
        ensureProfilesCopied(context, resDir, dataDir)
        nativeInit(dataDir, resDir)
        initialized = true
        return true
    }

    private fun ensureProfilesCopied(
        context: Context,
        resDir: String,
        dataDir: String,
    ) {
        // resources_dir()/profiles is used by find_preset_vendor() and load_system_models_from_json().
        copyProfilesTo(context, File(resDir, "profiles"))
        // data_dir()/system is the canonical system preset location read by load_system_presets_from_json().
        copyProfilesTo(context, File(dataDir, "system"))
    }

    private fun copyProfilesTo(
        context: Context,
        dest: File,
    ) {
        // Re-sync the bundled presets whenever the app updates: a one-time copy
        // never delivers profiles added by a release (new machine types, dual-
        // nozzle / Canvas variants) to upgrading installs.
        val version =
            try {
                context.packageManager
                    .getPackageInfo(context.packageName, 0)
                    .longVersionCode
                    .toString()
            } catch (e: Exception) {
                "0"
            }
        val stamp = File(dest, ".presets-version")
        if (runCatching { stamp.readText() }.getOrNull() == version) return
        dest.mkdirs()
        copyAssetDir(context.assets, "profiles", dest)
        runCatching { stamp.writeText(version) }
    }

    private fun copyAssetDir(
        assets: android.content.res.AssetManager,
        assetPath: String,
        dest: File,
    ) {
        val children = assets.list(assetPath) ?: return
        if (children.isEmpty()) return
        dest.mkdirs()
        for (child in children) {
            val childAsset = "$assetPath/$child"
            val childDest = File(dest, child)
            val sub = assets.list(childAsset)
            if (sub != null && sub.isNotEmpty()) {
                copyAssetDir(assets, childAsset, childDest)
            } else {
                assets.open(childAsset).use { input ->
                    childDest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
    }

    external fun nativeInit(
        dataDir: String,
        resDir: String,
    )

    external fun nativeSlice(
        modelPath: String,
        iniConfig: String,
        outPath: String,
        callback: SliceCallback,
    ): Boolean

    external fun nativeSliceWithPreset(
        modelPath: String,
        printerName: String,
        filamentName: String,
        processName: String,
        outPath: String,
        callback: SliceCallback,
    ): Boolean

    external fun nativeGetPrinters(): String

    external fun nativeGetFilaments(): String

    external fun nativeGetProcesses(): String

    external fun nativeSelectPrinter(printerName: String): Boolean

    external fun nativeSelectProcess(processName: String): Boolean

    external fun nativeGetCompatibleProcesses(): String

    external fun nativeGetCompatibleFilaments(): String

    external fun nativeSliceWithOverrides(
        modelPath: String,
        printerName: String,
        filamentName: String,
        processName: String,
        overridesIni: String,
        supportFilamentName: String,
        outPath: String,
        callback: SliceCallback,
    ): Boolean

    external fun nativeSetModelTransform(
        rotX: Float,
        rotY: Float,
        rotZ: Float,
        mirrorX: Boolean,
        mirrorY: Boolean,
        mirrorZ: Boolean,
        offsetX: Float,
        offsetY: Float,
    )

    /**
     * Per-piece placement: one flat array holding a row of nine floats per piece, in the
     * order the parser reported them -
     * `[rotX, rotY, rotZ, mirrorX, mirrorY, mirrorZ, offsetX, offsetY, print]` - degrees,
     * 0/1 flags, offset in mm relative to the bed centre, and 1 to print the piece / 0 to
     * leave it off the plate. [rowCount] is the number of rows packed in [poses].
     *
     * The slicer applies one pose per object instead of moving the whole model as a unit,
     * which is what makes "print only this model out of the 3MF" and "slide the two models
     * together so they fit the plate" possible: both are placement data, not a re-mesh.
     *
     * A null array, a size that is not rowCount * 9, or a rowCount that does not match the
     * number of objects in the model clears the poses, and the file is placed by
     * [nativeSetModelTransform] alone, exactly as before pieces existed. Above 512 rows the
     * same fallback applies rather than placing part of the model from stale data.
     */
    external fun nativeSetPartPoses(
        poses: FloatArray?,
        rowCount: Int,
    )

    external fun nativeCancel()

    external fun nativeIsRunning(): Boolean

    external fun nativeGetError(): String

    external fun nativeGetVersion(): String

    private external fun nativeParsePreview(
        path: String,
        targetTris: Int,
        callback: PreviewCallback?,
    ): Array<Any>?

    /**
     * Parses a model with the fast streaming preview parser and simplifies it for the
     * preview (slicing always re-reads the full-resolution file). Null when the native
     * path is unavailable; callers fall back to the Kotlin parser.
     * [onProgress] receives 0..100 percentages while the parse runs.
     *
     * The raw tuple is `[positions, normals, indices, bbox(6), Array<MeshPart>]`; older
     * libraries return the first four only, which leaves [MeshData.parts] empty and the
     * mesh treated as one anonymous piece.
     */
    fun parsePreviewMesh(
        path: String,
        targetTris: Int,
        onProgress: (Int) -> Unit = {},
    ): world.yemyat.slicepilot.model.MeshData? {
        if (!isLoaded) return null
        val callback =
            object : PreviewCallback {
                override fun onProgress(percent: Int) = onProgress(percent)
            }
        val parts =
            try {
                nativeParsePreview(path, targetTris, callback)
            } catch (t: Throwable) {
                android.util.Log.w("NativeLib", "nativeParsePreview failed", t)
                null
            } ?: return null
        return try {
            val pos = parts[0] as FloatArray
            val nrm = parts[1] as FloatArray
            val idx = parts[2] as IntArray
            val bb = parts[3] as FloatArray
            // Pieces arrive as a flat row table plus their names (older libraries stop after
            // the bbox, which leaves the mesh as one anonymous piece).
            val rows = parts.getOrNull(4) as? FloatArray ?: FloatArray(0)
            val names = (parts.getOrNull(5) as? Array<*>)?.mapNotNull { it as? String } ?: emptyList()
            val pieces: List<world.yemyat.slicepilot.model.MeshPart> =
                world.yemyat.slicepilot.model.MeshPart
                    .fromRows(rows, names)
            world.yemyat.slicepilot.model.MeshData(
                pos,
                nrm,
                idx,
                bb[0],
                bb[1],
                bb[2],
                bb[3],
                bb[4],
                bb[5],
                pieces,
            )
        } catch (t: Throwable) {
            android.util.Log.e("NativeLib", "nativeParsePreview result decode failed", t)
            null
        }
    }
}
