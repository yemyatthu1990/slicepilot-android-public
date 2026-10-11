package world.yemyat.slicepilot.preview

import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

class OrbitCamera {
    var theta: Float = DEFAULT_THETA
    var phi: Float = DEFAULT_PHI
    var radius: Float = 100f
    var targetX: Float = 0f
    var targetY: Float = 0f
    var targetZ: Float = 0f
    var fitCenterX: Float = 0f
    var fitCenterY: Float = 0f
    var fitCenterZ: Float = 0f
    var fitMaxRadius: Float = 100f
    var userZoomed: Boolean = false

    fun frame(centerX: Float, centerY: Float, centerZ: Float, fitRadius: Float) {
        targetX = centerX; targetY = centerY; targetZ = centerZ
        fitCenterX = centerX; fitCenterY = centerY; fitCenterZ = centerZ
        fitMaxRadius = max(1f, fitRadius)
        userZoomed = false
        theta = DEFAULT_THETA
        phi = DEFAULT_PHI
    }

    fun reframe() {
        targetX = fitCenterX; targetY = fitCenterY; targetZ = fitCenterZ
        userZoomed = false
    }

    fun eyeX(): Float = targetX + radius * sin(phi) * cos(theta)
    fun eyeY(): Float = targetY + radius * sin(phi) * sin(theta)
    fun eyeZ(): Float = targetZ + radius * cos(phi)

    fun fitRadius(width: Float, height: Float, fovYDegrees: Float, margin: Float): Float {
        val fovV = Math.toRadians(fovYDegrees.toDouble())
        val aspect = if (height > 0f) (width / height).toDouble() else 1.0
        val fovH = 2.0 * atan(tan(fovV / 2.0) * aspect)
        val fovFit = min(fovV, fovH)
        val d = fitMaxRadius / sin(fovFit / 2.0).toFloat() * margin
        return max(1f, d)
    }

    fun minZoom(): Float = max(0.5f, fitMaxRadius * 0.02f)

    fun maxZoom(): Float = max(fitMaxRadius * 30f, 1000f)

    fun zoomBy(scaleFactor: Float, currentRadius: Float) {
        userZoomed = true
        radius = (currentRadius / scaleFactor).coerceIn(minZoom(), maxZoom())
    }

    fun pan(distanceX: Float, distanceY: Float, viewportHeight: Float, radius: Float, fovYDegrees: Float) {
        if (viewportHeight <= 0f) return
        val worldPerPixel = 2f * radius * tan(Math.toRadians(fovYDegrees / 2.0).toFloat()) / viewportHeight
        val sp = sin(phi)
        val cp = cos(phi)
        targetX += (-sin(theta) * distanceX - cos(theta) * cp * distanceY) * worldPerPixel
        targetY += (cos(theta) * distanceX - sin(theta) * cp * distanceY) * worldPerPixel
        targetZ += sp * distanceY * worldPerPixel
    }

    companion object {
        const val DEFAULT_THETA = 0.8f
        const val DEFAULT_PHI = 1.05f
        val PHI_MIN = 0.08f
        val PHI_MAX = Math.PI.toFloat() - 0.08f
    }
}