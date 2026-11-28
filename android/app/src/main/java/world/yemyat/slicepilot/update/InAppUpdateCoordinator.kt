package world.yemyat.slicepilot.update

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManager
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.InstallStateUpdatedListener
import com.google.android.play.core.install.model.ActivityResult
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.InstallStatus
import com.google.android.play.core.install.model.UpdateAvailability
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import world.yemyat.slicepilot.tracking.EventNotifier

internal enum class UpdateFlowAction {
    NONE,
    START_IMMEDIATE,
    START_FLEXIBLE,
    RESUME_IMMEDIATE,
    SHOW_INSTALL_READY,
}

internal data class UpdateCheckSnapshot(
    val updateAvailability: Int,
    val installStatus: Int,
    val updatePriority: Int,
    val clientVersionStalenessDays: Int?,
    val immediateAllowed: Boolean,
    val flexibleAllowed: Boolean,
)

/**
 * Pure decision policy for Google Play In-App Updates.
 *
 * Keeps slicing, printing, and fullscreen camera sessions uninterrupted while nudging users on
 * older releases onto the latest build via flexible updates (or immediate updates when a release
 * is explicitly flagged critical with Play priority >= 4).
 */
internal object InAppUpdatePolicy {
    const val DEFAULT_DISMISS_COOLDOWN_MS: Long = 24L * 60L * 60L * 1000L
    const val IMMEDIATE_PRIORITY_THRESHOLD: Int = 4
    const val IMMEDIATE_FALLBACK_STALENESS_DAYS: Int = 14

    fun decide(
        snapshot: UpdateCheckSnapshot,
        isBusy: Boolean,
        nowMs: Long,
        lastDismissedAtMs: Long,
        promptedThisSession: Boolean,
        dismissCooldownMs: Long = DEFAULT_DISMISS_COOLDOWN_MS,
    ): UpdateFlowAction {
        if (snapshot.installStatus == InstallStatus.DOWNLOADED) {
            return if (isBusy) UpdateFlowAction.NONE else UpdateFlowAction.SHOW_INSTALL_READY
        }

        if (snapshot.updateAvailability == UpdateAvailability.DEVELOPER_TRIGGERED_UPDATE_IN_PROGRESS) {
            return if (snapshot.immediateAllowed) {
                UpdateFlowAction.RESUME_IMMEDIATE
            } else {
                UpdateFlowAction.NONE
            }
        }

        if (isBusy || promptedThisSession) return UpdateFlowAction.NONE
        if (snapshot.updateAvailability != UpdateAvailability.UPDATE_AVAILABLE) return UpdateFlowAction.NONE

        if (snapshot.updatePriority >= IMMEDIATE_PRIORITY_THRESHOLD && snapshot.immediateAllowed) {
            return UpdateFlowAction.START_IMMEDIATE
        }

        if (lastDismissedAtMs > 0L) {
            val elapsed = nowMs - lastDismissedAtMs
            if (elapsed in 0L until dismissCooldownMs) {
                return UpdateFlowAction.NONE
            }
        }

        if (snapshot.flexibleAllowed) return UpdateFlowAction.START_FLEXIBLE

        val staleness = snapshot.clientVersionStalenessDays ?: 0
        if (snapshot.immediateAllowed && staleness >= IMMEDIATE_FALLBACK_STALENESS_DAYS) {
            return UpdateFlowAction.START_IMMEDIATE
        }

        return UpdateFlowAction.NONE
    }
}

/**
 * Owns the Google Play [AppUpdateManager] lifecycle for [world.yemyat.slicepilot.MainActivity].
 *
 * Fails silently on debug/sideloaded installs and devices without Google Play Services so
 * non-Play runs are never impacted.
 */
class InAppUpdateCoordinator(
    context: Context,
    private val prefs: SharedPreferences,
    private val launcher: ActivityResultLauncher<IntentSenderRequest>,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {
    private val appUpdateManager: AppUpdateManager =
        AppUpdateManagerFactory.create(context.applicationContext)

    private val _updateReadyToInstall = MutableStateFlow(false)
    val updateReadyToInstall: StateFlow<Boolean> = _updateReadyToInstall.asStateFlow()

    private var promptedThisSession = false
    private var activeUpdateType: Int? = null
    private var installPromptDismissedThisSession = false

    private val installStateListener =
        InstallStateUpdatedListener { state ->
            when (state.installStatus()) {
                InstallStatus.DOWNLOADED -> {
                    installPromptDismissedThisSession = false
                    _updateReadyToInstall.value = true
                    EventNotifier.trackEvent("app_update_downloaded")
                }

                InstallStatus.FAILED -> {
                    EventNotifier.trackEvent(
                        "app_update_failed",
                        mapOf("error_code" to state.installErrorCode()),
                    )
                }

                InstallStatus.CANCELED -> {
                    EventNotifier.trackEvent("app_update_canceled")
                }

                else -> Unit
            }
        }

    init {
        appUpdateManager.registerListener(installStateListener)
    }

    fun checkForUpdate(isBusy: Boolean) {
        appUpdateManager.appUpdateInfo
            .addOnSuccessListener { info -> handleUpdateInfo(info, isBusy) }
            .addOnFailureListener { e ->
                // Expected on sideloaded/debug builds or devices without Play Store.
                Log.d(TAG, "In-app update check unavailable: ${e.message}")
            }
    }

    private fun handleUpdateInfo(
        info: AppUpdateInfo,
        isBusy: Boolean,
    ) {
        val snapshot =
            UpdateCheckSnapshot(
                updateAvailability = info.updateAvailability(),
                installStatus = info.installStatus(),
                updatePriority = info.updatePriority(),
                clientVersionStalenessDays = info.clientVersionStalenessDays(),
                immediateAllowed = info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE),
                flexibleAllowed = info.isUpdateTypeAllowed(AppUpdateType.FLEXIBLE),
            )
        val lastDismissed = prefs.getLong(PREF_LAST_DISMISSED_MS, 0L)
        val action =
            InAppUpdatePolicy.decide(
                snapshot = snapshot,
                isBusy = isBusy,
                nowMs = nowMs(),
                lastDismissedAtMs = lastDismissed,
                promptedThisSession = promptedThisSession,
            )

        when (action) {
            UpdateFlowAction.NONE -> Unit

            UpdateFlowAction.SHOW_INSTALL_READY -> {
                if (!installPromptDismissedThisSession) {
                    _updateReadyToInstall.value = true
                }
            }

            UpdateFlowAction.START_FLEXIBLE -> {
                launchUpdateFlow(info, AppUpdateType.FLEXIBLE, resuming = false)
            }

            UpdateFlowAction.START_IMMEDIATE -> {
                launchUpdateFlow(info, AppUpdateType.IMMEDIATE, resuming = false)
            }

            UpdateFlowAction.RESUME_IMMEDIATE -> {
                launchUpdateFlow(info, AppUpdateType.IMMEDIATE, resuming = true)
            }
        }
    }

    private fun launchUpdateFlow(
        info: AppUpdateInfo,
        updateType: Int,
        resuming: Boolean,
    ) {
        val mode = if (updateType == AppUpdateType.IMMEDIATE) "immediate" else "flexible"
        promptedThisSession = true
        activeUpdateType = updateType
        EventNotifier.trackEvent(
            if (resuming) "app_update_resumed" else "app_update_prompted",
            mapOf(
                "mode" to mode,
                "available_version_code" to info.availableVersionCode(),
                "staleness_days" to (info.clientVersionStalenessDays() ?: -1),
                "priority" to info.updatePriority(),
            ),
        )
        runCatching {
            appUpdateManager.startUpdateFlowForResult(
                info,
                launcher,
                AppUpdateOptions.newBuilder(updateType).build(),
            )
        }.onFailure { e ->
            Log.w(TAG, "Failed to launch $mode in-app update flow", e)
            EventNotifier.trackEvent(
                "app_update_failed",
                mapOf("mode" to mode, "reason" to (e.message ?: e.javaClass.simpleName)),
            )
        }
    }

    fun onActivityResult(resultCode: Int) {
        val mode = if (activeUpdateType == AppUpdateType.IMMEDIATE) "immediate" else "flexible"
        when (resultCode) {
            Activity.RESULT_OK -> {
                EventNotifier.trackEvent("app_update_accepted", mapOf("mode" to mode))
            }

            Activity.RESULT_CANCELED -> {
                if (activeUpdateType == AppUpdateType.FLEXIBLE) {
                    prefs.edit().putLong(PREF_LAST_DISMISSED_MS, nowMs()).apply()
                }
                EventNotifier.trackEvent("app_update_dismissed", mapOf("mode" to mode))
            }

            ActivityResult.RESULT_IN_APP_UPDATE_FAILED -> {
                EventNotifier.trackEvent(
                    "app_update_failed",
                    mapOf("mode" to mode, "result_code" to resultCode),
                )
            }
        }
        activeUpdateType = null
    }

    fun completeUpdate() {
        _updateReadyToInstall.value = false
        installPromptDismissedThisSession = true
        EventNotifier.trackEvent("app_update_install_triggered")
        runCatching {
            appUpdateManager.completeUpdate()
        }.onFailure { e ->
            Log.w(TAG, "completeUpdate() failed", e)
        }
    }

    fun dismissInstallPrompt() {
        _updateReadyToInstall.value = false
        installPromptDismissedThisSession = true
    }

    fun dispose() {
        appUpdateManager.unregisterListener(installStateListener)
    }

    companion object {
        private const val TAG = "InAppUpdate"
        internal const val PREF_LAST_DISMISSED_MS = "in_app_update_dismissed_at_ms"
    }
}
