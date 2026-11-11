package agency.encrypt.glrenderer.assets

import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.math.assertNear
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

private fun SubMesh.position(vertex: Int) = vertices.vec3(vertex * SubMesh.FLOATS_PER_VERTEX)
private fun SubMesh.normal(vertex: Int) = vertices.vec3(vertex * SubMesh.FLOATS_PER_VERTEX + 3)
private fun SubMesh.uv(vertex: Int) = vertices[vertex * SubMesh.FLOATS_PER_VERTEX + 6] to vertices[vertex * SubMesh.FLOATS_PER_VERTEX + 7]
private fun FloatArray.vec3(i: Int) = Vec3(this[i], this[i + 1], this[i + 2])

class ObjParserTest {

    private val square = """
        v 0 0 0
        v 1 0 0
        v 1 1 0
        v 0 1 0
    """.trimIndent()

    @Test
    fun fullCornersUsePositionUvAndNormal() {
        val model = ObjParser.parse(
            """
            $square
            vt 0.25 0.75
            vn 0 0 2
            f 1/1/1 2/1/1 3/1/1
            """.trimIndent(),
        )
        val mesh = model.subMeshes.single()
        assertEquals(3, mesh.vertexCount)
        assertNear(Vec3(1f, 0f, 0f), mesh.position(1))
        assertNear(Vec3(0f, 0f, 1f), mesh.normal(0))
        assertEquals(0.25f to 0.25f, mesh.uv(2), "v is flipped to 1 - v")
    }

    @Test
    fun polygonsAreFanTriangulated() {
        val mesh = ObjParser.parse("$square\nf 1 2 3 4").subMeshes.single()
        assertEquals(6, mesh.vertexCount)
        assertNear(Vec3(0f, 0f, 0f), mesh.position(3))
        assertNear(Vec3(1f, 1f, 0f), mesh.position(4))
        assertNear(Vec3(0f, 1f, 0f), mesh.position(5))
    }

    @Test
    fun facesWithoutNormalsGetTheFaceNormal() {
        val mesh = ObjParser.parse("$square\nf 1 2 3").subMeshes.single()
        repeat(3) { assertNear(Vec3(0f, 0f, 1f), mesh.normal(it)) }
    }

    @Test
    fun negativeIndicesCountBackFromTheLatestVertex() {
        val mesh = ObjParser.parse("$square\nf -4 -3 -2").subMeshes.single()
        assertNear(Vec3(0f, 0f, 0f), mesh.position(0))
        assertNear(Vec3(1f, 1f, 0f), mesh.position(2))
    }

    @Test
    fun positionNormalFormSkipsTheUv() {
        val mesh = ObjParser.parse("$square\nvn 0 0 -1\nf 1//1 2//1 3//1").subMeshes.single()
        assertNear(Vec3(0f, 0f, -1f), mesh.normal(0))
        assertEquals(0f to 1f, mesh.uv(0))
    }

    @Test
    fun objectsAndMaterialChangesSplitSubMeshes() {
        val model = ObjParser.parse(
            """
            mtllib a.mtl b.mtl
            $square
            o Box
            usemtl red
            f 1 2 3
            usemtl blue
            f 1 3 4
            f 1 2 4
            o Other
            f 2 3 4
            """.trimIndent(),
        )
        assertEquals(listOf("a.mtl", "b.mtl"), model.materialLibraries)
        assertEquals(
            listOf(Triple("Box", "red", 3), Triple("Box", "blue", 6), Triple("Other", "blue", 3)),
            model.subMeshes.map { Triple(it.name, it.material, it.vertexCount) },
        )
    }

    @Test
    fun toleratesCommentsAndIrregularWhitespace() {
        val model = ObjParser.parse("# header\nv  0 0 0\nv\t1 0 0 # trailing\nv 0 1 0\n\n f 1 2 3 \r\n")
        val mesh = model.subMeshes.single()
        assertEquals(3, mesh.vertexCount)
        assertNull(mesh.material)
    }

    @Test
    fun outOfRangeIndicesReportTheLine() {
        val error = assertFailsWith<ObjFormatException> { ObjParser.parse("$square\nf 1 2 9") }
        assertEquals("OBJ line 5: index 9 out of range in '9'", error.message)
    }
}
