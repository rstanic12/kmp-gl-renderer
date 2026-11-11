package agency.encrypt.glrenderer.math

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** Immutable 4x4 matrix stored column-major, the layout OpenGL expects. */
class Mat4 private constructor(private val m: FloatArray) {

    operator fun get(row: Int, column: Int) = m[column * 4 + row]

    operator fun times(o: Mat4): Mat4 {
        val r = FloatArray(16)
        for (c in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += this[row, k] * o[k, c]
                r[c * 4 + row] = sum
            }
        }
        return Mat4(r)
    }

    fun transformPoint(p: Vec3) = Vec3(
        this[0, 0] * p.x + this[0, 1] * p.y + this[0, 2] * p.z + this[0, 3],
        this[1, 0] * p.x + this[1, 1] * p.y + this[1, 2] * p.z + this[1, 3],
        this[2, 0] * p.x + this[2, 1] * p.y + this[2, 2] * p.z + this[2, 3],
    )

    fun translation() = Vec3(this[0, 3], this[1, 3], this[2, 3])

    /** Inverse-transpose of the upper 3x3, column-major: transforms normals correctly under non-uniform scale. */
    fun normalMatrix(): FloatArray {
        val a = this[0, 0]; val b = this[0, 1]; val c = this[0, 2]
        val d = this[1, 0]; val e = this[1, 1]; val f = this[1, 2]
        val g = this[2, 0]; val h = this[2, 1]; val i = this[2, 2]
        val c00 = e * i - f * h
        val c01 = -(d * i - f * g)
        val c02 = d * h - e * g
        val det = a * c00 + b * c01 + c * c02
        require(det != 0f) { "Matrix is not invertible" }
        val s = 1f / det
        val c10 = -(b * i - c * h)
        val c11 = a * i - c * g
        val c12 = -(a * h - b * g)
        val c20 = b * f - c * e
        val c21 = -(a * f - c * d)
        val c22 = a * e - b * d
        // The inverse-transpose equals the cofactor matrix divided by the determinant; emitted column by column.
        return floatArrayOf(
            c00 * s, c10 * s, c20 * s,
            c01 * s, c11 * s, c21 * s,
            c02 * s, c12 * s, c22 * s,
        )
    }

    fun toFloatArray(): FloatArray = m.copyOf()

    override fun equals(other: Any?) = other is Mat4 && m.contentEquals(other.m)
    override fun hashCode() = m.contentHashCode()
    override fun toString() = (0 until 4).joinToString("\n") { r -> (0 until 4).joinToString(" ") { c -> this[r, c].toString() } }

    companion object {
        val IDENTITY = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f))

        private fun fromRows(vararg rows: Float): Mat4 {
            val m = FloatArray(16)
            for (r in 0 until 4) for (c in 0 until 4) m[c * 4 + r] = rows[r * 4 + c]
            return Mat4(m)
        }

        fun translation(t: Vec3) = fromRows(
            1f, 0f, 0f, t.x,
            0f, 1f, 0f, t.y,
            0f, 0f, 1f, t.z,
            0f, 0f, 0f, 1f,
        )

        fun scale(s: Vec3) = fromRows(
            s.x, 0f, 0f, 0f,
            0f, s.y, 0f, 0f,
            0f, 0f, s.z, 0f,
            0f, 0f, 0f, 1f,
        )

        fun rotationX(degrees: Float): Mat4 {
            val r = radians(degrees); val c = cos(r); val s = sin(r)
            return fromRows(
                1f, 0f, 0f, 0f,
                0f, c, -s, 0f,
                0f, s, c, 0f,
                0f, 0f, 0f, 1f,
            )
        }

        fun rotationY(degrees: Float): Mat4 {
            val r = radians(degrees); val c = cos(r); val s = sin(r)
            return fromRows(
                c, 0f, s, 0f,
                0f, 1f, 0f, 0f,
                -s, 0f, c, 0f,
                0f, 0f, 0f, 1f,
            )
        }

        fun rotationZ(degrees: Float): Mat4 {
            val r = radians(degrees); val c = cos(r); val s = sin(r)
            return fromRows(
                c, -s, 0f, 0f,
                s, c, 0f, 0f,
                0f, 0f, 1f, 0f,
                0f, 0f, 0f, 1f,
            )
        }

        /** Translate * rotate (Y, then X, then Z — yaw, pitch, roll) * scale. */
        fun trs(translation: Vec3, rotationDegrees: Vec3, scale: Vec3) =
            translation(translation) *
                rotationY(rotationDegrees.y) * rotationX(rotationDegrees.x) * rotationZ(rotationDegrees.z) *
                scale(scale)

        fun perspective(fovYDegrees: Float, aspect: Float, near: Float, far: Float): Mat4 {
            val f = 1f / tan(radians(fovYDegrees) / 2f)
            return fromRows(
                f / aspect, 0f, 0f, 0f,
                0f, f, 0f, 0f,
                0f, 0f, (far + near) / (near - far), 2f * far * near / (near - far),
                0f, 0f, -1f, 0f,
            )
        }

        fun lookAt(eye: Vec3, target: Vec3, up: Vec3): Mat4 {
            val forward = (target - eye).normalized()
            val right = (forward cross up).normalized()
            val trueUp = right cross forward
            return fromRows(
                right.x, right.y, right.z, -(right dot eye),
                trueUp.x, trueUp.y, trueUp.z, -(trueUp dot eye),
                -forward.x, -forward.y, -forward.z, forward dot eye,
                0f, 0f, 0f, 1f,
            )
        }

        private fun radians(degrees: Float) = (degrees * PI / 180.0).toFloat()
    }
}
