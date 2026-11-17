package agency.encrypt.glrenderer.view

import android.annotation.SuppressLint
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.SystemClock
import agency.encrypt.glrenderer.render.RenderLoop
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * A GLSurfaceView that runs a [RenderLoop]: an OpenGL ES 3 context, continuous rendering, and the loop's callbacks
 * on the render thread. Forward [onResume] and [onPause] from the activity. To change anything the loop renders
 * from the UI thread (input, settings), wrap it in [queueEvent] so it runs on the render thread.
 */
@SuppressLint("ViewConstructor")
public open class EngineSurfaceView(context: Context, public val loop: RenderLoop) : GLSurfaceView(context) {

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(Es3ConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = loop.surfaceCreated()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = loop.surfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) = loop.drawFrame(SystemClock.elapsedRealtimeNanos() / 1e9)
        })
    }
}
