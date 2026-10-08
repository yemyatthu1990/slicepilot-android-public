package world.yemyat.slicepilot.preview

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

data class ModelTransform(
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val mirrorZ: Boolean = false,
    val rotX: Float = 0f,
    val rotY: Float = 0f,
    val rotZ: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    fun matrix3(): FloatArray {
        val cx = cos(Math.toRadians(rotX.toDouble())).toFloat()
        val sx = sin(Math.toRadians(rotX.toDouble())).toFloat()
        val cy = cos(Math.toRadians(rotY.toDouble())).toFloat()
        val sy = sin(Math.toRadians(rotY.toDouble())).toFloat()
        val cz = cos(Math.toRadians(rotZ.toDouble())).toFloat()
        val sz = sin(Math.toRadians(rotZ.toDouble())).toFloat()
        val msx = if (mirrorX) -1f else 1f
        val msy = if (mirrorY) -1f else 1f
        val msz = if (mirrorZ) -1f else 1f
        val m = FloatArray(9)
        m[0] = cz * cy; m[1] = cz * sy * sx - sz * cx; m[2] = cz * sy * cx + sz * sx
        m[3] = sz * cy; m[4] = sz * sy * sx + cz * cx; m[5] = sz * sy * cx - cz * sx
        m[6] = -sy; m[7] = cy * sx; m[8] = cy * cx
        m[0] *= msx; m[1] *= msx; m[2] *= msx
        m[3] *= msy; m[4] *= msy; m[5] *= msy
        m[6] *= msz; m[7] *= msz; m[8] *= msz
        return m
    }

    fun extents(halfX: Float, halfY: Float, halfZ: Float): FloatArray {
        val m = matrix3()
        return floatArrayOf(
            abs(m[0]) * halfX + abs(m[1]) * halfY + abs(m[2]) * halfZ,
            abs(m[3]) * halfX + abs(m[4]) * halfY + abs(m[5]) * halfZ,
            abs(m[6]) * halfX + abs(m[7]) * halfY + abs(m[8]) * halfZ,
        )
    }

    fun withinPlate(
        pivotX: Float, pivotY: Float,
        halfX: Float, halfY: Float, halfZ: Float,
        plateMinX: Float, plateMaxX: Float,
        plateMinY: Float, plateMaxY: Float,
        epsilon: Float,
    ): Boolean {
        val ext = extents(halfX, halfY, halfZ)
        val cx = (plateMinX + plateMaxX) * 0.5f + offsetX
        val cy = (plateMinY + plateMaxY) * 0.5f + offsetY
        return !(cx - ext[0] < plateMinX - epsilon || cx + ext[0] > plateMaxX + epsilon ||
            cy - ext[1] < plateMinY - epsilon || cy + ext[1] > plateMaxY + epsilon)
    }

    fun worldMatrix(
        pivotX: Float, pivotY: Float, pivotZ: Float,
        bedCenterX: Float, bedCenterY: Float,
        dropZ: Float,
        halfX: Float, halfY: Float, halfZ: Float,
    ): FloatArray {
        val m = matrix3()
        val extZ = abs(m[6]) * halfX + abs(m[7]) * halfY + abs(m[8]) * halfZ
        val cx = bedCenterX + offsetX
        val cy = bedCenterY + offsetY
        val cz = dropZ + extZ
        return Mat4.modelMatrix(m, pivotX, pivotY, pivotZ, cx, cy, cz)
    }

    fun toArray(): FloatArray = floatArrayOf(
        rotX, rotY, rotZ,
        if (mirrorX) 1f else 0f,
        if (mirrorY) 1f else 0f,
        if (mirrorZ) 1f else 0f,
        offsetX, offsetY,
    )

    companion object {
        fun fromArray(t: FloatArray): ModelTransform {
            if (t.size < 8) return ModelTransform()
            return ModelTransform(
                mirrorX = t[3] != 0f,
                mirrorY = t[4] != 0f,
                mirrorZ = t[5] != 0f,
                rotX = t[0], rotY = t[1], rotZ = t[2],
                offsetX = t[6], offsetY = t[7],
            )
        }
    }
}