package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.assets.Image
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.ModelRef
import agency.encrypt.glrenderer.scene.OrbitCamera
import agency.encrypt.glrenderer.scene.Transform
import agency.encrypt.glrenderer.scene.TransformSystem
import kotlin.test.Test
import kotlin.test.assertEquals

/** Records the calls the renderer makes, so draw order and resource reuse can be checked without a GPU. */
private class RecordingGl : Gl {
    val calls = mutableListOf<String>()
    private var nextHandle = 1
    private val uniformNames = mutableMapOf<Int, String>()
    private var boundVertexArray = 0

    override fun clearColor(r: Float, g: Float, b: Float, a: Float) {}
    override fun clear() {}
    override fun viewport(width: Int, height: Int) {}
    override fun enableDepthTest() {}
    override fun setBlending(enabled: Boolean) { calls += "blend $enabled" }
    override fun setDepthWrite(enabled: Boolean) { calls += "depthWrite $enabled" }
    override fun createProgram(vertexSource: String, fragmentSource: String) = nextHandle++
    override fun useProgram(program: Int) {}
    override fun uniformLocation(program: Int, name: String) = nextHandle++.also { uniformNames[it] = name }
    override fun uniformMatrix4(location: Int, columnMajor: FloatArray) {}
    override fun uniformMatrix3(location: Int, columnMajor: FloatArray) {}
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) {}
    override fun uniform1f(location: Int, value: Float) {
        if (uniformNames[location] == "uOpacity") calls += "opacity $value"
    }
    override fun uniform1i(location: Int, value: Int) {
        if (uniformNames[location] == "uHasDiffuseMap") calls += "textured ${value == 1}"
    }
    override fun createVertexArray(data: FloatArray) = nextHandle++.also { calls += "upload ${data.size} floats" }
    override fun bindVertexArray(vertexArray: Int) { boundVertexArray = vertexArray }
    override fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int) {}
    override fun createTexture(image: Image) = nextHandle++.also { calls += "texture ${image.width}x${image.height}" }
    override fun bindTexture(unit: Int, texture: Int) {}
    override fun drawTriangles(firstVertex: Int, vertexCount: Int) { calls += "draw $vertexCount vertices" }
    override fun error() = 0
}

private class MemoryAssets(private val files: Map<String, String>) : AssetSource {
    override fun readText(path: String) = files[path] ?: error("missing $path")
    override fun readImage(path: String) = Image(2, 2, ByteArray(16))
}

class RendererTest {

    private val assets = MemoryAssets(
        mapOf(
            "box.obj" to """
                mtllib box.mtl
                v 0 0 0
                v 1 0 0
                v 1 1 0
                v 0 1 0
                usemtl glass
                f 1 2 3
                usemtl wood
                f 1 2 3 4
            """.trimIndent(),
            "box.mtl" to """
                newmtl wood
                map_Kd wood.png
                newmtl glass
                d 0.5
            """.trimIndent(),
        ),
    )

    private fun scene(vararg positions: Vec3) = World().apply {
        positions.forEach { createEntity().add(Transform(position = it)).add(ModelRef("box.obj")) }
        addSystem(TransformSystem())
        update(0f)
    }

    @Test
    fun drawsEveryVertexOnceWithOpaqueBeforeTransparent() {
        val gl = RecordingGl()
        Renderer(gl, assets).render(scene(Vec3.ZERO), OrbitCamera())

        assertEquals(
            listOf(
                "upload 24 floats", // glass: 1 triangle x 3 vertices x 8 floats
                "texture 2x2",
                "upload 48 floats", // wood: a quad, fan-triangulated into 2 triangles
                "opacity 1.0", "textured true", "draw 6 vertices",
                "blend true", "depthWrite false",
                "opacity 0.5", "textured false", "draw 3 vertices",
                "depthWrite true", "blend false",
            ),
            gl.calls,
        )
    }

    @Test
    fun meshesAndTexturesAreUploadedOnceAndShared() {
        val gl = RecordingGl()
        val renderer = Renderer(gl, assets)
        val world = scene(Vec3.ZERO, Vec3(3f, 0f, 0f))

        repeat(3) { renderer.render(world, OrbitCamera()) }

        assertEquals(2, gl.calls.count { it.startsWith("upload") })
        assertEquals(1, gl.calls.count { it.startsWith("texture ") })
        assertEquals(12, gl.calls.count { it.startsWith("draw") })
    }
}
