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
        crashlytics.recordException(error.throwable ?: exceptionFor(error.message))
    }

    internal companion object {
        internal class PrintFailedException(message: String) : Exception(message)

        internal class HttpUploadFailedException(message: String) : Exception(message)

        internal class CloudLoginFailedException(message: String) : Exception(message)

        internal class PrintControlFailedException(message: String) : Exception(message)

        internal class PrinterPairFailedException(message: String) : Exception(message)

        internal class SliceFailedException(message: String) : Exception(message)

        internal class LanPrintFailedException(message: String) : Exception(message)

        internal class CameraStartFailedException(message: String) : Exception(message)

        internal class ModelDownloadFailedException(message: String) : Exception(message)

        internal class TrackedAppErrorException(message: String) : Exception(message)

        internal fun exceptionFor(message: String): Exception =
            when (message) {
                "print_failed" -> PrintFailedException(message)
                "http_upload_failed" -> HttpUploadFailedException(message)
                "cloud_login_failed" -> CloudLoginFailedException(message)
                "print_control_failed" -> PrintControlFailedException(message)
                "printer_pair_failed" -> PrinterPairFailedException(message)
                "slice_failed" -> SliceFailedException(message)
                "lan_cc1_start_print_failed", "lan_cc1_monitor_connect_failed" -> LanPrintFailedException(message)
                "camera_start_failed" -> CameraStartFailedException(message)
                "model_download_failed" -> ModelDownloadFailedException(message)
                else -> TrackedAppErrorException(message)
            }
    }
}
