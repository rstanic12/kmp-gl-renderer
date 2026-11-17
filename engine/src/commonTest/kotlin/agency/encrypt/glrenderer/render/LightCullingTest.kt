package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LightCullingTest {
    private val near = 0.1f
    private val width = 400
    private val height = 300
    private val projection = Mat4.perspective(70f, width.toFloat() / height, near, 100f)

    private fun rect(center: Vec3, radius: Float) = LightCulling.screenRect(center, radius, projection, near, width, height)

    /**
     * The pixel rectangle of everything inside the sphere that is in front of the near plane, found by projecting
     * thousands of points: the sphere's surface plus its cap on the near plane.
     */
    private fun bruteForce(center: Vec3, radius: Float): PixelRect? {
        var minX = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        fun project(p: Vec3) {
            if (p.z > -near) return
            val x = (projection[0, 0] * p.x / -p.z).coerceIn(-1f, 1f)
            val y = (projection[1, 1] * p.y / -p.z).coerceIn(-1f, 1f)
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val steps = 400
        for (i in 0..steps) for (j in 0 until steps) {
            val theta = PI * i / steps
            val phi = 2 * PI * j / steps
            project(center + Vec3((sin(theta) * cos(phi)).toFloat(), cos(theta).toFloat(), (sin(theta) * sin(phi)).toFloat()) * radius)
        }
        val capRadius2 = radius * radius - (-near - center.z) * (-near - center.z)
        if (capRadius2 > 0f) {
            val capRadius = sqrt(capRadius2)
            for (j in 0 until steps) {
                val phi = 2 * PI * j / steps
                project(Vec3(center.x + capRadius * cos(phi).toFloat(), center.y + capRadius * sin(phi).toFloat(), -near))
            }
        }
        if (minX > maxX || maxX <= -1f || minX >= 1f || maxY <= -1f || minY >= 1f) return null
        val left = floor((minX * 0.5f + 0.5f) * width).toInt()
        val bottom = floor((minY * 0.5f + 0.5f) * height).toInt()
        return PixelRect(
            left, bottom,
            ceil((maxX * 0.5f + 0.5f) * width).toInt() - left,
            ceil((maxY * 0.5f + 0.5f) * height).toInt() - bottom,
        )
    }

    private fun assertMatchesBruteForce(center: Vec3, radius: Float) {
        val exact = rect(center, radius)
        val sampled = bruteForce(center, radius)
        if (sampled == null) {
            assertNull(exact, "$center r=$radius")
            return
        }
        assertTrue(exact != null, "$center r=$radius: culled, but brute force sees $sampled")
        // Contains every sampled point, and is no more than a pixel or two larger (sampling misses the exact extremes).
        assertTrue(exact.x <= sampled.x && exact.y <= sampled.y, "$center r=$radius: $exact vs $sampled")
        assertTrue(exact.x + exact.width >= sampled.x + sampled.width, "$center r=$radius: $exact vs $sampled")
        assertTrue(exact.y + exact.height >= sampled.y + sampled.height, "$center r=$radius: $exact vs $sampled")
        assertTrue(sampled.x - exact.x <= 2 && sampled.y - exact.y <= 2, "$center r=$radius: $exact vs $sampled")
        assertTrue(exact.width - sampled.width <= 3 && exact.height - sampled.height <= 3, "$center r=$radius: $exact vs $sampled")
    }

    @Test
    fun boundsAreExactForSpheresInFront() {
        assertMatchesBruteForce(Vec3(0f, 0f, -5f), 1f)
        assertMatchesBruteForce(Vec3(2f, -1f, -4f), 1.5f)
        assertMatchesBruteForce(Vec3(-3f, 2f, -3f), 0.5f) // off-axis, where a bounding box over-covers the most
    }

    @Test
    fun boundsAreExactWhenTheSphereCrossesTheNearPlane() {
        assertMatchesBruteForce(Vec3(1f, 0.5f, -0.5f), 1f)
        assertMatchesBruteForce(Vec3(0.3f, 0f, 0.2f), 0.6f)
    }

    @Test
    fun boundsAreExactForRandomSpheres() {
        val random = Random(7)
        repeat(60) {
            val center = Vec3(random.nextFloat() * 8f - 4f, random.nextFloat() * 6f - 3f, random.nextFloat() * -8f + 1f)
            assertMatchesBruteForce(center, 0.2f + random.nextFloat() * 2f)
        }
    }

    @Test
    fun aCameraInsideTheLightIsCoveredCompletely() {
        assertEquals(PixelRect(0, 0, width, height), rect(Vec3(0f, 0f, -0.5f), 2f))
    }

    @Test
    fun lightsBehindOrBesideTheViewAreCulled() {
        assertNull(rect(Vec3(0f, 0f, 3f), 1f))
        assertNull(rect(Vec3(30f, 0f, -5f), 1f))
    }

    @Test
    fun clustersListLightsOnlyWhereTheirRectangleAndDepthRangeOverlap() {
        val grid = ClusterGrid(256, 128) // 4 x 2 tiles of 64 px
        grid.add(0, PixelRect(0, 0, 10, 10), nearDepth = 0.5f, farDepth = 1f)
        grid.add(1, PixelRect(60, 60, 80, 10), nearDepth = 20f, farDepth = 30f) // tiles x 0..2, y 0..1
        grid.build()

        val nearSlice = ClusterGrid.sliceOf(0.7f)
        val farSlice = ClusterGrid.sliceOf(25f)
        assertTrue(farSlice > nearSlice)
        assertEquals(listOf(0), grid.lightsIn(0, 0, nearSlice))
        assertEquals(listOf(1), grid.lightsIn(0, 0, farSlice))
        assertEquals(listOf(1), grid.lightsIn(2, 1, farSlice))
        assertEquals(emptyList(), grid.lightsIn(3, 0, farSlice))
        assertEquals(emptyList(), grid.lightsIn(2, 1, nearSlice), "light 1 is far behind this cluster")
    }

    @Test
    fun clustersHaveNoPerClusterLimit() {
        val grid = ClusterGrid(64, 64)
        repeat(Shaders.MAX_LIGHTS) { grid.add(it, PixelRect(0, 0, 64, 64), 1f, 2f) }
        grid.build()
        assertEquals(Shaders.MAX_LIGHTS, grid.lightsIn(0, 0, ClusterGrid.sliceOf(1.5f)).size)
    }

    @Test
    fun slicesGrowExponentiallyAndCoverTheDepthRange() {
        assertEquals(0, ClusterGrid.sliceOf(0.05f))
        assertEquals(ClusterGrid.SLICES - 1, ClusterGrid.sliceOf(99f))
        // Each slice spans the same depth ratio: (far / near) ^ (1 / SLICES).
        val ratio = 1000.0.pow(1.0 / ClusterGrid.SLICES)
        for (k in 0 until ClusterGrid.SLICES) {
            assertEquals(k, ClusterGrid.sliceOf((0.1 * ratio.pow(k + 0.5)).toFloat()))
        }
    }
}
