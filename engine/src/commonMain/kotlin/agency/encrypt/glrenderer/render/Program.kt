package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3

/** A linked shader program whose uniforms are set by name: `program["uExposure"] = 1.2f`. */
internal class Program(private val gl: Gl, vertexSource: String, fragmentSource: String) {
    private val id = gl.createProgram(vertexSource, fragmentSource)
    private val locations = HashMap<String, Int>()

    fun use() = gl.useProgram(id)

    private fun at(name: String) = locations.getOrPut(name) { gl.uniformLocation(id, name) }

    operator fun set(name: String, value: Float) = gl.uniform1f(at(name), value)
    operator fun set(name: String, value: Int) = gl.uniform1i(at(name), value)
    operator fun set(name: String, value: Boolean) = gl.uniform1i(at(name), if (value) 1 else 0)
    operator fun set(name: String, value: Vec3) = gl.uniform3f(at(name), value.x, value.y, value.z)
    operator fun set(name: String, value: Mat4) = gl.uniformMatrix4(at(name), value.toFloatArray())

    fun setVec2(name: String, x: Float, y: Float) = gl.uniform2f(at(name), x, y)

    /** Points each sampler uniform at a texture unit, once: the assignment is part of the program's state. */
    fun samplers(vararg units: Pair<String, Int>) {
        use()
        units.forEach { (name, unit) -> this[name] = unit }
    }
}
