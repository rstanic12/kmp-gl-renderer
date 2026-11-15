package agency.encrypt.glrenderer.gl

import android.opengl.GLES30
import agency.encrypt.glrenderer.assets.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class AndroidGl : Gl {
    private val handle = IntArray(1)
    private var staging: FloatBuffer = floatBuffer(1024)

    override fun viewport(x: Int, y: Int, width: Int, height: Int) = GLES30.glViewport(x, y, width, height)

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
        GLES30.glScissor(x, y, width, height)
    }

    override fun disableScissor() = GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)

    override fun clear(color: Boolean, depth: Boolean) {
        val mask = (if (color) GLES30.GL_COLOR_BUFFER_BIT else 0) or (if (depth) GLES30.GL_DEPTH_BUFFER_BIT else 0)
        GLES30.glClear(mask)
    }

    override fun setDepthTest(enabled: Boolean) = enable(GLES30.GL_DEPTH_TEST, enabled)
    override fun setDepthWrite(enabled: Boolean) = GLES30.glDepthMask(enabled)

    override fun setBlend(blend: Blend) {
        enable(GLES30.GL_BLEND, blend != Blend.NONE)
        when (blend) {
            Blend.NONE -> Unit
            Blend.ALPHA -> GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            Blend.ADDITIVE -> GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        }
    }

    override fun setPolygonOffset(factor: Float, units: Float) {
        enable(GLES30.GL_POLYGON_OFFSET_FILL, factor != 0f || units != 0f)
        GLES30.glPolygonOffset(factor, units)
    }

    private fun enable(capability: Int, enabled: Boolean) =
        if (enabled) GLES30.glEnable(capability) else GLES30.glDisable(capability)

    override fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GLES30.GL_VERTEX_SHADER, vertexSource)
        val fragment = compile(GLES30.GL_FRAGMENT_SHADER, fragmentSource)
        val program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertex)
        GLES30.glAttachShader(program, fragment)
        GLES30.glLinkProgram(program)
        GLES30.glDeleteShader(vertex)
        GLES30.glDeleteShader(fragment)
        GLES30.glGetProgramiv(program, GLES30.GL_LINK_STATUS, handle, 0)
        if (handle[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetProgramInfoLog(program)
            GLES30.glDeleteProgram(program)
            error("Program link failed: $log")
        }
        return program
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, handle, 0)
        if (handle[0] != GLES30.GL_TRUE) {
            val log = GLES30.glGetShaderInfoLog(shader)
            GLES30.glDeleteShader(shader)
            error("Shader compile failed: $log\n$source")
        }
        return shader
    }

    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun uniformMatrix4(location: Int, columnMajor: FloatArray) = GLES30.glUniformMatrix4fv(location, 1, false, columnMajor, 0)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location, x, y, z)
    override fun uniform2f(location: Int, x: Float, y: Float) = GLES30.glUniform2f(location, x, y)
    override fun uniform1f(location: Int, value: Float) = GLES30.glUniform1f(location, value)
    override fun uniform1i(location: Int, value: Int) = GLES30.glUniform1i(location, value)

    override fun createVertexArray(): Int {
        GLES30.glGenVertexArrays(1, handle, 0)
        return handle[0]
    }

    override fun bindVertexArray(vertexArray: Int) = GLES30.glBindVertexArray(vertexArray)

    override fun createBuffer(): Int {
        GLES30.glGenBuffers(1, handle, 0)
        return handle[0]
    }

    override fun bufferData(buffer: Int, data: FloatArray, floatCount: Int, dynamic: Boolean) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        val usage = if (dynamic) GLES30.GL_DYNAMIC_DRAW else GLES30.GL_STATIC_DRAW
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, floatCount * Float.SIZE_BYTES, stage(data, floatCount), usage)
    }

    override fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int, divisor: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, strideBytes, offsetBytes)
        GLES30.glVertexAttribDivisor(index, divisor)
    }

    override fun drawTriangles(vertexCount: Int, instanceCount: Int) =
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, vertexCount, instanceCount)

    override fun createTexture(image: Image): Int {
        val texture = genTexture()
        val pixels = ByteBuffer.allocateDirect(image.rgba.size).order(ByteOrder.nativeOrder())
        pixels.put(image.rgba).position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, image.width, image.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels,
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        parameters(GLES30.GL_LINEAR_MIPMAP_LINEAR, GLES30.GL_LINEAR, GLES30.GL_REPEAT)
        return texture
    }

    override fun createRenderTexture(width: Int, height: Int, format: TextureFormat): Int {
        val texture = genTexture()
        val (internal, layout, type) = when (format) {
            TextureFormat.RGBA8 -> Triple(GLES30.GL_RGBA8, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE)
            TextureFormat.RGBA16F -> Triple(GLES30.GL_RGBA16F, GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT)
            TextureFormat.DEPTH24, TextureFormat.SHADOW_DEPTH24 ->
                Triple(GLES30.GL_DEPTH_COMPONENT24, GLES30.GL_DEPTH_COMPONENT, GLES30.GL_UNSIGNED_INT)
        }
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, internal, width, height, 0, layout, type, null)
        val filter = if (format == TextureFormat.DEPTH24) GLES30.GL_NEAREST else GLES30.GL_LINEAR
        parameters(filter, filter, GLES30.GL_CLAMP_TO_EDGE)
        if (format == TextureFormat.SHADOW_DEPTH24) {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        }
        return texture
    }

    override fun createDataTexture(): Int {
        val texture = genTexture()
        parameters(GLES30.GL_NEAREST, GLES30.GL_NEAREST, GLES30.GL_CLAMP_TO_EDGE)
        return texture
    }

    override fun uploadDataTexture(texture: Int, width: Int, height: Int, rgba: FloatArray) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, width, height, 0,
            GLES30.GL_RGBA, GLES30.GL_FLOAT, stage(rgba, width * height * 4),
        )
    }

    private fun genTexture(): Int {
        GLES30.glGenTextures(1, handle, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, handle[0])
        return handle[0]
    }

    private fun parameters(minFilter: Int, magFilter: Int, wrap: Int) {
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, minFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, magFilter)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
    }

    override fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    override fun deleteTexture(texture: Int) = GLES30.glDeleteTextures(1, intArrayOf(texture), 0)

    override fun createFramebuffer(colors: List<Int>, depth: Int?): Int? {
        val previous = boundFramebuffer()
        GLES30.glGenFramebuffers(1, handle, 0)
        val framebuffer = handle[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
        colors.forEachIndexed { i, texture ->
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0 + i, GLES30.GL_TEXTURE_2D, texture, 0)
        }
        depth?.let { GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, it, 0) }
        val buffers = if (colors.isEmpty()) intArrayOf(GLES30.GL_NONE) else IntArray(colors.size) { GLES30.GL_COLOR_ATTACHMENT0 + it }
        GLES30.glDrawBuffers(buffers.size, buffers, 0)
        GLES30.glReadBuffer(if (colors.isEmpty()) GLES30.GL_NONE else GLES30.GL_COLOR_ATTACHMENT0)
        val complete = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, previous)
        if (!complete) deleteFramebuffer(framebuffer)
        return framebuffer.takeIf { complete }
    }

    override fun bindFramebuffer(framebuffer: Int) = GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, framebuffer)
    override fun deleteFramebuffer(framebuffer: Int) = GLES30.glDeleteFramebuffers(1, intArrayOf(framebuffer), 0)

    override fun boundFramebuffer(): Int {
        GLES30.glGetIntegerv(GLES30.GL_FRAMEBUFFER_BINDING, handle, 0)
        return handle[0]
    }

    override fun error(): Int = GLES30.glGetError()

    /** Copies into a reused direct buffer: per-frame instance and light uploads shouldn't allocate. */
    private fun stage(data: FloatArray, floatCount: Int): FloatBuffer {
        if (staging.capacity() < floatCount) staging = floatBuffer(maxOf(floatCount, staging.capacity() * 2))
        staging.clear()
        staging.put(data, 0, floatCount).position(0)
        return staging
    }

    private fun floatBuffer(floats: Int) =
        ByteBuffer.allocateDirect(floats * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
