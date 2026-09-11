package com.bigp.rubiksolver.ui.components

import android.opengl.GLSurfaceView
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.bigp.rubiksolver.cube.model.CubeState
import com.bigp.rubiksolver.render.CubeRenderer

/**
 * The 3D cube, embedded in Compose.
 *
 * Rendering happens on demand rather than continuously: a cube sitting still costs nothing, and a
 * frame is only drawn while a turn is animating or a finger is dragging. On a phone held up to
 * compare against a real cube, that is the difference between a warm battery and a cool one.
 */
@Composable
fun CubeSurface(
    state: CubeState,
    modifier: Modifier = Modifier,
    onRendererReady: (CubeRenderer) -> Unit = {},
) {
    val renderer = remember(state.size) { CubeRenderer(state) }
    val density = LocalDensity.current.density
    val viewRef = remember { arrayOfNulls<GLSurfaceView>(1) }

    DisposableEffect(renderer) {
        onRendererReady(renderer)
        onDispose { }
    }

    AndroidView(
        modifier = modifier.pointerInput(renderer) {
            detectDragGestures(
                onDrag = { _, drag ->
                    renderer.nudge(
                        CubeRenderer.dragToRadians(drag.x, density),
                        CubeRenderer.dragToRadians(drag.y, density),
                    )
                    viewRef[0]?.requestRender()
                }
            )
        },
        factory = { context ->
            GLSurfaceView(context).apply {
                setEGLContextClientVersion(2)
                setRenderer(renderer)
                // The solution screen advances the renderer's time-based slice animation. Keeping
                // it continuous while that screen is visible ensures each turn is actually shown;
                // the GLSurfaceView is disposed as soon as the user leaves the solution.
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                viewRef[0] = this
            }
        },
        update = { view ->
            viewRef[0] = view
            view.requestRender()
        },
    )
}

/** Drives one turn on the renderer, asking for frames until it lands. */
suspend fun CubeRenderer.playMove(
    move: com.bigp.rubiksolver.cube.model.Move,
    requestFrame: () -> Unit,
    durationMillis: Long = CubeRenderer.DEFAULT_TURN_MILLIS,
) {
    var landed = false
    animate(move, durationMillis) { landed = true }
    val deadline = System.currentTimeMillis() + durationMillis + 400
    while (!landed && System.currentTimeMillis() < deadline) {
        requestFrame()
        kotlinx.coroutines.delay(16)
    }
    requestFrame()
}
