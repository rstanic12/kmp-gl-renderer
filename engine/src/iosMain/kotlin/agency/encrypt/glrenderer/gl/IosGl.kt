@file:OptIn(ExperimentalForeignApi::class)

package agency.encrypt.glrenderer.gl

import agency.encrypt.glrenderer.assets.Image
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.gles3.*

/** OpenGL ES 3.0 through Apple's (deprecated, still shipping) OpenGLES framework. Requires a current EAGLContext. */
public class IosGl : Gl {

    override fun viewport(x: Int, y: Int, width: Int, height: Int): Unit = glViewport(x, y, width, height)

    override fun scissor(x: Int, y: Int, width: Int, height: Int) {
        glEnable(GL_SCISSOR_TEST.toUInt())
        glScissor(x, y, width, height)
    }

    override fun disableScissor(): Unit = glDisable(GL_SCISSOR_TEST.toUInt())
    override fun clearColor(r: Float, g: Float, b: Float, a: Float): Unit = glClearColor(r, g, b, a)

    override fun clear(color: Boolean, depth: Boolean) {
        val mask = (if (color) GL_COLOR_BUFFER_BIT else 0) or (if (depth) GL_DEPTH_BUFFER_BIT else 0)
        glClear(mask.toUInt())
    }

    override fun setDepthTest(enabled: Boolean): Unit = enable(GL_DEPTH_TEST, enabled)
    override fun setDepthWrite(enabled: Boolean): Unit = glDepthMask(enabled.toGl())

    override fun setBlend(blend: Blend) {
        enable(GL_BLEND, blend != Blend.NONE)
        when (blend) {
            Blend.NONE -> Unit
            Blend.ALPHA -> glBlendFunc(GL_SRC_ALPHA.toUInt(), GL_ONE_MINUS_SRC_ALPHA.toUInt())
            Blend.ADDITIVE -> glBlendFunc(GL_ONE.toUInt(), GL_ONE.toUInt())
        }
    }

    override fun setPolygonOffset(factor: Float, units: Float) {
        enable(GL_POLYGON_OFFSET_FILL, factor != 0f || units != 0f)
        glPolygonOffset(factor, units)
    }

    private fun enable(capability: Int, enabled: Boolean) =
        if (enabled) glEnable(capability.toUInt()) else glDisable(capability.toUInt())

    override fun createProgram(vertexSource: String, fragmentSource: String): Int {
        val vertex = compile(GL_VERTEX_SHADER.toUInt(), vertexSource)
        val fragment = compile(GL_FRAGMENT_SHADER.toUInt(), fragmentSource)
        val program = glCreateProgram()
        glAttachShader(program, vertex)
        glAttachShader(program, fragment)
        glLinkProgram(program)
        glDeleteShader(vertex)
        glDeleteShader(fragment)
        memScoped {
            val status = alloc<IntVar>()
            glGetProgramiv(program, GL_LINK_STATUS.toUInt(), status.ptr)
            if (status.value != GL_TRUE.toInt()) {
                val log = allocArray<ByteVar>(LOG_SIZE)
                glGetProgramInfoLog(program, LOG_SIZE, null, log)
                glDeleteProgram(program)
                error("Program link failed: ${log.toKString()}")
            }
        }
        return program.toInt()
    }

    private fun compile(type: UInt, source: String): UInt = memScoped {
        val shader = glCreateShader(type)
        glShaderSource(shader, 1, allocArrayOf(source.cstr.getPointer(this)), null)
        glCompileShader(shader)
        val status = alloc<IntVar>()
        glGetShaderiv(shader, GL_COMPILE_STATUS.toUInt(), status.ptr)
        if (status.value != GL_TRUE.toInt()) {
            val log = allocArray<ByteVar>(LOG_SIZE)
            glGetShaderInfoLog(shader, LOG_SIZE, null, log)
            glDeleteShader(shader)
            error("Shader compile failed: ${log.toKString()}\n$source")
        }
        shader
    }

    override fun deleteProgram(program: Int): Unit = glDeleteProgram(program.toUInt())

    override fun deleteVertexArray(vertexArray: Int): Unit = memScoped {
        glDeleteVertexArrays(1, alloc<UIntVar> { value = vertexArray.toUInt() }.ptr)
    }

    override fun deleteBuffer(buffer: Int): Unit = memScoped {
        glDeleteBuffers(1, alloc<UIntVar> { value = buffer.toUInt() }.ptr)
    }

    override fun useProgram(program: Int): Unit = glUseProgram(program.toUInt())
    override fun uniformLocation(program: Int, name: String): Int = glGetUniformLocation(program.toUInt(), name)
    override fun uniformMatrix4(location: Int, columnMajor: FloatArray): Unit = glUniformMatrix4fv(location, 1, false.toGl(), columnMajor.refTo(0))
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float): Unit = glUniform3f(location, x, y, z)
    override fun uniform2f(location: Int, x: Float, y: Float): Unit = glUniform2f(location, x, y)
    override fun uniform1f(location: Int, value: Float): Unit = glUniform1f(location, value)
    override fun uniform1i(location: Int, value: Int): Unit = glUniform1i(location, value)

    override fun createVertexArray(): Int = memScoped {
        val handle = alloc<UIntVar>()
        glGenVertexArrays(1, handle.ptr)
        handle.value.toInt()
    }

    override fun bindVertexArray(vertexArray: Int): Unit = glBindVertexArray(vertexArray.toUInt())

    override fun createBuffer(): Int = memScoped {
        val handle = alloc<UIntVar>()
        glGenBuffers(1, handle.ptr)
        handle.value.toInt()
    }

    override fun bufferData(buffer: Int, data: FloatArray, floatCount: Int, dynamic: Boolean) {
        glBindBuffer(GL_ARRAY_BUFFER.toUInt(), buffer.toUInt())
        val usage = if (dynamic) GL_DYNAMIC_DRAW else GL_STATIC_DRAW
        glBufferData(GL_ARRAY_BUFFER.toUInt(), (floatCount * Float.SIZE_BYTES).toLong(), data.refTo(0), usage.toUInt())
    }

    override fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int, divisor: Int) {
        glEnableVertexAttribArray(index.toUInt())
        // With a VBO bound, the "pointer" argument is a byte offset into it.
        glVertexAttribPointer(index.toUInt(), size, GL_FLOAT.toUInt(), false.toGl(), strideBytes, offsetBytes.toLong().toCPointer<CPointed>())
        glVertexAttribDivisor(index.toUInt(), divisor.toUInt())
    }

    override fun drawTriangles(vertexCount: Int, instanceCount: Int): Unit =
        glDrawArraysInstanced(GL_TRIANGLES.toUInt(), 0, vertexCount, instanceCount)

    override fun createTexture(image: Image): Int {
        val texture = genTexture()
        glPixelStorei(GL_UNPACK_ALIGNMENT.toUInt(), 1)
        glTexImage2D(
            GL_TEXTURE_2D.toUInt(), 0, GL_SRGB8_ALPHA8, image.width, image.height, 0,
            GL_RGBA.toUInt(), GL_UNSIGNED_BYTE.toUInt(), image.rgba.refTo(0),
        )
        glGenerateMipmap(GL_TEXTURE_2D.toUInt())
        parameters(GL_LINEAR_MIPMAP_LINEAR, GL_LINEAR, GL_REPEAT)
        return texture
    }

    override fun createRenderTexture(width: Int, height: Int, format: TextureFormat): Int {
        val texture = genTexture()
        val (internal, layout, type) = when (format) {
            TextureFormat.RGBA8 -> Triple(GL_RGBA8, GL_RGBA, GL_UNSIGNED_BYTE)
            TextureFormat.RGBA16F -> Triple(GL_RGBA16F, GL_RGBA, GL_HALF_FLOAT)
            TextureFormat.DEPTH24, TextureFormat.SHADOW_DEPTH24 -> Triple(GL_DEPTH_COMPONENT24, GL_DEPTH_COMPONENT, GL_UNSIGNED_INT)
        }
        glTexImage2D(GL_TEXTURE_2D.toUInt(), 0, internal, width, height, 0, layout.toUInt(), type.toUInt(), null)
        val filter = if (format == TextureFormat.DEPTH24) GL_NEAREST else GL_LINEAR
        parameters(filter, filter, GL_CLAMP_TO_EDGE)
        if (format == TextureFormat.SHADOW_DEPTH24) {
            glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_COMPARE_MODE.toUInt(), GL_COMPARE_REF_TO_TEXTURE)
            glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_COMPARE_FUNC.toUInt(), GL_LEQUAL)
        }
        return texture
    }

    override fun createDataTexture(): Int {
        val texture = genTexture()
        parameters(GL_NEAREST, GL_NEAREST, GL_CLAMP_TO_EDGE)
        return texture
    }

    override fun uploadDataTexture(texture: Int, width: Int, height: Int, rgba: FloatArray) {
        glBindTexture(GL_TEXTURE_2D.toUInt(), texture.toUInt())
        glTexImage2D(GL_TEXTURE_2D.toUInt(), 0, GL_RGBA32F, width, height, 0, GL_RGBA.toUInt(), GL_FLOAT.toUInt(), rgba.refTo(0))
    }

    private fun genTexture(): Int = memScoped {
        val handle = alloc<UIntVar>()
        glGenTextures(1, handle.ptr)
        glBindTexture(GL_TEXTURE_2D.toUInt(), handle.value)
        handle.value.toInt()
    }

    private fun parameters(minFilter: Int, magFilter: Int, wrap: Int) {
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_MIN_FILTER.toUInt(), minFilter)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_MAG_FILTER.toUInt(), magFilter)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_WRAP_S.toUInt(), wrap)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_WRAP_T.toUInt(), wrap)
    }

    override fun bindTexture(unit: Int, texture: Int) {
        glActiveTexture((GL_TEXTURE0 + unit).toUInt())
        glBindTexture(GL_TEXTURE_2D.toUInt(), texture.toUInt())
    }

    override fun deleteTexture(texture: Int): Unit = memScoped {
        glDeleteTextures(1, alloc<UIntVar> { value = texture.toUInt() }.ptr)
    }

    override fun createFramebuffer(colors: List<Int>, depth: Int?): Int? {
        val previous = boundFramebuffer()
        val framebuffer = memScoped {
            val handle = alloc<UIntVar>()
            glGenFramebuffers(1, handle.ptr)
            handle.value
        }
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer)
        colors.forEachIndexed { i, texture ->
            glFramebufferTexture2D(GL_FRAMEBUFFER.toUInt(), (GL_COLOR_ATTACHMENT0 + i).toUInt(), GL_TEXTURE_2D.toUInt(), texture.toUInt(), 0)
        }
        depth?.let { glFramebufferTexture2D(GL_FRAMEBUFFER.toUInt(), GL_DEPTH_ATTACHMENT.toUInt(), GL_TEXTURE_2D.toUInt(), it.toUInt(), 0) }
        memScoped {
            val buffers = if (colors.isEmpty()) listOf(GL_NONE) else colors.indices.map { GL_COLOR_ATTACHMENT0 + it }
            glDrawBuffers(buffers.size, allocArray<UIntVar>(buffers.size) { value = buffers[it].toUInt() })
        }
        glReadBuffer((if (colors.isEmpty()) GL_NONE else GL_COLOR_ATTACHMENT0).toUInt())
        val complete = glCheckFramebufferStatus(GL_FRAMEBUFFER.toUInt()) == GL_FRAMEBUFFER_COMPLETE.toUInt()
        glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), previous.toUInt())
        if (!complete) deleteFramebuffer(framebuffer.toInt())
        return framebuffer.toInt().takeIf { complete }
    }

    override fun bindFramebuffer(framebuffer: Int): Unit = glBindFramebuffer(GL_FRAMEBUFFER.toUInt(), framebuffer.toUInt())

    override fun deleteFramebuffer(framebuffer: Int): Unit = memScoped {
        glDeleteFramebuffers(1, alloc<UIntVar> { value = framebuffer.toUInt() }.ptr)
    }

    override fun boundFramebuffer(): Int = memScoped {
        val value = alloc<IntVar>()
        glGetIntegerv(GL_FRAMEBUFFER_BINDING.toUInt(), value.ptr)
        value.value
    }

    override fun error(): Int = glGetError().toInt()

    private fun Boolean.toGl(): UByte = (if (this) GL_TRUE else GL_FALSE).toUByte()

    private companion object {
        const val LOG_SIZE = 2048
    }
}
