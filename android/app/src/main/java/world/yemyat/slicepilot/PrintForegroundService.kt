package world.yemyat.slicepilot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.PowerManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableSharedFlow
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.cloud.PrintStatus

/**
 * Stateful policy mapping a monitored print session's [PrintStatus] stream to notification actions.
 *
 * Prevents three real-world bugs on Centauri Carbon 2 / Android 14+:
 * 1. Printer jumping straight to [PrintJobState.IDLE] on finish (or returning to standby between
 *    polls) previously kept the foreground service alive showing "Starting print…".
 * 2. Temperature-only status ticks while idle or printing previously called `notify()` every
 *    second, immediately resurrecting a swiped-away notification.
 * 3. A restored persisted session waking up after the print already finished previously revived a
 *    stale "Starting print…" notification instead of clearing the session.
 */
internal class PrintNotificationTracker(
    private val graceMs: Long = 45_000L,
) {
    sealed interface Action {
        data object Ignore : Action
        data object Update : Action
        data class Finish(val text: String) : Action
        data object Clear : Action
    }

    private var active = false
    private var justStarted = false
    private var startedAtMs = 0L
    private var sawActiveState = false
    private var lastActiveState: PrintJobState? = null
    private var lastRenderedKey: String? = null

    fun begin(justStarted: Boolean, nowMs: Long) {
        active = true
        this.justStarted = justStarted
        startedAtMs = nowMs
        sawActiveState = false
        lastActiveState = null
        lastRenderedKey = null
    }

    fun reset() {
        active = false
        justStarted = false
        startedAtMs = 0L
        sawActiveState = false
        lastActiveState = null
        lastRenderedKey = null
    }

    fun onStatus(printer: String, status: PrintStatus, nowMs: Long): Action {
        if (!active) return Action.Ignore
        val state = status.state

        if (state.isTerminal) {
            val text = when (state) {
                PrintJobState.COMPLETED -> "Print completed"
                PrintJobState.STOPPED -> "Print stopped"
                else -> "Printer reported an error"
            }
            reset()
            return Action.Finish(text)
        }

        if (state == PrintJobState.IDLE) {
            if (!status.live) return Action.Ignore
            return when {
                sawActiveState -> {
                    val text = if (lastActiveState == PrintJobState.STOPPING) {
                        "Print stopped"
                    } else {
                        "Print completed"
                    }
                    reset()
                    Action.Finish(text)
                }
                !justStarted || (nowMs - startedAtMs) >= graceMs -> {
                    reset()
                    Action.Clear
                }
                else -> Action.Ignore
            }
        }

        if (state == PrintJobState.CONNECTING) {
            // Never regress an already-active print notification back to "Connecting…",
            // and never post a notification for a restored session until the printer confirms
            // a job is actually running.
            if (sawActiveState || !justStarted) return Action.Ignore
            val key = "$printer|CONNECTING"
            if (key == lastRenderedKey) return Action.Ignore
            lastRenderedKey = key
            return Action.Update
        }

        // Active print states: PRINTING, PAUSED, PAUSING, RESUMING, STOPPING.
        sawActiveState = true
        justStarted = false
        lastActiveState = state
        val remainingBucket = (status.remainingSec ?: -1).let { if (it <= 0) -1 else it / 60 }
        val key = "$printer|${state.name}|${status.progress}|${status.currentLayer}|${status.totalLayer}|$remainingBucket"
        if (key == lastRenderedKey) return Action.Ignore
        lastRenderedKey = key
        return Action.Update
    }
}

/**
 * Keeps the app process alive while a print is running (the monitor's MQTT/RTM clients live in
 * this process, so Android suspending the app would freeze live progress) and mirrors the print
 * status into a persistent notification: percent, layer, time remaining, plus Pause/Resume/Stop
 * actions. Tapping the notification opens the print monitor screen — restoring a monitor
 * session even in a fresh process via [AppViewModel.openPrintMonitor].
 *
 * The print job runs on the printer; this service never gates it, only observes and controls.
 */
class PrintForegroundService : Service() {

    private var last: PrintNotificationState? = null
    private var printerName: String = "Printer"
    private var startedForeground = false

    /** Control actions tapped on the notification; the AppViewModel acts on them. */
    companion object {
        const val ACTION_START = "world.yemyat.slicepilot.print.START"
        const val ACTION_UPDATE = "world.yemyat.slicepilot.print.UPDATE"
        const val ACTION_FINISH = "world.yemyat.slicepilot.print.FINISH"
        const val ACTION_CLEAR = "world.yemyat.slicepilot.print.CLEAR"
        const val ACTION_PAUSE = "world.yemyat.slicepilot.print.PAUSE"
        const val ACTION_RESUME = "world.yemyat.slicepilot.print.RESUME"
        const val ACTION_STOP = "world.yemyat.slicepilot.print.STOP"

        /** MainActivity extra carrying a control action when no ViewModel collects [commands]. */
        const val EXTRA_PRINT_ACTION = "print_action"

        private const val RESULT_CHANNEL_ID = "print_result"
        private const val PROGRESS_CHANNEL_ID = "print_progress"
        private const val PROGRESS_NOTIFICATION_ID = 4301
        private const val RESULT_NOTIFICATION_ID = 4302
        private const val WAKELOCK_TAG = "SlicePilot:Print"
        private const val WAKELOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000

        val commands = MutableSharedFlow<String>(extraBufferCapacity = 8)

        /**
         * Set when Android 15+ enforces the 6-hour dataSync foreground-service timeout via
         * [onTimeout]. While set, live status updates keep refreshing the notification directly
         * without re-starting a foreground service into an exhausted quota.
         */
        @Volatile
        private var fgsTimedOut = false

        private val sessionGen = AtomicLong(0L)

        /** Non-zero while a print monitoring session is active; zeroed synchronously on finish/clear. */
        @Volatile
        private var activeSession = 0L

        /** Ensure a session token exists for a restored session once an active print is confirmed. */
        fun ensureSession() {
            if (activeSession == 0L) {
                activeSession = sessionGen.incrementAndGet()
            }
        }

        /** Begin monitoring print [printer] with a live progress notification. */
        fun start(context: Context, printer: String) {
            fgsTimedOut = false
            val session = sessionGen.incrementAndGet()
            activeSession = session
            ensureChannel(context)
            startService(
                context,
                Intent(context, PrintForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EX_SESSION, session)
                    .putExtra(EX_PRINTER, printer),
            )
        }

        /** Mirror a live status update into the progress notification. */
        fun notifyStatus(context: Context, printer: String, st: PrintStatus) {
            val session = activeSession
            if (session == 0L) return
            ensureChannel(context)
            if (fgsTimedOut) {
                postDirectStatusNotification(context, printer, st)
                return
            }
            startService(
                context,
                Intent(context, PrintForegroundService::class.java)
                    .setAction(ACTION_UPDATE)
                    .putExtra(EX_SESSION, session)
                    .putExtra(EX_PRINTER, printer)
                    .putExtra(EX_STATE, st.state.name)
                    .putExtra(EX_PERCENT, st.progress)
                    .putExtra(EX_LAYER, st.currentLayer)
                    .putExtra(EX_LAYERS, st.totalLayer)
                    .putExtra(EX_REMAIN, st.remainingSec ?: -1),
            )
        }

        /** Show a terminal result notification and stop the foreground service. */
        fun finish(context: Context, printer: String, text: String) {
            activeSession = 0L
            fgsTimedOut = false
            ensureChannel(context)
            // Stop directly instead of startForegroundService(ACTION_FINISH): works from any
            // lifecycle state, never flashes a dummy progress notification, and any already-queued
            // ACTION_UPDATE carries the invalidated session token and self-terminates.
            runCatching { context.stopService(Intent(context, PrintForegroundService::class.java)) }
            runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID) }
            postDirectResultNotification(context, "Printing on $printer", text)
        }

        /** Stop monitoring quietly (user left the monitor; the print itself continues). */
        fun clear(context: Context) {
            activeSession = 0L
            fgsTimedOut = false
            runCatching { context.stopService(Intent(context, PrintForegroundService::class.java)) }
            runCatching { NotificationManagerCompat.from(context).cancel(PROGRESS_NOTIFICATION_ID) }
        }

        private fun postDirectStatusNotification(context: Context, printer: String, st: PrintStatus) {
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val body = formatBody(st.state.name, st.progress, st.currentLayer, st.totalLayer, st.remainingSec ?: -1)
            val content = Intent(context, MainActivity::class.java)
                .putExtra("open_device_status", true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            val contentIntent = PendingIntent.getActivity(context, 4301, content, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, PROGRESS_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Printing on $printer")
                .setContentText(body)
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(contentIntent)
                .build()
            runCatching { nm.notify(PROGRESS_NOTIFICATION_ID, n) }
        }

        private fun postDirectResultNotification(context: Context, title: String, text: String) {
            context.getSharedPreferences("slicer_prefs", Context.MODE_PRIVATE)
                .edit().putBoolean("pending_device_status", true).apply()
            val nm = NotificationManagerCompat.from(context)
            if (!nm.areNotificationsEnabled()) return
            val content = Intent(context, MainActivity::class.java)
                .putExtra("open_device_status", true)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                )
            val intent = PendingIntent.getActivity(context, 4302, content, PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, RESULT_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .setContentIntent(intent)
                .build()
            runCatching { nm.notify(RESULT_NOTIFICATION_ID, n) }
        }

        internal fun formatBody(
            state: String?,
            percent: Int,
            layer: Int,
            layers: Int,
            remainingSeconds: Int,
        ): String =
            when (state) {
                null -> "Starting print…"
                PrintJobState.PAUSED.name -> "Paused · $percent% · ${fmtRemaining(remainingSeconds)} left"
                PrintJobState.PRINTING.name -> "Printing $percent% · Layer $layer/$layers · ${fmtRemaining(remainingSeconds)} left"
                PrintJobState.COMPLETED.name -> "Print completed"
                PrintJobState.STOPPED.name -> "Print stopped"
                PrintJobState.ERROR.name -> "Printer reported an error"
                PrintJobState.PAUSING.name -> "Pausing…"
                PrintJobState.RESUMING.name -> "Resuming…"
                PrintJobState.STOPPING.name -> "Stopping…"
                PrintJobState.CONNECTING.name -> "Connecting to printer…"
                PrintJobState.IDLE.name -> "Printer idle"
                else -> "Monitoring print…"
            }

        private fun fmtRemaining(seconds: Int): String {
            if (seconds <= 0) return "calculating"
            val h = seconds / 3600
            val m = (seconds % 3600) / 60
            return if (h > 0) "${h}h ${m}m" else "${m}m"
        }

        private fun startService(context: Context, intent: Intent) {
            try {
                if (!notificationsUsable(context)) {
                    // Notifications blocked: plain started service (the printer monitor
                    // works without the foreground service; startForeground would crash).
                    context.startService(intent)
                    return
                }
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Background start restrictions: the monitor works without the service.
            }
        }

        private fun ensureChannel(context: Context) {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mgr.createNotificationChannel(
                NotificationChannel(PROGRESS_CHANNEL_ID, "Printing", NotificationManager.IMPORTANCE_LOW),
            )
            mgr.createNotificationChannel(
                NotificationChannel(
                    RESULT_CHANNEL_ID,
                    "Print results",
                    NotificationManager.IMPORTANCE_HIGH,
                ).apply { description = "Completion, cancellation and failure of prints" },
            )
        }

        /**
         * True when the FGS progress notification can actually be posted. When the user
         * blocks the app's notifications the system refuses the startForeground
         * notification and kills the process with
         * RemoteServiceException$CannotPostForegroundServiceNotificationException
         * ("Bad notification for startForeground") — an async, uncatchable crash.
         * Callers then run the monitor as a plain started service instead.
         */
        private fun notificationsUsable(context: Context): Boolean {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
            // A user-deleted channel reads back null: recreate it (a deleted
            // channel cannot host the FGS notification — "Bad notification" crash).
            if (mgr.getNotificationChannel(PROGRESS_CHANNEL_ID) == null) ensureChannel(context)
            val importance = mgr.getNotificationChannel(PROGRESS_CHANNEL_ID)?.importance
                ?: NotificationManager.IMPORTANCE_LOW
            return importance != NotificationManager.IMPORTANCE_NONE
        }

        private const val EX_SESSION = "session"
        private const val EX_PRINTER = "printer"
        private const val EX_STATE = "state"
        private const val EX_PERCENT = "percent"
        private const val EX_LAYER = "layer"
        private const val EX_LAYERS = "layers"
        private const val EX_REMAIN = "remain"
        private const val EX_TEXT = "text"
    }

    private data class PrintNotificationState(
        val state: String,
        val percent: Int,
        val layer: Int,
        val layers: Int,
        val remainingSeconds: Int,
    )

    private val manager by lazy { NotificationManagerCompat.from(this) }
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                if (isStaleSession(intent)) {
                    stopStaleForeground()
                    return START_NOT_STICKY
                }
                printerName = intent.getStringExtra(EX_PRINTER) ?: printerName
                last = null
                beginForeground()
            }

            ACTION_UPDATE -> {
                if (isStaleSession(intent)) {
                    stopStaleForeground()
                    return START_NOT_STICKY
                }
                printerName = intent.getStringExtra(EX_PRINTER) ?: printerName
                last = PrintNotificationState(
                    state = intent.getStringExtra(EX_STATE) ?: "PRINTING",
                    percent = intent.getIntExtra(EX_PERCENT, -1),
                    layer = intent.getIntExtra(EX_LAYER, 0),
                    layers = intent.getIntExtra(EX_LAYERS, 0),
                    remainingSeconds = intent.getIntExtra(EX_REMAIN, -1),
                )
                beginForeground()
                refresh()
            }

            ACTION_FINISH -> {
                // startForegroundService() was used to reach us: startForeground() must be
                // called before anything can stopSelf(), or the system kills the process
                // (ForegroundServiceDidNotStartInTimeException).
                beginForeground()
                val printer = intent.getStringExtra(EX_PRINTER) ?: printerName
                val text = intent.getStringExtra(EX_TEXT) ?: "Print finished"
                endForeground()
                notifyResult("Printing on $printer", text)
                stopSelf()
            }

            ACTION_CLEAR -> {
                endForeground()
                manager.cancel(PROGRESS_NOTIFICATION_ID)
                stopSelf()
            }

            ACTION_PAUSE, ACTION_RESUME, ACTION_STOP -> {
                // Notification action buttons: forward to the ViewModel. After a swipe-kill
                // the ongoing notification survives (the service keeps the process alive)
                // with no ViewModel collecting the flow: relaunch MainActivity and hand the
                // action over as an extra (the restored monitor executes it on start).
                val action = intent.action!!
                if (commands.subscriptionCount.value > 0) {
                    commands.tryEmit(action)
                } else {
                    runCatching {
                        startActivity(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                .putExtra(EXTRA_PRINT_ACTION, action),
                        )
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun isStaleSession(intent: Intent): Boolean {
        val current = activeSession
        val incoming = intent.getLongExtra(EX_SESSION, 0L)
        return current == 0L || incoming != current
    }

    /**
     * Satisfy the startForegroundService() contract briefly if this stale intent launched the
     * service fresh, then tear down foreground mode and remove the progress notification at once.
     */
    private fun stopStaleForeground() {
        if (!startedForeground && notificationsUsable(this)) {
            ServiceCompat.startForeground(
                this,
                PROGRESS_NOTIFICATION_ID,
                build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            startedForeground = true
        }
        endForeground()
        manager.cancel(PROGRESS_NOTIFICATION_ID)
        stopSelf()
    }

    private fun beginForeground() {
        if (!startedForeground) {
            if (!notificationsUsable(this)) {
                // The service was reached through startForegroundService(): bailing out
                // without startForeground() leaves the promise unresolved and the system
                // kills the process ~10 s later (ForegroundServiceDidNotStartInTimeException).
                // stopSelf() cancels the timeout; the monitor then runs as a plain
                // started service (restarted by the status collector on the next tick
                // once notifications are re-enabled).
                stopSelf()
                return
            }
            ServiceCompat.startForeground(
                this,
                PROGRESS_NOTIFICATION_ID,
                build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
            startedForeground = true
            // Starting a print dismisses any pending "Slice completed" result —
            // the print has taken over that model's story.
            manager.cancel(SliceForegroundService.RESULT_NOTIFICATION_ID)
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKELOCK_TAG).apply {
                acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
    }

    private fun endForeground() {
        if (startedForeground) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            startedForeground = false
        }
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ enforces a 6-hour per-24h cap on dataSync foreground services and kills the
        // process with ForegroundServiceDidNotStopInTimeException if the service doesn't stop here.
        // Drop foreground status cleanly; subsequent updates keep refreshing a regular notification.
        fgsTimedOut = true
        endForeground()
        stopSelf()
    }

    override fun onDestroy() {
        endForeground()
        super.onDestroy()
    }

    private fun refresh() {
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(PROGRESS_NOTIFICATION_ID, build())
            } catch (_: Exception) {
            }
        }
    }

    private fun actionIntent(action: String): PendingIntent {
        val i = Intent(this, PrintForegroundService::class.java).setAction(action)
        return PendingIntent.getService(this, action.hashCode(), i, PendingIntent.FLAG_IMMUTABLE)
    }

    private fun build(): Notification {
        val content = Intent(this, MainActivity::class.java)
            .putExtra("open_device_status", true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    // SINGLE_TOP: after a swipe-kill the task record may survive and a
                    // plain CLEAR_TOP relaunch drops the extras (the tap opened Home).
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val contentIntent = PendingIntent.getActivity(
            // Unique request code: SliceForegroundService also builds
            // MainActivity PIs with codes 0/1 — sharing a code silently
            // reuses the slice notification's extras (taps landed on the
            // "Slice finished" screen).
            this, 4301, content, PendingIntent.FLAG_IMMUTABLE,
        )
        val st = last
        val body = if (st == null) {
            formatBody(null, -1, 0, 0, -1)
        } else {
            formatBody(st.state, st.percent, st.layer, st.layers, st.remainingSeconds)
        }
        val builder = NotificationCompat.Builder(this, PROGRESS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Printing on $printerName")
            .setContentText(body)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(contentIntent)
        if (st == null || st.state.showsControls()) {
            when (st?.state) {
                PrintJobState.PAUSED.name -> builder.addAction(0, "Resume", actionIntent(ACTION_RESUME))
                else -> builder.addAction(0, "Pause", actionIntent(ACTION_PAUSE))
            }
            builder.addAction(0, "Stop", actionIntent(ACTION_STOP))
        }
        return builder.build()
    }

    private fun notifyResult(title: String, text: String) {
        // A swipe-killed task discards the tap's extras, so also record the intent:
        // the next launch consumes it and opens the device status screen.
        getSharedPreferences("slicer_prefs", android.content.Context.MODE_PRIVATE)
            .edit().putBoolean("pending_device_status", true).apply()
        // A finished job has no monitor to show: land on the device status screen
        // (idle state, camera) instead of the plain home screen.
        val content = Intent(this, MainActivity::class.java)
            .putExtra("open_device_status", true)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    // SINGLE_TOP: after a swipe-kill the task record may survive and a
                    // plain CLEAR_TOP relaunch drops the extras (the tap opened Home).
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        val intent = PendingIntent.getActivity(this, 4302, content, PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(intent)
            .build()
        if (manager.areNotificationsEnabled()) {
            try {
                manager.notify(RESULT_NOTIFICATION_ID, n)
            } catch (_: Exception) {
            }
        }
    }

    private fun fmtRemaining(seconds: Int): String {
        if (seconds <= 0) return "calculating"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun String.showsControls(): Boolean =
        this == PrintJobState.PRINTING.name || this == PrintJobState.PAUSED.name ||
            this == PrintJobState.PAUSING.name || this == PrintJobState.RESUMING.name ||
            this == PrintJobState.STOPPING.name || this == PrintJobState.CONNECTING.name
}
