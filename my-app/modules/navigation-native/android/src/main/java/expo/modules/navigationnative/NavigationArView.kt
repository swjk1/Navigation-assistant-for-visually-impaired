package expo.modules.navigationnative

import android.content.Context
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.util.Log
import android.view.Surface
import android.widget.FrameLayout
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.views.ExpoView
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The native view that owns the ARCore render loop.
 *
 * This deliberately renders NOTHING. ARCore requires a GL context and a camera texture to produce
 * frames, so we give it the minimum it needs - an external OES texture and a draw callback - and
 * use the resulting frames purely as a sensor feed. There are no virtual objects to draw, and
 * pulling in a full AR rendering framework to display a camera preview the user cannot see would
 * be wasted battery.
 *
 * Mount it anywhere in the React tree (1x1 px is fine); its job is lifecycle, not pixels.
 */
class NavigationArView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

    private val glSurfaceView = GLSurfaceView(context)
    private val renderer = ArRenderer()

    init {
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.setRenderer(renderer)
        glSurfaceView.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        addView(
            glSurfaceView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val activity = appContext.activityProvider?.currentActivity
        val error = NavigationRuntime.initializeSession(context, activity)
        if (error != null) {
            Log.w(TAG, "ARCore session not ready: $error")
            return
        }
        NavigationRuntime.resumeSession()?.let { Log.w(TAG, "ARCore resume failed: $it") }
        glSurfaceView.onResume()
    }

    override fun onDetachedFromWindow() {
        // Order matters: the GL thread must stop before the session is paused, otherwise
        // Session.update() can run against a paused session.
        glSurfaceView.onPause()
        NavigationRuntime.pauseSession()
        super.onDetachedFromWindow()
    }

    private inner class ArRenderer : GLSurfaceView.Renderer {

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR,
            )
            GLES20.glTexParameteri(
                GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR,
            )
            NavigationRuntime.setCameraTexture(textureId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            NavigationRuntime.setDisplayGeometry(displayRotation(), width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            NavigationRuntime.onGlFrame()
        }
    }

    private fun displayRotation(): Int = try {
        display?.rotation ?: Surface.ROTATION_0
    } catch (e: Throwable) {
        Surface.ROTATION_0
    }

    private companion object {
        const val TAG = "NavigationArView"
    }
}
