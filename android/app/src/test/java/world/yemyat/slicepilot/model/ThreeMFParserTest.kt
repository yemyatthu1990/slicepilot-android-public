package world.yemyat.slicepilot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ThreeMFParserTest {

    /**
     * A unit cube scaled to 10x20x30 mm with 12 triangles. Built in memory so the parser
     * tests are self-contained — real-world models (Bunny, Stringhell, Büchse) live in
     * uncommitted local directories and are exercised via [localModel] when present.
     */
    private fun writeCube3mf(
        file: File,
        itemTransform: String? = null,
        useComponent: Boolean = false,
    ): File {
        val verts = listOf(
            0f, 0f, 0f, 10f, 0f, 0f, 10f, 20f, 0f, 0f, 20f, 0f,
            0f, 0f, 30f, 10f, 0f, 30f, 10f, 20f, 30f, 0f, 20f, 30f,
        )
        val tris = intArrayOf(
            0, 2, 1, 0, 3, 2,
            4, 5, 6, 4, 6, 7,
            0, 1, 5, 0, 5, 4,
            1, 2, 6, 1, 6, 5,
            2, 3, 7, 2, 7, 6,
            3, 0, 4, 3, 4, 7,
        )
        val sb = StringBuilder()
        sb.append(
            """<?xml version="1.0" encoding="UTF-8"?>
            <model unit="millimeter" xml:lang="en-US" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02">
              <resources><object id="1" type="model"><mesh><vertices>
            """.trimIndent()
        )
        for (i in verts.indices step 3) {
            sb.append("<vertex x=\"${verts[i]}\" y=\"${verts[i + 1]}\" z=\"${verts[i + 2]}\"/>")
        }
        sb.append("</vertices><triangles>")
        for (i in tris.indices step 3) {
            sb.append("<triangle v1=\"${tris[i]}\" v2=\"${tris[i + 1]}\" v3=\"${tris[i + 2]}\"/>")
        }
        val build = if (useComponent) {
            // Object 2 references object 1 through a transformed component.
            sb.append("</triangles></mesh></object>")
                .append(
                    """<object id="2" type="model"><components>
                        <component objectid="1" transform="1 0 0 0 1 0 0 0 1 5 5 5"/>
                       </components></object>
                       """.trimIndent()
                )
                .append("</resources><build><item objectid=\"2\"/></build>")
        } else {
            sb.append("</triangles></mesh></object>")
                .append("</resources><build><item objectid=\"1\"")
            if (itemTransform != null) sb.append(" transform=\"$itemTransform\"")
            sb.append("/></build>")
        }
        sb.append("</model>")

        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("3D/3dmodel.model"))
            zip.write(sb.toString().toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return file
    }

    private fun temp3mf(name: String): File =
        File.createTempFile(name, ".3mf").apply { deleteOnExit() }

    @Test
    fun testParseGeneratedCube() {
        val file = writeCube3mf(temp3mf("cube"))
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
    fun testParseCubeWithItemTransform() {
        val file = writeCube3mf(temp3mf("cube_tr"), itemTransform = "1 0 0 0 1 0 0 0 1 10 20 30")
        val mesh = ModelParser.parse(file)
        assertEquals(10f, mesh.minX, 1e-4f)
        assertEquals(20f, mesh.maxX, 1e-4f)
        assertEquals(20f, mesh.minY, 1e-4f)
        assertEquals(40f, mesh.maxY, 1e-4f)
        assertEquals(30f, mesh.minZ, 1e-4f)
        assertEquals(60f, mesh.maxZ, 1e-4f)
    }

    @Test
    fun testParseNestedComponentTransform() {
        val file = writeCube3mf(temp3mf("cube_comp"), useComponent = true)
        val mesh = ModelParser.parse(file)
        assertEquals(5f, mesh.minX, 1e-4f)
        assertEquals(15f, mesh.maxX, 1e-4f)
        assertEquals(5f, mesh.minY, 1e-4f)
        assertEquals(25f, mesh.maxY, 1e-4f)
        assertEquals(5f, mesh.minZ, 1e-4f)
        assertEquals(35f, mesh.maxZ, 1e-4f)
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

    // ---- Real-world models: local, uncommitted fixtures (skipped when absent) ----

    private fun localModel(path: String): File {
        val file = File(path)
        Assume.assumeTrue("local fixture $path not present", file.exists())
        return file
    }

    private fun assertParsedMesh(mesh: MeshData) {
        assertTrue(mesh.positions.isNotEmpty())
        assertTrue(mesh.indices.isNotEmpty())
        assertTrue(mesh.maxX > mesh.minX)
        assertTrue(mesh.maxY > mesh.minY)
        assertTrue(mesh.maxZ > mesh.minZ)
    }

    @Test
    fun testParseBunny() {
        assertParsedMesh(ModelParser.parse(localModel("../../resources/handy_models/Stanford_Bunny.3mf")))
    }

    @Test
    fun testParseStringhell() {
        assertParsedMesh(ModelParser.parse(localModel("../../resources/handy_models/Elegoo_stringhell.3mf")))
    }

    @Test
    fun testParseBuchse() {
        val mesh = ModelParser.parse(localModel("../../tests/data/test_3mf/Geräte/Büchse.3mf"))
        assertParsedMesh(mesh)
        assertEquals(0f, mesh.minX, 1e-4f)
        assertEquals(10f, mesh.maxX, 1e-4f)
        assertEquals(0f, mesh.minY, 1e-4f)
        assertEquals(20f, mesh.maxY, 1e-4f)
        assertEquals(0f, mesh.minZ, 1e-4f)
        assertEquals(30f, mesh.maxZ, 1e-4f)
    }

    @Test
    fun testAllResources3mf() {
        val dirs = listOf(File("../../resources"), File("../../tests/data")).filter { it.isDirectory }
        val files = dirs.flatMap { dir ->
            dir.walkTopDown().filter { it.isFile && it.extension.equals("3mf", ignoreCase = true) }.toList()
        }
        Assume.assumeTrue("no local 3mf fixtures present", files.isNotEmpty())
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
