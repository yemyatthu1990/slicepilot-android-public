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
import android.os.SystemClock
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import world.yemyat.slicepilot.model.GcodePreview
import world.yemyat.slicepilot.model.GcodePreviewParser
import world.yemyat.slicepilot.model.GcodeStats
import world.yemyat.slicepilot.tracking.EventNotifier
import world.yemyat.slicepilot.tracking.TrackingBootstrap

data class SliceServiceState(
    val jobId: Long = 0L,
    val isRunning: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val gcodePath: String? = null,
    val preview: GcodePreview? = null,
    val stats: GcodeStats? = null,
    val error: String? = null,
    val autoPrint: Boolean = false,
)

data class SliceTransform(
    val rotX: Float = 0f,
    val rotY: Float = 0f,
    val rotZ: Float = 0f,
    val mirrorX: Boolean = false,
    val mirrorY: Boolean = false,
    val mirrorZ: Boolean = false,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
)

class SliceForegroundService : Service() {

    companion object {
        const val ACTION_START = "world.yemyat.slicepilot.action.START_SLICE"
        const val ACTION_CANCEL = "world.yemyat.slicepilot.action.CANCEL_SLICE"

        const val EXTRA_JOB_ID = "job_id"
        const val EXTRA_MODEL_PATH = "model_path"
        const val EXTRA_PRINTER = "printer"
        const val EXTRA_FILAMENT = "filament"
        const val EXTRA_PROCESS = "process"
        const val EXTRA_OVERRIDES = "overrides"
        const val EXTRA_OUT_PATH = "out_path"
        const val EXTRA_AUTO_PRINT = "auto_print"
        const val EXTRA_ROT_X = "rot_x"
        const val EXTRA_ROT_Y = "rot_y"
        const val EXTRA_ROT_Z = "rot_z"
        const val EXTRA_MIRROR_X = "mirror_x"
        const val EXTRA_MIRROR_Y = "mirror_y"
        const val EXTRA_MIRROR_Z = "mirror_z"
        const val EXTRA_OFFSET_X = "offset_x"
        const val EXTRA_OFFSET_Y = "offset_y"

        private const val CHANNEL_PROGRESS = "slice_progress"
        private const val CHANNEL_RESULT = "slice_result"
        private const val GROUP_SLICE = "world.yemyat.slicepilot.slice"
        private const val NOTIFICATION_ID = 4201
        private const val RESULT_NOTIFICATION_ID = 4202

        private val _state = MutableStateFlow(SliceServiceState())
        val state: StateFlow<SliceServiceState> = _state.asStateFlow()

        fun start(
            context: Context,
            jobId: Long,
            modelPath: String,
            printer: String,
            filament: String,
            process: String,
            overrides: String,
            outPath: String,
            autoPrint: Boolean,
            transform: SliceTransform,
        ) {
            val intent = Intent(context, SliceForegroundService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_JOB_ID, jobId)
                putExtra(EXTRA_MODEL_PATH, modelPath)
                putExtra(EXTRA_PRINTER, printer)
                putExtra(EXTRA_FILAMENT, filament)
                putExtra(EXTRA_PROCESS, process)
                putExtra(EXTRA_OVERRIDES, overrides)
                putExtra(EXTRA_OUT_PATH, outPath)
                putExtra(EXTRA_AUTO_PRINT, autoPrint)
                putExtra(EXTRA_ROT_X, transform.rotX)
                putExtra(EXTRA_ROT_Y, transform.rotY)
                putExtra(EXTRA_ROT_Z, transform.rotZ)
                putExtra(EXTRA_MIRROR_X, transform.mirrorX)
                putExtra(EXTRA_MIRROR_Y, transform.mirrorY)
                putExtra(EXTRA_MIRROR_Z, transform.mirrorZ)
                putExtra(EXTRA_OFFSET_X, transform.offsetX)
                putExtra(EXTRA_OFFSET_Y, transform.offsetY)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun cancel(context: Context, jobId: Long) {
            val intent = Intent(context, SliceForegroundService::class.java).apply {
                action = ACTION_CANCEL
                putExtra(EXTRA_JOB_ID, jobId)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var sliceJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile
    private var lastNotifyAt = 0L

    override fun onCreate() {
        super.onCreate()
        TrackingBootstrap.ensure(this)
        NativeLib.init(this)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startSlicing(intent)
            ACTION_CANCEL -> cancelSlicing(intent.getLongExtra(EXTRA_JOB_ID, 0L))
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        sliceJob?.cancel()
        if (_state.value.isRunning) {
            NativeLib.nativeCancel()
            _state.update { it.copy(isRunning = false, jobId = 0L, status = "Cancelled") }
        }
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        scope.cancel()
        super.onDestroy()
    }

    private fun startSlicing(intent: Intent) {
        val jobId = intent.getLongExtra(EXTRA_JOB_ID, 0L)
        val modelPath = intent.getStringExtra(EXTRA_MODEL_PATH)
        val printer = intent.getStringExtra(EXTRA_PRINTER)
        val filament = intent.getStringExtra(EXTRA_FILAMENT)
        val process = intent.getStringExtra(EXTRA_PROCESS)
        val outPath = intent.getStringExtra(EXTRA_OUT_PATH)
        if (jobId == 0L || modelPath.isNullOrBlank() || printer.isNullOrBlank() || filament.isNullOrBlank() || process.isNullOrBlank() || outPath.isNullOrBlank()) {
            stopSelf()
            return
        }

        val overrides = intent.getStringExtra(EXTRA_OVERRIDES).orEmpty()
        val autoPrint = intent.getBooleanExtra(EXTRA_AUTO_PRINT, false)
        val transform = SliceTransform(
            rotX = intent.getFloatExtra(EXTRA_ROT_X, 0f),
            rotY = intent.getFloatExtra(EXTRA_ROT_Y, 0f),
            rotZ = intent.getFloatExtra(EXTRA_ROT_Z, 0f),
            mirrorX = intent.getBooleanExtra(EXTRA_MIRROR_X, false),
            mirrorY = intent.getBooleanExtra(EXTRA_MIRROR_Y, false),
            mirrorZ = intent.getBooleanExtra(EXTRA_MIRROR_Z, false),
            offsetX = intent.getFloatExtra(EXTRA_OFFSET_X, 0f),
            offsetY = intent.getFloatExtra(EXTRA_OFFSET_Y, 0f),
        )

        if (_state.value.isRunning && _state.value.jobId != jobId) {
            NativeLib.nativeCancel()
        }

        val startedAt = System.currentTimeMillis()
        _state.update {
            SliceServiceState(
                jobId = jobId,
                isRunning = true,
                progress = 0f,
                status = "Starting…",
                autoPrint = autoPrint,
            )
        }
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildProgressNotification("Starting…", 0f),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
        acquireWakeLock()

        sliceJob?.cancel()
        sliceJob = scope.launch {
            NativeLib.nativeSetModelTransform(
                transform.rotX,
                transform.rotY,
                transform.rotZ,
                transform.mirrorX,
                transform.mirrorY,
                transform.mirrorZ,
                transform.offsetX,
                transform.offsetY,
            )

            val ok = NativeLib.nativeSliceWithOverrides(
                modelPath,
                printer,
                filament,
                process,
                overrides,
                outPath,
                object : NativeLib.SliceCallback {
                    override fun onProgress(step: Int, text: String, percent: Int) {
                        if (_state.value.jobId != jobId) return
                        _state.update { it.copy(progress = percent / 100f, status = text) }
                        showProgressNotification()
                    }
                },
            )

            if (!ok) {
                handleFailure(jobId, "launch", NativeLib.nativeGetError(), startedAt, printer, filament, process, outPath)
                return@launch
            }

            while (NativeLib.nativeIsRunning()) {
                delay(250)
            }
            if (_state.value.jobId != jobId) return@launch

            val err = NativeLib.nativeGetError()
            if (err.isNotEmpty()) {
                handleFailure(jobId, "run", err, startedAt, printer, filament, process, outPath)
                return@launch
            }

            handleSuccess(jobId, outPath, startedAt, printer, filament, process)
        }
    }

    private fun cancelSlicing(jobId: Long) {
        if (jobId != 0L && _state.value.jobId != jobId) return
        sliceJob?.cancel()
        NativeLib.nativeCancel()
        _state.update {
            it.copy(
                jobId = 0L,
                isRunning = false,
                progress = 0f,
                status = "Cancelled",
                error = null,
            )
        }
        showProgressNotification(force = true)
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleFailure(
        jobId: Long,
        stage: String,
        error: String,
        startedAt: Long,
        printer: String,
        filament: String,
        process: String,
        outPath: String,
    ) {
        _state.update {
            it.copy(
                isRunning = false,
                progress = 0f,
                status = "Slice failed",
                error = error,
            )
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        showResultNotification(
            title = "Slice failed",
            text = error.ifBlank { "Unknown error" },
            openPreview = false,
            error = true,
        )
        EventNotifier.trackError(
            "slice_failed",
            null,
            mapOf(
                "stage" to stage,
                "error" to error,
                "printer" to printer,
                "filament" to filament,
                "process" to process,
                "output" to outPath,
                "duration_ms" to (System.currentTimeMillis() - startedAt),
            ),
        )
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun handleSuccess(
        jobId: Long,
        outPath: String,
        startedAt: Long,
        printer: String,
        filament: String,
        process: String,
    ) {
        _state.update {
            it.copy(
                gcodePath = outPath,
                status = "Parsing G-code preview 0%",
                progress = 0.80f,
            )
        }
        showProgressNotification(force = true)

        val preview = try {
            GcodePreviewParser.parse(File(outPath)) { p ->
                if (_state.value.jobId == jobId) {
                    _state.update {
                        it.copy(
                            progress = 0.80f + 0.18f * p,
                            status = "Parsing G-code preview ${(p * 100).toInt()}%",
                        )
                    }
                    showProgressNotification()
                }
            }
        } catch (_: Exception) {
            null
        }

        var stats: GcodeStats? = null
        try {
            _state.update { it.copy(status = "Parsing G-code stats", progress = 0.98f) }
            showProgressNotification(force = true)
            stats = GcodePreviewParser.parseStats(File(outPath))
        } catch (_: Exception) {
            if (_state.value.jobId == jobId) {
                _state.update { it.copy(stats = null) }
            }
        }

        if (_state.value.jobId != jobId) return

        _state.update {
            it.copy(
                isRunning = false,
                progress = 1f,
                status = "Slice complete",
                gcodePath = outPath,
                preview = preview,
                stats = stats,
                error = null,
            )
        }
        try {
            NotificationManagerCompat.from(this).cancel(NOTIFICATION_ID)
        } catch (_: Exception) {
        }
        showResultNotification(
            title = "Slice complete",
            text = completeNotificationText(outPath, stats),
            openPreview = true,
            error = false,
        )
        EventNotifier.trackEvent(
            "slice_completed",
            mapOf(
                "printer" to printer,
                "filament" to filament,
                "process" to process,
                "output" to outPath,
                "duration_ms" to (System.currentTimeMillis() - startedAt),
                "layer_count" to stats?.layerCount,
                "filament_mm" to stats?.filamentMm,
                "filament_grams" to stats?.filamentGrams,
                "estimated_seconds" to stats?.estimatedSeconds,
            ),
        )
        releaseWakeLock()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Slicing", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                description = "Shows slicing progress"
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Slice results", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shows slice completion and errors"
            },
        )
    }

    private fun showProgressNotification(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < 500) return
        lastNotifyAt = now
        val s = _state.value
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildProgressNotification(s.status, s.progress))
        } catch (_: Exception) {
        }
    }

    private fun showResultNotification(
        title: String,
        text: String,
        openPreview: Boolean,
        error: Boolean,
    ) {
        val notification = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(if (error) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openAppPendingIntent(openPreview))
            .setGroup(GROUP_SLICE)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(RESULT_NOTIFICATION_ID, notification)
        } catch (_: Exception) {
        }
    }

    private fun buildProgressNotification(status: String, progress: Float): Notification {
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Slicing model")
            .setContentText(status.ifBlank { "Working…" })
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setProgress(100, (progress.coerceIn(0f, 1f) * 100f).toInt(), false)
            .setContentIntent(openAppPendingIntent(false))
            .addAction(0, "Cancel", cancelPendingIntent(_state.value.jobId))
            .setGroup(GROUP_SLICE)
            .build()
    }

    private fun completeNotificationText(outPath: String, stats: GcodeStats?): String {
        val details = buildString {
            stats?.layerCount?.let { append("$it layers") }
            stats?.estimatedTimeLabel?.let {
                if (isNotEmpty()) append(" · ")
                append(it)
            }
            stats?.filamentGrams?.let {
                if (isNotEmpty()) append(" · ")
                append(String.format("%.1f g", it))
            }
        }
        val fileName = File(outPath).name
        return if (details.isBlank()) fileName else "$details · $fileName"
    }

    private fun openAppPendingIntent(openPreview: Boolean): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("open_preview", openPreview)
        }
        return PendingIntent.getActivity(
            this,
            if (openPreview) 1 else 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun cancelPendingIntent(jobId: Long): PendingIntent {
        val intent = Intent(this, SliceForegroundService::class.java).apply {
            action = ACTION_CANCEL
            putExtra(EXTRA_JOB_ID, jobId)
        }
        return PendingIntent.getService(
            this,
            jobId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val manager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = manager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SlicePilot:slicing").apply {
            setReferenceCounted(false)
            acquire(6 * 60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
