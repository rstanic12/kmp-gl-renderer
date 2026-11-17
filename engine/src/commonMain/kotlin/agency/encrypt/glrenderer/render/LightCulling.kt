package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.OrbitCamera
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Pixels, origin bottom-left, as glScissor and gl_FragCoord use them. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal object LightCulling {

    /**
     * The pixels a light of [radius] can reach: the exact bounding rectangle of its sphere's perspective projection,
     * including when the sphere crosses the near plane, or null if none of it is on screen.
     *
     * [center] is in view space (camera at the origin looking down -z). Each screen axis is solved in its own plane
     * through the eye, as in Mara and McGuire, "2D Polyhedral Bounds of a Clipped, Perspective-Projected 3D Sphere"
     * (JCGT 2013).
     */
    fun screenRect(center: Vec3, radius: Float, projection: Mat4, near: Float, width: Int, height: Int): PixelRect? {
        val nearZ = -near
        if (center.z - radius > nearZ) return null
        val (minX, maxX) = axisBounds(center.x, center.z, radius, nearZ, projection[0, 0])
        val (minY, maxY) = axisBounds(center.y, center.z, radius, nearZ, projection[1, 1])
        if (maxX < -1f || minX > 1f || maxY < -1f || minY > 1f) return null

        val left = floor((minX.coerceAtLeast(-1f) * 0.5f + 0.5f) * width).toInt()
        val right = ceil((maxX.coerceAtMost(1f) * 0.5f + 0.5f) * width).toInt()
        val bottom = floor((minY.coerceAtLeast(-1f) * 0.5f + 0.5f) * height).toInt()
        val top = ceil((maxY.coerceAtMost(1f) * 0.5f + 0.5f) * height).toInt()
        if (right <= left || top <= bottom) return null
        return PixelRect(left, bottom, right - left, top - bottom)
    }

    /**
     * NDC bounds along one axis; [a] and [z] are the centre's coordinates in that axis's plane, [scale] the projection's.
     *
     * In that plane the visible part of the sphere is its circle cut by the near plane: an arc, closed by a chord on the
     * near plane when the circle crosses it. Projected, its extremes can only be at the two points where lines from the
     * eye touch the circle (if they are in front of the near plane) or at the chord's ends, so the bounds are the
     * minimum and maximum over those candidates.
     */
    private fun axisBounds(a: Float, z: Float, r: Float, nearZ: Float, scale: Float): Pair<Float, Float> {
        var low = Float.MAX_VALUE
        var high = -Float.MAX_VALUE
        fun candidate(pointA: Float, pointZ: Float) {
            if (pointZ > nearZ) return
            val ndc = scale * pointA / -pointZ
            low = min(low, ndc)
            high = max(high, ndc)
        }

        val tSquared = a * a + z * z - r * r
        if (tSquared > 0f) {
            // Tangent points: the centre direction rotated by ± the half-angle the circle subtends, at tangent length.
            val length = sqrt(a * a + z * z)
            val cos = sqrt(tSquared) / length
            val sin = r / length
            candidate((cos * a + sin * z) * cos, (-sin * a + cos * z) * cos)
            candidate((cos * a - sin * z) * cos, (sin * a + cos * z) * cos)
        }
        val chordSquared = r * r - (nearZ - z) * (nearZ - z)
        if (chordSquared >= 0f) {
            val k = sqrt(chordSquared)
            candidate(a - k, nearZ)
            candidate(a + k, nearZ)
        }
        return low to high
    }
}

/**
 * Clustered forward+ light lists. The view frustum is cut into [TILE_SIZE] pixel tiles and [SLICES] depth slices,
 * spaced exponentially so near and far clusters are similar in shape; each cluster lists the lights whose screen
 * rectangle and depth range overlap it. ES 3.0 has no compute shaders, so the lists are built on the CPU and
 * uploaded as two RGBA32F textures:
 *
 * - [clusterData]: one texel per cluster, (offset, count) into the index list. Texel (x + slice * tilesX, y).
 * - [indexData]: every cluster's light indices back to back, four per texel, [INDEX_TEXTURE_WIDTH] texels per row.
 */
internal class ClusterGrid(val width: Int, val height: Int) {
    val tilesX = (width + TILE_SIZE - 1) / TILE_SIZE
    val tilesY = (height + TILE_SIZE - 1) / TILE_SIZE
    val clusterData = FloatArray(tilesX * tilesY * SLICES * 4)
    var indexData = FloatArray(INDEX_TEXTURE_WIDTH * 4)
        private set
    var indexCount = 0
        private set

    val clusterTextureWidth get() = tilesX * SLICES
    val clusterTextureHeight get() = tilesY
    val indexTextureHeight get() = maxOf(1, (indexCount + INDEX_TEXTURE_WIDTH * 4 - 1) / (INDEX_TEXTURE_WIDTH * 4))

    private class Entry(val light: Int, val x0: Int, val x1: Int, val y0: Int, val y1: Int, val s0: Int, val s1: Int)

    private val entries = mutableListOf<Entry>()
    private val counts = IntArray(tilesX * tilesY * SLICES)

    fun clear() {
        entries.clear()
    }

    /** Queues [light] for the clusters under [rect] between view depths [nearDepth] and [farDepth]. */
    fun add(light: Int, rect: PixelRect, nearDepth: Float, farDepth: Float) {
        entries += Entry(
            light,
            (rect.x / TILE_SIZE).coerceIn(0, tilesX - 1),
            ((rect.x + rect.width - 1) / TILE_SIZE).coerceIn(0, tilesX - 1),
            (rect.y / TILE_SIZE).coerceIn(0, tilesY - 1),
            ((rect.y + rect.height - 1) / TILE_SIZE).coerceIn(0, tilesY - 1),
            sliceOf(nearDepth),
            sliceOf(farDepth),
        )
    }

    /** Lays out the queued lights: count per cluster, prefix sums for offsets, then the indices themselves. */
    fun build() {
        counts.fill(0)
        forEachCluster { cluster, _ -> counts[cluster]++ }
        var offset = 0
        for (cluster in counts.indices) {
            clusterData[cluster * 4] = offset.toFloat()
            clusterData[cluster * 4 + 1] = 0f
            offset += counts[cluster]
        }
        indexCount = offset
        val needed = indexTextureHeight * INDEX_TEXTURE_WIDTH * 4
        if (indexData.size < needed) indexData = FloatArray(needed)
        forEachCluster { cluster, light ->
            val filled = clusterData[cluster * 4 + 1].toInt()
            indexData[clusterData[cluster * 4].toInt() + filled] = light.toFloat()
            clusterData[cluster * 4 + 1] = (filled + 1).toFloat()
        }
    }

    private inline fun forEachCluster(action: (cluster: Int, light: Int) -> Unit) {
        for (e in entries) {
            for (slice in e.s0..e.s1) for (y in e.y0..e.y1) for (x in e.x0..e.x1) {
                action(y * tilesX * SLICES + slice * tilesX + x, e.light)
            }
        }
    }

    fun lightsIn(tileX: Int, tileY: Int, slice: Int): List<Int> {
        val cluster = tileY * tilesX * SLICES + slice * tilesX + tileX
        val offset = clusterData[cluster * 4].toInt()
        return (0 until clusterData[cluster * 4 + 1].toInt()).map { indexData[offset + it].toInt() }
    }

    companion object {
        const val TILE_SIZE = 64
        const val SLICES = 16
        const val INDEX_TEXTURE_WIDTH = 1024

        private val LOG_RANGE = ln(OrbitCamera.FAR / OrbitCamera.NEAR)

        /** slice = log(depth) * SLICE_SCALE + SLICE_BIAS, the same formula the shader evaluates. */
        val SLICE_SCALE = SLICES / LOG_RANGE
        val SLICE_BIAS = -SLICES * ln(OrbitCamera.NEAR) / LOG_RANGE

        fun sliceOf(depth: Float): Int =
            if (depth <= OrbitCamera.NEAR) 0 else floor(ln(depth) * SLICE_SCALE + SLICE_BIAS).toInt().coerceIn(0, SLICES - 1)
    }
}
