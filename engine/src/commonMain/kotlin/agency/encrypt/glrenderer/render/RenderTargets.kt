package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.gl.TextureFormat

/**
 * Every screen-sized texture and the framebuffers that combine them. Recreated when the surface changes size.
 *
 * The HDR colour texture is shared: the G-buffer pass writes emission into it, the lighting passes add onto it,
 * and the forward pass draws into it (with the G-buffer's depth, so transparent surfaces are hidden correctly).
 */
internal class RenderTargets(private val gl: Gl, val width: Int, val height: Int, hdrFormat: TextureFormat) {
    class Level(val width: Int, val height: Int, val texture: Int, val framebuffer: Int)

    private val textures = mutableListOf<Int>()
    private val framebuffers = mutableListOf<Int>()

    val albedoMetallic = texture(width, height, TextureFormat.RGBA8)
    val normalRoughness = texture(width, height, TextureFormat.RGBA8)
    val depth = texture(width, height, TextureFormat.DEPTH24)
    val hdr = texture(width, height, hdrFormat)
    val display = texture(width, height, TextureFormat.RGBA8)

    val geometryFramebuffer = framebuffer("G-buffer", listOf(albedoMetallic, normalRoughness, hdr), depth)
    val lightingFramebuffer = framebuffer("lighting", listOf(hdr), null)
    val forwardFramebuffer = framebuffer("forward", listOf(hdr), depth)
    val displayFramebuffer = framebuffer("display", listOf(display), null)

    /** Bloom mip chain, from half resolution down. */
    val bloom: List<Level> = buildList {
        var w = width
        var h = height
        repeat(BLOOM_LEVELS) {
            w = maxOf(1, w / 2)
            h = maxOf(1, h / 2)
            val texture = texture(w, h, hdrFormat)
            add(Level(w, h, texture, framebuffer("bloom $it", listOf(texture), null)))
        }
    }

    fun delete() {
        framebuffers.forEach(gl::deleteFramebuffer)
        textures.forEach(gl::deleteTexture)
    }

    private fun texture(width: Int, height: Int, format: TextureFormat) =
        gl.createRenderTexture(width, height, format).also { textures += it }

    private fun framebuffer(name: String, colors: List<Int>, depth: Int?) =
        (gl.createFramebuffer(colors, depth) ?: error("The $name framebuffer is incomplete on this device"))
            .also { framebuffers += it }

    private companion object {
        const val BLOOM_LEVELS = 5
    }
}
