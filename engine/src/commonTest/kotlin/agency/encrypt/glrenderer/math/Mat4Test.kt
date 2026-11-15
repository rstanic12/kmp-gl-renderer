package agency.encrypt.glrenderer.math

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

fun assertNear(expected: Vec3, actual: Vec3, tolerance: Float = 1e-4f) {
    assertTrue((expected - actual).length() < tolerance, "expected $expected, got $actual")
}

class Mat4Test {

    @Test
    fun identityIsNeutral() {
        val m = Mat4.translation(Vec3(1f, 2f, 3f)) * Mat4.rotationY(30f)
        assertEquals(m, Mat4.IDENTITY * m)
        assertEquals(m, m * Mat4.IDENTITY)
    }

    @Test
    fun storageIsColumnMajor() {
        val array = Mat4.translation(Vec3(1f, 2f, 3f)).toFloatArray()
        assertEquals(listOf(1f, 2f, 3f), array.slice(12..14))
    }

    @Test
    fun rotationsFollowTheRightHandRule() {
        assertNear(Vec3(0f, 0f, -1f), Mat4.rotationY(90f).transformPoint(Vec3(1f, 0f, 0f)))
        assertNear(Vec3(0f, 1f, 0f), Mat4.rotationZ(90f).transformPoint(Vec3(1f, 0f, 0f)))
        assertNear(Vec3(0f, 0f, 1f), Mat4.rotationX(90f).transformPoint(Vec3(0f, 1f, 0f)))
    }

    @Test
    fun trsScalesThenRotatesThenTranslates() {
        val m = Mat4.trs(Vec3(10f, 0f, 0f), Vec3(0f, 90f, 0f), Vec3(2f, 2f, 2f))
        assertNear(Vec3(10f, 0f, -2f), m.transformPoint(Vec3(1f, 0f, 0f)))
    }

    @Test
    fun lookAtPutsTheTargetStraightAhead() {
        val view = Mat4.lookAt(Vec3(3f, 4f, 5f), Vec3(1f, 1f, 1f), Vec3.UP)
        val target = view.transformPoint(Vec3(1f, 1f, 1f))
        assertEquals(0f, target.x, 1e-4f)
        assertEquals(0f, target.y, 1e-4f)
        assertEquals(-(Vec3(2f, 3f, 4f).length()), target.z, 1e-4f)
    }

    @Test
    fun perspectiveMapsNearAndFarToTheClipRange() {
        val p = Mat4.perspective(60f, 1.5f, 0.5f, 20f)
        fun ndcZ(z: Float): Float {
            val clipZ = p[2, 2] * z + p[2, 3]
            val clipW = p[3, 2] * z
            return clipZ / clipW
        }
        assertEquals(-1f, ndcZ(-0.5f), 1e-4f)
        assertEquals(1f, ndcZ(-20f), 1e-4f)
    }

    @Test
    fun inverseUndoesTheTransform() {
        val m = Mat4.perspective(50f, 1.3f, 0.1f, 50f) * Mat4.lookAt(Vec3(2f, 3f, 4f), Vec3.ZERO, Vec3.UP) *
            Mat4.trs(Vec3(1f, -2f, 0.5f), Vec3(10f, 20f, 30f), Vec3(1f, 2f, 3f))
        val product = (m * m.inverse()).toFloatArray()
        val identity = Mat4.IDENTITY.toFloatArray()
        for (i in 0 until 16) assertEquals(identity[i], product[i], 1e-4f)
    }

    @Test
    fun orthographicMapsTheBoxToTheClipCube() {
        val o = Mat4.orthographic(-2f, 2f, -1f, 1f, 1f, 9f)
        assertNear(Vec3(-1f, -1f, -1f), o.transformPoint(Vec3(-2f, -1f, -1f)))
        assertNear(Vec3(1f, 1f, 1f), o.transformPoint(Vec3(2f, 1f, -9f)))
    }

    @Test
    fun normalMatrixKeepsNormalsPerpendicularUnderNonUniformScale() {
        val model = Mat4.rotationZ(30f) * Mat4.scale(Vec3(4f, 1f, 1f))
        // A 45-degree slope in the xy plane, its tangent and its normal.
        val tangent = Vec3(1f, 1f, 0f)
        val normal = Vec3(1f, -1f, 0f)
        val n = model.normalMatrix()
        val transformedNormal = Vec3(
            n[0] * normal.x + n[3] * normal.y + n[6] * normal.z,
            n[1] * normal.x + n[4] * normal.y + n[7] * normal.z,
            n[2] * normal.x + n[5] * normal.y + n[8] * normal.z,
        )
        val transformedTangent = model.transformPoint(tangent) - model.transformPoint(Vec3.ZERO)
        assertTrue(abs(transformedNormal dot transformedTangent) < 1e-4f)
        // The naive approach (upper 3x3 of the model matrix) would not be perpendicular here.
        val naive = model.transformPoint(normal) - model.transformPoint(Vec3.ZERO)
        assertTrue(abs(naive dot transformedTangent) > 1f)
    }
}
