package world.yemyat.slicepilot.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelTransformTest {
    @Test
    fun downFaceMatchesEveryCardinalFace() {
        for (face in ModelTransform.Face.values()) {
            val pose = ModelTransform().forFace(face)
            assertEquals("Expected $face for pose=$pose", face, pose.downFace())
        }
    }

    @Test
    fun downFaceSurvivesZRotationAndMirrors() {
        for (face in ModelTransform.Face.values()) {
            val turned = ModelTransform().forFace(face).rotated('z')!!.mirrored('x')!!.mirrored('z')!!
            assertEquals("Expected $face after Z turn + mirrors ($turned)", face, turned.downFace())
        }
    }

    @Test
    fun downFaceRecognizesEquivalentRotations() {
        val flippedViaY = ModelTransform().rotated('y')!!.rotated('y')!!
        assertEquals(ModelTransform.Face.TOP, flippedViaY.downFace())

        val frontViaX = ModelTransform().rotated('x')!!
        assertEquals(ModelTransform.Face.FRONT, frontViaX.downFace())
    }
}
