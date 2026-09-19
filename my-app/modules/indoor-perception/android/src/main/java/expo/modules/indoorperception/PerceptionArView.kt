package expo.modules.indoorperception

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
 * Hosts the app's single ARCore session.
 *
 * ARCore only produces frames while a GL context and a camera texture exist, so this view must
 * stay mounted for as long as either perception or navigation is running. It renders nothing -
 * there are no virtual objects to draw, and the camera preview is of no use to a blind user.
 *
 * This replaces `CameraView` + `takePictureAsync`. Both features now read from the same frame:
 * navigation takes pose and depth, perception takes the RGB image on demand.
 */
class PerceptionArView(context: Context, appContext: AppContext) : ExpoView(context, appContext) {

    private val glSurfaceView = GLSurfaceView(context)

    init {
        glSurfaceView.preserveEGLContextOnPause = true
        glSurfaceView.setEGLContextClientVersion(2)
        glSurfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        glSurfaceView.setRenderer(ArRenderer())
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
        ArFrameSource.initialize(context)?.let {
            Log.w(TAG, "AR session not ready: $it")
            return
        }
        ArFrameSource.resume()?.let { Log.w(TAG, "AR resume failed: $it") }
        glSurfaceView.onResume()
    }

    override fun onDetachedFromWindow() {
        // The GL thread must stop before the session is paused, or update() can run against a
        // paused session.
        glSurfaceView.onPause()
        ArFrameSource.pause()
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
            ArFrameSource.setCameraTexture(textureId)
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            ArFrameSource.setDisplayGeometry(displayRotation(), width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            ArFrameSource.onDrawFrame()
        }
    }

    private fun displayRotation(): Int = try {
        display?.rotation ?: Surface.ROTATION_0
    } catch (e: Throwable) {
        Surface.ROTATION_0
    }

    private companion object {
        const val TAG = "PerceptionArView"
    }
}
