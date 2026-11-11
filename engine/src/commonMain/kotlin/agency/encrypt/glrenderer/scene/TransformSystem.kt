package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.ecs.Entity
import agency.encrypt.glrenderer.ecs.System
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.math.Mat4

/** Resolves the [Parent] hierarchy: world = parentWorld * local, each matrix computed once per frame. */
class TransformSystem : System {
    override fun update(world: World, deltaSeconds: Float) {
        val resolved = HashMap<Int, Mat4>()
        val inProgress = HashSet<Int>()

        fun worldMatrix(entity: Entity): Mat4 {
            resolved[entity.id]?.let { return it }
            check(inProgress.add(entity.id)) { "Parent cycle through $entity" }
            val local = entity.get<Transform>()?.matrix() ?: Mat4.IDENTITY
            val parent = entity.get<Parent>()?.let { world.entity(it.entityId) }
            val matrix = if (parent != null) worldMatrix(parent) * local else local
            inProgress.remove(entity.id)
            resolved[entity.id] = matrix
            return matrix
        }

        world.each<Transform> { entity, _ ->
            entity.add(WorldTransform(worldMatrix(entity)))
        }
    }
}
