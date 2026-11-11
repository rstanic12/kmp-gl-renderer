package agency.encrypt.glrenderer.render

import agency.encrypt.glrenderer.assets.AssetSource
import agency.encrypt.glrenderer.assets.Material
import agency.encrypt.glrenderer.assets.MtlParser
import agency.encrypt.glrenderer.assets.ObjParser
import agency.encrypt.glrenderer.assets.SubMesh
import agency.encrypt.glrenderer.assets.siblingPath
import agency.encrypt.glrenderer.ecs.World
import agency.encrypt.glrenderer.gl.Gl
import agency.encrypt.glrenderer.math.Mat4
import agency.encrypt.glrenderer.math.Vec3
import agency.encrypt.glrenderer.scene.ModelRef
import agency.encrypt.glrenderer.scene.OrbitCamera
import agency.encrypt.glrenderer.scene.WorldTransform

/**
 * Draws every entity that has a [ModelRef] and a [WorldTransform].
 *
 * Must be created and used on the thread that owns the GL context. GPU resources are created lazily the
 * first time a model is drawn and live as long as the context; after a context loss, create a new Renderer.
 */
class Renderer(private val gl: Gl, private val assets: AssetSource) {

    var lightDirection = Vec3(0.4f, 1f, 0.6f).normalized()
    var lightColor = Vec3(1f, 0.96f, 0.9f)
    var skyColor = Vec3(0.20f, 0.23f, 0.30f)
    var groundColor = Vec3(0.07f, 0.06f, 0.06f)
    var clearColor = Vec3(0.09f, 0.10f, 0.12f)

    private class GpuPart(val vertexArray: Int, val vertexCount: Int, val material: Material, val texture: Int?)
    private class Draw(val part: GpuPart, val model: Mat4, val distance: Float)

    private val program = gl.createProgram(Shaders.VERTEX, Shaders.FRAGMENT)
    private val uniforms = Uniforms()
    private val models = HashMap<String, List<GpuPart>>()
    private val textures = HashMap<String, Int>()
    private var width = 1
    private var height = 1

    init {
        gl.enableDepthTest()
    }

    fun resize(width: Int, height: Int) {
        this.width = maxOf(width, 1)
        this.height = maxOf(height, 1)
    }

    fun render(world: World, camera: OrbitCamera) {
        gl.viewport(width, height)
        gl.clearColor(clearColor.x, clearColor.y, clearColor.z, 1f)
        gl.clear()

        val eye = camera.eye()
        val opaque = mutableListOf<Draw>()
        val transparent = mutableListOf<Draw>()
        world.each<ModelRef, WorldTransform> { _, ref, transform ->
            val distance = (transform.matrix.translation() - eye).length()
            for (part in model(ref.path)) {
                val draw = Draw(part, transform.matrix, distance)
                if (part.material.isTransparent) transparent += draw else opaque += draw
            }
        }

        gl.useProgram(program)
        gl.uniformMatrix4(uniforms.viewProjection, camera.viewProjection(width.toFloat() / height).toFloatArray())
        gl.uniform3f(uniforms.cameraPosition, eye.x, eye.y, eye.z)
        gl.uniform3f(uniforms.lightDirection, lightDirection.x, lightDirection.y, lightDirection.z)
        gl.uniform3f(uniforms.lightColor, lightColor.x, lightColor.y, lightColor.z)
        gl.uniform3f(uniforms.skyColor, skyColor.x, skyColor.y, skyColor.z)
        gl.uniform3f(uniforms.groundColor, groundColor.x, groundColor.y, groundColor.z)
        gl.uniform1i(uniforms.diffuseMap, 0)

        opaque.forEach(::draw)
        if (transparent.isNotEmpty()) {
            // Back to front, without writing depth, so overlapping transparent parts blend.
            gl.setBlending(true)
            gl.setDepthWrite(false)
            transparent.sortedByDescending { it.distance }.forEach(::draw)
            gl.setDepthWrite(true)
            gl.setBlending(false)
        }

        val error = gl.error()
        check(error == 0) { "GL error 0x${error.toString(16)} while rendering" }
    }

    private fun draw(draw: Draw) {
        val material = draw.part.material
        gl.uniformMatrix4(uniforms.model, draw.model.toFloatArray())
        gl.uniformMatrix3(uniforms.normalMatrix, draw.model.normalMatrix())
        gl.uniform3f(uniforms.diffuse, material.diffuse.x, material.diffuse.y, material.diffuse.z)
        gl.uniform3f(uniforms.specular, material.specular.x, material.specular.y, material.specular.z)
        gl.uniform1f(uniforms.shininess, material.shininess)
        gl.uniform1f(uniforms.opacity, material.opacity)
        gl.uniform1i(uniforms.hasDiffuseMap, if (draw.part.texture != null) 1 else 0)
        draw.part.texture?.let { gl.bindTexture(0, it) }
        gl.bindVertexArray(draw.part.vertexArray)
        gl.drawTriangles(0, draw.part.vertexCount)
    }

    private fun model(path: String): List<GpuPart> = models.getOrPut(path) {
        val obj = ObjParser.parse(assets.readText(path))
        val materials = obj.materialLibraries
            .flatMap { MtlParser.parse(assets.readText(siblingPath(path, it))).values }
            .associateBy { it.name }
        obj.subMeshes.map { mesh ->
            val material = mesh.material?.let { materials[it] } ?: Material.DEFAULT
            val texture = material.diffuseMap?.let { texture(siblingPath(path, it)) }
            GpuPart(upload(mesh), mesh.vertexCount, material, texture)
        }
    }

    private fun upload(mesh: SubMesh): Int {
        val vertexArray = gl.createVertexArray(mesh.vertices)
        val stride = SubMesh.FLOATS_PER_VERTEX * FLOAT_BYTES
        gl.vertexAttribute(Shaders.POSITION, 3, stride, 0)
        gl.vertexAttribute(Shaders.NORMAL, 3, stride, 3 * FLOAT_BYTES)
        gl.vertexAttribute(Shaders.UV, 2, stride, 6 * FLOAT_BYTES)
        return vertexArray
    }

    private fun texture(path: String): Int = textures.getOrPut(path) { gl.createTexture(assets.readImage(path)) }

    private inner class Uniforms {
        val model = gl.uniformLocation(program, "uModel")
        val viewProjection = gl.uniformLocation(program, "uViewProjection")
        val normalMatrix = gl.uniformLocation(program, "uNormalMatrix")
        val cameraPosition = gl.uniformLocation(program, "uCameraPosition")
        val lightDirection = gl.uniformLocation(program, "uLightDirection")
        val lightColor = gl.uniformLocation(program, "uLightColor")
        val skyColor = gl.uniformLocation(program, "uSkyColor")
        val groundColor = gl.uniformLocation(program, "uGroundColor")
        val diffuse = gl.uniformLocation(program, "uDiffuse")
        val specular = gl.uniformLocation(program, "uSpecular")
        val shininess = gl.uniformLocation(program, "uShininess")
        val opacity = gl.uniformLocation(program, "uOpacity")
        val hasDiffuseMap = gl.uniformLocation(program, "uHasDiffuseMap")
        val diffuseMap = gl.uniformLocation(program, "uDiffuseMap")
    }

    private companion object {
        const val FLOAT_BYTES = 4
    }
}
