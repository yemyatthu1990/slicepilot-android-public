package world.yemyat.slicepilot.preview

/**
 * One piece of [PreviewScene.model]: a range of the shared index buffer plus the column-major
 * matrix that poses it. The renderer issues one glDrawElements per entry, so a piece can be
 * moved, rotated or hidden without touching the shared vertex buffers.
 */
data class ModelPartDraw(
    /** First index of the piece, in indices (not bytes) — same units as MeshPart.indexOffset. */
    val indexOffset: Int,
    /** Indices to draw, i.e. triangles * 3. */
    val indexCount: Int,
    /** The piece's 4x4 model matrix (ModelTransform.worldMatrix for that piece). */
    val matrix: FloatArray,
)

class CameraState(
    val targetX: Float,
    val targetY: Float,
    val targetZ: Float,
    val theta: Float,
    val phi: Float,
    val radius: Float,
    val fitRadius: Float,
    val userZoomed: Boolean,
) {
    companion object {
        fun from(camera: OrbitCamera): CameraState =
            CameraState(
                targetX = camera.targetX,
                targetY = camera.targetY,
                targetZ = camera.targetZ,
                theta = camera.theta,
                phi = camera.phi,
                radius = camera.radius,
                fitRadius = camera.fitMaxRadius,
                userZoomed = camera.userZoomed,
            )
    }
}

class PreviewScene {
    @Volatile var model: MeshGeometry? = null

    @Volatile var modelColor: FloatArray = PreviewGeometry.DEFAULT_MODEL_COLOR

    /**
     * The model drawn piece by piece: each entry is a range of [model]'s index buffer and the
     * matrix that poses that piece. Empty means "draw [model] whole with [transform]", which
     * is what every single-part file (and every file before pieces) does.
     */
    @Volatile var partDraws: List<ModelPartDraw> = emptyList()

    @Volatile var plate: PlaneGeometry? = null

    @Volatile var plateColor: FloatArray = PreviewGeometry.DEFAULT_PLATE_COLOR

    @Volatile var gcode: List<GcodePartGeometry>? = null

    @Volatile var transform: FloatArray = Mat4.identity()

    @Volatile var background: FloatArray = PreviewGeometry.BACKGROUND_COLOR

    @Volatile var visibleLayers: Int = Int.MAX_VALUE

    @Volatile var revealEnabled: Boolean = false

    @Volatile var revealMinZ: Float = 0f

    @Volatile var revealMaxZ: Float = 0f

    @Volatile var revealFraction: Float = 1f

    @Volatile var camera: CameraState = CameraState.from(OrbitCamera())

    fun clearGeometry() {
        model = null
        partDraws = emptyList()
        plate = null
        gcode = null
        transform = Mat4.identity()
        visibleLayers = Int.MAX_VALUE
        revealEnabled = false
        revealMinZ = 0f
        revealMaxZ = 0f
        revealFraction = 1f
    }
}
