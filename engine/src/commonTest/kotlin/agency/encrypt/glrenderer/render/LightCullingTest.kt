package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.demo.DemoScene
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.PointLight
import agency.encrypt.glrenderer.scene.WorldTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightCullingTest {
    // Looking down -z from the origin, 90 degree field of view, square 100 x 100 viewport.
    private val viewProjection = Mat4.perspective(90f, 1f, 0.1f, 100f) * Mat4.lookAt(Vec3.ZERO, Vec3(0f, 0f, -1f), Vec3.UP)

    private fun rect(center: Vec3, radius: Float) = LightCulling.screenRect(center, radius, viewProjection, 100, 100)

    @Test
    fun aLightAheadCoversAWindowAroundItsCentre() {
        val r = rect(Vec3(0f, 0f, -10f), 1f)!!
        // At z = -10 with a 90 degree FOV, the half-width of the view is 10, so radius 1 is ~5 px each side.
        assertTrue(r.x in 42..45 && r.x + r.width in 55..58, "$r")
        assertTrue(r.y in 42..45 && r.y + r.height in 55..58, "$r")
    }

    @Test
    fun theRectangleIsClampedToTheViewport() {
        val r = rect(Vec3(9.5f, 0f, -10f), 2f)!!
        assertEquals(100, r.x + r.width)
    }

    @Test
    fun lightsBehindOrBesideTheViewAreCulled() {
        assertNull(rect(Vec3(0f, 0f, 10f), 1f))
        assertNull(rect(Vec3(30f, 0f, -10f), 1f))
    }

    @Test
    fun aLightAroundTheCameraCoversTheWholeScreen() {
        assertEquals(PixelRect(0, 0, 100, 100), rect(Vec3(0f, 0f, -0.5f), 2f))
    }

    @Test
    fun tilesListOnlyTheLightsThatTouchThem() {
        val grid = TileGrid(100, 70) // 4 x 3 tiles of 32 px
        grid.add(0, PixelRect(0, 0, 10, 10))
        grid.add(1, PixelRect(20, 20, 30, 30)) // spans tiles (0..1, 0..1)

        assertEquals(4, grid.tilesX)
        assertEquals(3, grid.tilesY)
        assertEquals(listOf(0, 1), grid.lightsIn(0, 0))
        assertEquals(listOf(1), grid.lightsIn(1, 1))
        assertEquals(emptyList(), grid.lightsIn(3, 2))
    }

    /** Regression: with 15 slots per tile, the demo dropped lights in the middle of the screen, leaving seams. */
    @Test
    fun theDemoSceneNeverFillsATileFromAnyAngle() {
        val world = DemoScene.create()
        val camera = DemoScene.camera()
        val (width, height) = 1344 to 2992
        repeat(8) {
            camera.orbit(45f, 0f)
            repeat(20) {
                world.update(0.5f)
                val grid = TileGrid(width, height)
                val viewProjection = camera.viewProjection(width.toFloat() / height)
                var index = 0
                world.each<PointLight, WorldTransform> { _, light, transform ->
                    LightCulling.screenRect(transform.matrix.translation(), light.radius, viewProjection, width, height)
                        ?.let { grid.add(index++, it) }
                }
                assertEquals(0, grid.overflowed, "yaw ${camera.yawDegrees}")
            }
        }
    }

    @Test
    fun aFullTileDropsFurtherLightsInsteadOfOverflowingIntoTheNext() {
        val grid = TileGrid(64, 32)
        repeat(TileGrid.MAX_LIGHTS_PER_TILE + 2) { grid.add(it, PixelRect(0, 0, 1, 1)) }

        assertEquals(TileGrid.MAX_LIGHTS_PER_TILE, grid.lightsIn(0, 0).size)
        assertEquals(2, grid.overflowed)
        assertEquals(emptyList(), grid.lightsIn(1, 0))
    }
}
