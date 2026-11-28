package world.yemyat.slicepilot.tracking

/**
 * App-facing entry point for event and error reporting.
 *
 * UI and domain code should use this object instead of referring to a concrete
 * tracking provider. The notifier forwards everything to EventTrackingRepository,
 * which fans the data out to registered trackers.
 */
object EventNotifier {

    private val repository: EventTrackingRepository = EventTrackingRepository.shared

    val registeredTrackerIds: List<String>
        get() = repository.registeredTrackerIds

    fun registerTracker(tracker: EventTracker) {
        repository.register(tracker)
    }

    fun unregisterTracker(trackerId: String) {
        repository.unregister(trackerId)
    }

    fun trackEvent(name: String, properties: TrackingProperties = emptyMap()) {
        repository.trackEvent(name, properties)
    }

    /** Logs a GA4-standard `screen_view` event for single-activity Compose transitions. */
    fun trackScreen(
        screenName: String,
        screenClass: String = screenName,
    ) {
        repository.trackEvent(
            "screen_view",
            mapOf(
                "screen_name" to screenName,
                "screen_class" to screenClass,
            ),
        )
    }

    fun trackError(
        message: String,
        throwable: Throwable? = null,
        properties: TrackingProperties = emptyMap(),
    ) {
        repository.trackError(message, throwable, properties)
    }
}
