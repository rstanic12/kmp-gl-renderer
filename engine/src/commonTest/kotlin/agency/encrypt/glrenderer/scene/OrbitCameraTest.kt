package agency.encrypt.glrenderer.scene

import agency.encrypt.glrenderer.math.Vec3
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.tan
import kotlin.test.Test
import kotlin.test.assertEquals

class OrbitCameraTest {

    @Test
    fun portraitScreensKeepTheHorizontalFieldOfView() {
        val camera = OrbitCamera(fovYDegrees = 45f)
        assertEquals(45f, camera.effectiveFovY(16f / 9f))

        val aspect = 0.5f
        val fovY = camera.effectiveFovY(aspect) * PI.toFloat() / 180f
        val horizontal = 2f * atan(tan(fovY / 2f) * aspect) * 180f / PI.toFloat()
        assertEquals(45f, horizontal, 1e-3f)
    }

    @Test
    fun pitchAndDistanceStayInRange() {
        val camera = OrbitCamera(target = Vec3.ZERO, distance = 5f, minDistance = 2f, maxDistance = 10f)
        camera.orbit(0f, 500f)
        camera.zoom(100f)
        assertEquals(85f, camera.pitchDegrees)
        assertEquals(2f, camera.distance)
        assertEquals(2f, camera.eye().length(), 1e-4f)
    }
}
