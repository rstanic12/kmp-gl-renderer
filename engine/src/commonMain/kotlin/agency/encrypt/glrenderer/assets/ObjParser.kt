package agency.encrypt.glrenderer.assets

import agency.encrypt.glrenderer.math.Vec3

/**
 * Triangles of one object drawn with one material.
 * [vertices] is interleaved, [FLOATS_PER_VERTEX] floats each: position xyz, normal xyz, uv.
 */
public class SubMesh(public val name: String, public val material: String?, public val vertices: FloatArray) {
    public val vertexCount: Int get() = vertices.size / FLOATS_PER_VERTEX

    public companion object {
        public const val FLOATS_PER_VERTEX: Int = 8
    }
}

public class ObjModel(public val materialLibraries: List<String>, public val subMeshes: List<SubMesh>)

/**
 * Wavefront OBJ reader. Handles `v`, `vt`, `vn`, `f` (triangles and convex polygons, fan-triangulated),
 * the `v`, `v/vt`, `v//vn` and `v/vt/vn` index forms, negative (relative) indices, `o`/`g` groups,
 * `usemtl` and `mtllib`. Faces without normals get a flat face normal.
 *
 * OBJ puts v = 1 at the top of an image, while textures are uploaded top row first (top at t = 0),
 * so the emitted coordinate is t = 1 - v.
 */
public object ObjParser {

    public fun parse(text: String): ObjModel {
        val positions = mutableListOf<Vec3>()
        val normals = mutableListOf<Vec3>()
        val uvs = mutableListOf<Pair<Float, Float>>()
        val libraries = mutableListOf<String>()
        val meshes = mutableListOf<SubMesh>()

        var name = ""
        var material: String? = null
        var current = FloatList()

        fun flush() {
            if (current.size > 0) meshes += SubMesh(name, material, current.toArray())
            current = FloatList()
        }

        text.lineSequence().forEachIndexed { lineIndex, rawLine ->
            val line = rawLine.substringBefore('#').trim()
            if (line.isEmpty()) return@forEachIndexed
            val parts = line.split(WHITESPACE)
            fun float(i: Int) = parts.getOrNull(i)?.toFloatOrNull()
                ?: throw ObjFormatException(lineIndex + 1, "expected a number in '$line'")

            when (parts[0]) {
                "v" -> positions += Vec3(float(1), float(2), float(3))
                "vn" -> normals += Vec3(float(1), float(2), float(3)).normalized()
                "vt" -> uvs += float(1) to (parts.getOrNull(2)?.toFloatOrNull() ?: 0f)
                "o", "g" -> {
                    flush()
                    name = parts.drop(1).joinToString(" ")
                }
                "usemtl" -> {
                    flush()
                    material = parts.getOrNull(1)
                }
                "mtllib" -> libraries += parts.drop(1)
                "f" -> {
                    if (parts.size < 4) throw ObjFormatException(lineIndex + 1, "a face needs at least 3 vertices")
                    val corners = parts.drop(1).map { parseCorner(it, positions.size, uvs.size, normals.size, lineIndex + 1) }
                    for (i in 1 until corners.size - 1) {
                        emitTriangle(current, corners[0], corners[i], corners[i + 1], positions, uvs, normals)
                    }
                }
            }
        }
        flush()
        return ObjModel(libraries, meshes)
    }

    private class Corner(val position: Int, val uv: Int?, val normal: Int?)

    private fun parseCorner(token: String, positionCount: Int, uvCount: Int, normalCount: Int, line: Int): Corner {
        val fields = token.split('/')
        fun index(field: String?, count: Int): Int? {
            if (field.isNullOrEmpty()) return null
            val raw = field.toIntOrNull() ?: throw ObjFormatException(line, "bad index '$token'")
            val resolved = if (raw < 0) count + raw else raw - 1
            if (resolved !in 0 until count) throw ObjFormatException(line, "index $raw out of range in '$token'")
            return resolved
        }
        return Corner(
            position = index(fields[0], positionCount) ?: throw ObjFormatException(line, "missing position in '$token'"),
            uv = index(fields.getOrNull(1), uvCount),
            normal = index(fields.getOrNull(2), normalCount),
        )
    }

    private fun emitTriangle(
        out: FloatList,
        a: Corner, b: Corner, c: Corner,
        positions: List<Vec3>, uvs: List<Pair<Float, Float>>, normals: List<Vec3>,
    ) {
        val pa = positions[a.position]
        val pb = positions[b.position]
        val pc = positions[c.position]
        val faceNormal by lazy { ((pb - pa) cross (pc - pa)).normalized() }
        for ((corner, p) in listOf(a to pa, b to pb, c to pc)) {
            val n = corner.normal?.let { normals[it] } ?: faceNormal
            val uv = corner.uv?.let { uvs[it] } ?: (0f to 0f)
            out.add(p.x, p.y, p.z, n.x, n.y, n.z, uv.first, 1f - uv.second)
        }
    }

    private val WHITESPACE = Regex("\\s+")
}

public class ObjFormatException(line: Int, message: String) : IllegalArgumentException("OBJ line $line: $message")

/** Growable FloatArray, so large meshes are not boxed into List<Float>. */
internal class FloatList {
    private var data = FloatArray(1024)
    var size = 0
        private set

    fun add(vararg values: Float) {
        if (size + values.size > data.size) data = data.copyOf(maxOf(data.size * 2, size + values.size))
        values.copyInto(data, size)
        size += values.size
    }

    fun toArray() = data.copyOf(size)
}
