package agency.encrypt.glrenderer.assets

import agency.encrypt.glrenderer.math.Vec3

data class Material(
    val name: String,
    val diffuse: Vec3 = Vec3(0.8f, 0.8f, 0.8f),
    val specular: Vec3 = Vec3.ZERO,
    val shininess: Float = 1f,
    val opacity: Float = 1f,
    val diffuseMap: String? = null,
) {
    val isTransparent get() = opacity < 1f

    companion object {
        val DEFAULT = Material("default")
    }
}

/**
 * Wavefront MTL reader for the Blinn-Phong subset the renderer uses: `Kd`, `Ks`, `Ns`, `d` / `Tr`, `map_Kd`.
 * Ambient (`Ka`) is ignored: ambient light is tinted by the diffuse colour instead, because exporters
 * commonly write `Ka 1 1 1`, which would wash every material out.
 */
object MtlParser {

    fun parse(text: String): Map<String, Material> {
        val materials = LinkedHashMap<String, Material>()
        var current: Material? = null

        fun commit() {
            current?.let { materials[it.name] = it }
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(WHITESPACE)
            fun color() = Vec3(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())

            if (parts[0] == "newmtl") {
                commit()
                current = Material(parts.drop(1).joinToString(" "))
                return@forEach
            }
            val material = current ?: return@forEach
            current = when (parts[0]) {
                "Kd" -> material.copy(diffuse = color())
                "Ks" -> material.copy(specular = color())
                "Ns" -> material.copy(shininess = parts[1].toFloat().coerceIn(1f, 1000f))
                "d" -> material.copy(opacity = parts[1].toFloat().coerceIn(0f, 1f))
                "Tr" -> material.copy(opacity = (1f - parts[1].toFloat()).coerceIn(0f, 1f))
                // Options such as "-bm 1" may precede the file name, which is always last.
                "map_Kd" -> material.copy(diffuseMap = parts.last())
                else -> material
            }
        }
        commit()
        return materials
    }

    private val WHITESPACE = Regex("\\s+")
}
