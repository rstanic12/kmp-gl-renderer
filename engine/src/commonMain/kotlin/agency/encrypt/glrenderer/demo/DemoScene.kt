package agency.encrypt.glrenderer.demo

import agency.encrypt.glrenderer.ecs.Component
import agency.encrypt.glrenderer.ecs.System
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.ModelRef
import agency.encrypt.glrenderer.scene.Parent
import agency.encrypt.glrenderer.scene.Spin
import agency.encrypt.glrenderer.scene.SpinSystem
import agency.encrypt.glrenderer.scene.Transform
import agency.encrypt.glrenderer.scene.TransformSystem

/**
 * turntable (spins)
 * └── teapot
 *     └── lid (lifts and tips open on request)
 *
 * The lid only knows its offset from the teapot; spinning the turntable carries both along.
 */
object DemoScene {
    fun create(): World = World().apply {
        val turntable = createEntity()
            .add(Transform())
            .add(Spin(Vec3(0f, 18f, 0f)))
            .add(ModelRef("turntable.obj"))
        val teapot = createEntity()
            .add(Transform(rotation = Vec3(0f, -30f, 0f)))
            .add(Parent(turntable.id))
            .add(ModelRef("teapot.obj"))
        createEntity()
            .add(Transform())
            .add(Parent(teapot.id))
            .add(ModelRef("lid.obj"))
            .add(Lid())

        addSystem(SpinSystem())
        addSystem(LidSystem())
        addSystem(TransformSystem())
    }

    fun toggleLid(world: World) {
        world.each<Lid> { entity, lid -> entity.add(lid.copy(open = !lid.open)) }
    }
}

data class Lid(val open: Boolean = false, val progress: Float = 0f) : Component

/** Eases the lid between closed and open: it rises and tips back, pivoting about its own centre. */
class LidSystem(private val secondsToOpen: Float = 0.6f) : System {
    override fun update(world: World, deltaSeconds: Float) {
        world.each<Lid, Transform> { entity, lid, transform ->
            val step = deltaSeconds / secondsToOpen
            val progress = (if (lid.open) lid.progress + step else lid.progress - step).coerceIn(0f, 1f)
            val eased = progress * progress * (3f - 2f * progress)
            val tilt = TILT_DEGREES * eased
            // Transform rotates about the origin; offsetting by (pivot - R * pivot) rotates about the pivot instead.
            val pivotOffset = PIVOT - Mat4.rotationZ(tilt).transformPoint(PIVOT)
            entity.add(lid.copy(progress = progress))
            entity.add(
                transform.copy(
                    position = pivotOffset + Vec3(0f, LIFT * eased, 0f),
                    rotation = Vec3(0f, 0f, tilt),
                ),
            )
        }
    }

    private companion object {
        const val LIFT = 0.4f
        const val TILT_DEGREES = 28f
        val PIVOT = Vec3(0f, 1.1f, 0f)
    }
}
