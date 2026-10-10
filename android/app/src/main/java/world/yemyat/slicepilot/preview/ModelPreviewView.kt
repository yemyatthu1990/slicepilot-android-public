package world.yemyat.slicepilot.preview

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.MeshData
import kotlin.math.max
import kotlin.math.sqrt

class ModelPreviewView(context: Context) : GLSurfaceView(context) {

    private val scene = PreviewScene()
    private val renderer = PreviewRenderer(scene)
    private val camera = OrbitCamera()

    private var baseMesh: MeshData? = null
    private var transform = ModelTransform()
    private var pivotX = 0f
    private var pivotY = 0f
    private var pivotZ = 0f
    private var dropZ = 0f
    private var halfX = 0f
    private var halfY = 0f
    private var halfZ = 0f
    private var plateCenterX = 0f
    private var plateCenterY = 0f
    private var plateHalfX = 128f
    private var plateHalfY = 128f

    private var autoRotate = false
    private var paused = false
    private var destroyed = false
    private var frameScheduled = false

    private val choreographer = Choreographer.getInstance()
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            frameScheduled = false
            if (paused || destroyed || !autoRotate) return
            camera.theta += AUTO_ROTATE_STEP
            publishCamera()
            frameScheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            if (destroyed) return false
            camera.zoomBy(detector.scaleFactor, effectiveRadius())
            publishCamera()
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (destroyed) return false
            if (e2.pointerCount >= 2) {
                camera.pan(distanceX, distanceY, height.toFloat(), effectiveRadius(), FIELD_OF_VIEW_DEGREES)
            } else {
                camera.theta += distanceX * ROTATE_SENSITIVITY
                camera.phi = (camera.phi + distanceY * ROTATE_SENSITIVITY)
                    .coerceIn(OrbitCamera.PHI_MIN, OrbitCamera.PHI_MAX)
            }
            publishCamera()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (destroyed) return false
            camera.reframe()
            publishCamera()
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        preserveEGLContextOnPause = true
        setRenderer(renderer)
        renderMode = RENDERMODE_WHEN_DIRTY
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onResume() {
        super.onResume()
        paused = false
        if (autoRotate) startAutoRotate()
    }

    override fun onPause() {
        paused = true
        stopAutoRotate()
        super.onPause()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        stopAutoRotate()
        baseMesh = null
        scene.clearGeometry()
    }

    fun setModel(mesh: MeshData) {
        if (destroyed) return
        scene.clearGeometry()
        baseMesh = null
        scene.model = MeshGeometry(PreviewGeometry.interleave(mesh.positions, mesh.normals), mesh.indices)
        scene.modelColor = PreviewGeometry.DEFAULT_MODEL_COLOR
        camera.frame(mesh.centerX, mesh.centerY, mesh.centerZ, mesh.maxRadius)
        publishCamera()
    }

    fun setModelWithPlate(
        mesh: MeshData,
        modelColorHex: String?,
        plateColorHex: String?,
        bedHalfX: Float = 128f,
        bedHalfY: Float = 128f,
    ) {
        if (destroyed) return
        scene.clearGeometry()
        baseMesh = mesh
        transform = ModelTransform()
        pivotX = mesh.centerX; pivotY = mesh.centerY; pivotZ = mesh.centerZ
        dropZ = 0f
        halfX = (mesh.maxX - mesh.minX) / 2f
        halfY = (mesh.maxY - mesh.minY) / 2f
        halfZ = (mesh.maxZ - mesh.minZ) / 2f
        plateCenterX = bedHalfX; plateCenterY = bedHalfY
        plateHalfX = bedHalfX; plateHalfY = bedHalfY
        scene.model = MeshGeometry(PreviewGeometry.interleave(mesh.positions, mesh.normals), mesh.indices)
        scene.modelColor = PreviewGeometry.parseColor(modelColorHex) ?: PreviewGeometry.DEFAULT_MODEL_COLOR
        scene.plate = PreviewGeometry.plate(bedHalfX, bedHalfY, -0.5f, bedHalfX, bedHalfY)
        scene.plateColor = PreviewGeometry.parseColor(plateColorHex) ?: PreviewGeometry.DEFAULT_PLATE_COLOR
        updateTransformMatrix()
        camera.frame(
            plateCenterX,
            plateCenterY,
            max(0f, mesh.centerZ - mesh.minZ),
            max(max(bedHalfX, bedHalfY), max(halfX, halfY) * sqrt(2f)),
        )
        publishCamera()
    }

    fun setGcodePreview(
        preview: GcodePreview,
        bedHalfX: Float = 128f,
        bedHalfY: Float = 128f,
        modelColorHex: String? = null,
    ) {
        if (destroyed) return
        scene.clearGeometry()
        baseMesh = null
        val modelColor = PreviewGeometry.parseColor(modelColorHex) ?: PreviewGeometry.GCODE_DEFAULT_COLOR
        try {
            scene.plate = PreviewGeometry.plate(bedHalfX, bedHalfY, -0.5f, bedHalfX, bedHalfY)
            scene.plateColor = PreviewGeometry.DEFAULT_PLATE_COLOR
            scene.gcode = PreviewGeometry.gcodeParts(preview, modelColor)
        } catch (_: OutOfMemoryError) {
            scene.clearGeometry()
            return
        }
        scene.visibleLayers = preview.layers.size
        camera.frame(
            preview.centerX,
            preview.centerY,
            preview.maxZ * 0.5f,
            max(preview.maxRadius, preview.maxZ * 0.5f),
        )
        publishCamera()
    }

    fun setMeshSlabs(mesh: MeshData, transform: FloatArray, bedHalfX: Float = 128f, bedHalfY: Float = 128f) {
        if (destroyed) return
        scene.clearGeometry()
        baseMesh = mesh
        this.transform = ModelTransform.fromArray(transform)
        pivotX = mesh.centerX; pivotY = mesh.centerY; pivotZ = mesh.centerZ
        dropZ = 0f
        halfX = (mesh.maxX - mesh.minX) / 2f
        halfY = (mesh.maxY - mesh.minY) / 2f
        halfZ = (mesh.maxZ - mesh.minZ) / 2f
        plateCenterX = bedHalfX; plateCenterY = bedHalfY
        plateHalfX = bedHalfX; plateHalfY = bedHalfY
        val extZ = this.transform.extents(halfX, halfY, halfZ)[2]
        scene.model = MeshGeometry(PreviewGeometry.interleave(mesh.positions, mesh.normals), mesh.indices)
        scene.modelColor = PreviewGeometry.REVEAL_COLOR
        scene.transform = this.transform.worldMatrix(pivotX, pivotY, pivotZ, bedHalfX, bedHalfY, dropZ, halfX, halfY, halfZ)
        scene.revealEnabled = true
        scene.revealMinZ = 0f
        scene.revealMaxZ = 2f * extZ
        scene.revealFraction = 0f
        scene.plate = PreviewGeometry.plate(bedHalfX, bedHalfY, -0.5f, bedHalfX, bedHalfY)
        camera.frame(
            bedHalfX + this.transform.offsetX,
            bedHalfY + this.transform.offsetY,
            extZ,
            max(mesh.maxRadius * 1.15f, 20f),
        )
        publishCamera()
    }

    fun setRevealFraction(fraction: Float) {
        scene.revealFraction = fraction.coerceIn(0f, 1f)
        requestRender()
    }

    fun setVisibleLayerCount(count: Int) {
        scene.visibleLayers = count
        requestRender()
    }

    fun setSceneBackground(r: Float, g: Float, b: Float) {
        scene.background = floatArrayOf(r, g, b)
        requestRender()
    }

    fun setModelColor(modelColorHex: String?) {
        scene.modelColor = PreviewGeometry.parseColor(modelColorHex) ?: PreviewGeometry.DEFAULT_MODEL_COLOR
        requestRender()
    }

    fun setPlateColor(plateColorHex: String?) {
        scene.plateColor = PreviewGeometry.parseColor(plateColorHex) ?: PreviewGeometry.DEFAULT_PLATE_COLOR
        requestRender()
    }

    fun setAutoRotate(enabled: Boolean) {
        autoRotate = enabled
        if (enabled) startAutoRotate() else stopAutoRotate()
    }

    fun nudgeModel(dx: Float, dy: Float): Boolean =
        tryApply(transform.copy(offsetX = transform.offsetX + dx, offsetY = transform.offsetY + dy))

    fun toggleMirror(axis: Char): Boolean {
        val next = when (axis) {
            'x' -> transform.copy(mirrorX = !transform.mirrorX)
            'y' -> transform.copy(mirrorY = !transform.mirrorY)
            'z' -> transform.copy(mirrorZ = !transform.mirrorZ)
            else -> return false
        }
        return tryApply(next)
    }

    fun rotateModel(axis: Char): Boolean {
        val next = when (axis) {
            'x' -> transform.copy(rotX = transform.rotX + 90f)
            'y' -> transform.copy(rotY = transform.rotY + 90f)
            'z' -> transform.copy(rotZ = transform.rotZ + 90f)
            else -> return false
        }
        return tryApply(next)
    }

    fun resetModelTransform() {
        transform = ModelTransform()
        updateTransformMatrix()
    }

    fun modelTransform(): FloatArray = transform.toArray()

    fun setModelTransform(t: FloatArray) {
        transform = ModelTransform.fromArray(t)
        updateTransformMatrix()
    }

    fun mirrorState(): BooleanArray = booleanArrayOf(transform.mirrorX, transform.mirrorY, transform.mirrorZ)

    fun clearModel() {
        baseMesh = null
        transform = ModelTransform()
        scene.clearGeometry()
        requestRender()
    }

    private fun tryApply(next: ModelTransform): Boolean {
        baseMesh ?: return false
        val fits = next.withinPlate(
            pivotX, pivotY,
            halfX, halfY, halfZ,
            plateCenterX - plateHalfX, plateCenterX + plateHalfX,
            plateCenterY - plateHalfY, plateCenterY + plateHalfY,
            PLATE_EPSILON,
        )
        if (!fits) return false
        transform = next
        updateTransformMatrix()
        return true
    }

    private fun updateTransformMatrix() {
        scene.transform = transform.worldMatrix(pivotX, pivotY, pivotZ, plateCenterX, plateCenterY, dropZ, halfX, halfY, halfZ)
        requestRender()
    }

    private fun publishCamera() {
        scene.camera = CameraState.from(camera)
        requestRender()
    }

    private fun effectiveRadius(): Float {
        if (camera.userZoomed) return camera.radius
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return camera.radius
        return camera.fitRadius(w, h, FIELD_OF_VIEW_DEGREES, FIT_MARGIN)
    }

    private fun startAutoRotate() {
        if (paused || destroyed || frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(frameCallback)
    }

    private fun stopAutoRotate() {
        choreographer.removeFrameCallback(frameCallback)
        frameScheduled = false
    }

    private companion object {
        const val FIELD_OF_VIEW_DEGREES = 40f
        const val FIT_MARGIN = 1.25f
        const val AUTO_ROTATE_STEP = 0.004f
        const val ROTATE_SENSITIVITY = 0.008f
        const val PLATE_EPSILON = 1e-2f
    }
}