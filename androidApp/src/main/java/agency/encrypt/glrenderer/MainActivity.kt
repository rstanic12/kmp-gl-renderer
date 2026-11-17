package agency.encrypt.glrenderer

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.TextView
import agency.encrypt.glrenderer.assets.AndroidAssetSource
import agency.encrypt.glrenderer.demo.DemoApp
import agency.encrypt.glrenderer.gl.AndroidGl
import agency.encrypt.glrenderer.view.EngineSurfaceView

class MainActivity : Activity() {

    private lateinit var surface: EngineSurfaceView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = DemoApp(AndroidGl(), AndroidAssetSource(assets))
        val padding = (24 * resources.displayMetrics.density).toInt()

        val status = label(15f, 220).apply { setPadding(0, padding * 2, 0, 0) }
        val hint = label(13f, 170).apply {
            setText(R.string.hint)
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, padding)
        }
        app.onStatusChanged = { text -> runOnUiThread { status.text = text } }
        surface = DemoSurface(this, app)
        setContentView(FrameLayout(this).apply {
            addView(surface)
            addView(status, overlay(Gravity.TOP))
            addView(hint, overlay(Gravity.BOTTOM))
        })
    }

    private fun label(size: Float, alpha: Int) = TextView(this).apply {
        setTextColor(Color.argb(alpha, 255, 255, 255))
        textSize = size
    }

    private fun overlay(edge: Int) = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT,
        FrameLayout.LayoutParams.WRAP_CONTENT,
        edge or Gravity.CENTER_HORIZONTAL,
    )

    override fun onResume() {
        super.onResume()
        surface.onResume()
    }

    override fun onPause() {
        surface.onPause()
        super.onPause()
    }
}

/** The engine's surface view plus the demo's gestures, each queued onto the render thread. */
@SuppressLint("ViewConstructor")
private class DemoSurface(activity: Activity, private val app: DemoApp) : EngineSurfaceView(activity, app.loop) {

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

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            queueEvent { app.togglePipeline() }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            queueEvent { app.toggleLid() }
            return true
        }

        override fun onLongPress(e: MotionEvent) {
            queueEvent { app.togglePause() }
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        gestureDetector.onTouchEvent(event)
        return true
    }
}
