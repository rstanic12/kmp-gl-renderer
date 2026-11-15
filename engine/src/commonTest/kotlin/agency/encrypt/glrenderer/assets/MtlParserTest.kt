package agency.encrypt.glrenderer.assets

import agency.encrypt.glrenderer.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MtlParserTest {

    @Test
    fun readsTheMetalRoughnessExtension() {
        val materials = MtlParser.parse(
            """
            # exported
            newmtl brass
            Kd 0.9 0.7 0.3
            Pm 1
            Pr 0.3
            Ns 900

            newmtl glass
            Kd 0.9 0.9 0.9
            Tr 0.75
            Ke 0 2.5 0
            map_Kd -bm 1 textures/glass.png
            """.trimIndent(),
        )
        assertEquals(listOf("brass", "glass"), materials.keys.toList())

        val brass = materials.getValue("brass")
        assertEquals(Vec3(0.9f, 0.7f, 0.3f), brass.baseColor)
        assertEquals(1f, brass.metallic)
        assertEquals(0.3f, brass.roughness, "an explicit Pr wins over Ns, whatever the order")
        assertFalse(brass.isTransparent)

        val glass = materials.getValue("glass")
        assertEquals(0.25f, glass.opacity, 1e-6f)
        assertTrue(glass.isTransparent)
        assertEquals(Vec3(0f, 2.5f, 0f), glass.emissive)
        assertEquals("textures/glass.png", glass.baseColorMap)
    }

    @Test
    fun blinnPhongExponentsBecomeRoughness() {
        val materials = MtlParser.parse("newmtl shiny\nNs 1000\nnewmtl matte\nNs 2\nnewmtl unspecified")
        val shiny = materials.getValue("shiny").roughness
        val matte = materials.getValue("matte").roughness
        assertTrue(shiny < 0.25f && matte > 0.8f, "shiny $shiny, matte $matte")
        assertEquals(Material.DEFAULT.roughness, materials.getValue("unspecified").roughness)
    }

    @Test
    fun clampsOutOfRangeValuesInsteadOfFailing() {
        val material = MtlParser.parse("newmtl m\nPr 0\nPm 3\nd 1.5").getValue("m")
        assertEquals(MtlParser.MIN_ROUGHNESS, material.roughness)
        assertEquals(1f, material.metallic)
        assertEquals(1f, material.opacity)
    }

    @Test
    fun siblingPathsResolveNextToTheModel() {
        assertEquals("models/car.mtl", siblingPath("models/car.obj", "car.mtl"))
        assertEquals("car.mtl", siblingPath("car.obj", "car.mtl"))
    }
}
