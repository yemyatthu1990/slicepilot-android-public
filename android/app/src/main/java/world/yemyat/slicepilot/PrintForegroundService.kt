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
import kotlinx.coroutines.flow.MutableSharedFlow
import world.yemyat.slicepilot.cloud.PrintJobState
import world.yemyat.slicepilot.cloud.PrintStatus

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

        private const val RESULT_CHANNEL_ID = "print_result"
        private const val PROGRESS_CHANNEL_ID = "print_progress"
        private const val PROGRESS_NOTIFICATION_ID = 4301
        private const val RESULT_NOTIFICATION_ID = 4302
        private const val WAKELOCK_TAG = "SlicePilot:Print"
        private const val WAKELOCK_TIMEOUT_MS = 24L * 60 * 60 * 1000

        val commands = MutableSharedFlow<String>(extraBufferCapacity = 8)

        /** Begin monitoring print [printer] with a live progress notification. */
        fun start(context: Context, printer: String) {
            ensureChannel(context)
            startService(
                context,
                Intent(context, PrintForegroundService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EX_PRINTER, printer),
            )
        }

        /** Mirror a live status update into the progress notification. */
        fun notifyStatus(context: Context, printer: String, st: PrintStatus) {
            ensureChannel(context)
            startService(
                context,
                Intent(context, PrintForegroundService::class.java)
                    .setAction(ACTION_UPDATE)
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
            ensureChannel(context)
            startService(
                context,
                Intent(context, PrintForegroundService::class.java)
                    .setAction(ACTION_FINISH)
                    .putExtra(EX_PRINTER, printer)
                    .putExtra(EX_TEXT, text),
            )
        }

        /** Stop monitoring quietly (user left the monitor; the print itself continues). */
        fun clear(context: Context) {
            // Plain startService: when the service is not running there is nothing to clear,
            // and startForegroundService() on a service that only stopSelf()s crashes the
            // app (startForeground() contract).
            try {
                context.startService(
                    Intent(context, PrintForegroundService::class.java).setAction(ACTION_CLEAR),
                )
            } catch (_: Exception) {
            }
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
                printerName = intent.getStringExtra(EX_PRINTER) ?: printerName
                beginForeground()
            }

            ACTION_UPDATE -> {
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
                beginForeground()
                endForeground()
                manager.cancel(PROGRESS_NOTIFICATION_ID)
                stopSelf()
            }

            ACTION_PAUSE, ACTION_RESUME, ACTION_STOP -> {
                // Notification action buttons: forward to the ViewModel.
                intent.action?.let { commands.tryEmit(it) }
            }
        }
        return START_NOT_STICKY
    }

    private fun beginForeground() {
        if (!startedForeground) {
            if (!notificationsUsable(this)) return // plain started service (see notificationsUsable)
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
        val body = when {
            st == null -> "Monitoring print…"
            st.state == PrintJobState.PAUSED.name ->
                "Paused · ${st.percent}% · ${fmtRemaining(st.remainingSeconds)} left"
            st.state == PrintJobState.PRINTING.name ->
                "Printing ${st.percent}% · Layer ${st.layer}/${st.layers} · ${fmtRemaining(st.remainingSeconds)} left"
            st.state == PrintJobState.COMPLETED.name -> "Print completed"
            st.state == PrintJobState.STOPPED.name -> "Print stopped"
            st.state == PrintJobState.ERROR.name -> "Printer reported an error"
            st.state == PrintJobState.PAUSING.name -> "Pausing…"
            st.state == PrintJobState.RESUMING.name -> "Resuming…"
            st.state == PrintJobState.STOPPING.name -> "Stopping…"
            else -> "Starting print…"
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
        if (st == null || !st.state.isTerminalName()) {
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

    private fun String.isTerminalName(): Boolean =
        this == PrintJobState.COMPLETED.name || this == PrintJobState.STOPPED.name ||
            this == PrintJobState.ERROR.name
}
