package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import kotlin.math.ceil
import kotlin.math.floor

/** Pixels, origin bottom-left, as glScissor and gl_FragCoord use them. */
data class PixelRect(val x: Int, val y: Int, val width: Int, val height: Int)

internal object LightCulling {

    /**
     * The pixels a light of [radius] at [center] can reach, or null if it is entirely off screen.
     * Projects the corners of the sphere's bounding box, which over-covers a little but never misses;
     * a box that crosses the near plane cannot be projected and conservatively covers the whole screen.
     */
    fun screenRect(center: Vec3, radius: Float, viewProjection: Mat4, width: Int, height: Int): PixelRect? {
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        var behind = 0
        for (corner in 0 until 8) {
            val x = center.x + if (corner and 1 == 0) -radius else radius
            val y = center.y + if (corner and 2 == 0) -radius else radius
            val z = center.z + if (corner and 4 == 0) -radius else radius
            val w = viewProjection[3, 0] * x + viewProjection[3, 1] * y + viewProjection[3, 2] * z + viewProjection[3, 3]
            if (w <= MIN_W) {
                behind++
                continue
            }
            val ndcX = (viewProjection[0, 0] * x + viewProjection[0, 1] * y + viewProjection[0, 2] * z + viewProjection[0, 3]) / w
            val ndcY = (viewProjection[1, 0] * x + viewProjection[1, 1] * y + viewProjection[1, 2] * z + viewProjection[1, 3]) / w
            minX = minOf(minX, ndcX); maxX = maxOf(maxX, ndcX)
            minY = minOf(minY, ndcY); maxY = maxOf(maxY, ndcY)
        }
        if (behind == 8) return null
        if (behind > 0) return PixelRect(0, 0, width, height)
        if (maxX < -1f || minX > 1f || maxY < -1f || minY > 1f) return null

        val left = floor((minX.coerceAtLeast(-1f) * 0.5f + 0.5f) * width).toInt()
        val right = ceil((maxX.coerceAtMost(1f) * 0.5f + 0.5f) * width).toInt()
        val bottom = floor((minY.coerceAtLeast(-1f) * 0.5f + 0.5f) * height).toInt()
        val top = ceil((maxY.coerceAtMost(1f) * 0.5f + 0.5f) * height).toInt()
        if (right <= left || top <= bottom) return null
        return PixelRect(left, bottom, right - left, top - bottom)
    }

    private const val MIN_W = 1e-4f
}

/**
 * Forward+ light lists: the screen is cut into [TILE_SIZE] pixel tiles, and each tile lists the lights whose
 * screen rectangle touches it. ES 3.0 has no compute shaders, so the lists are built on the CPU and uploaded
 * as an RGBA32F texture: each tile owns [TEXELS_PER_TILE] texels in a row, i.e. 64 floats holding
 * the light count followed by up to [MAX_LIGHTS_PER_TILE] light indices.
 */
internal class TileGrid(val width: Int, val height: Int) {
    val tilesX = (width + TILE_SIZE - 1) / TILE_SIZE
    val tilesY = (height + TILE_SIZE - 1) / TILE_SIZE
    val data = FloatArray(tilesX * tilesY * FLOATS_PER_TILE)

    /** Tiles that had more lights than fit; those lights are dropped for that tile. */
    var overflowed = 0
        private set

    val textureWidth get() = tilesX * TEXELS_PER_TILE
    val textureHeight get() = tilesY

    fun clear() {
        data.fill(0f)
        overflowed = 0
    }

    fun add(lightIndex: Int, rect: PixelRect) {
        val firstX = (rect.x / TILE_SIZE).coerceIn(0, tilesX - 1)
        val lastX = ((rect.x + rect.width - 1) / TILE_SIZE).coerceIn(0, tilesX - 1)
        val firstY = (rect.y / TILE_SIZE).coerceIn(0, tilesY - 1)
        val lastY = ((rect.y + rect.height - 1) / TILE_SIZE).coerceIn(0, tilesY - 1)
        for (ty in firstY..lastY) {
            for (tx in firstX..lastX) {
                val base = (ty * tilesX + tx) * FLOATS_PER_TILE
                val count = data[base].toInt()
                if (count == MAX_LIGHTS_PER_TILE) {
                    overflowed++
                    continue
                }
                data[base + 1 + count] = lightIndex.toFloat()
                data[base] = (count + 1).toFloat()
            }
        }
    }

    fun lightsIn(tileX: Int, tileY: Int): List<Int> {
        val base = (tileY * tilesX + tileX) * FLOATS_PER_TILE
        return (0 until data[base].toInt()).map { data[base + 1 + it].toInt() }
    }

    companion object {
        const val TILE_SIZE = 32
        const val TEXELS_PER_TILE = 16
        const val FLOATS_PER_TILE = TEXELS_PER_TILE * 4
        const val MAX_LIGHTS_PER_TILE = FLOATS_PER_TILE - 1
    }
}
