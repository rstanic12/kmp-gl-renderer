@file:OptIn(ExperimentalForeignApi::class)

package agency.encrypt.glrenderer.gl

import agency.encrypt.glrenderer.assets.Image
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointed
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.refTo
import kotlinx.cinterop.toCPointer
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.gles3.*

/** OpenGL ES 3.0 through Apple's (deprecated, still shipping) OpenGLES framework. Requires a current EAGLContext. */
class IosGl : Gl {

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = glClearColor(r, g, b, a)
    override fun clear() = glClear((GL_COLOR_BUFFER_BIT or GL_DEPTH_BUFFER_BIT).toUInt())
    override fun viewport(width: Int, height: Int) = glViewport(0, 0, width, height)
    override fun enableDepthTest() = glEnable(GL_DEPTH_TEST.toUInt())

    override fun setBlending(enabled: Boolean) {
        if (enabled) {
            glEnable(GL_BLEND.toUInt())
            glBlendFunc(GL_SRC_ALPHA.toUInt(), GL_ONE_MINUS_SRC_ALPHA.toUInt())
        } else {
            glDisable(GL_BLEND.toUInt())
        }
    }

    override fun setDepthWrite(enabled: Boolean) = glDepthMask(enabled.toGl())

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
            error("Shader compile failed: ${log.toKString()}")
        }
        shader
    }

    override fun useProgram(program: Int) = glUseProgram(program.toUInt())
    override fun uniformLocation(program: Int, name: String) = glGetUniformLocation(program.toUInt(), name)
    override fun uniformMatrix4(location: Int, columnMajor: FloatArray) = glUniformMatrix4fv(location, 1, false.toGl(), columnMajor.refTo(0))
    override fun uniformMatrix3(location: Int, columnMajor: FloatArray) = glUniformMatrix3fv(location, 1, false.toGl(), columnMajor.refTo(0))
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = glUniform3f(location, x, y, z)
    override fun uniform1f(location: Int, value: Float) = glUniform1f(location, value)
    override fun uniform1i(location: Int, value: Int) = glUniform1i(location, value)

    override fun createVertexArray(data: FloatArray): Int = memScoped {
        val handle = alloc<UIntVar>()
        glGenVertexArrays(1, handle.ptr)
        val vertexArray = handle.value
        glBindVertexArray(vertexArray)
        glGenBuffers(1, handle.ptr)
        glBindBuffer(GL_ARRAY_BUFFER.toUInt(), handle.value)
        glBufferData(GL_ARRAY_BUFFER.toUInt(), (data.size * Float.SIZE_BYTES).toLong(), data.refTo(0), GL_STATIC_DRAW.toUInt())
        vertexArray.toInt()
    }

    override fun bindVertexArray(vertexArray: Int) = glBindVertexArray(vertexArray.toUInt())

    override fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int) {
        glEnableVertexAttribArray(index.toUInt())
        // With a VBO bound, the "pointer" argument is a byte offset into it.
        glVertexAttribPointer(index.toUInt(), size, GL_FLOAT.toUInt(), false.toGl(), strideBytes, offsetBytes.toLong().toCPointer<CPointed>())
    }

    override fun createTexture(image: Image): Int = memScoped {
        val handle = alloc<UIntVar>()
        glGenTextures(1, handle.ptr)
        val texture = handle.value
        glBindTexture(GL_TEXTURE_2D.toUInt(), texture)
        glPixelStorei(GL_UNPACK_ALIGNMENT.toUInt(), 1)
        glTexImage2D(
            GL_TEXTURE_2D.toUInt(), 0, GL_RGBA8, image.width, image.height, 0,
            GL_RGBA.toUInt(), GL_UNSIGNED_BYTE.toUInt(), image.rgba.refTo(0),
        )
        glGenerateMipmap(GL_TEXTURE_2D.toUInt())
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_MIN_FILTER.toUInt(), GL_LINEAR_MIPMAP_LINEAR)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_MAG_FILTER.toUInt(), GL_LINEAR)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_WRAP_S.toUInt(), GL_REPEAT)
        glTexParameteri(GL_TEXTURE_2D.toUInt(), GL_TEXTURE_WRAP_T.toUInt(), GL_REPEAT)
        texture.toInt()
    }

    override fun bindTexture(unit: Int, texture: Int) {
        glActiveTexture((GL_TEXTURE0 + unit).toUInt())
        glBindTexture(GL_TEXTURE_2D.toUInt(), texture.toUInt())
    }

    override fun drawTriangles(firstVertex: Int, vertexCount: Int) = glDrawArrays(GL_TRIANGLES.toUInt(), firstVertex, vertexCount)

    override fun error(): Int = glGetError().toInt()

    private fun Boolean.toGl(): UByte = (if (this) GL_TRUE else GL_FALSE).toUByte()

    private companion object {
        const val LOG_SIZE = 2048
    }
}
