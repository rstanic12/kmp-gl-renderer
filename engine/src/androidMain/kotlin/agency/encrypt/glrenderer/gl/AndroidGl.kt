package agency.encrypt.glrenderer.gl

import android.opengl.GLES30
import agency.encrypt.glrenderer.assets.Image
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AndroidGl : Gl {
    private val handle = IntArray(1)

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) = GLES30.glClearColor(r, g, b, a)
    override fun clear() = GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
    override fun viewport(width: Int, height: Int) = GLES30.glViewport(0, 0, width, height)
    override fun enableDepthTest() = GLES30.glEnable(GLES30.GL_DEPTH_TEST)

    override fun setBlending(enabled: Boolean) {
        if (enabled) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
        }
    }

    override fun setDepthWrite(enabled: Boolean) = GLES30.glDepthMask(enabled)

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
            error("Shader compile failed: $log")
        }
        return shader
    }

    override fun useProgram(program: Int) = GLES30.glUseProgram(program)
    override fun uniformLocation(program: Int, name: String) = GLES30.glGetUniformLocation(program, name)
    override fun uniformMatrix4(location: Int, columnMajor: FloatArray) = GLES30.glUniformMatrix4fv(location, 1, false, columnMajor, 0)
    override fun uniformMatrix3(location: Int, columnMajor: FloatArray) = GLES30.glUniformMatrix3fv(location, 1, false, columnMajor, 0)
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) = GLES30.glUniform3f(location, x, y, z)
    override fun uniform1f(location: Int, value: Float) = GLES30.glUniform1f(location, value)
    override fun uniform1i(location: Int, value: Int) = GLES30.glUniform1i(location, value)

    override fun createVertexArray(data: FloatArray): Int {
        GLES30.glGenVertexArrays(1, handle, 0)
        val vertexArray = handle[0]
        GLES30.glBindVertexArray(vertexArray)
        GLES30.glGenBuffers(1, handle, 0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, handle[0])
        val buffer = ByteBuffer.allocateDirect(data.size * Float.SIZE_BYTES).order(ByteOrder.nativeOrder()).asFloatBuffer()
        buffer.put(data).position(0)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.size * Float.SIZE_BYTES, buffer, GLES30.GL_STATIC_DRAW)
        return vertexArray
    }

    override fun bindVertexArray(vertexArray: Int) = GLES30.glBindVertexArray(vertexArray)

    override fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int) {
        GLES30.glEnableVertexAttribArray(index)
        GLES30.glVertexAttribPointer(index, size, GLES30.GL_FLOAT, false, strideBytes, offsetBytes)
    }

    override fun createTexture(image: Image): Int {
        GLES30.glGenTextures(1, handle, 0)
        val texture = handle[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        val pixels = ByteBuffer.allocateDirect(image.rgba.size).order(ByteOrder.nativeOrder())
        pixels.put(image.rgba).position(0)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, image.width, image.height, 0,
            GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels,
        )
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        return texture
    }

    override fun bindTexture(unit: Int, texture: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
    }

    override fun drawTriangles(firstVertex: Int, vertexCount: Int) = GLES30.glDrawArrays(GLES30.GL_TRIANGLES, firstVertex, vertexCount)

    override fun error(): Int = GLES30.glGetError()
}
