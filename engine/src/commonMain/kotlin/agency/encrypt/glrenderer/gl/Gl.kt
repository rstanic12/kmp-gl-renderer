package agency.encrypt.glrenderer.gl

import agency.encrypt.glrenderer.assets.Image

enum class Blend { NONE, ALPHA, ADDITIVE }

enum class TextureFormat {
    RGBA8,

    /** Half-float colour. Always samplable; renderable only with EXT_color_buffer_(half_)float. */
    RGBA16F,

    /** Depth for sampling with texture() / texelFetch(): nearest filtering, no comparison. */
    DEPTH24,

    /** Depth for a sampler2DShadow: comparison on, linear filtering, so each lookup is a 2x2 PCF tap. */
    SHADOW_DEPTH24,
}

/**
 * The OpenGL ES 3.0 calls the renderer needs, behind one interface so the renderer itself is common code.
 * Android implements it with GLES30, iOS with Kotlin/Native's OpenGLES bindings.
 * Handles are the raw GL object names.
 */
interface Gl {
    fun viewport(x: Int, y: Int, width: Int, height: Int)

    /** Enables the scissor test with this rectangle (pixels, origin bottom-left). */
    fun scissor(x: Int, y: Int, width: Int, height: Int)
    fun disableScissor()
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear(color: Boolean, depth: Boolean)
    fun setDepthTest(enabled: Boolean)
    fun setDepthWrite(enabled: Boolean)
    fun setBlend(blend: Blend)

    /** Slope-scaled depth offset for filled polygons; (0, 0) turns it off. */
    fun setPolygonOffset(factor: Float, units: Float)

    /** Compiles and links, throwing with the driver's info log on failure. */
    fun createProgram(vertexSource: String, fragmentSource: String): Int
    fun useProgram(program: Int)
    fun uniformLocation(program: Int, name: String): Int
    fun uniformMatrix4(location: Int, columnMajor: FloatArray)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    fun uniform2f(location: Int, x: Float, y: Float)
    fun uniform1f(location: Int, value: Float)
    fun uniform1i(location: Int, value: Int)

    fun createVertexArray(): Int
    fun bindVertexArray(vertexArray: Int)
    fun createBuffer(): Int

    /** Replaces the contents of [buffer] with the first [floatCount] floats of [data]; the buffer stays bound. */
    fun bufferData(buffer: Int, data: FloatArray, floatCount: Int, dynamic: Boolean)

    /** Describes a float attribute read from the currently bound buffer; [divisor] 1 advances it per instance. */
    fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int, divisor: Int)
    fun drawTriangles(vertexCount: Int, instanceCount: Int)

    /** Uploads sRGB-encoded RGBA pixels with mipmaps, trilinear filtering and repeat wrapping. */
    fun createTexture(image: Image): Int

    /** An empty render target: linear filtering (nearest for [TextureFormat.DEPTH24]), clamped to the edge. */
    fun createRenderTexture(width: Int, height: Int, format: TextureFormat): Int

    /** A texture for arbitrary float data, read with texelFetch(). */
    fun createDataTexture(): Int

    /** Replaces the contents of a data texture with [rgba] (4 floats per texel) as RGBA32F. */
    fun uploadDataTexture(texture: Int, width: Int, height: Int, rgba: FloatArray)
    fun bindTexture(unit: Int, texture: Int)
    fun deleteTexture(texture: Int)

    /**
     * A framebuffer drawing into [colors] (in order, as fragment outputs 0, 1, ...) and testing against [depth].
     * Returns null, having deleted it again, when the driver reports it incomplete.
     */
    fun createFramebuffer(colors: List<Int>, depth: Int?): Int?
    fun bindFramebuffer(framebuffer: Int)
    fun deleteFramebuffer(framebuffer: Int)

    /** The framebuffer bound right now. On iOS the default one is GLKView's, not 0. */
    fun boundFramebuffer(): Int

    /** Returns the pending GL error code, 0 if none. */
    fun error(): Int
}
