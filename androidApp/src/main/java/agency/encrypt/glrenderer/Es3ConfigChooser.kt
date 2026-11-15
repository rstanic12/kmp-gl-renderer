package agency.encrypt.glrenderer

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * RGB888, ES 3 capable, no depth and no MSAA: the renderer draws into its own framebuffers,
 * antialiases with FXAA and only copies the finished image to the window.
 */
internal class Es3ConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            EGL10.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        egl.eglChooseConfig(display, attributes, configs, 1, count)
        return configs[0].takeIf { count[0] > 0 } ?: error("No ES 3 EGL config available")
    }

    private companion object {
        // EGL_OPENGL_ES3_BIT_KHR; EGL10 predates it.
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
