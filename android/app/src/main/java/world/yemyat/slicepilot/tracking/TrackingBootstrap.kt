package world.yemyat.slicepilot.tracking

import android.content.Context
import world.yemyat.slicepilot.BuildConfig

object TrackingBootstrap {
    @Volatile
    private var initialized = false

    fun ensure(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            if (BuildConfig.DEBUG) {
                EventNotifier.registerTracker(LogcatEventTracker())
            }
            EventNotifier.registerTracker(FirebaseEventTracker(appContext))
            EventNotifier.registerTracker(CrashlyticsEventTracker(appContext))
            initialized = true
        }
    }
}
