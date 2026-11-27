package com.hilalsameer.videoaistudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.os.PowerManager
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import com.hilalsameer.videoaistudio.media.AsrEngine
import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.MediaCommands
import com.hilalsameer.videoaistudio.media.MediaEngine
import com.hilalsameer.videoaistudio.media.SubtitleFormat
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
    data class Done(
        val segments: List<AsrSegment>,
        val inputName: String,
        val autoSaved: Boolean = false,
        val title: String? = null
    ) : TranscribeJobState
    data class Failed(val excerpt: String, val logTail: String) : TranscribeJobState

    /** Cancel was pressed: the request is registered instantly and
     *  shown at once; the job itself stops at the next part
     *  boundary (a native decode cannot be cut mid-part safely). */
    data object Cancelling : TranscribeJobState
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
                // Instant visible response (0.5.4): the flag stops
                // the job at the next part boundary, but the owner
                // sees his press registered NOW, not up to a part
                // later.
                if (job?.isActive == true) {
                    state.value = TranscribeJobState.Cancelling
                    updateNotificationForce(getString(R.string.m4_cancelling))
                }
            }
            ACTION_START -> {
                if (job?.isActive == true) return START_NOT_STICKY
                val uriStr = intent.getStringExtra(EXTRA_URI) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "video"
                val durationMs = intent.getLongExtra(EXTRA_DURATION_MS, 0L)
                val lang = intent.getStringExtra(EXTRA_LANG) ?: ""
                val title = intent.getStringExtra(EXTRA_TITLE)
                cancelFlag.set(false)
                startForeground(NOTIF_ID, buildNotification(getString(R.string.m4_notif_running), null, null))
                acquireWakeLock()
                job = scope.launch {
                    runPipeline(Uri.parse(uriStr), name, durationMs, lang, title)
                }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(uri: Uri, name: String, durationMs: Long, lang: String, title: String?) {
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
                    acquireWakeLock()
                    updateNotification(
                        getString(R.string.m4_transcribing, done, total), done, total
                    )
                },
                { cancelFlag.get() }
            )
            if (cancelFlag.get()) {
                finish(TranscribeJobState.Cancelled)
            } else {
                // 0.5.4: the result must not live only in memory —
                // write the SRT + TXT into Downloads the moment the
                // job completes, before anything can kill us.
                val saved = autoSave(name, title, segments)
                finish(TranscribeJobState.Done(segments, name, saved, title))
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
                getString(
                    if (terminal.autoSaved) R.string.m4_notif_done_saved
                    else R.string.m4_notif_done
                ),
                android.R.drawable.stat_sys_download_done
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

    /** Unthrottled notification swap for state the owner must see
     *  the instant it happens (a registered cancel press). */
    private fun updateNotificationForce(text: String) {
        lastNotifMs = System.currentTimeMillis()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIF_ID, buildNotification(text, null, null))
    }

    /**
     * Write the finished SRT + TXT into the public Downloads folder
     * via MediaStore (no storage permission needed for our own
     * files on API 29+). Returns false on any failure — the in-app
     * export buttons remain the fallback.
     */
    private fun autoSave(name: String, title: String?, segments: List<AsrSegment>): Boolean {
        if (segments.isEmpty()) return false
        if (Build.VERSION.SDK_INT < 29) return false
        val base = SubtitleFormat.exportBaseName(name, title)
        return try {
            writeDownload(
                base + "_subtitles.srt", "application/x-subrip",
                SubtitleFormat.buildSrt(segments)
            )
            writeDownload(
                base + "_transcript.txt", "text/plain",
                SubtitleFormat.buildTxt(segments)
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun writeDownload(displayName: String, mime: String, content: String) {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("MediaStore insert failed")
        resolver.openOutputStream(uri)?.use { out ->
            out.write(content.toByteArray(Charsets.UTF_8))
        } ?: throw IllegalStateException("MediaStore open failed")
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
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

    /** Acquires the wake lock, or — called again per part (0.5.7) —
     *  renews its deadline. The original one-shot 6-hour timeout
     *  could lapse in the middle of a very long film and let the
     *  CPU suspend again; renewing on every part keeps the lock
     *  alive for exactly as long as the job itself runs. */
    private fun acquireWakeLock() {
        val wl = wakeLock ?: run {
            val pm = getSystemService(PowerManager::class.java)
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoaistudio:asr").also {
                it.setReferenceCounted(false)
                wakeLock = it
            }
        }
        wl.acquire(WAKELOCK_TIMEOUT_MS)
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
        private const val EXTRA_TITLE = "title"

        /** The one job state both the service and the screen read. */
        val state = MutableStateFlow<TranscribeJobState>(TranscribeJobState.Idle)

        fun start(
            context: Context, uri: String, name: String,
            durationMs: Long, lang: String, title: String?
        ) {
            val intent = Intent(context, TranscribeService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_URI, uri)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_DURATION_MS, durationMs)
                putExtra(EXTRA_LANG, lang)
                putExtra(EXTRA_TITLE, title)
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
