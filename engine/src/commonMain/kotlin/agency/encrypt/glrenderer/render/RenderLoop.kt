package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.scene.Camera

/**
 * Drives a [World] and a [Renderer] from a platform's GL surface callbacks: [surfaceCreated], [surfaceChanged] and
 * [drawFrame], all called on the thread that owns the GL context. On Android, `EngineSurfaceView` calls them;
 * on iOS, call them from a GLKView's delegate.
 *
 * Each frame runs the world's systems with the time since the last frame, then renders. A new GL context (after the
 * app was backgrounded, say) gets a new [Renderer], configured again by [configure]; the world and camera carry on.
 */
public class RenderLoop(
    private val gl: Gl,
    private val assets: AssetSource,
    public val world: World,
    public var camera: Camera,
    private val configure: (Renderer) -> Unit = {},
) {
    /** The current renderer, or null before the first [surfaceCreated]. */
    public var renderer: Renderer? = null
        private set

    /** While paused, frames are still drawn but systems see no time pass. */
    public var paused: Boolean = false

    private var width = 1
    private var height = 1
    private var lastFrameSeconds: Double? = null

    /** A GL context was created; anything made in a previous one is already gone with it. */
    public fun surfaceCreated() {
        renderer = Renderer(gl, assets).also {
            it.resize(width, height)
            configure(it)
        }
    }

    public fun surfaceChanged(width: Int, height: Int) {
        this.width = width
        this.height = height
        renderer?.resize(width, height)
    }

    /** [timeSeconds] is any monotonic clock; steps longer than [maxStepSeconds] are clamped, so a stall doesn't jump. */
    public fun drawFrame(timeSeconds: Double, maxStepSeconds: Float = 0.1f) {
        val renderer = checkNotNull(renderer) { "drawFrame before surfaceCreated" }
        val delta = lastFrameSeconds?.let { (timeSeconds - it).toFloat().coerceIn(0f, maxStepSeconds) } ?: 0f
        lastFrameSeconds = timeSeconds
        world.update(if (paused) 0f else delta)
        renderer.render(world, camera)
    }

    /** Frees the renderer's GL objects. Call on the GL thread while the context is alive, e.g. before tearing it down. */
    public fun dispose() {
        renderer?.dispose()
        renderer = null
    }
}
