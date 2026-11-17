package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3

/** What the renderer needs from a camera. [OrbitCamera] is one; implement this for any other kind. */
public interface Camera {
    /** Distance to the near clip plane; [projection] must use the same value. */
    public val near: Float

    /** Distance to the far clip plane; [projection] must use the same value. */
    public val far: Float

    /** World to view space: the camera at the origin, looking down -z, +y up. */
    public fun view(): Mat4

    /** View to clip space for a viewport of this width / height. */
    public fun projection(aspect: Float): Mat4
}

/** The camera's position in world space. */
public fun Camera.position(): Vec3 = view().inverse().translation()

/** The unit vector the camera looks along, in world space. */
public fun Camera.forward(): Vec3 {
    val view = view()
    return Vec3(-view[2, 0], -view[2, 1], -view[2, 2]).normalized()
}
