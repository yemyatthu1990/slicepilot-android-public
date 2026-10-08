package world.yemyat.slicepilot.tracking

import android.content.Context
import com.google.firebase.crashlytics.FirebaseCrashlytics

class CrashlyticsEventTracker(context: Context) : EventTracker {
    override val id: String = "firebase-crashlytics"

    private val crashlytics: FirebaseCrashlytics = FirebaseCrashlytics.getInstance()

    override fun trackEvent(event: TrackingEvent) {
        crashlytics.log("event ${event.name} ${event.properties}")
    }

    override fun trackError(error: TrackingError) {
        for ((key, value) in error.properties) {
            when (value) {
                null -> crashlytics.setCustomKey(key, "")
                is Boolean -> crashlytics.setCustomKey(key, value)
                is Int -> crashlytics.setCustomKey(key, value)
                is Long -> crashlytics.setCustomKey(key, value)
                is Float -> crashlytics.setCustomKey(key, value.toDouble())
                is Double -> crashlytics.setCustomKey(key, value)
                else -> crashlytics.setCustomKey(key, value.toString())
            }
        }
        crashlytics.setCustomKey("timestamp_ms", error.timestampMs)
        crashlytics.recordException(error.throwable ?: Exception(error.message))
    }
}
