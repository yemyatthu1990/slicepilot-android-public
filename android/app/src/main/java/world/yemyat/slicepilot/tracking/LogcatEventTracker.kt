package world.yemyat.slicepilot.tracking

import android.util.Log

class LogcatEventTracker(
    override val id: String = "logcat",
    private val tag: String = "EventTracking",
) : EventTracker {
    override fun trackEvent(event: TrackingEvent) {
        Log.i(tag, "event ${event.name} ${event.properties}")
    }

    override fun trackError(error: TrackingError) {
        Log.e(tag, "error ${error.message} ${error.properties}", error.throwable)
    }
}
