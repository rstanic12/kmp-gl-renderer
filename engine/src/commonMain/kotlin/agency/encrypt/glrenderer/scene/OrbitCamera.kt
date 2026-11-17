package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A camera that circles [target]: yaw and pitch in degrees, at [distance]. Suits touch orbit and pinch zoom. */
public class OrbitCamera(
    public var target: Vec3 = Vec3.ZERO,
    yawDegrees: Float = 30f,
    pitchDegrees: Float = 20f,
    distance: Float = 5f,
    private val minDistance: Float = 2f,
    private val maxDistance: Float = 12f,
    private val fovYDegrees: Float = 45f,
    override val near: Float = 0.1f,
    override val far: Float = 100f,
) : Camera {
    public var yawDegrees: Float = yawDegrees
        private set
    public var pitchDegrees: Float = pitchDegrees.coerceIn(MIN_PITCH, MAX_PITCH)
        private set
    public var distance: Float = distance.coerceIn(minDistance, maxDistance)
        private set

    public fun orbit(deltaYawDegrees: Float, deltaPitchDegrees: Float) {
        yawDegrees = (yawDegrees + deltaYawDegrees) % 360f
        pitchDegrees = (pitchDegrees + deltaPitchDegrees).coerceIn(MIN_PITCH, MAX_PITCH)
    }

    /** factor > 1 moves the camera closer. */
    public fun zoom(factor: Float) {
        if (factor > 0f) distance = (distance / factor).coerceIn(minDistance, maxDistance)
    }

    public fun eye(): Vec3 {
        val yaw = yawDegrees * PI.toFloat() / 180f
        val pitch = pitchDegrees * PI.toFloat() / 180f
        return target + Vec3(cos(pitch) * sin(yaw), sin(pitch), cos(pitch) * cos(yaw)) * distance
    }

    override fun view(): Mat4 = Mat4.lookAt(eye(), target, Vec3.UP)

    override fun projection(aspect: Float): Mat4 = Mat4.perspective(effectiveFovY(aspect), aspect, near, far)

    public fun viewProjection(aspect: Float): Mat4 = projection(aspect) * view()

    /** On portrait screens, widen the vertical FOV so the horizontal FOV never drops below [fovYDegrees]. */
    public fun effectiveFovY(aspect: Float): Float {
        if (aspect >= 1f) return fovYDegrees
        val halfFov = fovYDegrees * PI.toFloat() / 360f
        return (2f * atan(tan(halfFov) / aspect) * 180f / PI.toFloat()).coerceAtMost(MAX_FOV_Y)
    }

    private companion object {
        const val MIN_PITCH = -10f
        const val MAX_PITCH = 85f
        const val MAX_FOV_Y = 100f
    }
}
