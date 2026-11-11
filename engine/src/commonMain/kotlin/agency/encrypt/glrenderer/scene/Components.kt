package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.ecs.Component
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3

/** Local transform, relative to the [Parent] if there is one. Rotation is Euler degrees. */
data class Transform(
    val position: Vec3 = Vec3.ZERO,
    val rotation: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3.ONE,
) : Component {
    fun matrix() = Mat4.trs(position, rotation, scale)
}

data class Parent(val entityId: Int) : Component

/** Written by [TransformSystem] every frame: the entity's transform in world space. */
data class WorldTransform(val matrix: Mat4) : Component

/** Path of an OBJ file in the asset source; its `mtllib` materials are loaded with it. */
data class ModelRef(val path: String) : Component

/** Continuous rotation, in degrees per second on each axis. */
data class Spin(val degreesPerSecond: Vec3) : Component
