package agency.encrypt.glrenderer.demo

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.render.Pipeline
import agency.encrypt.glrenderer.render.Renderer
import agency.encrypt.glrenderer.scene.PointLight

/**
 * Everything a platform host needs: forward the GL surface lifecycle and touch input, all on the GL thread.
 * The scene and the chosen settings survive a GL context loss; only the [Renderer] and its GPU resources are recreated.
 */
class DemoApp(private val gl: Gl, private val assets: AssetSource) {

    private val world = DemoScene.create()
    private val camera = DemoScene.camera()
    private val lightCount = world.entities.count { it.has<PointLight>() }
    private var renderer: Renderer? = null
    private var pipeline = Pipeline.DEFERRED
    private var paused = false
    private var width = 1
    private var height = 1
    private var lastFrameSeconds: Double? = null

    /** One line for the host to show, e.g. "Deferred · 32 point lights". */
    val status: String
        get() {
            val hdr = if (renderer?.hdr == false) " · no HDR on this GPU" else ""
            val pause = if (paused) " · paused" else ""
            return "${pipeline.label} · $lightCount point lights$hdr$pause"
        }

    fun surfaceCreated() {
        renderer = Renderer(gl, assets).also {
            it.resize(width, height)
            it.shadowCenter = Vec3(0f, 0.4f, 0f)
            it.shadowRadius = 2.2f
        }
    }

    fun surfaceChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
        renderer?.resize(width, height)
    }

    fun drawFrame(timeSeconds: Double) {
        val delta = lastFrameSeconds?.let { (timeSeconds - it).toFloat().coerceIn(0f, MAX_STEP) } ?: 0f
        lastFrameSeconds = timeSeconds
        world.update(if (paused) 0f else delta)
        val renderer = checkNotNull(renderer) { "drawFrame before surfaceCreated" }
        renderer.pipeline = pipeline
        renderer.render(world, camera)
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

    fun togglePipeline() {
        pipeline = Pipeline.entries[(pipeline.ordinal + 1) % Pipeline.entries.size]
    }

    fun togglePause() {
        paused = !paused
    }

    private companion object {
        const val MAX_STEP = 0.1f
    }
}
