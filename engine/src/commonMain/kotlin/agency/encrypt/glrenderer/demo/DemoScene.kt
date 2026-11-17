package agency.encrypt.glrenderer.demo

import agency.encrypt.glrenderer.ecs.Component
import agency.encrypt.glrenderer.ecs.System
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.ModelRef
import agency.encrypt.glrenderer.scene.OrbitCamera
import agency.encrypt.glrenderer.scene.Parent
import agency.encrypt.glrenderer.scene.PointLight
import agency.encrypt.glrenderer.scene.Spin
import agency.encrypt.glrenderer.scene.SpinSystem
import agency.encrypt.glrenderer.scene.Tint
import agency.encrypt.glrenderer.scene.Transform
import agency.encrypt.glrenderer.scene.TransformSystem
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * floor
 * turntable (spins)
 * └── teapot
 *     └── lid (lifts and tips open on request)
 * light ring × 3 (each spins at its own speed)
 * └── point light + bulb × n (the ring above the teapot casts shadows)
 *
 * The lid only knows its offset from the teapot, and each light only its place on its ring:
 * the transform hierarchy carries them along.
 */
object DemoScene {
    fun create(): World = World().apply {
        createEntity()
            .add(Transform(position = Vec3(0f, -0.12f, 0f)))
            .add(ModelRef("floor.obj"))
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

        lightRing(count = 12, radius = 2.4f, height = 0.25f, degreesPerSecond = 24f, hueOffset = 0f)
        // The ring above the teapot reaches further and casts shadows: the teapot throws six coloured shadows.
        lightRing(
            count = 6, radius = 1.3f, height = 2.1f, degreesPerSecond = -30f, hueOffset = 0.5f,
            intensity = 18f, reach = 3.6f, castsShadows = true,
        )
        lightRing(count = 14, radius = 3.6f, height = 0.1f, degreesPerSecond = 11f, hueOffset = 0.25f)

        addSystem(SpinSystem())
        addSystem(LidSystem())
        addSystem(TransformSystem())
    }

    fun camera() = OrbitCamera(target = Vec3(0f, 0.45f, 0f), yawDegrees = 35f, pitchDegrees = 24f, distance = 5.6f)

    fun toggleLid(world: World) {
        world.each<Lid> { entity, lid -> entity.add(lid.copy(open = !lid.open)) }
    }

    private fun World.lightRing(
        count: Int,
        radius: Float,
        height: Float,
        degreesPerSecond: Float,
        hueOffset: Float,
        intensity: Float = LIGHT_INTENSITY,
        reach: Float = LIGHT_RADIUS,
        castsShadows: Boolean = false,
    ) {
        val ring = createEntity()
            .add(Transform(position = Vec3(0f, height, 0f)))
            .add(Spin(Vec3(0f, degreesPerSecond, 0f)))
        repeat(count) { i ->
            val angle = 2f * PI.toFloat() * i / count
            val color = hue(hueOffset + i.toFloat() / count)
            createEntity()
                .add(Transform(position = Vec3(cos(angle) * radius, 0f, sin(angle) * radius)))
                .add(Parent(ring.id))
                .add(PointLight(color * intensity, reach, castsShadows))
                .add(ModelRef("bulb.obj"))
                .add(Tint(color * BULB_GLOW))
        }
    }

    /** HSV to RGB at full value, with the hue [h] in turns. */
    private fun hue(h: Float): Vec3 {
        fun channel(n: Float): Float {
            val k = (n + h * 6f) % 6f
            return 1f - SATURATION * minOf(k, 4f - k, 1f).coerceAtLeast(0f)
        }
        return Vec3(channel(5f), channel(3f), channel(1f))
    }

    private const val SATURATION = 0.8f
    private const val LIGHT_INTENSITY = 5f
    private const val LIGHT_RADIUS = 1.8f
    private const val BULB_GLOW = 12f
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
