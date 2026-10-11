package world.yemyat.slicepilot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ThreeMFParserTest {
    @Test
    fun testParseBunny() {
        val file = File("../../resources/handy_models/Stanford_Bunny.3mf")
        assertTrue(file.exists())
        val mesh = ModelParser.parse(file)
        assertNotNull(mesh)
        assertTrue(mesh.positions.isNotEmpty())
        assertTrue(mesh.indices.isNotEmpty())
        // Verify valid bounding box
        assertTrue(mesh.maxX > mesh.minX)
        assertTrue(mesh.maxY > mesh.minY)
        assertTrue(mesh.maxZ > mesh.minZ)
    }

    @Test
    fun testParseStringhell() {
        val file = File("../../resources/handy_models/Elegoo_stringhell.3mf")
        assertTrue(file.exists())
        val mesh = ModelParser.parse(file)
        assertNotNull(mesh)
        assertTrue(mesh.positions.isNotEmpty())
        assertTrue(mesh.indices.isNotEmpty())
        assertTrue(mesh.maxX > mesh.minX)
        assertTrue(mesh.maxY > mesh.minY)
        assertTrue(mesh.maxZ > mesh.minZ)
    }

    @Test
    fun testParseBuchse() {
        val file = File("../../tests/data/test_3mf/Geräte/Büchse.3mf")
        assertTrue(file.exists())
        val mesh = ModelParser.parse(file)
        assertNotNull(mesh)
        assertTrue(mesh.positions.isNotEmpty())
        assertTrue(mesh.indices.isNotEmpty())
        assertEquals(0f, mesh.minX, 1e-4f)
        assertEquals(10f, mesh.maxX, 1e-4f)
        assertEquals(0f, mesh.minY, 1e-4f)
        assertEquals(20f, mesh.maxY, 1e-4f)
        assertEquals(0f, mesh.minZ, 1e-4f)
        assertEquals(30f, mesh.maxZ, 1e-4f)
    }

    @Test
    fun testTransformCombine() {
        // Translation by (10, 20, 30)
        val t1 = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            10f, 20f, 30f
        )
        // Translation by (1, 2, 3)
        val t2 = floatArrayOf(
            1f, 0f, 0f,
            0f, 1f, 0f,
            0f, 0f, 1f,
            1f, 2f, 3f
        )
        val c = ThreeMFParser.combine(t1, t2)
        assertNotNull(c)
        assertEquals(11f, c!![9], 1e-5f)
        assertEquals(22f, c[10], 1e-5f)
        assertEquals(33f, c[11], 1e-5f)
    }

    @Test
    fun testAllResources3mf() {
        val dirs = listOf(
            File("../../resources"),
            File("../../tests/data")
        )
        val files = dirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension.equals("3mf", ignoreCase = true) }.toList()
        }
        assertTrue("Found ${files.size} 3mf files", files.isNotEmpty())
        for (f in files) {
            println("Testing 3mf: ${f.name} (${f.length()} bytes)")
            val mesh = ModelParser.parse(f)
            assertNotNull("Mesh null for ${f.name}", mesh)
            assertTrue("Positions empty for ${f.name}", mesh.positions.isNotEmpty())
            assertTrue("Indices empty for ${f.name}", mesh.indices.isNotEmpty())
            println("  -> Parsed ${f.name}: verts=${mesh.positions.size / 3}, tris=${mesh.indices.size / 3}")
        }
    }
}
