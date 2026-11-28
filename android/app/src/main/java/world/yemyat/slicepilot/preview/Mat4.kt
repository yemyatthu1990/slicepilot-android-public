package world.yemyat.slicepilot.preview

import kotlin.math.sqrt
import kotlin.math.tan

object Mat4 {
    fun identity(): FloatArray = floatArrayOf(
        1f, 0f, 0f, 0f,
        0f, 1f, 0f, 0f,
        0f, 0f, 1f, 0f,
        0f, 0f, 0f, 1f,
    )

    fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = (1.0 / tan(Math.toRadians(fovYDegrees.toDouble()) / 2.0)).toFloat()
        val out = FloatArray(16)
        out[0] = f / aspect
        out[5] = f
        out[10] = (far + near) / (near - far)
        out[11] = -1f
        out[14] = (2f * far * near) / (near - far)
        return out
    }

    fun lookAt(
        eyeX: Float, eyeY: Float, eyeZ: Float,
        centerX: Float, centerY: Float, centerZ: Float,
        upX: Float, upY: Float, upZ: Float,
    ): FloatArray {
        var fx = centerX - eyeX
        var fy = centerY - eyeY
        var fz = centerZ - eyeZ
        val fl = sqrt(fx * fx + fy * fy + fz * fz).coerceAtLeast(1e-9f)
        fx /= fl; fy /= fl; fz /= fl

        var sx = fy * upZ - fz * upY
        var sy = fz * upX - fx * upZ
        var sz = fx * upY - fy * upX
        val sl = sqrt(sx * sx + sy * sy + sz * sz).coerceAtLeast(1e-9f)
        sx /= sl; sy /= sl; sz /= sl

        val ux = sy * fz - sz * fy
        val uy = sz * fx - sx * fz
        val uz = sx * fy - sy * fx

        val out = FloatArray(16)
        out[0] = sx; out[4] = sy; out[8] = sz
        out[1] = ux; out[5] = uy; out[9] = uz
        out[2] = -fx; out[6] = -fy; out[10] = -fz
        out[12] = -(sx * eyeX + sy * eyeY + sz * eyeZ)
        out[13] = -(ux * eyeX + uy * eyeY + uz * eyeZ)
        out[14] = fx * eyeX + fy * eyeY + fz * eyeZ
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
        return out
    }

    fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val out = FloatArray(16)
        for (c in 0..3) {
            val c0 = c * 4
            val b0 = b[c0]; val b1 = b[c0 + 1]; val b2 = b[c0 + 2]; val b3 = b[c0 + 3]
            for (r in 0..3) {
                out[c0 + r] = a[r] * b0 + a[4 + r] * b1 + a[8 + r] * b2 + a[12 + r] * b3
            }
        }
        return out
    }

    fun modelMatrix(
        m3: FloatArray,
        pivotX: Float, pivotY: Float, pivotZ: Float,
        translateX: Float, translateY: Float, translateZ: Float,
    ): FloatArray {
        val out = FloatArray(16)
        out[0] = m3[0]; out[1] = m3[3]; out[2] = m3[6]
        out[4] = m3[1]; out[5] = m3[4]; out[6] = m3[7]
        out[8] = m3[2]; out[9] = m3[5]; out[10] = m3[8]
        out[12] = translateX - (m3[0] * pivotX + m3[1] * pivotY + m3[2] * pivotZ)
        out[13] = translateY - (m3[3] * pivotX + m3[4] * pivotY + m3[5] * pivotZ)
        out[14] = translateZ - (m3[6] * pivotX + m3[7] * pivotY + m3[8] * pivotZ)
        out[3] = 0f; out[7] = 0f; out[11] = 0f; out[15] = 1f
        return out
    }
}