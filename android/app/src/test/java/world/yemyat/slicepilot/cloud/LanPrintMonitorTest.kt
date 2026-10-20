package world.yemyat.slicepilot.cloud

import org.junit.Assert.assertEquals
import org.junit.Test
import org.json.JSONObject

class LanPrintMonitorTest {

    private val fullStatus = JSONObject(
        """
        {
            "error_code": 0,
            "machine_status": { "status": 2, "sub_status": 2075, "progress": 45 },
            "print_status": {
                "filename": "benchy.gcode",
                "current_layer": 225,
                "total_layer": 500,
                "print_duration": 3600,
                "total_duration": 8000,
                "remaining_time_sec": 4400,
                "progress": 45,
                "state": "printing"
            },
            "extruder": { "temperature": 215.0, "target": 220 },
            "heater_bed": { "temperature": 58.5, "target": 60 },
            "ztemperature_sensor": { "temperature": 33.0 },
            "fans": {
                "fan": { "speed": 255, "rpm": 5000 },
                "aux_fan": { "speed": 178, "rpm": 3500 },
                "box_fan": { "speed": 25, "rpm": 800 }
            },
            "led": { "status": 1 },
            "gcode_move_inf": { "x": 88.1, "y": 139.9, "z": 1.6, "speed": 9019, "speed_mode": 2 }
        }
        """.trimIndent()
    )

    @Test
    fun parseFullStatus() {
        val s = LanPrintMonitor.mergePrintStatus(PrintStatus(), fullStatus)
        assertEquals(PrintJobState.PRINTING, s.state)
        assertEquals("benchy.gcode", s.filename)
        assertEquals(45, s.progress)
        assertEquals(225, s.currentLayer)
        assertEquals(500, s.totalLayer)
        assertEquals(3600, s.elapsedSec)
        assertEquals(4400, s.remainingSec)
        assertEquals(215f, s.nozzleTemp, 0.001f)
        assertEquals(220f, s.nozzleTarget, 0.001f)
        assertEquals(58.5f, s.bedTemp, 0.001f)
        assertEquals(60f, s.bedTarget, 0.001f)
        assertEquals(2, s.speedMode)
        assertEquals(150, s.speedPercent)
        assertEquals(33f, s.chamberTemp, 0.001f)
        assertEquals(255, s.partFanSpeed)
        assertEquals(178, s.auxFanSpeed)
        assertEquals(25, s.boxFanSpeed)
        assertEquals(true, s.lightOn)
        assertEquals(true, s.live)
    }

    @Test
    fun deltaUpdateKeepsUnseenFields() {
        val base = LanPrintMonitor.mergePrintStatus(PrintStatus(), fullStatus)
        val delta = JSONObject(
            """
            {
                "error_code": 0,
                "machine_status": { "progress": 46 },
                "print_status": { "current_layer": 230, "print_duration": 3650 }
            }
            """.trimIndent()
        )
        val s = LanPrintMonitor.mergePrintStatus(base, delta)
        assertEquals(46, s.progress)
        assertEquals(230, s.currentLayer)
        assertEquals(3650, s.elapsedSec)
        // Fields absent from the delta keep their previous values.
        assertEquals(500, s.totalLayer)
        assertEquals("benchy.gcode", s.filename)
        assertEquals(PrintJobState.PRINTING, s.state)
        assertEquals(215f, s.nozzleTemp, 0.001f)
        assertEquals(4400, s.remainingSec)
    }

    @Test
    fun pausedSubStatusMapsToPaused() {
        val delta = JSONObject(
            """{ "machine_status": { "status": 2, "sub_status": 2502 } }"""
        )
        val s = LanPrintMonitor.mergePrintStatus(
            LanPrintMonitor.mergePrintStatus(PrintStatus(), fullStatus),
            delta,
        )
        assertEquals(PrintJobState.PAUSED, s.state)
    }

    @Test
    fun cancelledStateMapsToStopped() {
        val delta = JSONObject(
            """{ "print_status": { "state": "cancelled" } }"""
        )
        val s = LanPrintMonitor.mergePrintStatus(
            LanPrintMonitor.mergePrintStatus(PrintStatus(), fullStatus),
            delta,
        )
        assertEquals(PrintJobState.STOPPED, s.state)
        assertEquals(true, s.state.isTerminal)
    }

    @Test
    fun completedSubStatusMapsToCompleted() {
        val delta = JSONObject(
            """{ "machine_status": { "status": 2, "sub_status": 2077 } }"""
        )
        val s = LanPrintMonitor.mergePrintStatus(
            LanPrintMonitor.mergePrintStatus(PrintStatus(), fullStatus),
            delta,
        )
        assertEquals(PrintJobState.COMPLETED, s.state)
    }

    @Test
    fun remainingTimeDerivedFromDurations() {
        val delta = JSONObject(
            """{ "print_status": { "total_duration": 8000, "print_duration": 3600 } }"""
        )
        val s = LanPrintMonitor.mergePrintStatus(PrintStatus(), delta)
        assertEquals(4400, s.remainingSec)
    }

    @Test
    fun wrappedReportValueDeltaIsUnpacked() {
        val delta = JSONObject(
            """{ "reportValue": "{\"print_status\":{\"progress\":80,\"state\":\"paused\"}}" }"""
        )
        val s = LanPrintMonitor.mergePrintStatus(
            LanPrintMonitor.mergePrintStatus(PrintStatus(), fullStatus),
            delta,
        )
        assertEquals(80, s.progress)
        assertEquals(PrintJobState.PAUSED, s.state)
        // Unrelated fields survive the merge.
        assertEquals(500, s.totalLayer)
    }

    @Test
    fun mapJobStatePrefersPrintStateString() {
        assertEquals(
            PrintJobState.PAUSED,
            LanPrintMonitor.mapJobState("paused", 2, 2075, PrintJobState.PRINTING),
        )
        assertEquals(
            PrintJobState.ERROR,
            LanPrintMonitor.mapJobState(null, 14, null, PrintJobState.PRINTING),
        )
        assertEquals(
            PrintJobState.IDLE,
            LanPrintMonitor.mapJobState(null, 1, null, PrintJobState.PRINTING),
        )
        // Idle printer at the main screen is Idle, not a stuck terminal state.
        assertEquals(
            PrintJobState.IDLE,
            LanPrintMonitor.mapJobState("standby", 1, null, PrintJobState.CONNECTING),
        )
    }

    @Test
    fun explicitIdleReleasesLatchedTerminalState() {
        // A latched STOPPED must not show "Print stopped" forever: once the printer
        // reports idle ("standby" / machine status 1), the state unlatches to Idle.
        assertEquals(
            PrintJobState.IDLE,
            LanPrintMonitor.mapJobState("standby", null, null, PrintJobState.STOPPED),
        )
        assertEquals(
            PrintJobState.IDLE,
            LanPrintMonitor.mapJobState(null, 1, null, PrintJobState.COMPLETED),
        )
        // CC2 idle/manual-ready machine status 26 (the fan-control gate in Matrix).
        assertEquals(
            PrintJobState.IDLE,
            LanPrintMonitor.mapJobState(null, 26, 2600, PrintJobState.STOPPED),
        )
        // Late printing echoes still latch (the anti-flicker rule is unchanged).
        assertEquals(
            PrintJobState.STOPPED,
            LanPrintMonitor.mapJobState("printing", null, null, PrintJobState.STOPPED),
        )
    }

    @Test
    fun stopStateLatchesAgainstLatePrintingStatus() {
        // Observed on-device: after 1022 the CC2 keeps streaming printing status for seconds,
        // and the 30 s full-status resync can deliver stale printing after STOPPED. The UI
        // must not flicker back to Printing.
        assertEquals(
            PrintJobState.STOPPING,
            LanPrintMonitor.mapJobState(null, 2, 2075, PrintJobState.STOPPING),
        )
        assertEquals(
            PrintJobState.STOPPING,
            LanPrintMonitor.mapJobState("printing", null, null, PrintJobState.STOPPING),
        )
        assertEquals(
            PrintJobState.STOPPED,
            LanPrintMonitor.mapJobState(null, 2, 2075, PrintJobState.STOPPED),
        )
        assertEquals(
            PrintJobState.STOPPED,
            LanPrintMonitor.mapJobState("printing", null, null, PrintJobState.STOPPED),
        )
        assertEquals(
            PrintJobState.COMPLETED,
            LanPrintMonitor.mapJobState(null, 2, 2075, PrintJobState.COMPLETED),
        )
        // The real stop confirmation still lands.
        assertEquals(
            PrintJobState.STOPPED,
            LanPrintMonitor.mapJobState(null, 3, 2504, PrintJobState.STOPPING),
        )
        // Non-terminal transitions keep working (resume: PAUSED -> printing).
        assertEquals(
            PrintJobState.PRINTING,
            LanPrintMonitor.mapJobState(null, 2, 2075, PrintJobState.PAUSED),
        )
    }

    @Test
    fun remainingEstimateBlendsWithObservedRate() {
        // The printer's linear estimate (600 s left at 50%) is too short when the print has
        // already taken an hour: the observed rate estimate (3600 s) wins.
        val json = JSONObject(
            """{"print_status":{"progress":50,"print_duration":3600,"remaining_time_sec":600}}""",
        )
        assertEquals(3600, LanPrintMonitor.mergePrintStatus(PrintStatus(), json).remainingSec)
        // A longer printer estimate (endgame tower/purge moves) is preserved.
        val json2 = JSONObject(
            """{"print_status":{"progress":50,"print_duration":600,"remaining_time_sec":3000}}""",
        )
        assertEquals(3000, LanPrintMonitor.mergePrintStatus(PrintStatus(), json2).remainingSec)
        // Below 5% progress the observed estimate is too noisy to use.
        val json3 = JSONObject(
            """{"print_status":{"progress":2,"print_duration":300,"remaining_time_sec":9000}}""",
        )
        assertEquals(9000, LanPrintMonitor.mergePrintStatus(PrintStatus(), json3).remainingSec)
    }

    @Test
    fun cc1PrintStateMapping() {
        assertEquals(PrintJobState.PRINTING, LanPrintMonitorCC1.mapCc1PrintState(13, PrintJobState.IDLE))
        assertEquals(PrintJobState.PAUSED, LanPrintMonitorCC1.mapCc1PrintState(6, PrintJobState.PRINTING))
        assertEquals(PrintJobState.STOPPED, LanPrintMonitorCC1.mapCc1PrintState(8, PrintJobState.PRINTING))
        assertEquals(PrintJobState.COMPLETED, LanPrintMonitorCC1.mapCc1PrintState(9, PrintJobState.PRINTING))
        assertEquals(PrintJobState.ERROR, LanPrintMonitorCC1.mapCc1PrintState(14, PrintJobState.PRINTING))
        // Terminal states latch: late status frames must not resurrect the job…
        assertEquals(PrintJobState.STOPPED, LanPrintMonitorCC1.mapCc1PrintState(13, PrintJobState.STOPPED))
        // …while explicit idle (0) releases the latch (printer back at its main screen).
        assertEquals(PrintJobState.IDLE, LanPrintMonitorCC1.mapCc1PrintState(0, PrintJobState.STOPPED))
        // Resin-only and unknown codes keep the previous state.
        assertEquals(null, LanPrintMonitorCC1.mapCc1PrintState(3, PrintJobState.PRINTING))
    }

    @Test
    fun cc1StatusParsesPrintingPayload() {
        val json = JSONObject(
            """{
              "TempOfHotbed": 50.2, "TempTargetHotbed": 50,
              "TempOfNozzle": 212.5, "TempTargetNozzle": 215,
              "TempOfBox": 29.3,
              "CurrentFanSpeed": {"ModelFan": 58, "AuxiliaryFan": 0, "BoxFan": 68},
              "LightStatus": {"SecondLight": 1},
              "PrintInfo": {
                "Status": 13, "CurrentLayer": 34, "TotalLayer": 438,
                "CurrentTicks": 365, "TotalTicks": 4504,
                "Filename": "benchy.gcode", "PrintSpeedPct": 100, "Progress": 9
              }
            }""".trimIndent(),
        )
        val s = LanPrintMonitorCC1.mapCc1Status(PrintStatus(), json)
        assertEquals(PrintJobState.PRINTING, s.state)
        assertEquals("benchy.gcode", s.filename)
        assertEquals(34, s.currentLayer)
        assertEquals(438, s.totalLayer)
        assertEquals(365, s.elapsedSec)
        assertEquals(9, s.progress)
        assertEquals(212.5f, s.nozzleTemp, 0.01f)
        assertEquals(215f, s.nozzleTarget, 0.01f)
        assertEquals(50.2f, s.bedTemp, 0.01f)
        assertEquals(29.3f, s.chamberTemp, 0.01f)
        assertEquals(58 * 255 / 100, s.partFanSpeed)
        assertEquals(68 * 255 / 100, s.boxFanSpeed)
        assertEquals(true, s.lightOn)
        assertEquals(4504 - 365, s.remainingSec)
        assertEquals(true, s.live)
    }

    @Test
    fun cc1StatusHandlesPairFormTemps() {
        // Older firmware: TempOfNozzle is [target, actual].
        val json = JSONObject("""{"TempOfNozzle": [215.0, 210.5]}""")
        val s = LanPrintMonitorCC1.mapCc1Status(PrintStatus(), json)
        assertEquals(210.5f, s.nozzleTemp, 0.01f)
        assertEquals(215f, s.nozzleTarget, 0.01f)
    }
}
