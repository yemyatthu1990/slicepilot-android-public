package world.yemyat.slicepilot.preview

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLExt
import android.opengl.GLES30
import android.util.Log
import world.yemyat.slicepilot.model.MeshData
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.sqrt

object ThumbnailRenderer {

    private const val TAG = "ThumbnailRenderer"

    fun render(
        mesh: MeshData,
        size: Int = 144,
        bedHalfX: Float = 128f,
        bedHalfY: Float = 128f,
        transform: ModelTransform = ModelTransform(),
        modelColorHex: String? = null,
    ): Bitmap? {
        val display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) return null
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) return null

        val configAttrs = intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_DEPTH_SIZE, 16,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = IntArray(1)
        if (!EGL14.eglChooseConfig(display, configAttrs, 0, configs, 0, 1, numConfigs, 0) || numConfigs[0] <= 0) {
            EGL14.eglTerminate(display)
            return null
        }
        val config = configs[0] ?: run { EGL14.eglTerminate(display); return null }

        val contextAttrs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
        val context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, contextAttrs, 0)
        val surfaceAttrs = intArrayOf(EGL14.EGL_WIDTH, size, EGL14.EGL_HEIGHT, size, EGL14.EGL_NONE)
        val surface = EGL14.eglCreatePbufferSurface(display, config, surfaceAttrs, 0)
        if (context == EGL14.EGL_NO_CONTEXT || surface == EGL14.EGL_NO_SURFACE) {
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
            EGL14.eglTerminate(display)
            return null
        }
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
            return null
        }

        return try {
            val scene = PreviewScene()
            val pivotX = mesh.centerX
            val pivotY = mesh.centerY
            val pivotZ = mesh.centerZ
            val halfX = (mesh.maxX - mesh.minX) / 2f
            val halfY = (mesh.maxY - mesh.minY) / 2f
            val halfZ = (mesh.maxZ - mesh.minZ) / 2f
            scene.model = MeshGeometry(PreviewGeometry.interleave(mesh.positions, mesh.normals), mesh.indices)
            scene.modelColor = PreviewGeometry.parseColor(modelColorHex) ?: PreviewGeometry.DEFAULT_MODEL_COLOR
            scene.plate = PreviewGeometry.plate(bedHalfX, bedHalfY, -0.5f, bedHalfX, bedHalfY)
            scene.plateColor = PreviewGeometry.DEFAULT_PLATE_COLOR
            scene.transform = transform.worldMatrix(pivotX, pivotY, pivotZ, bedHalfX, bedHalfY, 0f, halfX, halfY, halfZ)

            val cam = OrbitCamera()
            cam.frame(
                bedHalfX,
                bedHalfY,
                max(0f, mesh.centerZ - mesh.minZ),
                max(max(bedHalfX, bedHalfY), max(halfX, halfY) * sqrt(2f)),
            )
            scene.camera = CameraState.from(cam)

            val renderer = PreviewRenderer(scene)
            renderer.onSurfaceCreated(null, null)
            renderer.onSurfaceChanged(null, size, size)
            renderer.onDrawFrame(null)

            val buffer = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
            GLES30.glReadPixels(0, 0, size, size, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, buffer)
            buffer.position(0)

            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val srcRow = size - 1 - y
                for (x in 0 until size) {
                    val i = (srcRow * size + x) * 4
                    val r = buffer.get(i).toInt() and 0xFF
                    val g = buffer.get(i + 1).toInt() and 0xFF
                    val b = buffer.get(i + 2).toInt() and 0xFF
                    val a = buffer.get(i + 3).toInt() and 0xFF
                    pixels[y * size + x] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        } catch (t: Throwable) {
            Log.w(TAG, "Thumbnail render failed", t)
            null
        } finally {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, surface)
            EGL14.eglDestroyContext(display, context)
            EGL14.eglTerminate(display)
        }
    }
}
