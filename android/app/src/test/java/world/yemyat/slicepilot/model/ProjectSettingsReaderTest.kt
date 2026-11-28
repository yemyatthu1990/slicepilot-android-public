package world.yemyat.slicepilot.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProjectSettingsReaderTest {

    @Test
    fun noBrimWithNonZeroBrimWidthParsesAsNone() {
        val settings = ProjectSettingsReader.parse(
            """
            {
              "brim_type": "no_brim",
              "brim_width": "5",
              "raft_layers": "0",
              "skirt_loops": "0",
              "skirt_height": "1"
            }
            """.trimIndent(),
        )
        assertNotNull(settings)
        assertEquals("none", settings!!.adhesion)
    }

    @Test
    fun autoBrimWithNonZeroBrimWidthParsesAsAuto() {
        val settings = ProjectSettingsReader.parse(
            """
            {
              "brim_type": "auto_brim",
              "brim_width": "5",
              "raft_layers": "0",
              "skirt_loops": "0",
              "skirt_height": "1"
            }
            """.trimIndent(),
        )
        assertNotNull(settings)
        assertEquals("auto", settings!!.adhesion)
    }

    @Test
    fun outerOnlyBrimParsesAsBrim() {
        val settings = ProjectSettingsReader.parse(
            """
            {
              "brim_type": "outer_only",
              "brim_width": "6",
              "raft_layers": "0",
              "skirt_loops": "0",
              "skirt_height": "1"
            }
            """.trimIndent(),
        )
        assertNotNull(settings)
        assertEquals("brim", settings!!.adhesion)
        assertEquals(6f, settings.brimWidth)
    }

    @Test
    fun skirtRequiresNonZeroSkirtLoopsWhenPresent() {
        val settings = ProjectSettingsReader.parse(
            """
            {
              "brim_type": "no_brim",
              "brim_width": "5",
              "raft_layers": "0",
              "skirt_loops": "2",
              "skirt_height": "1"
            }
            """.trimIndent(),
        )
        assertNotNull(settings)
        assertEquals("skirt", settings!!.adhesion)
    }

    @Test
    fun raftTakesPrecedence() {
        val settings = ProjectSettingsReader.parse(
            """
            {
              "brim_type": "auto_brim",
              "brim_width": "5",
              "raft_layers": "2",
              "skirt_loops": "0",
              "skirt_height": "1"
            }
            """.trimIndent(),
        )
        assertNotNull(settings)
        assertEquals("raft", settings!!.adhesion)
    }

    @Test
    fun legacyConfigWithoutBrimTypeStillInfersAdhesion() {
        val none = ProjectSettingsReader.parse(
            """{"raft_layers": "0", "brim_width": "0", "skirt_height": "0"}""",
        )
        assertEquals("none", none!!.adhesion)

        val brim = ProjectSettingsReader.parse(
            """{"raft_layers": "0", "brim_width": "4", "skirt_height": "0"}""",
        )
        assertEquals("brim", brim!!.adhesion)
    }
}
