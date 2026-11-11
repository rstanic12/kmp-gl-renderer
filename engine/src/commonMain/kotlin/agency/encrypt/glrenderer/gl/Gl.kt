package agency.encrypt.glrenderer.gl

import agency.encrypt.glrenderer.assets.Image

/**
 * The OpenGL ES 3.0 calls the renderer needs, behind one interface so the renderer itself is common code.
 * Android implements it with GLES30, iOS with Kotlin/Native's OpenGLES bindings.
 * Handles are the raw GL object names.
 */
interface Gl {
    fun clearColor(r: Float, g: Float, b: Float, a: Float)
    fun clear()
    fun viewport(width: Int, height: Int)
    fun enableDepthTest()
    fun setBlending(enabled: Boolean)
    fun setDepthWrite(enabled: Boolean)

    /** Compiles and links, throwing with the driver's info log on failure. */
    fun createProgram(vertexSource: String, fragmentSource: String): Int
    fun useProgram(program: Int)
    fun uniformLocation(program: Int, name: String): Int
    fun uniformMatrix4(location: Int, columnMajor: FloatArray)
    fun uniformMatrix3(location: Int, columnMajor: FloatArray)
    fun uniform3f(location: Int, x: Float, y: Float, z: Float)
    fun uniform1f(location: Int, value: Float)
    fun uniform1i(location: Int, value: Int)

    /** Uploads [data] into a new VBO bound to a new VAO; the VAO stays bound so attributes can be described. */
    fun createVertexArray(data: FloatArray): Int
    fun bindVertexArray(vertexArray: Int)
    fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int)

    /** Uploads RGBA pixels with mipmaps, linear filtering and repeat wrapping. */
    fun createTexture(image: Image): Int
    fun bindTexture(unit: Int, texture: Int)

    fun drawTriangles(firstVertex: Int, vertexCount: Int)

    /** Returns the pending GL error code, 0 if none. */
    fun error(): Int
}
