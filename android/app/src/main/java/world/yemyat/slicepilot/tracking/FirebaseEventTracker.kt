package world.yemyat.slicepilot.tracking

import android.content.Context
import android.os.Bundle
import androidx.core.os.bundleOf
import com.google.firebase.analytics.FirebaseAnalytics

class FirebaseEventTracker(context: Context) : EventTracker {
    override val id: String = "firebase-analytics"

    private val analytics: FirebaseAnalytics = FirebaseAnalytics.getInstance(context)

    override fun trackEvent(event: TrackingEvent) {
        val params = bundleOf()
        putProperties(params, event.properties)
        params.putLong("timestamp_ms", event.timestampMs)
        analytics.logEvent(sanitizeEventName(event.name), params)
    }

    override fun trackError(error: TrackingError) {
        val params = bundleOf(
            "error_message" to error.message,
            "exception_class" to error.throwable?.javaClass?.name.orEmpty(),
            "timestamp_ms" to error.timestampMs,
        )
        putProperties(params, error.properties)
        analytics.logEvent("app_error", params)
    }

    private fun putProperties(bundle: Bundle, properties: TrackingProperties) {
        for ((key, value) in properties) {
            when (value) {
                null -> bundle.putString(key, null)
                is String -> bundle.putString(key, value)
                is Boolean -> bundle.putBoolean(key, value)
                is Int -> bundle.putInt(key, value)
                is Long -> bundle.putLong(key, value)
                is Float -> bundle.putDouble(key, value.toDouble())
                is Double -> bundle.putDouble(key, value)
                else -> bundle.putString(key, value.toString())
            }
        }
    }

    private fun sanitizeEventName(name: String): String {
        val cleaned = name.replace(Regex("[^A-Za-z0-9_]"), "_")
        return cleaned.take(40).ifBlank { "event" }
    }
}
