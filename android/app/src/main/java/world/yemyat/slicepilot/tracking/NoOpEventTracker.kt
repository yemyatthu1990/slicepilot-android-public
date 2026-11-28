package world.yemyat.slicepilot.tracking

class NoOpEventTracker(
    override val id: String = "noop",
) : EventTracker {
    override fun trackEvent(event: TrackingEvent) = Unit

    override fun trackError(error: TrackingError) = Unit
}
