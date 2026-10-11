package world.yemyat.slicepilot.preview

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
        fun from(camera: OrbitCamera): CameraState = CameraState(
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