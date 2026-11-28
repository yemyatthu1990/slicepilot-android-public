package world.yemyat.slicepilot.update

import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import org.junit.Assert.assertEquals
import org.junit.Test

class InAppUpdatePolicyTest {
    private val now = 10_000_000_000L

    private fun snapshot(
        availability: Int = UpdateAvailability.UPDATE_AVAILABLE,
        installStatus: Int = InstallStatus.UNKNOWN,
        priority: Int = 0,
        stalenessDays: Int? = 3,
        immediateAllowed: Boolean = true,
        flexibleAllowed: Boolean = true,
    ) = UpdateCheckSnapshot(
        updateAvailability = availability,
        installStatus = installStatus,
        updatePriority = priority,
        clientVersionStalenessDays = stalenessDays,
        immediateAllowed = immediateAllowed,
        flexibleAllowed = flexibleAllowed,
    )

    @Test
    fun availableNormalUpdateTriggersFlexible() {
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot(),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = 0L,
                promptedThisSession = false,
            )
        assertEquals(UpdateFlowAction.START_FLEXIBLE, action)
    }

    @Test
    fun busySessionDefersNewPromptAndInstallBanner() {
        assertEquals(
            UpdateFlowAction.NONE,
            InAppUpdatePolicy.decide(
                snapshot = snapshot(),
                isBusy = true,
                nowMs = now,
                lastDismissedAtMs = 0L,
                promptedThisSession = false,
            ),
        )
        assertEquals(
            UpdateFlowAction.NONE,
            InAppUpdatePolicy.decide(
                snapshot = snapshot(installStatus = InstallStatus.DOWNLOADED),
                isBusy = true,
                nowMs = now,
                lastDismissedAtMs = 0L,
                promptedThisSession = true,
            ),
        )
    }

    @Test
    fun downloadedUpdateShowsInstallReadyWhenIdleEvenIfAlreadyPrompted() {
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot(installStatus = InstallStatus.DOWNLOADED),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = now - 1_000L,
                promptedThisSession = true,
            )
        assertEquals(UpdateFlowAction.SHOW_INSTALL_READY, action)
    }

    @Test
    fun inProgressImmediateUpdateAlwaysResumes() {
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot(availability = UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS),
                isBusy = true,
                nowMs = now,
                lastDismissedAtMs = now - 1_000L,
                promptedThisSession = true,
            )
        assertEquals(UpdateFlowAction.RESUME_IMMEDIATE, action)
    }

    @Test
    fun dismissCooldownSuppressesFlexiblePromptFor24Hours() {
        val oneHourAgo = now - 60L * 60L * 1000L
        assertEquals(
            UpdateFlowAction.NONE,
            InAppUpdatePolicy.decide(
                snapshot = snapshot(),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = oneHourAgo,
                promptedThisSession = false,
            ),
        )

        val twentyFiveHoursAgo = now - 25L * 60L * 60L * 1000L
        assertEquals(
            UpdateFlowAction.START_FLEXIBLE,
            InAppUpdatePolicy.decide(
                snapshot = snapshot(),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = twentyFiveHoursAgo,
                promptedThisSession = false,
            ),
        )
    }

    @Test
    fun highPriorityUpdateUsesImmediateAndBypassesCooldown() {
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot(priority = 4),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = now - 1_000L,
                promptedThisSession = false,
            )
        assertEquals(UpdateFlowAction.START_IMMEDIATE, action)
    }

    @Test
    fun promptedThisSessionPreventsRepeatPrompt() {
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot(),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = 0L,
                promptedThisSession = true,
            )
        assertEquals(UpdateFlowAction.NONE, action)
    }

    @Test
    fun immediateFallbackTriggersOnlyWhenFlexibleDisallowedAndVeryStale() {
        assertEquals(
            UpdateFlowAction.NONE,
            InAppUpdatePolicy.decide(
                snapshot = snapshot(flexibleAllowed = false, immediateAllowed = true, stalenessDays = 5),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = 0L,
                promptedThisSession = false,
            ),
        )
        assertEquals(
            UpdateFlowAction.START_IMMEDIATE,
            InAppUpdatePolicy.decide(
                snapshot = snapshot(flexibleAllowed = false, immediateAllowed = true, stalenessDays = 14),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = 0L,
                promptedThisSession = false,
            ),
        )
    }

    @Test
    fun backwardsClockSkewDoesNotPermanentlySuppressUpdates() {
        val futureDismiss = now + 60_000L
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot(),
                isBusy = false,
                nowMs = now,
                lastDismissedAtMs = futureDismiss,
                promptedThisSession = false,
            )
        assertEquals(UpdateFlowAction.START_FLEXIBLE, action)
    }
}
