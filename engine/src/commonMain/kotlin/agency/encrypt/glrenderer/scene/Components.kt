package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.ecs.Component
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3

/** Local transform, relative to the [Parent] if there is one. Rotation is Euler degrees. */
public data class Transform(
    val position: Vec3 = Vec3.ZERO,
    val rotation: Vec3 = Vec3.ZERO,
    val scale: Vec3 = Vec3.ONE,
) : Component {
    public fun matrix(): Mat4 = Mat4.trs(position, rotation, scale)
}

public data class Parent(val entityId: Int) : Component

/** Written by [TransformSystem] every frame: the entity's transform in world space. */
public data class WorldTransform(val matrix: Mat4) : Component

/** Path of an OBJ file in the asset source; its `mtllib` materials are loaded with it. */
public data class ModelRef(val path: String) : Component

/** Continuous rotation, in degrees per second on each axis. */
public data class Spin(val degreesPerSecond: Vec3) : Component

/** Linear colour that multiplies a model's base colour and emission, so instances of one mesh can differ. */
public data class Tint(val color: Vec3) : Component

/**
 * A point light at the entity's world position. [color] is linear and carries the intensity;
 * the light fades smoothly to nothing at [radius], which is also what culling uses.
 * With [castsShadows], it renders a cube shadow map, within the renderer's budget of shadowed lights.
 */
public data class PointLight(val color: Vec3, val radius: Float, val castsShadows: Boolean = false) : Component
