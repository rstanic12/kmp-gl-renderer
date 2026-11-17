package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.ecs.System
import agency.encrypt.glrenderer.ecs.World

public class SpinSystem : System {
    override fun update(world: World, deltaSeconds: Float) {
        world.each<Transform, Spin> { entity, transform, spin ->
            entity.add(transform.copy(rotation = transform.rotation + spin.degreesPerSecond * deltaSeconds))
        }
    }
}
