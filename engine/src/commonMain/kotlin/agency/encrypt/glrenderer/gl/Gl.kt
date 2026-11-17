package agency.encrypt.glrenderer.gl

import agency.encrypt.glrenderer.assets.Image

public enum class Blend { NONE, ALPHA, ADDITIVE }

public enum class TextureFormat {
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
public interface Gl {
    public fun viewport(x: Int, y: Int, width: Int, height: Int)

    /** Enables the scissor test with this rectangle (pixels, origin bottom-left). */
    public fun scissor(x: Int, y: Int, width: Int, height: Int)
    public fun disableScissor()
    public fun clearColor(r: Float, g: Float, b: Float, a: Float)
    public fun clear(color: Boolean, depth: Boolean)
    public fun setDepthTest(enabled: Boolean)
    public fun setDepthWrite(enabled: Boolean)
    public fun setBlend(blend: Blend)

    /** Slope-scaled depth offset for filled polygons; (0, 0) turns it off. */
    public fun setPolygonOffset(factor: Float, units: Float)

    /** Compiles and links, throwing with the driver's info log on failure. */
    public fun createProgram(vertexSource: String, fragmentSource: String): Int
    public fun deleteProgram(program: Int)
    public fun useProgram(program: Int)
    public fun uniformLocation(program: Int, name: String): Int
    public fun uniformMatrix4(location: Int, columnMajor: FloatArray)
    public fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    public fun uniform2f(location: Int, x: Float, y: Float)
    public fun uniform1f(location: Int, value: Float)
    public fun uniform1i(location: Int, value: Int)

    public fun createVertexArray(): Int
    public fun bindVertexArray(vertexArray: Int)
    public fun deleteVertexArray(vertexArray: Int)
    public fun createBuffer(): Int
    public fun deleteBuffer(buffer: Int)

    /** Replaces the contents of [buffer] with the first [floatCount] floats of [data]; the buffer stays bound. */
    public fun bufferData(buffer: Int, data: FloatArray, floatCount: Int, dynamic: Boolean)

    /** Describes a float attribute read from the currently bound buffer; [divisor] 1 advances it per instance. */
    public fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int, divisor: Int)
    public fun drawTriangles(vertexCount: Int, instanceCount: Int)

    /** Uploads sRGB-encoded RGBA pixels with mipmaps, trilinear filtering and repeat wrapping. */
    public fun createTexture(image: Image): Int

    /** An empty render target: linear filtering (nearest for [TextureFormat.DEPTH24]), clamped to the edge. */
    public fun createRenderTexture(width: Int, height: Int, format: TextureFormat): Int

    /** A texture for arbitrary float data, read with texelFetch(). */
    public fun createDataTexture(): Int

    /** Replaces the contents of a data texture with [rgba] (4 floats per texel) as RGBA32F. */
    public fun uploadDataTexture(texture: Int, width: Int, height: Int, rgba: FloatArray)
    public fun bindTexture(unit: Int, texture: Int)
    public fun deleteTexture(texture: Int)

    /**
     * A framebuffer drawing into [colors] (in order, as fragment outputs 0, 1, ...) and testing against [depth].
     * Returns null, having deleted it again, when the driver reports it incomplete.
     */
    public fun createFramebuffer(colors: List<Int>, depth: Int?): Int?
    public fun bindFramebuffer(framebuffer: Int)
    public fun deleteFramebuffer(framebuffer: Int)

    /** The framebuffer bound right now. On iOS the default one is GLKView's, not 0. */
    public fun boundFramebuffer(): Int

    /** Returns the pending GL error code, 0 if none. */
    public fun error(): Int
}
