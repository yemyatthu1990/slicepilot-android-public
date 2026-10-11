package world.yemyat.slicepilot.tracking

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Central registry for event/error trackers.
 *
 * This repository is intentionally provider-agnostic. At startup, the app can
 * register one or more EventTracker implementations. Later, a Firebase tracker,
 * Sentry tracker, or custom backend tracker can be plugged in without changing
 * call sites.
 */
class EventTrackingRepository {

    companion object {
        val shared = EventTrackingRepository()
    }

    private val trackers = CopyOnWriteArrayList<EventTracker>()

    val registeredTrackerIds: List<String>
        get() = trackers.map { it.id }

    fun register(tracker: EventTracker) {
        if (trackers.none { it.id == tracker.id }) {
            trackers.add(tracker)
        }
    }

    fun unregister(trackerId: String) {
        trackers.removeAll { it.id == trackerId }
    }

    fun clear() {
        trackers.clear()
    }

    fun trackEvent(name: String, properties: TrackingProperties = emptyMap()) {
        val event = TrackingEvent(name = name, properties = properties)
        for (tracker in trackers) {
            runCatching { tracker.trackEvent(event) }
        }
    }

    fun trackError(
        message: String,
        throwable: Throwable? = null,
        properties: TrackingProperties = emptyMap(),
    ) {
        val error = TrackingError(
            message = message,
            throwable = throwable,
            properties = properties + ("thread" to Thread.currentThread().name),
        )
        for (tracker in trackers) {
            runCatching { tracker.trackError(error) }
        }
    }
}
