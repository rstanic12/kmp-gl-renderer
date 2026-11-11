package agency.encrypt.glrenderer

import android.opengl.GLSurfaceView
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/** RGB888 + depth, ES 3 capable, with 4x MSAA when the device offers it. */
internal class MultisampleConfigChooser : GLSurfaceView.EGLConfigChooser {

    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig =
        choose(egl, display, samples = 4) ?: choose(egl, display, samples = 0) ?: error("No ES 3 EGL config available")

    private fun choose(egl: EGL10, display: EGLDisplay, samples: Int): EGLConfig? {
        val multisample = if (samples > 0) intArrayOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples) else intArrayOf()
        val attributes = intArrayOf(
            EGL10.EGL_RED_SIZE, 8,
            EGL10.EGL_GREEN_SIZE, 8,
            EGL10.EGL_BLUE_SIZE, 8,
            EGL10.EGL_DEPTH_SIZE, 16,
            EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
        ) + multisample + EGL10.EGL_NONE
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        egl.eglChooseConfig(display, attributes, configs, 1, count)
        return if (count[0] > 0) configs[0] else null
    }

    private companion object {
        // EGL_OPENGL_ES3_BIT_KHR; EGL10 predates it.
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}
