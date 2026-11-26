package com.hilalsameer.videoaistudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hilalsameer.videoaistudio.media.AsrEngine
import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.MediaCommands
import com.hilalsameer.videoaistudio.media.MediaEngine
import com.hilalsameer.videoaistudio.media.prepareInputFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** UI-visible state of the transcription job, owned by the service. */
sealed interface TranscribeJobState {
    data object Idle : TranscribeJobState
    data object Preparing : TranscribeJobState
    data class Extracting(val fraction: Float) : TranscribeJobState
    data class Transcribing(val done: Int, val total: Int) : TranscribeJobState
    data class Done(val segments: List<AsrSegment>, val inputName: String) : TranscribeJobState
    data class Failed(val excerpt: String, val logTail: String) : TranscribeJobState
    data object Cancelled : TranscribeJobState
}

/**
 * Foreground service running the Milestone 4 pipeline (0.5.1,
 * TD-008). Field lesson of the first night: a plain activity
 * coroutine is frozen by Doze once the screen sleeps — a 2.5-hour
 * film crawled at ~25 min per part overnight. A foreground service
 * plus a partial wake lock keeps the job at full speed with the
 * screen off, shows live progress in a notification, and posts a
 * completion notification the owner finds in the morning.
 *
 * Fully offline: the service moves no network traffic (the app
 * declares no INTERNET permission at all).
 */
class TranscribeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cancelFlag = AtomicBoolean(false)
    private val engine = MediaEngine()
    private var asr: AsrEngine? = null
    private var lastNotifMs = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.m4_notif_channel),
            NotificationManager.IMPORTANCE_LOW
        )
        nm.createNotificationChannel(channel)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelFlag.set(true)
                engine.cancel()
            }
            ACTION_START -> {
                if (job?.isActive == true) return START_NOT_STICKY
                val uriStr = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "video"
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                val lang = intent.getStringExtra(EXTRA_LANG) ?: ""
                cancelFlag.set(false)
                startForeground(NOTIF_ID, buildNotification(getString(R.string.m4_notif_running), null, null))
                acquireWakeLock()
                job = scope.launch {
                    runPipeline(Uri.parse(uriStr), name, durationMs, lang)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(uri: Uri, name: String, durationMs: Long, lang: String) {
        try {
            state.value = TranscribeJobState.Preparing
            val input = withContext(Dispatchers.IO) { prepareInputFile(this@TranscribeService, uri, name) }
            val wav = File(cacheDir, "asr_in.wav")
            state.value = TranscribeJobState.Extracting(0f)
            val extract = engine.run(
                MediaCommands.extractWav16k(input.absolutePath, wav.absolutePath)
            ) { ms ->
                val frac = if (durationMs > 0) {
                    (ms.toFloat() / durationMs).coerceIn(0f, 1f)
                } else 0f
                state.value = TranscribeJobState.Extracting(frac)
                updateNotification(getString(R.string.m4_extracting), (frac * 100).toInt(), 100)
            }
            if (extract.cancelled || cancelFlag.get()) {
                finish(TranscribeJobState.Cancelled)
                return
            }
            if (!extract.success) {
                finish(
                    TranscribeJobState.Failed(
                        MediaCommands.errorExcerpt(extract.output), extract.output.takeLast(600)
                    )
                )
                return
            }
            state.value = TranscribeJobState.Transcribing(0, 0)
            val engineAsr = asr ?: AsrEngine(applicationContext).also { asr = it }
            val segments = engineAsr.transcribe(
                wav, lang,
                { done, total ->
                    state.value = TranscribeJobState.Transcribing(done, total)
                    updateNotification(
                        getString(R.string.m4_transcribing, done, total), done, total
                    )
                },
                { cancelFlag.get() }
            )
            if (cancelFlag.get()) {
                finish(TranscribeJobState.Cancelled)
            } else {
                finish(TranscribeJobState.Done(segments, name))
            }
        } catch (e: Exception) {
            finish(TranscribeJobState.Failed(e.message ?: "error", e.message ?: "error"))
        }
    }

    private fun finish(terminal: TranscribeJobState) {
        state.value = terminal
        releaseWakeLock()
        when (terminal) {
            is TranscribeJobState.Done -> postFinalNotification(
                getString(R.string.m4_notif_done), android.R.drawable.stat_sys_download_done
            )
            is TranscribeJobState.Failed -> postFinalNotification(
                getString(R.string.m4_notif_failed), android.R.drawable.stat_notify_error
            )
            else -> { }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, progress: Int?, max: Int?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 1,
            Intent(this, TranscribeService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.tool_transcribe))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.m2_cancel), cancelIntent
            )
        if (progress != null && max != null && max > 0) {
            b.setProgress(max, progress, false)
        }
        return b.build()
    }

    private fun updateNotification(text: String, progress: Int?, max: Int?) {
        val now = System.currentTimeMillis()
        if (now - lastNotifMs < 1000L) return
        lastNotifMs = now
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, progress, max))
    }

    private fun postFinalNotification(text: String, icon: Int) {
        val openIntent = PendingIntent.getActivity(
            this, 2,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.tool_transcribe))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_NOTIF_ID, n)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(PowerManager::class.java)
        val wl = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoaistudio:asr")
        wl.setReferenceCounted(false)
        wl.acquire(WAKELOCK_TIMEOUT_MS)
        wakeLock = wl
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (e: Exception) { }
        wakeLock = null
    }

    override fun onDestroy() {
        cancelFlag.set(true)
        engine.cancel()
        job?.cancel()
        releaseWakeLock()
        try { asr?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "transcribe"
        private const val NOTIF_ID = 41
        private const val DONE_NOTIF_ID = 42
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        private const val ACTION_START = "com.hilalsameer.videoaistudio.ASR_START"
        private const val ACTION_CANCEL = "com.hilalsameer.videoaistudio.ASR_CANCEL"
        private const val EXTRA_URI = "uri"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DURATION_MS = "durationMs"
        private const val EXTRA_LANG = "lang"

        /** The one job state both the service and the screen read. */
        val state = MutableStateFlow<TranscribeJobState>(TranscribeJobState.Idle)

        fun start(context: Context, uri: String, name: String, durationMs: Long, lang: String) {
            val intent = Intent(context, TranscribeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_LANG, lang)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, TranscribeService::class.java).setAction(ACTION_CANCEL)
            context.startService(intent)
        }
    }
}
