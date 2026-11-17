package agency.encrypt.glrenderer.demo

import agency.encrypt.glrenderer.scene.PointLight
import agency.encrypt.glrenderer.scene.WorldTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DemoSceneTest {

    @Test
    fun lidOpensOnRequest() {
        val world = DemoScene.create()
        val lid = world.entities.single { it.has<Lid>() }
        world.update(0f)
        val closed = lid.get<WorldTransform>()!!.matrix.translation()

        DemoScene.toggleLid(world)
        repeat(10) { world.update(0.1f) }

        assertEquals(1f, lid.get<Lid>()!!.progress)
        val open = lid.get<WorldTransform>()!!.matrix.translation()
        assertTrue(open.y > closed.y, "lid lifted: $closed -> $open")
    }

    @Test
    fun lightsRideTheirSpinningRings() {
        val world = DemoScene.create()
        val light = world.entities.first { it.has<PointLight>() }
        world.update(0f)
        val before = light.get<WorldTransform>()!!.matrix.translation()
        world.update(1f)
        val after = light.get<WorldTransform>()!!.matrix.translation()

        assertTrue((after - before).length() > 0.1f, "$before -> $after")
        assertEquals(before.y, after.y, 1e-4f)
    }
}
