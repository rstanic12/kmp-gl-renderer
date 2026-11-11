package agency.encrypt.glrenderer

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import agency.encrypt.glrenderer.assets.AndroidAssetSource
import agency.encrypt.glrenderer.demo.DemoApp
import agency.encrypt.glrenderer.gl.AndroidGl
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class MainActivity : Activity() {

    private lateinit var surface: GLSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = DemoApp(AndroidGl(), AndroidAssetSource(assets))

        surface = DemoSurface(this, app)
        val hint = TextView(this).apply {
            setText(R.string.hint)
            setTextColor(Color.argb(170, 255, 255, 255))
            textSize = 13f
            setPadding(0, 0, 0, (24 * resources.displayMetrics.density).toInt())
        }
        setContentView(FrameLayout(this).apply {
            addView(surface)
            addView(hint, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ))
        })
    }

    override fun onResume() {
        super.onResume()
        surface.onResume()
    }

    override fun onPause() {
        surface.onPause()
        super.onPause()
    }
}

/** Hosts [DemoApp]: GL callbacks run on the render thread, and touch input is queued onto it. */
@SuppressLint("ViewConstructor")
private class DemoSurface(activity: Activity, private val app: DemoApp) : GLSurfaceView(activity) {

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val factor = detector.scaleFactor
            queueEvent { app.zoom(factor) }
            return true
        }
    })

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (e2.pointerCount > 1) return false
            val dx = -distanceX / width
            val dy = -distanceY / height
            queueEvent { app.orbit(dx, dy) }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { app.toggleLid() }
            return true
        }
    })

    init {
        setEGLContextClientVersion(3)
        setEGLConfigChooser(MultisampleConfigChooser())
        preserveEGLContextOnPause = true
        setRenderer(object : Renderer {
            override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) = app.surfaceCreated()
            override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = app.surfaceChanged(width, height)
            override fun onDrawFrame(gl: GL10?) = app.drawFrame(SystemClock.elapsedRealtimeNanos() / 1e9)
        })
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
