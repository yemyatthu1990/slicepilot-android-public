package world.yemyat.slicepilot

import android.content.Context
import java.io.File

object NativeLib {
    interface SliceCallback {
        fun onProgress(step: Int, text: String, percent: Int)
    }

    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        System.loadLibrary("slice_core")
        val dataDir = context.getDir("slic3r", Context.MODE_PRIVATE).absolutePath
        val resDir = context.applicationInfo.dataDir + "/resources"
        ensureProfilesCopied(context, resDir, dataDir)
        nativeInit(dataDir, resDir)
        initialized = true
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
}
