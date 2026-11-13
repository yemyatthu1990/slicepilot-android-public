package world.yemyat.slicepilot.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import world.yemyat.slicepilot.preview.ModelTransform

/**
 * The piece layer: how the native parser's row table becomes parts, and how those parts get
 * posed on a plate. Everything here is pure data — the point of the tests is that a file with
 * two models stops being one indivisible blob without breaking the single-model case that
 * every release before pieces had.
 */
class MeshPartTest {
    private fun cubePart(
        name: String,
        x: Float,
        y: Float,
        size: Float = 10f,
    ) = MeshPart(
        name = name,
        vertexOffset = 0,
        vertexCount = 8,
        indexOffset = 0,
        indexCount = 36,
        minX = x,
        minY = y,
        minZ = 0f,
        maxX = x + size,
        maxY = y + size,
        maxZ = size,
    )

    private fun meshOf(vararg names: String): MeshData {
        val parts =
            names.mapIndexed { i, n ->
                cubePart(n, x = i * 20f, y = 0f)
            }
        return MeshData(
            positions = FloatArray(3),
            normals = FloatArray(3),
            indices = IntArray(3),
            minX = 0f,
            minY = 0f,
            minZ = 0f,
            maxX = 100f,
            maxY = 100f,
            maxZ = 10f,
            parts = parts.toList(),
        )
    }

    // -- the native row table ----------------------------------------------------------

    @Test
    fun decodesRowsAndNamesTogether() {
        val rows =
            floatArrayOf(
                // vertex start, count, index start, count, then min xyz and max xyz
                0f,
                8f,
                0f,
                36f,
                0f,
                0f,
                0f,
                10f,
                10f,
                10f,
                8f,
                8f,
                36f,
                36f,
                20f,
                0f,
                0f,
                30f,
                10f,
                10f,
            )
        val parts = MeshPart.fromRows(rows, listOf("left", "right"))
        assertEquals(2, parts.size)
        assertEquals("left", parts[0].name)
        assertEquals(8, parts[0].vertexCount)
        assertEquals(36, parts[0].indexCount)
        assertEquals("right", parts[1].name)
        assertEquals(20f, parts[1].minX, 0f)
        assertEquals(30f, parts[1].maxX, 0f)
    }

    @Test
    fun unnamedPiecesStillGetAName() {
        val rows = FloatArray(MeshPart.ROW_FLOATS * 2)
        val parts = MeshPart.fromRows(rows, listOf("", ""))
        assertEquals(listOf("Part 1", "Part 2"), parts.map { it.name })
    }

    @Test
    fun aPartialRowAtTheEndIsDroppedNotGuessed() {
        val rows = FloatArray(MeshPart.ROW_FLOATS + 4)
        assertEquals(1, MeshPart.fromRows(rows, listOf("only")).size)
        assertEquals(0, MeshPart.fromRows(FloatArray(0), emptyList()).size)
    }

    // -- pose rows ---------------------------------------------------------------------

    @Test
    fun poseRowIsTheModelTransformRowPlusThePrintFlag() {
        val pose = ModelTransform(rotX = 90f, rotY = 0f, rotZ = 180f, mirrorY = true, offsetX = 3f, offsetY = -4f)
        val part = cubePart("a", 0f, 0f)
        assertRowEquals(pose, true, part.poseRow(pose, print = true))
        assertRowEquals(pose, false, part.poseRow(pose, print = false))
    }

    private fun assertRowEquals(
        pose: ModelTransform,
        print: Boolean,
        row: FloatArray,
    ) {
        assertEquals(MeshPart.POSE_ROW_FLOATS, row.size)
        assertArrayEquals(pose.toArray(), row.copyOfRange(0, 8), 0f)
        assertEquals(if (print) 1f else 0f, row[8], 0f)
    }

    @Test
    fun poseRowsAreOnlyOfferedWhenTheMeshActuallyHasPieces() {
        // A plain STL (or a decimated preview) has no parts of its own: the caller keeps using
        // the single whole-model transform instead of a per-piece list.
        val anonymous =
            MeshData(FloatArray(3), FloatArray(3), IntArray(3), 0f, 0f, 0f, 10f, 10f, 10f)
        assertNull(PartPlacement.poseRows(anonymous, listOf(ModelTransform())))
        val two = meshOf("a", "b")
        val rows = PartPlacement.poseRows(two, listOf(ModelTransform(), ModelTransform()))
        assertNotNull(rows)
        assertEquals(2 * MeshPart.POSE_ROW_FLOATS, rows!!.size)
    }

    @Test
    fun skippingAPieceWritesZeroIntoItsRow() {
        val two = meshOf("a", "b")
        val rows = PartPlacement.poseRows(two, listOf(ModelTransform(), ModelTransform()), skipped = setOf(1))
        assertNotNull(rows)
        assertEquals(1f, rows!![8], 0f)
        assertEquals(0f, rows[MeshPart.POSE_ROW_FLOATS + 8], 0f)
    }

    // -- placement ---------------------------------------------------------------------

    @Test
    fun aSinglePieceIsCentredOnThePlate() {
        val one = meshOf("only")
        val poses = PartPlacement.defaultPoses(one, 220f, 220f)
        assertEquals(1, poses.size)
        // One piece is the pre-parts case: centred on the plate, no authored offset applied.
        assertEquals(0f, poses[0].offsetX, 0.01f)
        assertEquals(0f, poses[0].offsetY, 0.01f)
    }

    @Test
    fun anAuthoredLayoutThatFitsIsKept() {
        val two = meshOf("a", "b")
        val poses = PartPlacement.defaultPoses(two, 220f, 220f)
        assertEquals(2, poses.size)
        assertEquals(-105f, poses[0].offsetX, 0.01f)
        assertEquals(-85f, poses[1].offsetX, 0.01f)
    }

    @Test
    fun packingRefusesAPartBiggerThanThePlate() {
        val huge = listOf(cubePart("huge", 0f, 0f, 400f))
        assertNull(PartPlacement.packedPoses(huge, 220f, 220f))
    }

    @Test
    fun unionFootprintIgnoresSkippedParts() {
        val two = meshOf("a", "b")
        val poses = listOf(ModelTransform(), ModelTransform(offsetX = 50f))
        val both = PartPlacement.unionFootprint(two.parts, poses)
        val onlyFirst = PartPlacement.unionFootprint(two.parts, poses, listOf(true, false))
        assertTrue(onlyFirst.first < both.first)
    }

    @Test
    fun fitsChecksPlateWidthDepthAndHeight() {
        val two = meshOf("a", "b")
        val poses = listOf(ModelTransform(), ModelTransform())
        assertTrue(PartPlacement.fits(two, poses, emptyList(), 220f, 220f, 250f))
        assertFalse(PartPlacement.fits(two, poses, emptyList(), 5f, 5f, 250f))
        assertFalse(PartPlacement.fits(two, poses, emptyList(), 220f, 220f, 1f))
    }
}
