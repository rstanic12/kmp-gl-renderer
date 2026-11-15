package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

class OrbitCamera(
    var target: Vec3 = Vec3.ZERO,
    yawDegrees: Float = 30f,
    pitchDegrees: Float = 20f,
    distance: Float = 5f,
    private val minDistance: Float = 2f,
    private val maxDistance: Float = 12f,
    private val fovYDegrees: Float = 45f,
) {
    var yawDegrees = yawDegrees
        private set
    var pitchDegrees = pitchDegrees.coerceIn(MIN_PITCH, MAX_PITCH)
        private set
    var distance = distance.coerceIn(minDistance, maxDistance)
        private set

    fun orbit(deltaYawDegrees: Float, deltaPitchDegrees: Float) {
        yawDegrees = (yawDegrees + deltaYawDegrees) % 360f
        pitchDegrees = (pitchDegrees + deltaPitchDegrees).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /** factor > 1 moves the camera closer. */
    fun zoom(factor: Float) {
        if (factor > 0f) distance = (distance / factor).coerceIn(minDistance, maxDistance)
    }

    fun eye(): Vec3 {
        val yaw = yawDegrees * PI.toFloat() / 180f
        val pitch = pitchDegrees * PI.toFloat() / 180f
        return target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance
    }

    fun viewProjection(aspect: Float): Mat4 =
        Mat4.perspective(effectiveFovY(aspect), aspect, NEAR, FAR) * Mat4.lookAt(eye(), target, Vec3.UP)

    /** On portrait screens, widen the vertical FOV so the horizontal FOV never drops below [fovYDegrees]. */
    fun effectiveFovY(aspect: Float): Float {
        if (aspect >= 1f) return fovYDegrees
        val halfFov = fovYDegrees * PI.toFloat() / 360f
        return (2f * atan(tan(halfFov) / aspect) * 180f / PI.toFloat()).coerceAtMost(MAX_FOV_Y)
    }

    companion object {
        const val NEAR = 0.1f
        const val FAR = 100f
        private const val MIN_PITCH = -10f
        private const val MAX_PITCH = 85f
        private const val MAX_FOV_Y = 100f
    }
}
