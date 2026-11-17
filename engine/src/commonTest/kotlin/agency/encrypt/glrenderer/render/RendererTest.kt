package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.assets.Image
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.gl.Blend
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.gl.TextureFormat
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.ModelRef
import agency.encrypt.glrenderer.scene.OrbitCamera
import agency.encrypt.glrenderer.scene.PointLight
import agency.encrypt.glrenderer.scene.Transform
import agency.encrypt.glrenderer.scene.TransformSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Records what the renderer asks of GL, so passes, draw order, instancing and resource reuse can be checked
 * without a GPU. Programs are named after their fragment shader, draws after the program in use.
 */
private class RecordingGl(private val halfFloatRenderable: Boolean = true) : Gl {
    val calls = mutableListOf<String>()
    val textureFormats = mutableMapOf<Int, TextureFormat>()
    private var nextHandle = 1
    private val programNames = mutableMapOf<Int, String>()
    private val uniformNames = mutableMapOf<Int, String>()
    private var program = ""

    private val fragmentNames = mapOf(
        Shaders.SHADOW_FRAGMENT to "shadow",
        Shaders.GBUFFER_FRAGMENT to "gbuffer",
        Shaders.FORWARD_FRAGMENT to "forward",
        Shaders.DEFERRED_SCENE_FRAGMENT to "sceneLight",
        Shaders.DEFERRED_POINT_FRAGMENT to "pointLight",
        Shaders.BLOOM_DOWN_FRAGMENT to "bloomDown",
        Shaders.BLOOM_UP_FRAGMENT to "bloomUp",
        Shaders.COMPOSITE_FRAGMENT to "composite",
        Shaders.FXAA_FRAGMENT to "fxaa",
    )

    fun draws() = calls.filter { it.startsWith("draw ") }

    override fun viewport(x: Int, y: Int, width: Int, height: Int) {}
    override fun scissor(x: Int, y: Int, width: Int, height: Int) { calls += "scissor" }
    override fun disableScissor() {}
    override fun clearColor(r: Float, g: Float, b: Float, a: Float) {}
    override fun clear(color: Boolean, depth: Boolean) {}
    override fun setDepthTest(enabled: Boolean) {}
    override fun setDepthWrite(enabled: Boolean) { calls += "depthWrite $enabled" }
    override fun setBlend(blend: Blend) { calls += "blend $blend" }
    override fun setPolygonOffset(factor: Float, units: Float) {}
    override fun createProgram(vertexSource: String, fragmentSource: String) =
        nextHandle++.also { programNames[it] = fragmentNames.getValue(fragmentSource) }
    override fun useProgram(program: Int) { this.program = programNames.getValue(program) }
    override fun uniformLocation(program: Int, name: String) = nextHandle++.also { uniformNames[it] = name }
    override fun uniformMatrix4(location: Int, columnMajor: FloatArray) {}
    override fun uniform3f(location: Int, x: Float, y: Float, z: Float) {}
    override fun uniform2f(location: Int, x: Float, y: Float) {}
    override fun uniform1f(location: Int, value: Float) {
        if (uniformNames[location] == "uOpacity") calls += "opacity $value"
    }
    override fun uniform1i(location: Int, value: Int) {}
    override fun createVertexArray() = nextHandle++
    override fun bindVertexArray(vertexArray: Int) {}
    override fun createBuffer() = nextHandle++
    override fun bufferData(buffer: Int, data: FloatArray, floatCount: Int, dynamic: Boolean) {
        if (!dynamic) calls += "upload $floatCount floats"
    }
    override fun vertexAttribute(index: Int, size: Int, strideBytes: Int, offsetBytes: Int, divisor: Int) {}
    override fun drawTriangles(vertexCount: Int, instanceCount: Int) { calls += "draw $program $vertexCount x$instanceCount" }
    override fun createTexture(image: Image) = nextHandle++.also { calls += "texture ${image.width}x${image.height}" }
    override fun createRenderTexture(width: Int, height: Int, format: TextureFormat) = nextHandle++.also { textureFormats[it] = format }
    override fun createDataTexture() = nextHandle++
    override fun uploadDataTexture(texture: Int, width: Int, height: Int, rgba: FloatArray) {}
    override fun bindTexture(unit: Int, texture: Int) {}
    override fun deleteTexture(texture: Int) {}
    override fun createFramebuffer(colors: List<Int>, depth: Int?): Int? =
        if (!halfFloatRenderable && colors.any { textureFormats[it] == TextureFormat.RGBA16F }) null else nextHandle++
    override fun bindFramebuffer(framebuffer: Int) {}
    override fun deleteFramebuffer(framebuffer: Int) {}
    override fun boundFramebuffer() = 0
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

    private fun renderer(gl: Gl) = Renderer(gl, assets).apply {
        resize(64, 64)
        bloom = false
        fxaa = false
    }

    @Test
    fun deferredFrameRunsShadowGeometryLightingTransparentAndPost() {
        val gl = RecordingGl()
        renderer(gl).render(scene(Vec3.ZERO), OrbitCamera())

        assertEquals(
            listOf(
                "draw shadow 6 x1", // wood only: transparent glass casts no shadow
                "draw gbuffer 6 x1",
                "draw sceneLight 3 x1",
                "draw forward 3 x1", // glass, forward-shaded on top
                "draw composite 3 x1",
            ),
            gl.draws(),
        )
    }

    @Test
    fun forwardPlusShadesOpaqueAndTransparentInTheSamePass() {
        val gl = RecordingGl()
        renderer(gl).apply { pipeline = Pipeline.FORWARD_PLUS }.render(scene(Vec3.ZERO), OrbitCamera())

        assertEquals(
            listOf("draw shadow 6 x1", "draw forward 6 x1", "draw forward 3 x1", "draw composite 3 x1"),
            gl.draws(),
        )
    }

    @Test
    fun transparentSurfacesBlendAfterOpaqueWithoutWritingDepth() {
        val gl = RecordingGl()
        renderer(gl).apply { pipeline = Pipeline.FORWARD_PLUS }.render(scene(Vec3.ZERO), OrbitCamera())

        val forward = gl.calls.dropWhile { it != "draw forward 6 x1" }
        assertEquals(
            listOf("draw forward 6 x1", "blend ALPHA", "depthWrite false", "opacity 0.5", "draw forward 3 x1", "depthWrite true", "blend NONE"),
            forward.filterNot { it.startsWith("opacity 1") }.take(7),
        )
    }

    @Test
    fun repeatedMeshesAreInstancedAndUploadedOnce() {
        val gl = RecordingGl()
        val renderer = renderer(gl)
        val world = scene(Vec3.ZERO, Vec3(3f, 0f, 0f), Vec3(-3f, 0f, 0f))

        repeat(3) { renderer.render(world, OrbitCamera()) }

        assertEquals(2, gl.calls.count { it.startsWith("upload") })
        assertEquals(1, gl.calls.count { it.startsWith("texture ") })
        // The opaque part is one instanced draw per pass; transparent instances are drawn one by one, sorted.
        assertEquals(3, gl.draws().count { it == "draw gbuffer 6 x3" })
        assertEquals(9, gl.draws().count { it == "draw forward 3 x1" })
        // Per frame: shadow 1 + G-buffer 1 + transparent 3.
        assertEquals(5, renderer.meshDrawCalls)
    }

    @Test
    fun deferredDrawsOneScissoredPassPerVisibleLight() {
        val gl = RecordingGl()
        val renderer = renderer(gl)
        val world = scene(Vec3.ZERO).apply {
            createEntity().add(Transform(position = Vec3(0f, 1f, 0f))).add(PointLight(Vec3.ONE, 1f))
            createEntity().add(Transform(position = Vec3(1f, 0f, 0f))).add(PointLight(Vec3.ONE, 1f))
            // Behind the default camera, which looks at the origin from +z.
            createEntity().add(Transform(position = Vec3(0f, 0f, 40f))).add(PointLight(Vec3.ONE, 1f))
            update(0f)
        }

        renderer.render(world, OrbitCamera())

        assertEquals(2, renderer.visibleLights)
        assertEquals(2, gl.draws().count { it == "draw pointLight 3 x1" })
        assertEquals(2, gl.calls.count { it == "scissor" })
    }

    @Test
    fun shadowedPointLightsRenderSixFacesOfOnlyTheCastersTheyReach() {
        val gl = RecordingGl()
        val renderer = renderer(gl)
        val world = scene(Vec3.ZERO, Vec3(20f, 0f, 0f)).apply {
            createEntity().add(Transform(position = Vec3(0.5f, 2f, 0f))).add(PointLight(Vec3.ONE, 3f, castsShadows = true))
            createEntity().add(Transform(position = Vec3(-0.5f, 2f, 0f))).add(PointLight(Vec3.ONE, 3f))
            update(0f)
        }

        renderer.render(world, OrbitCamera())

        assertEquals(1, renderer.shadowedLights)
        // Six cube faces with the one nearby box (the far one is out of reach), then the key light's map with both.
        assertEquals(List(6) { "draw shadow 6 x1" } + "draw shadow 6 x2", gl.draws().filter { it.startsWith("draw shadow") })
    }

    @Test
    fun theShadowedLightBudgetIsCapped() {
        val gl = RecordingGl()
        val renderer = renderer(gl)
        val world = scene(Vec3.ZERO).apply {
            repeat(Shaders.POINT_SHADOW_SLOTS + 3) {
                createEntity().add(Transform(position = Vec3(it * 0.1f, 1f, 0f))).add(PointLight(Vec3.ONE, 3f, castsShadows = true))
            }
            update(0f)
        }

        renderer.render(world, OrbitCamera())

        assertEquals(Shaders.POINT_SHADOW_SLOTS, renderer.shadowedLights)
        renderer.pointShadows = false
        renderer.render(world, OrbitCamera())
        assertEquals(0, renderer.shadowedLights)
    }

    @Test
    fun postProcessingRunsBloomChainThenTonemapThenFxaa() {
        val gl = RecordingGl()
        Renderer(gl, assets).apply { resize(64, 64) }.render(scene(Vec3.ZERO), OrbitCamera())

        val post = gl.draws().dropWhile { !it.startsWith("draw bloom") }.map { it.substringAfter("draw ").substringBefore(' ') }
        assertEquals(List(5) { "bloomDown" } + List(4) { "bloomUp" } + listOf("composite", "fxaa"), post)
    }

    @Test
    fun fallsBackToEightBitTargetsWithoutHalfFloatRendering() {
        val gl = RecordingGl(halfFloatRenderable = false)
        val renderer = renderer(gl)
        renderer.render(scene(Vec3.ZERO), OrbitCamera())

        assertFalse(renderer.hdr)
        // Only the start-up probe asked for half-float; every screen target fell back to RGBA8.
        assertEquals(1, gl.textureFormats.values.count { it == TextureFormat.RGBA16F })
        assertEquals(1, gl.draws().count { it.startsWith("draw composite") })
    }
}
