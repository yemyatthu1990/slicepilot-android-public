package world.yemyat.slicepilot

import android.content.Context
import java.io.File

object NativeLib {
    interface SliceCallback {
        fun onProgress(step: Int, text: String, percent: Int)
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

    private fun ensureProfilesCopied(context: Context, resDir: String, dataDir: String) {
        // resources_dir()/profiles is used by find_preset_vendor() and load_system_models_from_json().
        copyProfilesOnce(context, File(resDir, "profiles"))
        // data_dir()/system is the canonical system preset location read by load_system_presets_from_json().
        copyProfilesOnce(context, File(dataDir, "system"))
    }

    private fun copyProfilesOnce(context: Context, dest: File) {
        if (dest.exists() && (dest.list()?.isNotEmpty() == true)) return
        dest.mkdirs()
        copyAssetDir(context.assets, "profiles", dest)
    }

    private fun copyAssetDir(assets: android.content.res.AssetManager, assetPath: String, dest: File) {
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

    external fun nativeInit(dataDir: String, resDir: String)
    external fun nativeSlice(modelPath: String, iniConfig: String, outPath: String, callback: SliceCallback): Boolean
    external fun nativeSliceWithPreset(
        modelPath: String,
        printerName: String,
        filamentName: String,
        processName: String,
        outPath: String,
        callback: SliceCallback
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
        outPath: String,
        callback: SliceCallback
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
    external fun nativeCancel()
    external fun nativeIsRunning(): Boolean
    external fun nativeGetError(): String
    external fun nativeGetVersion(): String

    private external fun nativeParsePreview(
        path: String, targetTris: Int, callback: PreviewCallback?
    ): Array<Any>?

    /**
     * Parses a model with the native libslic3r readers and simplifies it for the
     * preview (slicing always re-reads the full-resolution file). Null when the
     * native path is unavailable; callers fall back to the Kotlin parser.
     * [onProgress] receives 0..100 percentages while the parse runs.
     */
    fun parsePreviewMesh(
        path: String,
        targetTris: Int,
        onProgress: (Int) -> Unit = {},
    ): world.yemyat.slicepilot.model.MeshData? {
        if (!isLoaded) return null
        val callback = object : PreviewCallback {
            override fun onProgress(percent: Int) = onProgress(percent)
        }
        val parts = try {
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
            world.yemyat.slicepilot.model.MeshData(pos, nrm, idx, bb[0], bb[1], bb[2], bb[3], bb[4], bb[5])
        } catch (t: Throwable) {
            android.util.Log.e("NativeLib", "nativeParsePreview result decode failed", t)
            null
        }
    }
}
