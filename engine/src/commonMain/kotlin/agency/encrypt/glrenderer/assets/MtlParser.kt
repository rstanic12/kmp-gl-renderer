package agency.encrypt.glrenderer.assets

import agency.encrypt.glrenderer.math.Vec3
import kotlin.math.sqrt

/** Metal/roughness material. Colours are as written in the file (sRGB); [emissive] is linear and may exceed 1. */
public data class Material(
    val name: String,
    val baseColor: Vec3 = Vec3(0.8f, 0.8f, 0.8f),
    val metallic: Float = 0f,
    val roughness: Float = 0.5f,
    val emissive: Vec3 = Vec3.ZERO,
    val opacity: Float = 1f,
    val baseColorMap: String? = null,
) {
    public val isTransparent: Boolean get() = opacity < 1f

    public companion object {
        public val DEFAULT: Material = Material("default")
    }
}

/**
 * Wavefront MTL reader for the PBR extension (`Pr`, `Pm`, `Ke`) plus the classic `Kd`, `d` / `Tr` and `map_Kd`.
 * Files without `Pr` get a roughness derived from the Blinn-Phong exponent `Ns`, so older exports still look plausible.
 * `Ka` and `Ks` have no metal/roughness equivalent and are ignored.
 */
public object MtlParser {

    public fun parse(text: String): Map<String, Material> {
        val materials = LinkedHashMap<String, Material>()
        var current: Material? = null
        var hasRoughness = false

        fun commit() {
            current?.let { materials[it.name] = it }
        }

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEach
            val parts = line.split(WHITESPACE)
            fun color() = Vec3(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
            fun value() = parts[1].toFloat()

            if (parts[0] == "newmtl") {
                commit()
                current = Material(parts.drop(1).joinToString(" "))
                hasRoughness = false
                return@forEach
            }
            val material = current ?: return@forEach
            current = when (parts[0]) {
                "Kd" -> material.copy(baseColor = color())
                "Pm" -> material.copy(metallic = value().coerceIn(0f, 1f))
                "Pr" -> material.copy(roughness = value().coerceIn(MIN_ROUGHNESS, 1f)).also { hasRoughness = true }
                "Ns" -> if (hasRoughness) material else material.copy(roughness = roughnessFromExponent(value()))
                "Ke" -> material.copy(emissive = color())
                "d" -> material.copy(opacity = value().coerceIn(0f, 1f))
                "Tr" -> material.copy(opacity = (1f - value()).coerceIn(0f, 1f))
                // Options such as "-bm 1" may precede the file name, which is always last.
                "map_Kd" -> material.copy(baseColorMap = parts.last())
                else -> material
            }
        }
        commit()
        return materials
    }

    /** The usual Blinn-Phong to GGX mapping: exponent n has roughly the lobe of alpha = sqrt(2 / (n + 2)), roughness = sqrt(alpha). */
    private fun roughnessFromExponent(exponent: Float): Float =
        sqrt(sqrt(2f / (exponent.coerceAtLeast(0f) + 2f))).coerceIn(MIN_ROUGHNESS, 1f)

    /** Below this the GGX highlight is narrower than a pixel and aliases. */
    public const val MIN_ROUGHNESS: Float = 0.08f

    private val WHITESPACE = Regex("\\s+")
}
