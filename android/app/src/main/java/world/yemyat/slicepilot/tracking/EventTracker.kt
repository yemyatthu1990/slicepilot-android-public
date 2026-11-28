package world.yemyat.slicepilot.tracking

typealias TrackingProperties = Map<String, Any?>

data class TrackingEvent(
    val name: String,
    val properties: TrackingProperties = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
)

data class TrackingError(
    val message: String,
    val throwable: Throwable? = null,
    val properties: TrackingProperties = emptyMap(),
    val timestampMs: Long = System.currentTimeMillis(),
)

/**
 * A pluggable destination for app events and errors.
 *
 * Concrete implementations can forward data to Firebase, Sentry, Datadog, a local
 * debug console, or any other provider. The app only talks to EventNotifier and
 * EventTrackingRepository, so providers can be added without touching UI code.
 */
interface EventTracker {
    val id: String

    fun trackEvent(event: TrackingEvent)

    fun trackError(error: TrackingError)
}
