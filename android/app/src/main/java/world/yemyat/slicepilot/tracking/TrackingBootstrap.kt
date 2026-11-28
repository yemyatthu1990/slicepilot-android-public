package world.yemyat.slicepilot.tracking

import android.content.Context
import android.provider.Settings
import com.google.firebase.analytics.FirebaseAnalytics
import world.yemyat.slicepilot.BuildConfig

object TrackingBootstrap {
    @Volatile
    private var initialized = false

    internal fun isTestLabDevice(testLabSetting: String?): Boolean =
        testLabSetting.equals("true", ignoreCase = true)

    internal fun shouldEnableAnalytics(
        isDebug: Boolean,
        isTestLab: Boolean,
    ): Boolean = !isDebug && !isTestLab

    fun ensure(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            if (BuildConfig.DEBUG) {
                EventNotifier.registerTracker(LogcatEventTracker())
            }
            val testLabSetting =
                runCatching {
                    Settings.System.getString(appContext.contentResolver, "firebase.test.lab")
                }.getOrNull()
            val analyticsEnabled =
                shouldEnableAnalytics(
                    isDebug = BuildConfig.DEBUG,
                    isTestLab = isTestLabDevice(testLabSetting),
                )
            // Explicitly disable SDK-level collection for debug builds and Play Pre-Launch /
            // Firebase Test Lab bots so automatic session_start / first_open events don't
            // pollute production GA4 metrics.
            runCatching {
                FirebaseAnalytics.getInstance(appContext).setAnalyticsCollectionEnabled(analyticsEnabled)
            }
            if (analyticsEnabled) {
                EventNotifier.registerTracker(FirebaseEventTracker(appContext))
            }
            EventNotifier.registerTracker(CrashlyticsEventTracker(appContext))
            initialized = true
        }
    }
}
