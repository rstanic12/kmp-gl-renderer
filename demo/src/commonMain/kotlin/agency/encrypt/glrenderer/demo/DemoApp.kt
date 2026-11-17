package agency.encrypt.glrenderer.demo

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.render.Pipeline
import agency.encrypt.glrenderer.render.RenderLoop
import agency.encrypt.glrenderer.scene.PointLight

/**
 * The demo on top of the engine's [RenderLoop]: the teapot scene, renderer settings, and touch input.
 * Everything runs on the GL thread; the hosts forward input there.
 */
class DemoApp(gl: Gl, assets: AssetSource) {

    private val camera = DemoScene.camera()
    private var pipeline = Pipeline.DEFERRED

    val loop = RenderLoop(gl, assets, DemoScene.create(), camera) { renderer ->
        renderer.pipeline = pipeline
        renderer.shadowCenter = Vec3(0f, 0.4f, 0f)
        renderer.shadowRadius = 2.2f
        // A dim key light, so the coloured point lights and their shadows carry the scene.
        renderer.lightColor = Vec3(1.1f, 1.05f, 1f)
        onStatusChanged?.invoke(status)
    }

    private val lightCount = loop.world.entities.count { it.has<PointLight>() }
    private val shadowCasterCount = loop.world.entities.count { it.get<PointLight>()?.castsShadows == true }

    /** Called on the GL thread whenever [status] may have changed. */
    var onStatusChanged: ((String) -> Unit)? = null

    /** One line for the host to show, e.g. "Deferred · 32 point lights, 6 with shadows". */
    val status: String
        get() {
            val hdr = if (loop.renderer?.hdr == false) " · no HDR on this GPU" else ""
            val pause = if (loop.paused) " · paused" else ""
            return "${pipeline.label} · $lightCount point lights, $shadowCasterCount with shadows$hdr$pause"
        }

    fun surfaceCreated() = loop.surfaceCreated()

    fun surfaceChanged(width: Int, height: Int) = loop.surfaceChanged(width, height)

    fun drawFrame(timeSeconds: Double) = loop.drawFrame(timeSeconds)

    /** Drag, as a fraction of the view's width and height: a full-width swipe turns the camera half a circle. */
    fun orbit(dxFraction: Float, dyFraction: Float) {
        camera.orbit(-dxFraction * 180f, dyFraction * 90f)
    }

    /** Pinch scale factor; above 1 zooms in. */
    fun zoom(factor: Float) {
        camera.zoom(factor)
    }

    fun toggleLid() {
        DemoScene.toggleLid(loop.world)
    }

    fun togglePipeline() {
        pipeline = Pipeline.entries[(pipeline.ordinal + 1) % Pipeline.entries.size]
        loop.renderer?.pipeline = pipeline
        onStatusChanged?.invoke(status)
    }

    fun togglePause() {
        loop.paused = !loop.paused
        onStatusChanged?.invoke(status)
    }
}
