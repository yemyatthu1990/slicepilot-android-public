package world.yemyat.slicepilot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import world.yemyat.slicepilot.PrintNotificationTracker.Action
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.cloud.PrintStatus

class PrintNotificationTrackerTest {

    @Test
    fun printingToIdleFinishesSessionAndNeverResurrectsOnCoolingTicks() {
        val tracker = PrintNotificationTracker()
        tracker.begin(justStarted = true, nowMs = 0L)

        // Active print updates once; temperature-only ticks with identical progress are ignored.
        val printing = PrintStatus(
            state = PrintJobState.PRINTING,
            progress = 99,
            currentLayer = 240,
            totalLayer = 240,
            remainingSec = 60,
            nozzleTemp = 215f,
            live = true,
        )
        assertEquals(Action.Update, tracker.onStatus("Centauri Carbon 2", printing, 10_000L))
        assertEquals(
            Action.Ignore,
            tracker.onStatus("Centauri Carbon 2", printing.copy(nozzleTemp = 214.5f), 11_000L),
        )

        // Printer finishes and jumps straight to IDLE (missed COMPLETED frame).
        val idleCooling1 = PrintStatus(state = PrintJobState.IDLE, nozzleTemp = 190f, live = true)
        assertEquals(
            Action.Finish("Print completed"),
            tracker.onStatus("Centauri Carbon 2", idleCooling1, 15_000L),
        )

        // Subsequent cooling / poll ticks must NOT re-post any notification.
        assertEquals(
            Action.Ignore,
            tracker.onStatus("Centauri Carbon 2", idleCooling1.copy(nozzleTemp = 170f), 20_000L),
        )
        assertEquals(
            Action.Ignore,
            tracker.onStatus("Centauri Carbon 2", idleCooling1.copy(nozzleTemp = 150f), 30_000L),
        )
    }

    @Test
    fun stoppingToIdleFinishesAsStopped() {
        val tracker = PrintNotificationTracker()
        tracker.begin(justStarted = true, nowMs = 0L)

        assertEquals(
            Action.Update,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.STOPPING, live = true), 5_000L),
        )
        assertEquals(
            Action.Finish("Print stopped"),
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.IDLE, live = true), 7_000L),
        )
    }

    @Test
    fun terminalStateFinishesDirectlyWithoutExtraUpdate() {
        val tracker = PrintNotificationTracker()
        tracker.begin(justStarted = true, nowMs = 0L)

        assertEquals(
            Action.Update,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.PRINTING, progress = 50, live = true), 2_000L),
        )
        assertEquals(
            Action.Finish("Print completed"),
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.COMPLETED, progress = 100, live = true), 5_000L),
        )
        assertEquals(
            Action.Ignore,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.COMPLETED, progress = 100, live = true), 6_000L),
        )
    }

    @Test
    fun restoredSessionSeeingIdleClearsWithoutPostingStartingPrint() {
        val tracker = PrintNotificationTracker()
        tracker.begin(justStarted = false, nowMs = 0L)

        // CONNECTING on restore does not flash a notification.
        assertEquals(
            Action.Ignore,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.CONNECTING, live = false), 500L),
        )
        // Printer is already IDLE: clear session quietly.
        assertEquals(
            Action.Clear,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.IDLE, live = true), 1_000L),
        )
        assertEquals(
            Action.Ignore,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.IDLE, nozzleTemp = 30f, live = true), 2_000L),
        )
    }

    @Test
    fun freshStartToleratesBriefIdleThenClearsIfNeverStarts() {
        val tracker = PrintNotificationTracker(graceMs = 45_000L)
        tracker.begin(justStarted = true, nowMs = 1_000L)

        // Printer still reports IDLE briefly right after start command.
        assertEquals(
            Action.Ignore,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.IDLE, live = true), 5_000L),
        )
        // If it stays IDLE past the startup grace window, clear instead of hanging forever.
        assertEquals(
            Action.Clear,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.IDLE, live = true), 50_000L),
        )
    }

    @Test
    fun transientReconnectDoesNotRegressActivePrintToStarting() {
        val tracker = PrintNotificationTracker()
        tracker.begin(justStarted = true, nowMs = 0L)

        assertEquals(
            Action.Update,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.PRINTING, progress = 40, live = true), 5_000L),
        )
        assertEquals(
            Action.Ignore,
            tracker.onStatus("CC2", PrintStatus(state = PrintJobState.CONNECTING, live = false), 8_000L),
        )
    }

    @Test
    fun formatBodyNeverMapsIdleToStartingPrint() {
        val idleBody = PrintForegroundService.formatBody(PrintJobState.IDLE.name, 0, 0, 0, -1)
        assertEquals("Printer idle", idleBody)
        assertFalse(idleBody.contains("Starting", ignoreCase = true))

        val connectingBody = PrintForegroundService.formatBody(PrintJobState.CONNECTING.name, 0, 0, 0, -1)
        assertEquals("Connecting to printer…", connectingBody)
    }
}
