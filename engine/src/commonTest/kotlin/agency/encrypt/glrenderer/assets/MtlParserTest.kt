package agency.encrypt.glrenderer.assets

import agency.encrypt.glrenderer.math.Vec3
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MtlParserTest {

    @Test
    fun readsTheBlinnPhongSubset() {
        val materials = MtlParser.parse(
            """
            # exported
            newmtl paint
            Ka 1 1 1
            Kd 0.5 0.25 0.125
            Ks 1 1 1
            Ns 250
            d 1

            newmtl glass
            Kd 0.9 0.9 0.9
            Tr 0.75
            map_Kd -bm 1 textures/glass.png
            """.trimIndent(),
        )
        assertEquals(listOf("paint", "glass"), materials.keys.toList())

        val paint = materials.getValue("paint")
        assertEquals(Vec3(0.5f, 0.25f, 0.125f), paint.diffuse)
        assertEquals(Vec3(1f, 1f, 1f), paint.specular)
        assertEquals(250f, paint.shininess)
        assertFalse(paint.isTransparent)

        val glass = materials.getValue("glass")
        assertEquals(0.25f, glass.opacity, 1e-6f)
        assertTrue(glass.isTransparent)
        assertEquals("textures/glass.png", glass.diffuseMap)
    }

    @Test
    fun clampsOutOfRangeValuesInsteadOfFailing() {
        val material = MtlParser.parse("newmtl m\nNs 4000\nd 1.5").getValue("m")
        assertEquals(1000f, material.shininess)
        assertEquals(1f, material.opacity)
    }

    @Test
    fun siblingPathsResolveNextToTheModel() {
        assertEquals("models/car.mtl", siblingPath("models/car.obj", "car.mtl"))
        assertEquals("car.mtl", siblingPath("car.obj", "car.mtl"))
    }
}
