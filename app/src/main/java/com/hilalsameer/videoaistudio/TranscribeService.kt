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
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.media.AsrEngine
import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.DraftsRepository
import com.hilalsameer.videoaistudio.media.MediaCommands
import com.hilalsameer.videoaistudio.media.MediaEngine
import com.hilalsameer.videoaistudio.media.TranscriptionDraft
import com.hilalsameer.videoaistudio.media.prepareInputFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** UI-visible state of the transcription job, owned by the service. */
sealed interface TranscribeJobState {
    data object Idle : TranscribeJobState
    data object Preparing : TranscribeJobState
    data class Extracting(val fraction: Float) : TranscribeJobState

    /** The speech model is loading (0.9.4, TD-022): a named
     *  phase of its own, because it is the longest wait in the
     *  pipeline and until 0.9.4 it ran invisibly inside the
     *  engine call behind a generic label. It carries no
     *  percent — the native load reports none, and none is
     *  fabricated. */
    data object LoadingEngine : TranscribeJobState

    /** Decoding under way. [chunkElapsedSec] is the live clock
     *  of the chunk currently being decoded (0.9.5, TD-023):
     *  a chunk decode is one opaque native call with no
     *  internal progress to report, so the honest moving
     *  number is how long the current atom has been cooking —
     *  re-emitted once a second by the service's ticker. */
    data class Transcribing(
        val done: Int,
        val total: Int,
        val chunkElapsedSec: Int = 0
    ) : TranscribeJobState
    data class Done(
        val segments: List<AsrSegment>,
        val inputName: String,
        val title: String? = null
    ) : TranscribeJobState
    data class Failed(val excerpt: String, val logTail: String) : TranscribeJobState

    /** Cancel was pressed: the request is registered instantly
     *  and shown at once. Since 0.9.5 (TD-023) the job thread
     *  abandons its decode await within a poll slice, so this
     *  state is brief; any decode still in flight finishes on
     *  the decode thread in the background and is discarded. */
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

    // The live clock's single source (0.9.5, TD-023): the
    // progress callback owns (done, total) and restarts the
    // current chunk's clock; the ticker only reads and re-emits.
    @Volatile private var progDone = 0
    @Volatile private var progTotal = 0
    @Volatile private var chunkStartMs = 0L
    private var tickerJob: Job? = null
    private var lastTickNotifMs = 0L

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
                // 0.10.1 (TD-026): a new job replaces the work
                // draft — the old result's safety net is cleared
                // with the old result, and the job's own media
                // and language are remembered so the draft this
                // job writes (and any correction-station save
                // after it) can carry them.
                lastUri = uriStr
                lastLang = lang
                restoredFromDraft = false
                DraftsRepository.clearTranscription(applicationContext)
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
                val pct = (frac * 100).roundToInt()
                updateNotification(getString(R.string.m4_extracting, pct), pct, 100)
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
            val engineAsr = asr ?: AsrEngine(applicationContext).also { asr = it }
            // 0.9.4 (TD-022): the model load is a named phase,
            // run BEFORE transcribe() — and a cancel pressed
            // during it is honoured the moment it returns,
            // before any VAD or decode work happens. Until
            // 0.9.4 the load ran inside transcribe() behind
            // the generic preparing label, and a cancel waited
            // it out in silence.
            state.value = TranscribeJobState.LoadingEngine
            updateNotificationForce(getString(R.string.m4_loading_engine))
            // 0.9.5 (TD-023): the construction runs on the
            // decode thread and its await polls the cancel flag
            // — a cancel during the load frees this phase at
            // once (false), and the existing check below ends
            // the job before any VAD or decode work happens.
            val ready = engineAsr.prepare(lang) { cancelFlag.get() }
            if (!ready || cancelFlag.get()) {
                finish(TranscribeJobState.Cancelled)
                return
            }
            progDone = 0
            progTotal = 0
            chunkStartMs = System.currentTimeMillis()
            state.value = TranscribeJobState.Transcribing(0, 0)
            updateNotificationForce(getString(R.string.m4_analysing))
            // The live clock (0.9.5, TD-023): once a second,
            // re-emit the Transcribing state with the current
            // chunk's elapsed seconds, and refresh the
            // notification's text with the same clock at most
            // every ~2 s (its bar already carries done/total).
            // The ticker never fights a registered cancel: it
            // stops the moment the flag rises or the state
            // leaves Transcribing.
            tickerJob = scope.launch {
                while (true) {
                    delay(1000)
                    if (cancelFlag.get()) break
                    if (state.value !is TranscribeJobState.Transcribing) break
                    val elapsed =
                        ((System.currentTimeMillis() - chunkStartMs) / 1000).toInt()
                    state.value = TranscribeJobState.Transcribing(
                        progDone, progTotal, elapsed
                    )
                    val now = System.currentTimeMillis()
                    if (progTotal > 0 && now - lastTickNotifMs >= 2000) {
                        lastTickNotifMs = now
                        updateNotification(
                            getString(
                                R.string.m4_chunk_elapsed,
                                formatDuration(elapsed * 1000L)
                            ),
                            progDone, progTotal
                        )
                    }
                }
            }
            val windowSegments = try {
                engineAsr.transcribe(
                    wav, lang,
                    { done, total ->
                        progDone = done
                        progTotal = total
                        chunkStartMs = System.currentTimeMillis()
                        state.value = TranscribeJobState.Transcribing(done, total)
                        acquireWakeLock()
                        if (total > 0) {
                            updateNotification(
                                getString(
                                    R.string.m4_transcribing, done, total,
                                    done * 100 / total
                                ),
                                done, total
                            )
                        } else {
                            // No plan, no counter: the honest label
                            // for the VAD + planning window (TD-022).
                            updateNotification(
                                getString(R.string.m4_analysing), null, null
                            )
                        }
                    },
                    { cancelFlag.get() }
                )
            } finally {
                tickerJob?.cancel()
            }
            // 0.6.4: Whisper's window segments become sentence
            // cues before anything downstream sees them (Done
            // state, translation source) — one giant cue per
            // window was the owner's field report. 0.7.0: the
            // windows themselves are now speech chunks.
            val segments = com.hilalsameer.videoaistudio.media.SubtitleSegmenter
                .resegment(windowSegments)
            if (cancelFlag.get()) {
                finish(TranscribeJobState.Cancelled)
            } else {
                // 0.7.0: auto-save is GONE (the owner's order —
                // "manual saving is better"). Nothing is written
                // to Downloads on completion; the result lives in
                // the Done state, and saving happens only from the
                // export buttons the owner presses himself.
                // 0.10.1 (TD-026): what IS written is the work
                // draft — app-internal storage only, so leaving
                // the tool or the app can no longer discard the
                // result. A draft is not a save: no file appears
                // anywhere the owner can see, and no Project is
                // registered (see DraftsRepository).
                DraftsRepository.saveTranscription(
                    applicationContext,
                    TranscriptionDraft(
                        segments, name, title, uri.toString(), lang,
                        System.currentTimeMillis()
                    )
                )
                finish(TranscribeJobState.Done(segments, name, title))
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
                getString(R.string.m4_notif_done),
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

        /** 0.10.0 (TD-025): the owner's hand corrections to a
         *  finished transcription, keyed by the recognized
         *  text — the transcription counterpart of
         *  TranslateService.MEMORY_PREFS. */
        const val CORRECTION_PREFS = "transcription_correction_memory"

        /** The one job state both the service and the screen read. */
        val state = MutableStateFlow<TranscribeJobState>(TranscribeJobState.Idle)

        /** 0.10.1 (TD-026): true only while the Done state on
         *  screen came back from the work draft (see
         *  [restoreFromDraft]) — the screen states it plainly,
         *  so a restored result never masquerades as a job the
         *  owner just ran. A new job or a fresh pipeline result
         *  clears it. */
        @Volatile
        var restoredFromDraft: Boolean = false
            private set

        /** The last job's media and language, remembered at
         *  ACTION_START so a correction-station save can write
         *  a complete draft without re-asking the screen. */
        @Volatile private var lastUri: String? = null
        @Volatile private var lastLang: String = ""

        /**
         * 0.10.0 (TD-025): the corrected cues become THE
         * transcription result. The Done state is replaced by
         * one carrying [corrected], so every consumer of the
         * result — the transcription screen's SRT/TXT exports
         * and the translation tool's in-memory source — sees
         * the corrected text with the original timings. The
         * size guard is the structural half of the station's
         * contract: correction can neither add nor remove a
         * cue, so a list of any other size is not a
         * correction and is refused.
         *
         * 0.10.1 (TD-026): the save also refreshes the work
         * draft — the corrected list is the working list now,
         * and it is the draft that must survive an exit.
         */
        fun applyCorrectedSegments(context: Context, corrected: List<AsrSegment>) {
            val cur = state.value as? TranscribeJobState.Done ?: return
            if (corrected.size != cur.segments.size) return
            state.value = cur.copy(segments = corrected)
            DraftsRepository.saveTranscription(
                context,
                TranscriptionDraft(
                    corrected, cur.inputName, cur.title, lastUri, lastLang,
                    System.currentTimeMillis()
                )
            )
        }

        /**
         * 0.10.1 (TD-026): brings a saved draft back as the
         * Done state when the tool opens idle. The draft's
         * media and language become the remembered ones, so a
         * correction save after a restore still writes a
         * complete draft.
         */
        fun restoreFromDraft(draft: TranscriptionDraft) {
            lastUri = draft.mediaUri
            lastLang = draft.language
            restoredFromDraft = true
            state.value = TranscribeJobState.Done(
                draft.segments, draft.inputName, draft.title
            )
        }

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
