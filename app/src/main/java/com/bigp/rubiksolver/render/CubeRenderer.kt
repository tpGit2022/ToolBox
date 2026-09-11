package com.bigp.rubiksolver.render

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.cube.model.Direction
import com.bigp.rubiksolver.cube.model.FaceId
import com.bigp.rubiksolver.cube.model.FaceletColor
import com.bigp.rubiksolver.cube.model.Move
import com.bigp.rubiksolver.cube.model.MoveEngine
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Draws an N x N x N cube and animates one turn at a time.
 *
 * Written against OpenGL ES 2.0 directly rather than pulling in a 3D engine: there is exactly one
 * object in this scene, its geometry is a few hundred triangles, and the only interesting part is
 * rotating a chosen slab of it. A renderer for that is smaller than the configuration a general
 * engine would need, and it leaves the animation entirely under this app's control.
 *
 * Geometry matches [MoveEngine] exactly -- same axes, same coordinates, same idea of which layers a
 * move turns -- so what the user sees on screen is the same move the solver is talking about.
 */
class CubeRenderer(initialState: CubeState) : GLSurfaceView.Renderer {

    @Volatile
    private var state: CubeState = initialState

    /** Turn in flight, if any. */
    private class Animation(val move: Move, val durationMillis: Long) {
        var startedAt: Long = 0L
        var progress: Float = 0f
    }

    @Volatile
    private var animation: Animation? = null

    @Volatile
    private var onAnimationFinished: (() -> Unit)? = null

    /** Viewer-controlled orientation, in radians. */
    @Volatile
    var yaw: Float = 0.6f

    @Volatile
    var pitch: Float = -0.5f

    private var program = 0
    private var aPosition = 0
    private var aNormal = 0
    private var aColor = 0
    private var uMvp = 0
    private var uModel = 0

    private lateinit var positionBuffer: FloatBuffer
    private lateinit var normalBuffer: FloatBuffer
    private lateinit var colorBuffer: FloatBuffer

    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProjection = FloatArray(16)
    private val model = FloatArray(16)
    private val mvp = FloatArray(16)
    private val spin = FloatArray(16)
    private val orient = FloatArray(16)
    private val scratch = FloatArray(16)

    // ---- Public control -------------------------------------------------------------------

    fun setState(newState: CubeState) {
        state = newState
        animation = null
    }

    /** Starts turning [move]; [onFinished] runs on the GL thread once the turn lands. */
    fun animate(move: Move, durationMillis: Long = DEFAULT_TURN_MILLIS, onFinished: () -> Unit) {
        onAnimationFinished = onFinished
        animation = Animation(move, durationMillis)
    }

    val isAnimating: Boolean get() = animation != null

    fun nudge(deltaYaw: Float, deltaPitch: Float) {
        yaw += deltaYaw
        pitch = (pitch + deltaPitch).coerceIn(-1.35f, 1.35f)
    }

    // ---- GLSurfaceView.Renderer ------------------------------------------------------------

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.027f, 0.035f, 0.06f, 1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        // No back-face culling: the scene is a few hundred triangles, and the depth test alone
        // gets it right without any winding order to keep straight.

        val vertex = GLES20.glCreateShader(GLES20.GL_VERTEX_SHADER).also {
            GLES20.glShaderSource(it, VERTEX_SHADER)
            GLES20.glCompileShader(it)
        }
        val fragment = GLES20.glCreateShader(GLES20.GL_FRAGMENT_SHADER).also {
            GLES20.glShaderSource(it, FRAGMENT_SHADER)
            GLES20.glCompileShader(it)
        }
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, vertex)
            GLES20.glAttachShader(it, fragment)
            GLES20.glLinkProgram(it)
        }
        aPosition = GLES20.glGetAttribLocation(program, "aPosition")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uModel = GLES20.glGetUniformLocation(program, "uModel")

        positionBuffer = allocate(CUBE_POSITIONS)
        normalBuffer = allocate(CUBE_NORMALS)
        colorBuffer = allocateEmpty(CUBE_POSITIONS.size)
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / height.coerceAtLeast(1)
        Matrix.perspectiveM(projection, 0, 42f, aspect, 1f, 30f)
    }

    override fun onDrawFrame(unused: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        advanceAnimation()

        val n = state.n
        // Frame the cube so every size fills about the same amount of screen.
        val distance = 4.2f + n * 0.95f
        Matrix.setLookAtM(view, 0, 0f, 0f, distance, 0f, 0f, 0f, 0f, 1f, 0f)
        Matrix.setIdentityM(orient, 0)
        Matrix.rotateM(orient, 0, Math.toDegrees(pitch.toDouble()).toFloat(), 1f, 0f, 0f)
        Matrix.rotateM(orient, 0, Math.toDegrees(yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(scratch, 0, view, 0, orient, 0)
        Matrix.multiplyMM(viewProjection, 0, projection, 0, scratch, 0)

        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(aPosition)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glVertexAttribPointer(aPosition, 3, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, 0, normalBuffer)

        val active = animation
        val turningCoordinate = active?.let { layerCoordinateSet(it.move, n) }
        val axis = active?.let { axisVector(it.move.face) }
        val angle = active?.let {
            // Animate along the shortest visible path. CCW is represented as three clockwise
            // quarter-turns in the state engine, but drawing that literally would misleadingly
            // show a 270-degree clockwise spin instead of a 90-degree anticlockwise one.
            val target = when (it.move.direction) {
                Direction.CW -> -90f
                Direction.CCW -> 90f
                Direction.DOUBLE -> -180f
            }
            target * it.progress
        } ?: 0f

        val half = (n - 1) / 2f
        for (x in 0 until n) for (y in 0 until n) for (z in 0 until n) {
            if (!isSurfaceCubie(x, y, z, n)) continue
            fillColors(x, y, z, n)
            Matrix.setIdentityM(model, 0)
            if (turningCoordinate != null && axis != null) {
                val coord = intArrayOf(x, y, z)
                if (coord[axisIndex(active.move.face)] in turningCoordinate) {
                    // Matrix.multiplyMM must not alias its inputs, and spin times identity is spin.
                    Matrix.setIdentityM(spin, 0)
                    Matrix.rotateM(spin, 0, angle, axis[0], axis[1], axis[2])
                    System.arraycopy(spin, 0, model, 0, 16)
                }
            }
            Matrix.translateM(model, 0, (x - half) * SPACING, (y - half) * SPACING, (z - half) * SPACING)
            Matrix.multiplyMM(mvp, 0, viewProjection, 0, model, 0)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uModel, 1, false, model, 0)
            GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, 0, colorBuffer)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, CUBE_POSITIONS.size / 3)
        }

        GLES20.glDisableVertexAttribArray(aPosition)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
    }

    // ---- Internals -------------------------------------------------------------------------

    private fun advanceAnimation() {
        val active = animation ?: return
        val now = System.nanoTime() / 1_000_000L
        if (active.startedAt == 0L) active.startedAt = now
        val elapsed = (now - active.startedAt).toFloat() / active.durationMillis
        if (elapsed >= 1f) {
            // Commit the turn to the model, then drop the animation.
            state = state.apply(active.move)
            animation = null
            onAnimationFinished?.invoke()
            onAnimationFinished = null
        } else {
            active.progress = easeInOut(elapsed.coerceIn(0f, 1f))
        }
    }

    /** Smoothstep. A turn that starts and stops abruptly reads as a glitch rather than a move. */
    private fun easeInOut(t: Float): Float = t * t * (3f - 2f * t)

    private fun layerCoordinateSet(move: Move, n: Int): Set<Int> =
        move.depths.map { depth ->
            when (move.face) {
                FaceId.U, FaceId.R, FaceId.F -> n - 1 - depth
                FaceId.D, FaceId.L, FaceId.B -> depth
            }
        }.toSet()

    private fun axisIndex(face: FaceId): Int = when (face) {
        FaceId.R, FaceId.L -> 0
        FaceId.U, FaceId.D -> 1
        FaceId.F, FaceId.B -> 2
    }

    private fun axisVector(face: FaceId): FloatArray {
        val n = MoveEngine.normalOf(face)
        return floatArrayOf(n[0].toFloat(), n[1].toFloat(), n[2].toFloat())
    }

    private fun isSurfaceCubie(x: Int, y: Int, z: Int, n: Int): Boolean =
        x == 0 || y == 0 || z == 0 || x == n - 1 || y == n - 1 || z == n - 1

    /**
     * Writes the six face colours of one cubie into the shared colour buffer.
     *
     * Faces that point into the cube get the plastic colour, so a turn in progress shows dark
     * interior walls the way a real cube does instead of flashing stickers that are not there.
     */
    private fun fillColors(x: Int, y: Int, z: Int, n: Int) {
        colorBuffer.position(0)
        for (faceIndex in FACE_ORDER.indices) {
            val face = FACE_ORDER[faceIndex]
            val colour = stickerColour(x, y, z, face, n)
            repeat(6) {
                colorBuffer.put(colour[0])
                colorBuffer.put(colour[1])
                colorBuffer.put(colour[2])
            }
        }
        colorBuffer.position(0)
    }

    private val plastic = floatArrayOf(0.055f, 0.065f, 0.09f)

    private fun stickerColour(x: Int, y: Int, z: Int, face: FaceId, n: Int): FloatArray {
        val onFace = when (face) {
            FaceId.U -> y == n - 1
            FaceId.D -> y == 0
            FaceId.R -> x == n - 1
            FaceId.L -> x == 0
            FaceId.F -> z == n - 1
            FaceId.B -> z == 0
        }
        if (!onFace) return plastic
        val rowCol = faceRowCol(face, x, y, z, n)
        val colour = state.colorAt(face, rowCol[0], rowCol[1])
        return rgbOf(colour)
    }

    /** Inverse of [MoveEngine.coordOf] for one face. */
    private fun faceRowCol(face: FaceId, x: Int, y: Int, z: Int, n: Int): IntArray {
        val m = n - 1
        return when (face) {
            FaceId.U -> intArrayOf(z, x)
            FaceId.D -> intArrayOf(m - z, x)
            FaceId.F -> intArrayOf(m - y, x)
            FaceId.B -> intArrayOf(m - y, m - x)
            FaceId.R -> intArrayOf(m - y, m - z)
            FaceId.L -> intArrayOf(m - y, z)
        }
    }

    private val colourCache = HashMap<FaceletColor, FloatArray>()

    private fun rgbOf(colour: FaceletColor): FloatArray = colourCache.getOrPut(colour) {
        val argb = colour.srgb
        floatArrayOf(
            ((argb shr 16) and 0xFF) / 255f,
            ((argb shr 8) and 0xFF) / 255f,
            (argb and 0xFF) / 255f,
        )
    }

    private fun allocate(data: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(data); position(0) }

    private fun allocateEmpty(size: Int): FloatBuffer =
        ByteBuffer.allocateDirect(size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    companion object {
        const val DEFAULT_TURN_MILLIS = 300L

        /** Cubie size plus the gap between cubies. */
        private const val SPACING = 1.06f
        private const val HALF = 0.5f

        /** Face draw order; the geometry below is written in this order. */
        private val FACE_ORDER = listOf(FaceId.U, FaceId.R, FaceId.F, FaceId.D, FaceId.L, FaceId.B)

        private fun quad(
            a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray,
        ): FloatArray = floatArrayOf(
            a[0], a[1], a[2], b[0], b[1], b[2], c[0], c[1], c[2],
            a[0], a[1], a[2], c[0], c[1], c[2], d[0], d[1], d[2],
        )

        private fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)

        /** Six faces, two triangles each, wound counter-clockwise seen from outside. */
        private val CUBE_POSITIONS: FloatArray = run {
            val h = HALF
            val u = quad(v(-h, h, h), v(h, h, h), v(h, h, -h), v(-h, h, -h))
            val r = quad(v(h, h, h), v(h, -h, h), v(h, -h, -h), v(h, h, -h))
            val f = quad(v(-h, -h, h), v(h, -h, h), v(h, h, h), v(-h, h, h))
            val d = quad(v(-h, -h, -h), v(h, -h, -h), v(h, -h, h), v(-h, -h, h))
            val l = quad(v(-h, h, -h), v(-h, -h, -h), v(-h, -h, h), v(-h, h, h))
            val b = quad(v(h, -h, -h), v(-h, -h, -h), v(-h, h, -h), v(h, h, -h))
            u + r + f + d + l + b
        }

        private val CUBE_NORMALS: FloatArray = run {
            val normals = listOf(
                v(0f, 1f, 0f), v(1f, 0f, 0f), v(0f, 0f, 1f),
                v(0f, -1f, 0f), v(-1f, 0f, 0f), v(0f, 0f, -1f),
            )
            val out = FloatArray(CUBE_POSITIONS.size)
            var i = 0
            for (nrm in normals) repeat(6) {
                out[i++] = nrm[0]; out[i++] = nrm[1]; out[i++] = nrm[2]
            }
            out
        }

        private const val VERTEX_SHADER = """
            uniform mat4 uMvp;
            uniform mat4 uModel;
            attribute vec4 aPosition;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vColor;
            void main() {
                vec3 worldNormal = normalize(mat3(uModel[0].xyz, uModel[1].xyz, uModel[2].xyz) * aNormal);
                vec3 keyLight = normalize(vec3(0.35, 0.75, 0.55));
                vec3 fillLight = normalize(vec3(-0.5, -0.2, 0.4));
                float key = max(dot(worldNormal, keyLight), 0.0);
                float fill = max(dot(worldNormal, fillLight), 0.0);
                float shade = 0.58 + 0.34 * key + 0.12 * fill;
                vColor = aColor * shade;
                gl_Position = uMvp * aPosition;
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            varying vec3 vColor;
            void main() {
                gl_FragColor = vec4(vColor, 1.0);
            }
        """

        /** Converts a drag in pixels into a rotation that feels one-to-one under a finger. */
        fun dragToRadians(pixels: Float, density: Float): Float = pixels / (density * 140f)
    }
}
