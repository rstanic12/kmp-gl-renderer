package agency.encrypt.glrenderer.assets

import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Parses the real files in /assets, so a regenerated asset that the renderer can't read fails the build. */
class DemoAssetsTest {
    private val dir = File("../assets")

    private fun load(name: String): Pair<ObjModel, Map<String, Material>> {
        val obj = ObjParser.parse(File(dir, name).readText())
        val materials = obj.materialLibraries.flatMap { MtlParser.parse(File(dir, it).readText()).values }.associateBy { it.name }
        return obj to materials
    }

    @Test
    fun everyModelResolvesItsMaterialsAndTextures() {
        for (name in listOf("turntable.obj", "teapot.obj", "lid.obj")) {
            val (obj, materials) = load(name)
            assertTrue(obj.subMeshes.isNotEmpty(), name)
            for (mesh in obj.subMeshes) {
                val material = materials[mesh.material]
                assertTrue(material != null, "$name: material ${mesh.material} not found")
                material.diffuseMap?.let { assertTrue(File(dir, it).isFile, "$name: missing texture $it") }
            }
        }
    }

    @Test
    fun normalsAreUnitLength() {
        val mesh = load("teapot.obj").first.subMeshes.single()
        assertEquals(6864 * 3, mesh.vertexCount)
        for (i in 0 until mesh.vertexCount) {
            val o = i * SubMesh.FLOATS_PER_VERTEX + 3
            val length = mesh.vertices[o] * mesh.vertices[o] + mesh.vertices[o + 1] * mesh.vertices[o + 1] + mesh.vertices[o + 2] * mesh.vertices[o + 2]
            assertTrue(abs(length - 1f) < 1e-3f, "normal $i has squared length $length")
        }
    }
}
