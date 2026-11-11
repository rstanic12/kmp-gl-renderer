package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.demo.DemoScene
import agency.encrypt.glrenderer.demo.Lid
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.math.assertNear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SceneTest {

    @Test
    fun childTransformsComposeThroughTheHierarchy() {
        val world = World()
        val root = world.createEntity().add(Transform(position = Vec3(10f, 0f, 0f), rotation = Vec3(0f, 90f, 0f)))
        val child = world.createEntity().add(Transform(position = Vec3(1f, 0f, 0f))).add(Parent(root.id))
        val grandchild = world.createEntity().add(Transform(scale = Vec3(2f, 2f, 2f))).add(Parent(child.id))
        world.addSystem(TransformSystem())

        world.update(0f)

        val matrix = grandchild.get<WorldTransform>()!!.matrix
        // Local (1,0,0) is scaled to (2,0,0), moved by the child to (3,0,0), turned by the root to (0,0,-3), then shifted.
        assertNear(Vec3(10f, 0f, -3f), matrix.transformPoint(Vec3(1f, 0f, 0f)))
    }

    @Test
    fun parentCyclesAreRejected() {
        val world = World()
        val a = world.createEntity().add(Transform())
        val b = world.createEntity().add(Transform()).add(Parent(a.id))
        a.add(Parent(b.id))
        world.addSystem(TransformSystem())

        assertFailsWith<IllegalStateException> { world.update(0f) }
    }

    @Test
    fun spinAdvancesWithTime() {
        val world = World()
        val entity = world.createEntity().add(Transform()).add(Spin(Vec3(0f, 90f, 0f)))
        world.addSystem(SpinSystem())

        world.update(0.5f)

        assertEquals(45f, entity.get<Transform>()!!.rotation.y)
    }

    @Test
    fun demoLidOpensOnRequest() {
        val world = DemoScene.create()
        val lid = world.entities.single { it.has<Lid>() }
        world.update(0f)
        val closed = lid.get<WorldTransform>()!!.matrix.translation()

        DemoScene.toggleLid(world)
        repeat(10) { world.update(0.1f) }

        assertEquals(1f, lid.get<Lid>()!!.progress)
        val open = lid.get<WorldTransform>()!!.matrix.translation()
        assertEquals(true, open.y > closed.y, "lid lifted: $closed -> $open")
    }
}
