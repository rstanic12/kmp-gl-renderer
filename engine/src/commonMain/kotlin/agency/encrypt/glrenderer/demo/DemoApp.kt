package agency.encrypt.glrenderer.demo

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.render.Renderer
import agency.encrypt.glrenderer.scene.OrbitCamera

/**
 * Everything a platform host needs: forward the GL surface lifecycle and touch input, all on the GL thread.
 * The scene survives a GL context loss; only the [Renderer] and its GPU resources are recreated.
 */
class DemoApp(private val gl: Gl, private val assets: AssetSource) {

    private val world = DemoScene.create()
    private val camera = OrbitCamera(target = Vec3(0f, 0.55f, 0f), yawDegrees = 35f, pitchDegrees = 22f, distance = 4.4f)
    private var renderer: Renderer? = null
    private var width = 1
    private var height = 1
    private var lastFrameSeconds: Double? = null

    fun surfaceCreated() {
        renderer = Renderer(gl, assets).also { it.resize(width, height) }
    }

    fun surfaceChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
        renderer?.resize(width, height)
    }

    fun drawFrame(timeSeconds: Double) {
        val delta = lastFrameSeconds?.let { (timeSeconds - it).toFloat().coerceIn(0f, MAX_STEP) } ?: 0f
        lastFrameSeconds = timeSeconds
        world.update(delta)
        checkNotNull(renderer) { "drawFrame before surfaceCreated" }.render(world, camera)
    }

    /** Drag, as a fraction of the view's width and height: a full-width swipe turns the camera half a circle. */
    fun orbit(dxFraction: Float, dyFraction: Float) {
        camera.orbit(-dxFraction * 180f, dyFraction * 90f)
    }

    /** Pinch scale factor; above 1 zooms in. */
    fun zoom(factor: Float) {
        camera.zoom(factor)
    }

    fun toggleLid() {
        DemoScene.toggleLid(world)
    }

    private companion object {
        const val MAX_STEP = 0.1f
    }
}
