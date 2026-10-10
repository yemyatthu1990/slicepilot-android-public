package world.yemyat.slicepilot.preview

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

class PreviewRenderer(private val scene: PreviewScene) : GLSurfaceView.Renderer {

    private class GpuPart(val vbo: Int, val cumulativeVertices: IntArray, val layerCount: Int)

    private var program = 0
    private var uModel = 0
    private var uViewProj = 0
    private var uColor = 0
    private var uAlpha = 0
    private var uRevealZ = 0
    private var uPlateMin = 0
    private var uPlateMax = 0
    private var uPlateEdge = 0
    private var viewportWidth = 1
    private var viewportHeight = 1

    private var modelVbo = 0
    private var modelIbo = 0
    private var modelIndexCount = 0
    private var uploadedModel: MeshGeometry? = null

    private var plateVbo = 0
    private var uploadedPlate: PlaneGeometry? = null

    private var gpuGcode: List<GpuPart> = emptyList()
    private var uploadedGcode: List<GcodePartGeometry>? = null

    private val single = IntArray(1)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glClearColor(0.93f, 0.94f, 0.96f, 1f)
        program = buildProgram()
        if (program != 0) {
            uModel = GLES30.glGetUniformLocation(program, "uModel")
            uViewProj = GLES30.glGetUniformLocation(program, "uViewProj")
            uColor = GLES30.glGetUniformLocation(program, "uColor")
            uAlpha = GLES30.glGetUniformLocation(program, "uAlpha")
            uRevealZ = GLES30.glGetUniformLocation(program, "uRevealZ")
            uPlateMin = GLES30.glGetUniformLocation(program, "uPlateMin")
            uPlateMax = GLES30.glGetUniformLocation(program, "uPlateMax")
            uPlateEdge = GLES30.glGetUniformLocation(program, "uPlateEdge")
        }
        deleteUploads()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewportWidth = width.coerceAtLeast(1)
        viewportHeight = height.coerceAtLeast(1)
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (program == 0) return
        syncUploads()

        val background = scene.background
        GLES30.glClearColor(background[0], background[1], background[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        val camera = scene.camera
        val aspect = viewportWidth.toFloat() / viewportHeight.toFloat()
        val radius = if (camera.userZoomed) camera.radius else fitRadius(camera.fitRadius, aspect)
        val near = max(0.05f, radius * 0.01f)
        val far = (radius + camera.fitRadius * 2f) * 4f + 1000f
        val projection = Mat4.perspective(FIELD_OF_VIEW_DEGREES, aspect, near, far)

        val sp = sin(camera.phi)
        val cp = cos(camera.phi)
        val eyeX = camera.targetX + radius * sp * cos(camera.theta)
        val eyeY = camera.targetY + radius * sp * sin(camera.theta)
        val eyeZ = camera.targetZ + radius * cp
        val view = Mat4.lookAt(
            eyeX, eyeY, eyeZ,
            camera.targetX, camera.targetY, camera.targetZ,
            0f, 0f, 1f,
        )
        val viewProjection = Mat4.multiply(projection, view)
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, viewProjection, 0)

        drawModel()
        drawGcode()
        drawPlate()

        GLES30.glDisableVertexAttribArray(0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
    }

    private fun syncUploads() {
        val model = scene.model
        if (model !== uploadedModel) {
            if (modelVbo != 0) { single[0] = modelVbo; GLES30.glDeleteBuffers(1, single, 0); modelVbo = 0 }
            if (modelIbo != 0) { single[0] = modelIbo; GLES30.glDeleteBuffers(1, single, 0); modelIbo = 0 }
            modelIndexCount = 0
            if (model != null) {
                modelVbo = uploadVertices(model.vertices)
                modelIbo = uploadIndices(model.indices)
                modelIndexCount = model.indices.size
            }
            uploadedModel = model
        }

        val plate = scene.plate
        if (plate !== uploadedPlate) {
            if (plateVbo != 0) { single[0] = plateVbo; GLES30.glDeleteBuffers(1, single, 0); plateVbo = 0 }
            if (plate != null) plateVbo = uploadVertices(plate.vertices)
            uploadedPlate = plate
        }

        val gcode = scene.gcode
        if (gcode !== uploadedGcode) {
            for (part in gpuGcode) {
                single[0] = part.vbo
                GLES30.glDeleteBuffers(1, single, 0)
            }
            gpuGcode = gcode?.map {
                GpuPart(uploadVertices(it.vertices), it.cumulativeVertices, it.layerCount)
            } ?: emptyList()
            uploadedGcode = gcode
        }
    }

    private fun drawPlate() {
        val plate = uploadedPlate ?: return
        if (plateVbo == 0) return
        bindVertexLayout(plateVbo)
        GLES30.glUniformMatrix4fv(uModel, 1, false, IDENTITY, 0)
        GLES30.glUniform1f(uRevealZ, NO_CLIP)
        GLES30.glUniform1f(uAlpha, PLATE_ALPHA)
        GLES30.glUniform2f(uPlateMin, plate.minX, plate.minY)
        GLES30.glUniform2f(uPlateMax, plate.maxX, plate.maxY)
        GLES30.glUniform1f(uPlateEdge, max(1.5f, min(plate.maxX - plate.minX, plate.maxY - plate.minY) * 0.0075f))
        GLES30.glUniform3f(uColor, scene.plateColor[0], scene.plateColor[1], scene.plateColor[2])
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDepthMask(false)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, plate.vertices.size / 6)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private fun drawModel() {
        if (uploadedModel == null) return
        if (modelVbo == 0 || modelIbo == 0 || modelIndexCount == 0) return
        bindVertexLayout(modelVbo)
        GLES30.glUniformMatrix4fv(uModel, 1, false, scene.transform, 0)
        GLES30.glUniform1f(uRevealZ, revealZ())
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform1f(uPlateEdge, 0f)
        setColor(scene.modelColor)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, modelIbo)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, modelIndexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    private fun drawGcode() {
        if (gpuGcode.isEmpty()) return
        val layers = scene.visibleLayers
        GLES30.glUniformMatrix4fv(uModel, 1, false, IDENTITY, 0)
        GLES30.glUniform1f(uRevealZ, NO_CLIP)
        GLES30.glUniform1f(uAlpha, 1f)
        GLES30.glUniform1f(uPlateEdge, 0f)
        var i = 0
        for (part in uploadedGcode ?: return) {
            val gpu = gpuGcode[i++]
            val n = layers.coerceIn(0, part.layerCount)
            val count = part.cumulativeVertices[n]
            if (count <= 0 || gpu.vbo == 0) continue
            bindPositionOnlyLayout(gpu.vbo)
            setColor(part.color)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, count)
        }
    }

    private fun revealZ(): Float {
        if (!scene.revealEnabled) return NO_CLIP
        val minZ = scene.revealMinZ
        return minZ + scene.revealFraction.coerceIn(0f, 1f) * (scene.revealMaxZ - minZ)
    }

    private fun bindVertexLayout(vbo: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, STRIDE_BYTES, NORMAL_OFFSET_BYTES)
    }

    private fun bindPositionOnlyLayout(vbo: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, POSITION_STRIDE_BYTES, 0)
        GLES30.glDisableVertexAttribArray(1)
        GLES30.glVertexAttrib3f(1, 0f, 0f, 1f)
    }

    private fun setColor(color: FloatArray) {
        GLES30.glUniform3f(uColor, color[0], color[1], color[2])
    }

    private fun fitRadius(fitMaxRadius: Float, aspect: Float): Float {
        val fovV = Math.toRadians(FIELD_OF_VIEW_DEGREES.toDouble())
        val fovH = 2.0 * atan(tan(fovV / 2.0) * aspect)
        val fovFit = min(fovV, fovH)
        return max(1f, fitMaxRadius / sin(fovFit / 2.0).toFloat() * FIT_MARGIN)
    }

    private fun uploadVertices(data: FloatArray): Int {
        val buffer = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(data)
        buffer.position(0)
        GLES30.glGenBuffers(1, single, 0)
        val id = single[0]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)
        return id
    }

    private fun uploadIndices(indices: IntArray): Int {
        val buffer = ByteBuffer.allocateDirect(indices.size * 4).order(ByteOrder.nativeOrder())
        buffer.asIntBuffer().put(indices)
        buffer.position(0)
        GLES30.glGenBuffers(1, single, 0)
        val id = single[0]
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, id)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, buffer, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, 0)
        return id
    }

    private fun deleteUploads() {
        if (modelVbo != 0) { single[0] = modelVbo; GLES30.glDeleteBuffers(1, single, 0); modelVbo = 0 }
        if (modelIbo != 0) { single[0] = modelIbo; GLES30.glDeleteBuffers(1, single, 0); modelIbo = 0 }
        if (plateVbo != 0) { single[0] = plateVbo; GLES30.glDeleteBuffers(1, single, 0); plateVbo = 0 }
        for (part in gpuGcode) {
            single[0] = part.vbo
            GLES30.glDeleteBuffers(1, single, 0)
        }
        gpuGcode = emptyList()
        uploadedModel = null
        uploadedPlate = null
        uploadedGcode = null
        modelIndexCount = 0
    }

    private fun buildProgram(): Int {
        val vertex = compileShader(GLES30.GL_VERTEX_SHADER, Shaders.VERTEX)
        val fragment = compileShader(GLES30.GL_FRAGMENT_SHADER, Shaders.FRAGMENT)
        if (vertex == 0 || fragment == 0) {
            if (vertex != 0) GLES30.glDeleteShader(vertex)
            if (fragment != 0) GLES30.glDeleteShader(fragment)
            return 0
        }
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        val status = IntArray(1)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Program link failed: ${GLES30.glGetProgramInfoLog(program)}")
            GLES30.glDeleteProgram(program)
            return 0
        }
        return program
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] != GLES30.GL_TRUE) {
            Log.e(TAG, "Shader compile failed: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    companion object {
        private const val TAG = "PreviewRenderer"
        private const val FIELD_OF_VIEW_DEGREES = 40f
        private const val FIT_MARGIN = 1.25f
        private const val STRIDE_BYTES = 24
        private const val POSITION_STRIDE_BYTES = 12
        private const val NORMAL_OFFSET_BYTES = 12
        private const val NO_CLIP = 1.0e9f
        private const val PLATE_ALPHA = 0.5f
        private val IDENTITY = Mat4.identity()
    }
}