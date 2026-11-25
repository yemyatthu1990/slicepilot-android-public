package world.yemyat.slicepilot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GcodePreviewParserTest {
    @Test
    fun `parses ElegooSlicer CHANGE_LAYER, FEATURE, dynamic LAYER_HEIGHT, and Z height`() {
        val tmp = File.createTempFile("elegoo_bbl_test", ".gcode")
        try {
            tmp.writeText(
                """
                ; HEADER_BLOCK_START
                ; model printing time: 11m 45s; total estimated time: 11m 46s
                ; total layer number: 3
                ; HEADER_BLOCK_END
                ; FEATURE: Custom
                M83
                G90
                G1 X127 Y-1.2 Z0.5 F20000
                G1 X87 Y-1.2 E10
                ; CHANGE_LAYER
                ; Z_HEIGHT: 0.2
                ; LAYER_HEIGHT: 0.2
                G1 X100 Y100 Z0.2 F30000
                ; FEATURE: Outer wall
                ; LINE_WIDTH: 0.42
                G1 X120 Y100 E1.0
                ; FEATURE: Sparse infill
                G1 X120 Y120 E1.0
                ; CHANGE_LAYER
                ; Z_HEIGHT: 0.32
                ; LAYER_HEIGHT: 0.12
                G1 X100 Y100 Z0.32 F30000
                ; FEATURE: Outer wall
                G1 X120 Y100 E1.0
                ; CHANGE_LAYER
                ; Z_HEIGHT: 0.44
                ; LAYER_HEIGHT: 0.12
                G1 X100 Y100 Z0.44 F30000
                ; FEATURE: Support
                G1 X120 Y100 E1.0
                """.trimIndent(),
            )
            val preview = GcodePreviewParser.parse(tmp)
            assertEquals(3, preview.layers.size)
            assertEquals(0.44f, preview.maxZ, 0.001f)
            // Start-gcode purge line at Y=-1.2 must not inflate model bounds.
            assertTrue("Expected minY near 100, got ${preview.minY}", preview.minY > 90f)
            // Layer 0 has both PERIMETER and INFILL parsed from `; FEATURE:` comments.
            val layer0Parts = preview.layers[0].parts.map { it.part }.toSet()
            assertTrue(GcodePart.PERIMETER in layer0Parts)
            assertTrue(GcodePart.INFILL in layer0Parts)
            // Layer 1 uses the updated 0.12mm layer height (z top=0.32, bottom=0.20).
            val layer1Verts = preview.layers[1].parts.single().vertices
            val zs = (2 until layer1Verts.size step 3).map { layer1Verts[it] }
            assertEquals(0.32f, zs.max(), 0.001f)
            assertEquals(0.20f, zs.min(), 0.001f)
            // Layer 2 classifies `; FEATURE: Support` as SUPPORT.
            assertEquals(GcodePart.SUPPORT, preview.layers[2].parts.single().part)

            val stats = GcodePreviewParser.parseStats(tmp)
            assertEquals(3, stats.layerCount)
            assertEquals(706L, stats.estimatedSeconds)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `parses colon LAYER markers, G2 G3 arcs, and G91 relative Z`() {
        val tmp = File.createTempFile("arcs_and_rel_test", ".gcode")
        try {
            tmp.writeText(
                """
                M83
                G90
                ;LAYER_COUNT:2
                ;LAYER:0
                ;TYPE:Outer wall
                G1 X10 Y0 Z0.2
                G3 X0 Y10 I-10 J0 E2.0
                ;LAYER:1
                G91
                G1 Z0.2
                G90
                G1 X10 Y0
                G2 X0 Y-10 I-10 J0 E2.0
                """.trimIndent(),
            )
            val preview = GcodePreviewParser.parse(tmp)
            assertEquals(2, preview.layers.size)
            assertEquals(0.4f, preview.maxZ, 0.001f)
            // Arc moves must be linearized into multiple 3D ribbon segments per layer.
            val l0Verts = preview.layers[0].parts.single().vertices
            val l1Verts = preview.layers[1].parts.single().vertices
            assertTrue("Expected linearized CCW arc geometry", l0Verts.size >= 54 * 4)
            assertTrue("Expected linearized CW arc geometry", l1Verts.size >= 54 * 4)
            assertEquals(0.4f, preview.layers[1].z, 0.001f)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `records extruded layer Z instead of trailing Z-hop and preserves spiral vase slope`() {
        val tmp = File.createTempFile("zhop_spiral_test", ".gcode")
        try {
            tmp.writeText(
                """
                M83
                G90
                ; CHANGE_LAYER
                ; LAYER_HEIGHT: 0.2
                G1 X0 Y0 Z0.6
                G1 Z0.2
                ; FEATURE: Outer wall
                G1 X20 Y0 E1.0
                G1 Z0.6
                ; CHANGE_LAYER
                G1 Z0.4
                G1 X20 Y20 Z0.5 E1.0
                """.trimIndent(),
            )
            val preview = GcodePreviewParser.parse(tmp)
            assertEquals(2, preview.layers.size)
            assertEquals(0.2f, preview.layers[0].z, 0.001f)
            assertEquals(0.5f, preview.layers[1].z, 0.001f)
            assertEquals(0.5f, preview.maxZ, 0.001f)
            // Layer 1 slopes from z=0.4 to z=0.5 (bottoms 0.2 to 0.3).
            val l1Verts = preview.layers[1].parts.single().vertices
            val zs = (2 until l1Verts.size step 3).map { l1Verts[it] }
            assertEquals(0.5f, zs.max(), 0.001f)
            assertEquals(0.2f, zs.min(), 0.001f)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun `keeps full height on dense multi-hundred-layer prints without odd-layer deadlock`() {
        val tmp = File.createTempFile("huge_dense_test", ".gcode")
        try {
            // Generate a >12 MB high-detail G-code (500 layers, 0.2mm -> 100.0mm tall,
            // with micro-segmented outer walls, inner walls, and dense infill) that
            // previously deadlocked at odd layerIndex once totalFloats crossed 9M.
            tmp.bufferedWriter().use { w ->
                w.write("M83\nG90\n")
                for (layer in 1..500) {
                    val z = layer * 0.2f
                    w.write("; CHANGE_LAYER\n; LAYER_HEIGHT: 0.2\nG1 Z%.2f\n".format(z))
                    // Outer wall: 400 tiny 0.2mm micro-segments around a 40mm perimeter
                    w.write("; FEATURE: Outer wall\nG1 X100.00 Y100.00\n")
                    for (s in 1..100) w.write("G1 X%.2f Y100.00 E0.01\n".format(100f + s * 0.2f))
                    for (s in 1..100) w.write("G1 X120.00 Y%.2f E0.01\n".format(100f + s * 0.2f))
                    for (s in 1..100) w.write("G1 X%.2f Y120.00 E0.01\n".format(120f - s * 0.2f))
                    for (s in 1..100) w.write("G1 X100.00 Y%.2f E0.01\n".format(120f - s * 0.2f))
                    // Hidden inner wall + dense infill
                    w.write("; FEATURE: Inner wall\nG1 X101.00 Y101.00\n")
                    for (s in 1..100) w.write("G1 X%.2f Y101.00 E0.01\n".format(101f + s * 0.18f))
                    w.write("; FEATURE: Sparse infill\nG1 X102.00 Y102.00\n")
                    for (s in 1..200) w.write("G1 X%.2f Y%.2f E0.01\n".format(102f + (s % 2) * 16f, 102f + s * 0.08f))
                }
            }
            assertTrue("Expected > 7 MB synthetic file, got ${tmp.length()}", tmp.length() > 7_000_000L)
            val preview = GcodePreviewParser.parse(tmp)
            // Must render all the way to the top of the 100mm model without chopping off!
            assertTrue("Expected full height near 100mm, got ${preview.maxZ}", preview.maxZ >= 99.6f)
            assertTrue("Expected >= 250 layers kept, got ${preview.layers.size}", preview.layers.size >= 250)
            val totalFloats = preview.layers.sumOf { l -> l.parts.sumOf { it.vertices.size.toLong() } }
            assertTrue("Must stay within 48 MB hard cap, got $totalFloats floats", totalFloats <= 12L * 1024 * 1024)
        } finally {
            tmp.delete()
        }
    }
}
