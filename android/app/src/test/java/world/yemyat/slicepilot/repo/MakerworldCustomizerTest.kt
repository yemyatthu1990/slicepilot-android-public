package world.yemyat.slicepilot.repo

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

class MakerworldCustomizerTest {
    @Test
    fun objToStlBytesConvertsTrianglesQuadsExtraWhitespaceAndNegativeIndices() {
        val obj =
            """
            mtllib model.mtl
            v   0.0   0.0   0.0
            v  10.0   0.0   0.0
            v  10.0  10.0   0.0
            v   0.0  10.0   0.0
            f 1/1/1  2/2/1  3/3/1  4/4/1
            f -4 -3 -2
            """.trimIndent()

        val stl = MakerworldCustomizer.objToStlBytes(obj)
        assertNotNull(stl)
        // Quad fan-triangulates into 2 triangles + 1 negative-indexed triangle = 3 triangles.
        assertEquals(84 + 3 * 50, stl!!.size)
        val triCount = ByteBuffer.wrap(stl, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int
        assertEquals(3, triCount)
    }

    @Test
    fun unwrapMakerWorldContainerRestoresAsciiStlWithoutDuplicatingOuterLoop() {
        val header = ByteArray(80)
        "MW 1.0 customizer".toByteArray().copyInto(header)
        val asciiBody =
            """
            outer loop
              vertex 0 0 0
              vertex 1 0 0
              vertex 0 1 0
            endloop
            endfacet
            endsolid Model
            """.trimIndent().toByteArray(Charsets.UTF_8)

        val unwrapped = MakerworldCustomizer.unwrapMakerWorldContainer(header + asciiBody)
        val text = unwrapped.toString(Charsets.UTF_8)
        assertTrue(text.startsWith("solid Model\nfacet normal 0 0 0\nouter loop\n"))
        assertEquals(1, Regex("outer loop").findAll(text).count())
    }

    @Test
    fun unwrapMakerWorldContainerPreservesValidBinaryStlWithMwHeader() {
        val buf = ByteBuffer.allocate(84 + 50).order(ByteOrder.LITTLE_ENDIAN)
        val hdr = ByteArray(80)
        "MW 1.0 binary stl".toByteArray().copyInto(hdr)
        buf.put(hdr)
        buf.putInt(1)
        buf.put(ByteArray(50))
        val raw = buf.array()

        val unwrapped = MakerworldCustomizer.unwrapMakerWorldContainer(raw)
        assertEquals(raw.size, unwrapped.size)
        assertEquals(1, ByteBuffer.wrap(unwrapped, 80, 4).order(ByteOrder.LITTLE_ENDIAN).int)
    }

    @Test
    fun writeNormalizedMeshPreserves3mfZipAndConvertsObjToStl() {
        val tmpDir = Files.createTempDirectory("mw_custom_test").toFile()
        try {
            val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x14, 0x00)
            val threeMf = MakerworldCustomizer.writeNormalizedMesh(zipBytes, "custom_123.stl", tmpDir)
            assertEquals("3mf", threeMf.extension)
            assertEquals(zipBytes.size, threeMf.readBytes().size)

            val objBytes = "v 0 0 0\nv 1 0 0\nv 0 1 0\nf 1 2 3\n".toByteArray()
            val stl = MakerworldCustomizer.writeNormalizedMesh(objBytes, "custom_123.obj", tmpDir)
            assertEquals("stl", stl.extension)
            assertEquals(84 + 50, stl.readBytes().size)
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @Test
    fun parseMakerworldParametricHandlesMultiFileAndPrefersScad() {
        val json =
            JSONObject()
                .put("customizedByMakerLab", true)
                .put("categoryByMakerLab", "Parametric Model Maker")
                .put(
                    "designExtension",
                    JSONObject().put(
                        "model_files",
                        JSONArray()
                            .put(
                                JSONObject()
                                    .put("modelName", "default.3mf")
                                    .put("modelType", "3mf")
                                    .put("unikey", "ignored-3mf"),
                            ).put(
                                JSONObject()
                                    .put("modelName", "base.f3d")
                                    .put("modelType", "f3d")
                                    .put("unikey", "key-f3d")
                                    .put("protected", true),
                            ).put(
                                JSONObject()
                                    .put("modelName", "Name_Keychain.scad")
                                    .put("modelType", "scad")
                                    .put("unikey", "key-scad")
                                    .put("protected", false),
                            ),
                    ),
                )

        val info = parseMakerworldParametric("3049453", json)
        assertNotNull(info)
        assertEquals("scad", info!!.modelType)
        assertEquals("key-scad", info.unikey)
        assertEquals(2, info.sourceFiles.size)

        val f3dInfo = info.forFile(info.sourceFiles[1])
        assertEquals("f3d", f3dInfo.modelType)
        assertTrue(f3dInfo.isProtected)
        assertTrue(f3dInfo.mobileUrl.contains("protected=true"))
    }

    @Test
    fun parseJobFromJsonHandlesStlUrlsArrayAndRejectsIncompleteJobs() {
        val pending = """{"id":"1","status":"running"}"""
        assertNull(MakerworldCustomizer.parseJobFromJson(pending))

        val ready =
            """{"id":"42","status":"success","stlUrls":["https://cdn.example.com/keychain.stl"]}"""
        val job = MakerworldCustomizer.parseJobFromJson(ready)
        assertNotNull(job)
        assertEquals("42", job!!.id)
        assertEquals("https://cdn.example.com/keychain.stl", job.stlUrl)
    }

    @Test
    fun buildProtectedOpenScadParamsAppendsDashDOverridesForEditableParams() {
        val scad =
            """
            keychain_text = "Sam";
            text_size = 20;
            ring = false;
            raw_pts = [1, 2];
            """.trimIndent()
        val params = MakerworldCustomizer.buildProtectedOpenScadParams("--backend=manifold", scad)
        assertTrue(params.contains("--backend=manifold"))
        assertTrue(params.contains("-D\nkeychain_text=\"Sam\""))
        assertTrue(params.contains("-D\ntext_size=20"))
        assertTrue(params.contains("-D\nring=false"))
        assertTrue(!params.contains("raw_pts"))
    }

    @Test
    fun writeNormalizedMeshPacksMultiMaterialObjIntoMultiColour3mf() {
        val obj =
            """
            # OpenSCAD obj exporter
            mtllib model.mtl
            v 0 0 0
            v 10 0 0
            v 10 10 0
            v 0 0 2
            v 10 0 2
            v 10 10 2
            usemtl #ffffff
            f 1 2 3
            usemtl #ff1493
            f 4 5 6
            """.trimIndent()
        val mtl =
            """
            newmtl #ffffff
            Kd 1 1 1
            newmtl #ff1493
            Kd 1 0.0784314 0.576471
            """.trimIndent()

        val tmpDir = Files.createTempDirectory("mw_multicolour_test").toFile()
        try {
            val out =
                MakerworldCustomizer.writeNormalizedMesh(
                    raw = obj.toByteArray(Charsets.UTF_8),
                    suggestedName = "custom_3049453.stl",
                    cacheDir = tmpDir,
                    mtlText = mtl,
                )
            assertEquals("3mf", out.extension)

            java.util.zip.ZipFile(out).use { zip ->
                val projJson =
                    JSONObject(
                        zip.getInputStream(zip.getEntry("Metadata/project_settings.config")).readBytes().toString(Charsets.UTF_8),
                    )
                val colors = projJson.getJSONArray("filament_colour")
                assertEquals(2, colors.length())
                assertEquals("#FFFFFF", colors.getString(0))
                assertEquals("#FF1493", colors.getString(1))

                val modelSettings =
                    zip.getInputStream(zip.getEntry("Metadata/model_settings.config")).readBytes().toString(Charsets.UTF_8)
                assertTrue(modelSettings.contains("""<object id="3">"""))
                assertTrue(modelSettings.contains("""<part id="1" subtype="normal_part">"""))
                assertTrue(modelSettings.contains("""<metadata key="extruder" value="2"/>"""))

                val modelXml =
                    zip.getInputStream(zip.getEntry("3D/3dmodel.model")).readBytes().toString(Charsets.UTF_8)
                assertTrue(modelXml.contains("""<component objectid="1""""))
                assertTrue(modelXml.contains("""<component objectid="2""""))
            }
        } finally {
            tmpDir.deleteRecursively()
        }
    }
}
