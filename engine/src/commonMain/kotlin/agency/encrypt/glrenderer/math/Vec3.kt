package agency.encrypt.glrenderer.math

import kotlin.math.sqrt

public data class Vec3(val x: Float = 0f, val y: Float = 0f, val z: Float = 0f) {
    public operator fun plus(o: Vec3): Vec3 = Vec3(x + o.x, y + o.y, z + o.z)
    public operator fun minus(o: Vec3): Vec3 = Vec3(x - o.x, y - o.y, z - o.z)
    public operator fun times(s: Float): Vec3 = Vec3(x * s, y * s, z * s)

    /** Component-wise, for colours. */
    public operator fun times(o: Vec3): Vec3 = Vec3(x * o.x, y * o.y, z * o.z)
    public operator fun unaryMinus(): Vec3 = Vec3(-x, -y, -z)

    public infix fun dot(o: Vec3): Float = x * o.x + y * o.y + z * o.z
    public infix fun cross(o: Vec3): Vec3 = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)

    public fun length(): Float = sqrt(this dot this)

    public fun normalized(): Vec3 {
        val length = length()
        return if (length > 0f) this * (1f / length) else this
    }

    public companion object {
        public val ZERO: Vec3 = Vec3()
        public val ONE: Vec3 = Vec3(1f, 1f, 1f)
        public val UP: Vec3 = Vec3(0f, 1f, 0f)
    }
}
