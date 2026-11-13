package world.yemyat.slicepilot.preview

import android.content.Context
import android.opengl.GLSurfaceView
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.MeshData
import world.yemyat.slicepilot.model.PartPlacement
import kotlin.math.max
import kotlin.math.sqrt

class ModelPreviewView(
    context: Context,
) : GLSurfaceView(context) {
    private val scene = PreviewScene()
    private val renderer = PreviewRenderer(scene)
    private val camera = OrbitCamera()

    private var baseMesh: MeshData? = null
    private var transform = ModelTransform()

    // Per-piece poses for baseMesh. Null keeps the old behaviour (the whole mesh drawn with
    // [transform]); setting them makes the renderer draw one piece at a time, each with its
    // own matrix - see PreviewScene.partDraws and model/PartPlacement.
    private var partPoses: List<ModelTransform>? = null
    private var partsSkipped: Set<Int> = emptySet()

    /** Which piece the UI has picked, drawn in [PreviewGeometry.SELECTED_PART_COLOR]; -1 = none. */
    private var selectedPart: Int = -1
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
    private val frameCallback =
        object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frameScheduled = false
                if (paused || destroyed || !autoRotate) return
                camera.theta += AUTO_ROTATE_STEP
                publishCamera()
                frameScheduled = true
                choreographer.postFrameCallback(this)
            }
        }

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    if (destroyed) return false
                    camera.zoomBy(detector.scaleFactor, effectiveRadius())
                    publishCamera()
                    return true
                }
            },
        )

    private val gestureDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    if (destroyed) return false
                    if (e2.pointerCount >= 2) {
                        camera.pan(distanceX, distanceY, height.toFloat(), effectiveRadius(), FIELD_OF_VIEW_DEGREES)
                    } else {
                        camera.theta += distanceX * ROTATE_SENSITIVITY
                        camera.phi =
                            (camera.phi + distanceY * ROTATE_SENSITIVITY)
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
            },
        )

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
        baseMesh = mesh
        rebuildPartDraws()
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
        pivotX = mesh.centerX
        pivotY = mesh.centerY
        pivotZ = mesh.centerZ
        dropZ = 0f
        halfX = (mesh.maxX - mesh.minX) / 2f
        halfY = (mesh.maxY - mesh.minY) / 2f
        halfZ = (mesh.maxZ - mesh.minZ) / 2f
        plateCenterX = bedHalfX
        plateCenterY = bedHalfY
        plateHalfX = bedHalfX
        plateHalfY = bedHalfY
        scene.model = MeshGeometry(PreviewGeometry.interleave(mesh.positions, mesh.normals), mesh.indices)
        scene.modelColor = PreviewGeometry.parseColor(modelColorHex) ?: PreviewGeometry.DEFAULT_MODEL_COLOR
        scene.plate = PreviewGeometry.plate(bedHalfX, bedHalfY, -0.5f, bedHalfX, bedHalfY)
        scene.plateColor = PreviewGeometry.parseColor(plateColorHex) ?: PreviewGeometry.DEFAULT_PLATE_COLOR
        updateTransformMatrix()
        rebuildPartDraws()
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

    /**
     * The mesh as the slicer is going to build it: the geometry plus a reveal band that shows
     * only the height printed so far. Like every other viewer, a file with pieces has to be
     * drawn piece by piece - the first version drew the whole mesh with [transform] here, so a
     * multi-part file snapped back to the layout the file itself carried the moment slicing
     * started (and the pieces the user had switched off came back with it), because those poses
     * live one per piece in [partPoses] and not in that single transform.
     */
    fun setMeshSlabs(
        mesh: MeshData,
        transform: FloatArray,
        bedHalfX: Float = 128f,
        bedHalfY: Float = 128f,
        partPoses: List<ModelTransform>? = null,
        partsSkipped: Set<Int> = emptySet(),
        selectedPart: Int = -1,
    ) {
        if (destroyed) return
        scene.clearGeometry()
        baseMesh = mesh
        this.transform = ModelTransform.fromArray(transform)
        this.partPoses = partPoses
        this.partsSkipped = partsSkipped
        this.selectedPart = selectedPart
        pivotX = mesh.centerX
        pivotY = mesh.centerY
        pivotZ = mesh.centerZ
        dropZ = 0f
        halfX = (mesh.maxX - mesh.minX) / 2f
        halfY = (mesh.maxY - mesh.minY) / 2f
        halfZ = (mesh.maxZ - mesh.minZ) / 2f
        plateCenterX = bedHalfX
        plateCenterY = bedHalfY
        plateHalfX = bedHalfX
        plateHalfY = bedHalfY
        // How high the print gets: the pieces' own height when the file is laid out piece by
        // piece (a rotated piece lies down shorter, a skipped one adds nothing), the whole
        // mesh's otherwise. The band sweeps from the plate to that height, so the plate the
        // user posed is the plate that animates.
        val extZ =
            posesForMesh(mesh)
                ?.let { PartPlacement.printedHeightMm(mesh.parts, it, partsSkipped) / 2f }
                ?: this.transform.extents(halfX, halfY, halfZ)[2]
        scene.model = MeshGeometry(PreviewGeometry.interleave(mesh.positions, mesh.normals), mesh.indices)
        scene.modelColor = PreviewGeometry.REVEAL_COLOR
        scene.transform = this.transform.worldMatrix(pivotX, pivotY, pivotZ, bedHalfX, bedHalfY, dropZ, halfX, halfY, halfZ)
        rebuildPartDraws()
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

    fun setSceneBackground(
        r: Float,
        g: Float,
        b: Float,
    ) {
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

    fun nudgeModel(
        dx: Float,
        dy: Float,
    ): Boolean = tryApply(transform.nudged(dx, dy))

    /**
     * The plate movement ([dx], [dy] in mm) that looks like the arrow [direction] points, for
     * the camera the preview is currently drawn with. The setup sheet asks for this instead of
     * hardcoding (0, step): an arrow has to move a piece the way it points on screen, not along
     * the plate's own axes (see [OrbitCamera.plateDelta]).
     */
    fun screenDelta(
        direction: ScreenDirection,
        step: Float,
    ): Pair<Float, Float> = camera.plateDelta(direction, step)

    /** Move the model one step towards a screen direction — what the Move arrows mean. */
    fun nudgeScreen(
        direction: ScreenDirection,
        step: Float,
    ): Boolean {
        val (dx, dy) = screenDelta(direction, step)
        return nudgeModel(dx, dy)
    }

    fun toggleMirror(axis: Char): Boolean = transform.mirrored(axis)?.let(::tryApply) ?: false

    /** Turn the model 90° around one axis — kept for the existing callers of [rotateBy]. */
    fun rotateModel(axis: Char): Boolean = rotateBy(axis, 90f)

    /**
     * Turn the model [degrees] around one axis. Angles are stored mod 360, so eight
     * taps on X read 0° again instead of the old 720°: the number on screen always
     * describes the pose that is drawn and the one the slicer receives.
     */
    fun rotateBy(
        axis: Char,
        degrees: Float,
    ): Boolean = transform.rotated(axis, degrees)?.let(::tryApply) ?: false

    /** "Put this face on the plate" — the six faces of the authored bounding box. */
    fun setFaceDown(face: ModelTransform.Face): Boolean = tryApply(transform.forFace(face))

    /** The pose the renderer draws — and the pose the slicer has to reproduce. */
    fun currentTransform(): ModelTransform = transform

    /** What the plate has to hold at this pose: width, depth, height in mm. */
    fun footprintMm(): FloatArray = transform.extents(halfX, halfY, halfZ)

    fun resetModelTransform() {
        transform = ModelTransform()
        updateTransformMatrix()
    }

    fun modelTransform(): FloatArray = transform.toArray()

    /**
     * Pose the pieces of the current model individually - the preview half of "move this
     * model, leave that one". [poses] is in the order the parser reported the pieces (see
     * model/PartPlacement); [skipped] names the pieces left off the plate; [selected] is the
     * piece the Pieces list has picked, drawn in [PreviewGeometry.SELECTED_PART_COLOR] so the
     * plate answers "which one is Part 3?" on a file of eight identical clips.
     * Passing null (or a model with a single piece) draws the mesh whole with
     * [setModelTransform], as before.
     */
    fun setPartPoses(
        poses: List<ModelTransform>?,
        skipped: Set<Int> = emptySet(),
        selected: Int = -1,
    ) {
        partPoses = poses
        partsSkipped = skipped
        selectedPart = selected
        rebuildPartDraws()
    }

    fun partPoses(): List<ModelTransform>? = partPoses

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
        val fits =
            next.withinPlate(
                pivotX,
                pivotY,
                halfX,
                halfY,
                halfZ,
                plateCenterX - plateHalfX,
                plateCenterX + plateHalfX,
                plateCenterY - plateHalfY,
                plateCenterY + plateHalfY,
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

    /**
     * Fill PreviewScene.partDraws from the pieces of baseMesh and their poses: printed pieces
     * get a draw for their slice of the shared index buffer with their own matrix, skipped
     * pieces get none. Anything the mesh has no pose for falls back to the whole-mesh draw.
     */
    private fun rebuildPartDraws() {
        val mesh = baseMesh ?: return
        val poses = posesForMesh(mesh) ?: return
        scene.partDraws =
            buildList {
                mesh.parts.forEachIndexed { index, part ->
                    if (index in partsSkipped) return@forEachIndexed
                    val pose = poses[index]
                    add(
                        ModelPartDraw(
                            indexOffset = part.indexOffset,
                            indexCount = part.indexCount,
                            matrix =
                                pose.worldMatrix(
                                    part.centerX,
                                    part.centerY,
                                    (part.minZ + part.maxZ) / 2f,
                                    plateCenterX,
                                    plateCenterY,
                                    dropZ,
                                    part.halfX,
                                    part.halfY,
                                    part.halfZ,
                                ),
                            color =
                                if (index == selectedPart) {
                                    PreviewGeometry.SELECTED_PART_COLOR
                                } else {
                                    null
                                },
                        ),
                    )
                }
            }
        requestRender()
    }

    /**
     * The poses to draw [mesh] with: [partPoses] only describe it when they carry one pose for
     * every piece of a multi-piece file. Anything else (a plain STL, a decimated copy, a pose
     * list from a different file) has no per-piece answer, so the caller draws the mesh whole
     * with the single [transform] and the pieces stay as the file authored them.
     */
    private fun posesForMesh(mesh: MeshData): List<ModelTransform>? =
        partPoses?.takeIf { mesh.parts.size > 1 && it.size == mesh.parts.size }

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
