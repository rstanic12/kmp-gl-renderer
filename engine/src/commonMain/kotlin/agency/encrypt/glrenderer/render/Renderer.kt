package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.assets.Material
import agency.encrypt.glrenderer.assets.MtlParser
import agency.encrypt.glrenderer.assets.ObjParser
import agency.encrypt.glrenderer.assets.SubMesh
import agency.encrypt.glrenderer.assets.siblingPath
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.gl.Blend
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.gl.TextureFormat
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.ModelRef
import agency.encrypt.glrenderer.scene.OrbitCamera
import agency.encrypt.glrenderer.scene.PointLight
import agency.encrypt.glrenderer.scene.Tint
import agency.encrypt.glrenderer.scene.WorldTransform
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.pow
import kotlin.math.sqrt

enum class Pipeline(val label: String) {
    /** G-buffer, then lighting per light over the pixels it can reach. */
    DEFERRED("Deferred"),

    /** One shading pass per surface, looping over its cluster's light list. */
    FORWARD_PLUS("Forward+"),
}

/**
 * Draws every entity that has a [ModelRef] and a [WorldTransform], lit by every [PointLight], a directional key light
 * with a shadow map and a sky/ground ambient term. Shading is metal/roughness PBR into an HDR target, followed by
 * bloom, ACES tonemapping and FXAA.
 *
 * Frame: point light cube shadows → key light shadow → (deferred: G-buffer + lighting passes | forward+: one clustered
 * forward pass) → transparent surfaces, forward-shaded back to front → bloom → tonemap → FXAA.
 * Meshes drawn more than once are instanced.
 *
 * Must be created and used on the thread that owns the GL context. GPU resources are created lazily the
 * first time a model is drawn and live as long as the context; after a context loss, create a new Renderer.
 */
class Renderer(private val gl: Gl, private val assets: AssetSource) {

    var pipeline = Pipeline.DEFERRED
    var shadows = true

    /** Cube shadows for point lights with [PointLight.castsShadows], up to [Shaders.POINT_SHADOW_SLOTS] of them. */
    var pointShadows = true
    var bloom = true
    var fxaa = true

    var exposure = 1f
    var bloomStrength = 0.05f
    var bloomThreshold = 1f

    /** Direction towards the key light. */
    var lightDirection = Vec3(0.4f, 1f, 0.6f).normalized()
    var lightColor = Vec3(3f, 2.85f, 2.6f)
    var skyColor = Vec3(0.20f, 0.23f, 0.30f)
    var groundColor = Vec3(0.07f, 0.06f, 0.06f)
    var background = Vec3(0.012f, 0.014f, 0.02f)

    /** The sphere the key light's shadow map covers; keep it tight around what should cast shadows. */
    var shadowCenter = Vec3.ZERO
    var shadowRadius = 3f

    /** False when the device cannot render to half-float textures: lighting then clips at 1 before tonemapping. */
    val hdr: Boolean

    /** Mesh draw calls in the last frame, all passes; instancing keeps this independent of how often a mesh repeats. */
    var meshDrawCalls = 0
        private set

    /** Point lights that reached the screen in the last frame. */
    var visibleLights = 0
        private set

    /** Point lights that rendered a cube shadow map in the last frame. */
    var shadowedLights = 0
        private set

    private class GpuMaterial(
        val baseColor: Vec3,
        val metallic: Float,
        val roughness: Float,
        val emissive: Vec3,
        val opacity: Float,
        val texture: Int?,
    ) {
        val isTransparent get() = opacity < 1f
        val isEmissive get() = emissive != Vec3.ZERO
    }

    /** One material's triangles of a model; [boundsCenter] and [boundsRadius] bound them in model space. */
    private class GpuPart(
        val vertexArray: Int,
        val vertexCount: Int,
        val instanceBuffer: Int,
        val material: GpuMaterial,
        val boundsCenter: Vec3,
        val boundsRadius: Float,
    )

    private class Instance(val model: Mat4, val tint: Vec3)
    private class TransparentDraw(val part: GpuPart, val instance: Instance, val distance: Float)
    private class VisibleLight(val position: Vec3, val radius: Float, val color: Vec3, val rect: PixelRect, val shadowSlot: Int)

    private val shadowProgram = Program(gl, Shaders.GEOMETRY_VERTEX, Shaders.SHADOW_FRAGMENT)
    private val geometryProgram = Program(gl, Shaders.GEOMETRY_VERTEX, Shaders.GBUFFER_FRAGMENT)
    private val forwardProgram = Program(gl, Shaders.GEOMETRY_VERTEX, Shaders.FORWARD_FRAGMENT)
    private val sceneLightProgram = Program(gl, Shaders.FULLSCREEN_VERTEX, Shaders.DEFERRED_SCENE_FRAGMENT)
    private val pointLightProgram = Program(gl, Shaders.FULLSCREEN_VERTEX, Shaders.DEFERRED_POINT_FRAGMENT)
    private val bloomDownProgram = Program(gl, Shaders.FULLSCREEN_VERTEX, Shaders.BLOOM_DOWN_FRAGMENT)
    private val bloomUpProgram = Program(gl, Shaders.FULLSCREEN_VERTEX, Shaders.BLOOM_UP_FRAGMENT)
    private val compositeProgram = Program(gl, Shaders.FULLSCREEN_VERTEX, Shaders.COMPOSITE_FRAGMENT)
    private val fxaaProgram = Program(gl, Shaders.FULLSCREEN_VERTEX, Shaders.FXAA_FRAGMENT)

    private val emptyVertexArray = gl.createVertexArray()
    private val shadowMap = gl.createRenderTexture(SHADOW_MAP_SIZE, SHADOW_MAP_SIZE, TextureFormat.SHADOW_DEPTH24)
    private val shadowFramebuffer = gl.createFramebuffer(emptyList(), shadowMap) ?: error("The shadow framebuffer is incomplete")
    private val pointShadowAtlas = gl.createRenderTexture(
        6 * Shaders.POINT_SHADOW_FACE_SIZE, Shaders.POINT_SHADOW_SLOTS * Shaders.POINT_SHADOW_FACE_SIZE, TextureFormat.SHADOW_DEPTH24,
    )
    private val pointShadowFramebuffer =
        gl.createFramebuffer(emptyList(), pointShadowAtlas) ?: error("The point shadow framebuffer is incomplete")
    private val lightTexture = gl.createDataTexture()
    private val clusterTexture = gl.createDataTexture()
    private val lightIndexTexture = gl.createDataTexture()
    private val lightData = FloatArray(Shaders.MAX_LIGHTS * 2 * 4)
    private val lights = mutableListOf<VisibleLight>()

    private val models = HashMap<String, List<GpuPart>>()
    private val textures = HashMap<String, Int>()
    private val opaque = LinkedHashMap<GpuPart, MutableList<Instance>>()
    private val transparent = mutableListOf<TransparentDraw>()
    private var instanceData = FloatArray(FLOATS_PER_INSTANCE * 16)

    private var width = 1
    private var height = 1
    private var targets: RenderTargets? = null
    private var clusters = ClusterGrid(1, 1)

    init {
        hdr = canRenderTo(TextureFormat.RGBA16F)
        geometryProgram.samplers("uBaseColorMap" to UNIT_BASE_COLOR)
        forwardProgram.samplers(
            "uBaseColorMap" to UNIT_BASE_COLOR, "uShadowMap" to UNIT_SHADOW_MAP, "uPointShadowAtlas" to UNIT_POINT_SHADOWS,
            "uLights" to UNIT_LIGHTS, "uClusters" to UNIT_CLUSTERS, "uLightIndices" to UNIT_LIGHT_INDICES,
        )
        sceneLightProgram.samplers(
            "uShadowMap" to UNIT_SHADOW_MAP, "uAlbedoMetallic" to UNIT_ALBEDO,
            "uNormalRoughness" to UNIT_NORMAL, "uDepth" to UNIT_DEPTH,
        )
        pointLightProgram.samplers(
            "uAlbedoMetallic" to UNIT_ALBEDO, "uNormalRoughness" to UNIT_NORMAL, "uDepth" to UNIT_DEPTH,
            "uPointShadowAtlas" to UNIT_POINT_SHADOWS,
        )
        bloomDownProgram.samplers("uSource" to UNIT_POST_SOURCE)
        bloomUpProgram.samplers("uSource" to UNIT_POST_SOURCE)
        compositeProgram.samplers("uHdr" to UNIT_POST_SOURCE, "uBloom" to UNIT_POST_BLOOM)
        fxaaProgram.samplers("uSource" to UNIT_POST_SOURCE)
    }

    fun resize(width: Int, height: Int) {
        this.width = maxOf(width, 1)
        this.height = maxOf(height, 1)
    }

    fun render(world: World, camera: OrbitCamera) {
        val screen = gl.boundFramebuffer()
        val targets = targetsForSize()
        val view = camera.view()
        val projection = camera.projection(width.toFloat() / height)
        val viewProjection = projection * view
        val eye = camera.eye()
        meshDrawCalls = 0

        collectSurfaces(world, eye)
        collectLights(world, view, projection)
        val shadowMatrix = BIAS * lightViewProjection()

        gl.disableScissor()
        gl.setBlend(Blend.NONE)
        gl.setDepthTest(true)
        gl.setDepthWrite(true)
        gl.bindTexture(UNIT_SHADOW_MAP, shadowMap)
        gl.bindTexture(UNIT_POINT_SHADOWS, pointShadowAtlas)
        if (shadowedLights > 0) pointShadowPass()

        // Uploaded once, then drawn by the key light's shadow pass and the main pass.
        for ((part, instances) in opaque) if (instances.isNotEmpty()) uploadInstances(part, instances)
        if (shadows) shadowPass()

        when (pipeline) {
            Pipeline.DEFERRED -> {
                geometryPass(targets, viewProjection)
                deferredLighting(targets, viewProjection, eye, shadowMatrix)
            }
            Pipeline.FORWARD_PLUS -> {
                gl.bindFramebuffer(targets.forwardFramebuffer)
                gl.viewport(0, 0, width, height)
                gl.clearColor(background.x, background.y, background.z, 1f)
                gl.clear(color = true, depth = true)
                useForward(viewProjection, camera, shadowMatrix)
                drawOpaque(forwardProgram)
            }
        }
        transparentPass(targets, viewProjection, camera, shadowMatrix)
        postProcess(targets, screen)

        val error = gl.error()
        check(error == 0) { "GL error 0x${error.toString(16)} while rendering" }
    }

    private fun targetsForSize(): RenderTargets {
        targets?.let { if (it.width == width && it.height == height) return it }
        targets?.delete()
        clusters = ClusterGrid(width, height)
        return RenderTargets(gl, width, height, if (hdr) TextureFormat.RGBA16F else TextureFormat.RGBA8).also { targets = it }
    }

    private fun collectSurfaces(world: World, eye: Vec3) {
        opaque.values.forEach { it.clear() }
        transparent.clear()
        world.each<ModelRef, WorldTransform> { entity, ref, transform ->
            val instance = Instance(transform.matrix, entity.get<Tint>()?.color ?: Vec3.ONE)
            for (part in model(ref.path)) {
                if (part.material.isTransparent) {
                    transparent += TransparentDraw(part, instance, (transform.matrix.translation() - eye).length())
                } else {
                    opaque.getOrPut(part) { mutableListOf() } += instance
                }
            }
        }
    }

    /**
     * Culls point lights to the screen, hands out shadow atlas slots in entity order, fills the light texture
     * (row 0: position + radius, row 1: colour + shadow slot) and builds the forward+ clusters.
     */
    private fun collectLights(world: World, view: Mat4, projection: Mat4) {
        lights.clear()
        clusters.clear()
        var slots = 0
        world.each<PointLight, WorldTransform> { _, light, transform ->
            if (lights.size == Shaders.MAX_LIGHTS) return@each
            val position = transform.matrix.translation()
            val viewCenter = view.transformPoint(position)
            val rect = LightCulling.screenRect(viewCenter, light.radius, projection, OrbitCamera.NEAR, width, height) ?: return@each
            val slot = if (pointShadows && light.castsShadows && slots < Shaders.POINT_SHADOW_SLOTS) slots++ else -1
            val index = lights.size
            lights += VisibleLight(position, light.radius, light.color, rect, slot)
            clusters.add(index, rect, -viewCenter.z - light.radius, -viewCenter.z + light.radius)

            val row0 = index * 4
            val row1 = (Shaders.MAX_LIGHTS + index) * 4
            lightData[row0] = position.x; lightData[row0 + 1] = position.y; lightData[row0 + 2] = position.z
            lightData[row0 + 3] = light.radius
            lightData[row1] = light.color.x; lightData[row1 + 1] = light.color.y; lightData[row1 + 2] = light.color.z
            lightData[row1 + 3] = slot.toFloat()
        }
        visibleLights = lights.size
        shadowedLights = slots
        clusters.build()
        gl.uploadDataTexture(lightTexture, Shaders.MAX_LIGHTS, 2, lightData)
        gl.uploadDataTexture(clusterTexture, clusters.clusterTextureWidth, clusters.clusterTextureHeight, clusters.clusterData)
        gl.uploadDataTexture(lightIndexTexture, ClusterGrid.INDEX_TEXTURE_WIDTH, clusters.indexTextureHeight, clusters.indexData)
    }

    /**
     * Six faces per shadowed light, each a 90°-plus perspective view into its tile of the atlas. Only instances whose
     * bounding sphere reaches into the light's sphere are drawn, so a light far from everything costs nearly nothing.
     */
    private fun pointShadowPass() {
        val size = Shaders.POINT_SHADOW_FACE_SIZE
        gl.bindFramebuffer(pointShadowFramebuffer)
        gl.viewport(0, 0, 6 * size, Shaders.POINT_SHADOW_SLOTS * size)
        gl.clear(color = false, depth = true)
        gl.setPolygonOffset(2f, 4f)
        shadowProgram.use()
        val fovY = (2.0 * atan(Shaders.POINT_SHADOW_FOV_SCALE.toDouble()) * 180.0 / PI).toFloat()
        for (light in lights) {
            if (light.shadowSlot < 0) continue
            val casters = opaque.entries.mapNotNull { (part, instances) ->
                if (part.material.isEmissive) return@mapNotNull null
                val reaching = instances.filter { reaches(part, it, light) }
                if (reaching.isEmpty()) null else part to reaching
            }
            if (casters.isEmpty()) continue
            casters.forEach { (part, instances) -> uploadInstances(part, instances) }
            val projection = Mat4.perspective(fovY, 1f, Shaders.POINT_SHADOW_NEAR, light.radius)
            for (face in 0 until 6) {
                val forward = Shaders.CUBE_FORWARD[face].let { Vec3(it[0], it[1], it[2]) }
                val up = Shaders.CUBE_UP[face].let { Vec3(it[0], it[1], it[2]) }
                gl.viewport(face * size, light.shadowSlot * size, size, size)
                shadowProgram["uViewProjection"] = projection * Mat4.lookAt(light.position, light.position + forward, up)
                casters.forEach { (part, instances) -> drawPart(null, part, instances.size) }
            }
        }
        gl.setPolygonOffset(0f, 0f)
    }

    private fun reaches(part: GpuPart, instance: Instance, light: VisibleLight): Boolean {
        val center = instance.model.transformPoint(part.boundsCenter)
        val radius = part.boundsRadius * instance.model.maxScale()
        return (center - light.position).length() < radius + light.radius
    }

    /** An orthographic view down the key light that just contains the shadow sphere. */
    private fun lightViewProjection(): Mat4 {
        val r = shadowRadius
        val up = if (abs(lightDirection.y) > 0.99f) Vec3(0f, 0f, 1f) else Vec3.UP
        val view = Mat4.lookAt(shadowCenter + lightDirection * (2f * r), shadowCenter, up)
        return Mat4.orthographic(-r, r, -r, r, r * 0.5f, r * 3.5f) * view
    }

    private fun shadowPass() {
        gl.bindFramebuffer(shadowFramebuffer)
        gl.viewport(0, 0, SHADOW_MAP_SIZE, SHADOW_MAP_SIZE)
        gl.clear(color = false, depth = true)
        gl.setPolygonOffset(2f, 4f)
        shadowProgram.use()
        shadowProgram["uViewProjection"] = lightViewProjection()
        drawOpaque(null)
        gl.setPolygonOffset(0f, 0f)
    }

    private fun geometryPass(targets: RenderTargets, viewProjection: Mat4) {
        gl.bindFramebuffer(targets.geometryFramebuffer)
        gl.viewport(0, 0, width, height)
        gl.clearColor(0f, 0f, 0f, 0f)
        gl.clear(color = true, depth = true)
        geometryProgram.use()
        geometryProgram["uViewProjection"] = viewProjection
        drawOpaque(geometryProgram)
    }

    private fun deferredLighting(targets: RenderTargets, viewProjection: Mat4, eye: Vec3, shadowMatrix: Mat4) {
        gl.bindFramebuffer(targets.lightingFramebuffer)
        gl.setDepthTest(false)
        gl.setDepthWrite(false)
        gl.setBlend(Blend.ADDITIVE)
        gl.bindVertexArray(emptyVertexArray)
        gl.bindTexture(UNIT_ALBEDO, targets.albedoMetallic)
        gl.bindTexture(UNIT_NORMAL, targets.normalRoughness)
        gl.bindTexture(UNIT_DEPTH, targets.depth)
        val inverseViewProjection = viewProjection.inverse()

        sceneLightProgram.use()
        sceneLightProgram["uInverseViewProjection"] = inverseViewProjection
        sceneLightProgram["uBackground"] = background
        setSceneLighting(sceneLightProgram, eye, shadowMatrix)
        gl.drawTriangles(3, 1)

        pointLightProgram.use()
        pointLightProgram["uInverseViewProjection"] = inverseViewProjection
        pointLightProgram["uCameraPosition"] = eye
        for (light in lights) {
            gl.scissor(light.rect.x, light.rect.y, light.rect.width, light.rect.height)
            pointLightProgram["uPointLightPosition"] = light.position
            pointLightProgram["uPointLightRadius"] = light.radius
            pointLightProgram["uPointLightColor"] = light.color
            pointLightProgram["uPointShadowSlot"] = light.shadowSlot
            gl.drawTriangles(3, 1)
        }
        gl.disableScissor()
        gl.setBlend(Blend.NONE)
        gl.setDepthTest(true)
        gl.setDepthWrite(true)
    }

    /** Back to front, blended, depth-tested against the opaque scene but not writing depth. */
    private fun transparentPass(targets: RenderTargets, viewProjection: Mat4, camera: OrbitCamera, shadowMatrix: Mat4) {
        if (transparent.isEmpty()) return
        gl.bindFramebuffer(targets.forwardFramebuffer)
        gl.viewport(0, 0, width, height)
        gl.setBlend(Blend.ALPHA)
        gl.setDepthWrite(false)
        useForward(viewProjection, camera, shadowMatrix)
        for (draw in transparent.sortedByDescending { it.distance }) {
            uploadInstances(draw.part, listOf(draw.instance))
            drawPart(forwardProgram, draw.part, 1)
        }
        gl.setDepthWrite(true)
        gl.setBlend(Blend.NONE)
    }

    private fun useForward(viewProjection: Mat4, camera: OrbitCamera, shadowMatrix: Mat4) {
        gl.bindTexture(UNIT_LIGHTS, lightTexture)
        gl.bindTexture(UNIT_CLUSTERS, clusterTexture)
        gl.bindTexture(UNIT_LIGHT_INDICES, lightIndexTexture)
        forwardProgram.use()
        forwardProgram["uViewProjection"] = viewProjection
        forwardProgram["uCameraForward"] = camera.forward()
        forwardProgram["uClusterTilesX"] = clusters.tilesX
        setSceneLighting(forwardProgram, camera.eye(), shadowMatrix)
    }

    private fun setSceneLighting(program: Program, eye: Vec3, shadowMatrix: Mat4) {
        program["uCameraPosition"] = eye
        program["uLightDirection"] = lightDirection.normalized()
        program["uLightColor"] = lightColor
        program["uSkyColor"] = skyColor
        program["uGroundColor"] = groundColor
        program["uShadows"] = shadows
        program["uShadowMatrix"] = shadowMatrix
        // About one and a half shadow-map texels, in world units: enough to stop self-shadowing acne.
        program["uShadowNormalOffset"] = 3f * shadowRadius / SHADOW_MAP_SIZE
    }

    private fun postProcess(targets: RenderTargets, screen: Int) {
        gl.setDepthTest(false)
        gl.setDepthWrite(false)
        gl.bindVertexArray(emptyVertexArray)

        if (bloom) {
            bloomDownProgram.use()
            bloomDownProgram["uThreshold"] = bloomThreshold
            var source = targets.hdr
            var sourceWidth = width
            var sourceHeight = height
            targets.bloom.forEachIndexed { i, level ->
                gl.bindFramebuffer(level.framebuffer)
                gl.viewport(0, 0, level.width, level.height)
                gl.bindTexture(UNIT_POST_SOURCE, source)
                bloomDownProgram.setVec2("uTexel", 1f / sourceWidth, 1f / sourceHeight)
                bloomDownProgram["uPrefilter"] = i == 0
                gl.drawTriangles(3, 1)
                source = level.texture
                sourceWidth = level.width
                sourceHeight = level.height
            }
            bloomUpProgram.use()
            gl.setBlend(Blend.ADDITIVE)
            for (i in targets.bloom.lastIndex downTo 1) {
                val from = targets.bloom[i]
                val to = targets.bloom[i - 1]
                gl.bindFramebuffer(to.framebuffer)
                gl.viewport(0, 0, to.width, to.height)
                gl.bindTexture(UNIT_POST_SOURCE, from.texture)
                bloomUpProgram.setVec2("uTexel", 1f / from.width, 1f / from.height)
                gl.drawTriangles(3, 1)
            }
            gl.setBlend(Blend.NONE)
        }

        gl.bindFramebuffer(if (fxaa) targets.displayFramebuffer else screen)
        gl.viewport(0, 0, width, height)
        gl.bindTexture(UNIT_POST_SOURCE, targets.hdr)
        gl.bindTexture(UNIT_POST_BLOOM, targets.bloom.first().texture)
        compositeProgram.use()
        compositeProgram["uBloomEnabled"] = bloom
        compositeProgram["uBloomStrength"] = bloomStrength
        compositeProgram["uExposure"] = exposure
        gl.drawTriangles(3, 1)

        if (fxaa) {
            gl.bindFramebuffer(screen)
            gl.bindTexture(UNIT_POST_SOURCE, targets.display)
            fxaaProgram.use()
            fxaaProgram.setVec2("uTexel", 1f / width, 1f / height)
            gl.drawTriangles(3, 1)
        }
        gl.setDepthTest(true)
        gl.setDepthWrite(true)
    }

    /**
     * Draws every opaque batch. With a null program only geometry is needed: that is the key light's shadow pass,
     * which skips emissive surfaces, since a glowing bulb casting a dark shadow looks wrong.
     */
    private fun drawOpaque(program: Program?) {
        for ((part, instances) in opaque) {
            if (instances.isEmpty() || (program == null && part.material.isEmissive)) continue
            drawPart(program, part, instances.size)
        }
    }

    private fun drawPart(program: Program?, part: GpuPart, instanceCount: Int) {
        if (program != null) {
            val m = part.material
            program["uBaseColor"] = m.baseColor
            program["uMetallic"] = m.metallic
            program["uRoughness"] = m.roughness
            program["uEmissive"] = m.emissive
            program["uOpacity"] = m.opacity
            program["uHasBaseColorMap"] = m.texture != null
            m.texture?.let { gl.bindTexture(UNIT_BASE_COLOR, it) }
        }
        gl.bindVertexArray(part.vertexArray)
        gl.drawTriangles(part.vertexCount, instanceCount)
        meshDrawCalls++
    }

    private fun uploadInstances(part: GpuPart, instances: List<Instance>) {
        val needed = instances.size * FLOATS_PER_INSTANCE
        if (instanceData.size < needed) instanceData = FloatArray(needed * 2)
        instances.forEachIndexed { i, instance ->
            val o = i * FLOATS_PER_INSTANCE
            instance.model.copyInto(instanceData, o)
            instance.model.normalMatrix().copyInto(instanceData, o + 16)
            instanceData[o + 25] = instance.tint.x
            instanceData[o + 26] = instance.tint.y
            instanceData[o + 27] = instance.tint.z
            instanceData[o + 28] = 1f
        }
        gl.bufferData(part.instanceBuffer, instanceData, needed, dynamic = true)
    }

    private fun model(path: String): List<GpuPart> = models.getOrPut(path) {
        val obj = ObjParser.parse(assets.readText(path))
        val materials = obj.materialLibraries
            .flatMap { MtlParser.parse(assets.readText(siblingPath(path, it))).values }
            .associateBy { it.name }
        obj.subMeshes.map { mesh ->
            val material = mesh.material?.let { materials[it] } ?: Material.DEFAULT
            upload(mesh, gpuMaterial(path, material))
        }
    }

    private fun gpuMaterial(modelPath: String, material: Material) = GpuMaterial(
        baseColor = material.baseColor.let { Vec3(toLinear(it.x), toLinear(it.y), toLinear(it.z)) },
        metallic = material.metallic,
        roughness = material.roughness,
        emissive = material.emissive,
        opacity = material.opacity,
        texture = material.baseColorMap?.let { texture(siblingPath(modelPath, it)) },
    )

    private fun upload(mesh: SubMesh, material: GpuMaterial): GpuPart {
        val vertexArray = gl.createVertexArray()
        gl.bindVertexArray(vertexArray)
        gl.bufferData(gl.createBuffer(), mesh.vertices, mesh.vertices.size, dynamic = false)
        val stride = SubMesh.FLOATS_PER_VERTEX * FLOAT_BYTES
        gl.vertexAttribute(Shaders.POSITION, 3, stride, 0, divisor = 0)
        gl.vertexAttribute(Shaders.NORMAL, 3, stride, 3 * FLOAT_BYTES, divisor = 0)
        gl.vertexAttribute(Shaders.UV, 2, stride, 6 * FLOAT_BYTES, divisor = 0)

        val instanceBuffer = gl.createBuffer()
        gl.bufferData(instanceBuffer, FloatArray(FLOATS_PER_INSTANCE), FLOATS_PER_INSTANCE, dynamic = true)
        val instanceStride = FLOATS_PER_INSTANCE * FLOAT_BYTES
        for (column in 0 until 4) {
            gl.vertexAttribute(Shaders.MODEL + column, 4, instanceStride, column * 4 * FLOAT_BYTES, divisor = 1)
        }
        for (column in 0 until 3) {
            gl.vertexAttribute(Shaders.NORMAL_MATRIX + column, 3, instanceStride, (16 + column * 3) * FLOAT_BYTES, divisor = 1)
        }
        gl.vertexAttribute(Shaders.TINT, 4, instanceStride, 25 * FLOAT_BYTES, divisor = 1)
        gl.bindVertexArray(0)

        val (center, radius) = bounds(mesh)
        return GpuPart(vertexArray, mesh.vertexCount, instanceBuffer, material, center, radius)
    }

    /** A bounding sphere around the box of the mesh's positions. */
    private fun bounds(mesh: SubMesh): Pair<Vec3, Float> {
        val min = FloatArray(3) { Float.MAX_VALUE }
        val max = FloatArray(3) { -Float.MAX_VALUE }
        for (v in 0 until mesh.vertexCount) {
            for (axis in 0 until 3) {
                val p = mesh.vertices[v * SubMesh.FLOATS_PER_VERTEX + axis]
                if (p < min[axis]) min[axis] = p
                if (p > max[axis]) max[axis] = p
            }
        }
        if (mesh.vertexCount == 0) return Vec3.ZERO to 0f
        val half = Vec3(max[0] - min[0], max[1] - min[1], max[2] - min[2]) * 0.5f
        return Vec3(min[0], min[1], min[2]) + half to sqrt(half dot half)
    }

    private fun texture(path: String): Int = textures.getOrPut(path) { gl.createTexture(assets.readImage(path)) }

    private fun canRenderTo(format: TextureFormat): Boolean {
        val texture = gl.createRenderTexture(4, 4, format)
        val framebuffer = gl.createFramebuffer(listOf(texture), null)
        framebuffer?.let(gl::deleteFramebuffer)
        gl.deleteTexture(texture)
        return framebuffer != null
    }

    private companion object {
        const val FLOAT_BYTES = 4

        /** Model matrix (16), normal matrix (9), tint (4). */
        const val FLOATS_PER_INSTANCE = 29
        const val SHADOW_MAP_SIZE = 2048

        const val UNIT_BASE_COLOR = 0
        const val UNIT_SHADOW_MAP = 1
        const val UNIT_LIGHTS = 2
        const val UNIT_CLUSTERS = 3
        const val UNIT_ALBEDO = 4
        const val UNIT_NORMAL = 5
        const val UNIT_DEPTH = 6
        const val UNIT_POST_SOURCE = 7
        const val UNIT_POST_BLOOM = 8
        const val UNIT_POINT_SHADOWS = 9
        const val UNIT_LIGHT_INDICES = 10

        /** Clip space [-1, 1] to texture space [0, 1]. */
        val BIAS = Mat4.translation(Vec3(0.5f, 0.5f, 0.5f)) * Mat4.scale(Vec3(0.5f, 0.5f, 0.5f))

        fun toLinear(c: Float) = c.coerceAtLeast(0f).pow(2.2f)
    }
}
