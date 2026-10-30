package com.rawpocket

import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * Vista previa revelada en GPU. Cada cambio de [params] solo sube 10 números a la GPU y pide un
 * fotograma: no hay copias de píxeles entre CPU y GPU mientras se mueven los controles.
 */
@Composable
fun GpuPreview(photo: GpuPhoto?, params: FloatArray, view: PreviewView, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val renderer = remember { DevelopRenderer(context.applicationContext, view) }
    val glView = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            preserveEGLContextOnPause = true
            setRenderer(renderer)
            renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY  // solo se dibuja cuando algo cambia
        }
    }

    DisposableEffect(view, glView) {
        view.invalidate = { glView.requestRender() }
        onDispose { view.invalidate = {} }
    }

    // El hilo de GL se pausa y reanuda con la actividad.
    DisposableEffect(lifecycleOwner, glView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> glView.onResume()
                Lifecycle.Event.ON_PAUSE -> glView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            glView.onPause()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { glView },
        update = { view ->
            renderer.photo = photo
            renderer.params = params
            view.requestRender()
        },
    )
}
