package com.hilalsameer.videoaistudio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.CorrectionMemory
import com.hilalsameer.videoaistudio.media.DraftsRepository
import com.hilalsameer.videoaistudio.media.MtEngine
import com.hilalsameer.videoaistudio.media.MtLoadTracker
import com.hilalsameer.videoaistudio.media.MtLanguages
import com.hilalsameer.videoaistudio.media.SrtParser
import com.hilalsameer.videoaistudio.media.TranslateFix
import com.hilalsameer.videoaistudio.media.TranslationDraft
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Observable state of a translation job (Milestone 5). */
sealed interface TranslateJobState {
    data object Idle : TranslateJobState

    /** Model-load phase with real per-file progress (TD-019):
     *  [filesDone] of [filesTotal] NLLB files fully loaded, and
     *  the file currently loading, if one is known. */
    data class LoadingModel(
        val filesDone: Int, val filesTotal: Int, val currentFile: String?
    ) : TranslateJobState
    data class Translating(val done: Int, val total: Int) : TranslateJobState
    data class Done(
        val segments: List<AsrSegment>,
        val source: List<AsrSegment>,
        val numberFlags: List<Int>,
        val inputName: String,
        /** Segments containing at least one unit that passed
         *  through untranslated (TD-020) — 0 on a clean job;
         *  the Done screen and the final notification both
         *  state the count when it is not. */
        val passedSegments: Int = 0,
        /** The job's language pair, as UI codes (0.10.1,
         *  TD-026): the translation correction station
         *  re-translates single cues and needs the pair the
         *  job actually used, and the work draft persists it
         *  so a restored result keeps its own pair. */
        val srcLang: String = "en",
        val tgtLang: String = "ar"
    ) : TranslateJobState

    data class Failed(val message: String) : TranslateJobState
    data object Cancelled : TranslateJobState
    data object Cancelling : TranslateJobState
}

/**
 * Foreground service running a whole-file translation (Milestone
 * 5) with the same survival contract as transcription (TD-008):
 * the job lives here — not in the screen — under a partial wake
 * lock renewed per segment, with a progress notification and an
 * honest cancel. The screen only starts the job and observes it.
 *
 * Segments are translated one by one and keep their source
 * timestamps untouched, so the translated SRT is synchronised by
 * construction. Per segment, in order: the owner's correction
 * memory (TD-006 Layer 3) serves his own past corrections
 * verbatim; the NLLB engine translates the rest; TD-006 Layer 1
 * then verifies the segment's numbers survived — a segment whose
 * numbers did not is FLAGGED in the result, never silently
 * rewritten. Saving is MANUAL-ONLY (0.9.4, TD-022 — the owner's
 * explicit decision, mirroring transcription's 0.7.0 rule): this
 * service writes no file at completion; the result lives in the
 * Done state and is saved only from the screen's export buttons.
 *
 * TD-020: the engine now reports per segment how many of its
 * units really translated and how many passed through (see
 * MtResult). The job aggregates those counts; if EVERY segment
 * came back fully untranslated the job ends Failed with the
 * engine's first real error — never as a fake success — and a
 * partially passed job carries its count into the Done state
 * and the final notification. Correction-memory segments are
 * the owner's own words and never count as pass-throughs.
 */
class TranslateService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val cancelFlag = AtomicBoolean(false)
    private var mt: MtEngine? = null
    private var lastNotifMs = 0L
    // 0.9.0 (TD-017): progress notifications advance on whole
    // percent changes, not on a clock — a 113-cue file used to
    // leave the owner staring at a notification that told him
    // nothing about how far along the job was.
    private var lastNotifPct = -1

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.tool_translate),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                cancelFlag.set(true)
                if (state.value is TranslateJobState.Translating ||
                    state.value is TranslateJobState.LoadingModel
                ) {
                    state.value = TranslateJobState.Cancelling
                    updateNotificationForce(getString(R.string.m4_cancelling))
                }
            }
            ACTION_START -> {
                if (job?.isActive == true) return START_NOT_STICKY
                val srt = intent.getStringExtra(EXTRA_SRT) ?: return START_NOT_STICKY
                val name = intent.getStringExtra(EXTRA_NAME) ?: "video"
                val title = intent.getStringExtra(EXTRA_TITLE)
                val src = intent.getStringExtra(EXTRA_SRC) ?: "en"
                val tgt = intent.getStringExtra(EXTRA_TGT) ?: "ar"
                // 0.10.1 (TD-026): a new job replaces the work
                // draft, exactly as in transcription.
                restoredFromDraft = false
                DraftsRepository.clearTranslation(applicationContext)
                cancelFlag.set(false)
                lastNotifPct = -1
                startForeground(
                    NOTIF_ID,
                    buildNotification(getString(R.string.m5_notif_running), null, null)
                )
                acquireWakeLock()
                job = scope.launch { runPipeline(srt, name, title, src, tgt) }
            }
        }
        return START_NOT_STICKY
    }

    private suspend fun runPipeline(
        srt: String, name: String, title: String?, src: String, tgt: String
    ) {
        try {
            val source = SrtParser.parse(srt)
            if (source.isEmpty()) {
                finish(TranslateJobState.Failed(getString(R.string.m5_err_empty)))
                return
            }
            val srcF = MtLanguages.flores(src)
            val tgtF = MtLanguages.flores(tgt)
            if (srcF == null || tgtF == null) {
                finish(TranslateJobState.Failed(getString(R.string.m5_err_language)))
                return
            }
            // TD-019: the load phase reports real per-file
            // progress (the owner's whole wait used to happen
            // here, behind a bare spinner), and both engine waits
            // take the cancel flag so a stop lands in ~250 ms.
            val tracker = MtLoadTracker()
            state.value = TranslateJobState.LoadingModel(
                0, tracker.totalFiles, null
            )
            updateNotification(
                getString(R.string.m5_notif_loading), 0, tracker.totalFiles
            )
            val engine = mt ?: MtEngine(applicationContext).also { mt = it }
            engine.onLoadProgress = { file, status, pct ->
                val snap = tracker.onEvent(file, status, pct)
                if (state.value is TranslateJobState.LoadingModel) {
                    state.value = TranslateJobState.LoadingModel(
                        snap.done, snap.total, snap.currentFile
                    )
                }
                updateNotification(
                    getString(R.string.m5_notif_loading) +
                        (snap.currentFile?.let { " — $it" } ?: ""),
                    snap.done, snap.total
                )
            }
            if (!engine.ensureLoaded { cancelFlag.get() }) {
                if (cancelFlag.get()) {
                    finish(TranslateJobState.Cancelled)
                    return
                }
                finish(
                    TranslateJobState.Failed(
                        getString(R.string.m5_err_model) + ": " + (engine.lastError ?: "")
                    )
                )
                return
            }
            if (cancelFlag.get()) {
                finish(TranslateJobState.Cancelled)
                return
            }
            // TD-006 Layer 3: the owner's own corrections, loaded once.
            val memory: Map<String, String> =
                getSharedPreferences(MEMORY_PREFS, Context.MODE_PRIVATE).all
                    .mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
                    .toMap()
            val out = ArrayList<AsrSegment>(source.size)
            val flags = ArrayList<Int>()
            // TD-020 pass-through accounting (engine-served
            // segments only — correction-memory segments are
            // the owner's own translations and never count).
            var segmentsFullyPassed = 0
            var segmentsPartiallyPassed = 0
            var jobFirstError: String? = null
            for ((i, seg) in source.withIndex()) {
                if (cancelFlag.get()) {
                    finish(TranslateJobState.Cancelled)
                    return
                }
                val fromMemory = CorrectionMemory.lookup(seg.text, memory)
                val translated: String
                if (fromMemory != null) {
                    translated = fromMemory
                } else {
                    val r = engine.translate(
                        seg.text, srcF, tgtF
                    ) { cancelFlag.get() }
                    translated = r.text
                    if (jobFirstError == null) jobFirstError = r.firstError
                    when {
                        r.unitsTotal > 0 && r.unitsPassed >= r.unitsTotal ->
                            segmentsFullyPassed++
                        r.unitsPassed > 0 -> segmentsPartiallyPassed++
                    }
                }
                if (!TranslateFix.numbersPreserved(seg.text, translated)) {
                    flags.add(i)
                }
                out.add(AsrSegment(seg.startSec, seg.endSec, translated))
                state.value = TranslateJobState.Translating(i + 1, source.size)
                acquireWakeLock()
                val pct = (i + 1) * 100 / source.size
                updateNotification(
                    getString(R.string.m5_translating, i + 1, source.size, pct),
                    i + 1, source.size
                )
            }
            if (cancelFlag.get()) {
                finish(TranslateJobState.Cancelled)
                return
            }
            // TD-020: the end-of-job honesty decision. A job in
            // which every segment came back as its own source
            // text is a FAILURE with the engine's first real
            // error attached — it is not saved to Downloads and
            // never presented as a completed translation.
            when (val outcome = TranslateFix.outcome(
                source.size, segmentsFullyPassed, segmentsPartiallyPassed,
                jobFirstError
            )) {
                is TranslateFix.TranslateOutcome.FailedAllPassed -> {
                    val message = buildString {
                        append(getString(R.string.m5_err_all_passed))
                        val err = outcome.firstError
                        if (err != null) {
                            append(": ").append(err)
                        } else {
                            append(" ").append(getString(R.string.m5_err_all_empty))
                        }
                    }
                    finish(TranslateJobState.Failed(message))
                }
                is TranslateFix.TranslateOutcome.DoneWithWarning -> {
                    finish(
                        TranslateJobState.Done(
                            out, source, flags, name,
                            passedSegments = outcome.passedSegments,
                            srcLang = src, tgtLang = tgt
                        )
                    )
                }
                TranslateFix.TranslateOutcome.DoneClean -> {
                    finish(
                        TranslateJobState.Done(
                            out, source, flags, name,
                            srcLang = src, tgtLang = tgt
                        )
                    )
                }
            }
        } catch (e: Exception) {
            if (cancelFlag.get()) {
                finish(TranslateJobState.Cancelled)
            } else {
                finish(TranslateJobState.Failed(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    private fun finish(terminal: TranslateJobState) {
        // 0.10.1 (TD-026): a finished translation writes its
        // work draft — app-internal storage only, so the
        // result survives leaving the tool or the app. Not a
        // save: no file appears anywhere the owner can see
        // and no Project is registered (see DraftsRepository);
        // the manual-save rule of TD-022 stands untouched.
        if (terminal is TranslateJobState.Done) {
            lastMediaUri = sourceMediaUri
            DraftsRepository.saveTranslation(
                applicationContext,
                TranslationDraft(
                    terminal.segments, terminal.source, terminal.numberFlags,
                    terminal.inputName, terminal.passedSegments,
                    terminal.srcLang, terminal.tgtLang, sourceMediaUri,
                    System.currentTimeMillis()
                )
            )
            sourceMediaUri = null
        }
        state.value = terminal
        releaseWakeLock()
        when (terminal) {
            is TranslateJobState.Done -> {
                // TD-022: no auto-save exists, so the one Done
                // text states the plain fact — open the app to
                // see and export the result.
                var text = getString(R.string.m5_notif_done)
                // TD-020: the pass-through count is stated in
                // the notification too, not only on the screen.
                if (terminal.passedSegments > 0) {
                    text += " " + getString(
                        R.string.m5_passed_warning,
                        terminal.passedSegments, terminal.segments.size
                    )
                }
                postFinalNotification(
                    text, android.R.drawable.stat_sys_download_done
                )
            }
            is TranslateJobState.Failed -> postFinalNotification(
                getString(R.string.m5_notif_failed), android.R.drawable.stat_notify_error
            )
            else -> { }
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun buildNotification(text: String, progress: Int?, max: Int?): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 10,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val cancelIntent = PendingIntent.getService(
            this, 11,
            Intent(this, TranslateService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val b = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.tool_translate))
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
        if (progress != null && max != null && max > 0) {
            // TD-017: progress updates fire on whole-percent
            // changes only — at most 100 notifications per job,
            // each one carrying a number the owner can act on.
            val pct = progress * 100 / max
            if (pct == lastNotifPct) return
            lastNotifPct = pct
        } else {
            val now = System.currentTimeMillis()
            if (now - lastNotifMs < 1000L) return
            lastNotifMs = now
        }
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, progress, max))
    }

    private fun updateNotificationForce(text: String) {
        lastNotifMs = System.currentTimeMillis()
        lastNotifPct = -1
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_ID, buildNotification(text, null, null))
    }

    private fun postFinalNotification(text: String, icon: Int) {
        val openIntent = PendingIntent.getActivity(
            this, 12,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(getString(R.string.tool_translate))
            .setContentText(text)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(DONE_NOTIF_ID, n)
    }

    /** Same renewal discipline as transcription (0.5.7): acquiring
     *  again on the same lock pushes its deadline forward. */
    private fun acquireWakeLock() {
        val wl = wakeLock ?: run {
            val pm = getSystemService(PowerManager::class.java)
            pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "videoaistudio:mt").also {
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
        job?.cancel()
        releaseWakeLock()
        try { mt?.release() } catch (e: Exception) { }
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL_ID = "translate"
        private const val NOTIF_ID = 51
        private const val DONE_NOTIF_ID = 52
        private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L
        const val MEMORY_PREFS = "translation_memory"

        private const val ACTION_START = "com.hilalsameer.videoaistudio.MT_START"
        private const val ACTION_CANCEL = "com.hilalsameer.videoaistudio.MT_CANCEL"
        private const val EXTRA_SRT = "srt"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_SRC = "src"
        private const val EXTRA_TGT = "tgt"

        val state = MutableStateFlow<TranslateJobState>(TranslateJobState.Idle)

        /** 0.10.1 (TD-026): the media the translation's source
         *  was heard from, when the screen knows it (the source
         *  was «آخر تفريغ», whose media is the transcription's
         *  input). The screen sets this just before [start];
         *  [finish] consumes it into the work draft, where it
         *  lets the translation correction station offer
         *  hear-the-cue after a restore. Null when the source
         *  has no media (an imported SRT) — the station then
         *  honestly works without preview. */
        @Volatile
        var sourceMediaUri: String? = null

        /** 0.10.1 (TD-026): true only while the Done state on
         *  screen came back from the work draft — stated
         *  plainly on the screen, as in transcription. */
        @Volatile
        var restoredFromDraft: Boolean = false
            private set

        /** The current result's media, carried across
         *  correction-station saves so their drafts stay
         *  complete. */
        @Volatile private var lastMediaUri: String? = null

        /**
         * 0.10.1 (TD-026): the translation correction
         *  station's save — the corrected cues become THE
         *  translation result, under the same size guard as
         *  transcription's station (correction can neither
         *  add nor remove a cue). Every consumer of the Done
         *  state — the SRT/TXT exports and the Projects
         *  registration at manual export — then works from
         *  the corrected text with the original timings. The
         *  save also refreshes the work draft.
         */
        fun applyCorrectedSegments(context: Context, corrected: List<AsrSegment>) {
            val cur = state.value as? TranslateJobState.Done ?: return
            if (corrected.size != cur.segments.size) return
            val next = cur.copy(segments = corrected)
            state.value = next
            DraftsRepository.saveTranslation(
                context,
                TranslationDraft(
                    next.segments, next.source, next.numberFlags,
                    next.inputName, next.passedSegments,
                    next.srcLang, next.tgtLang, lastMediaUri,
                    System.currentTimeMillis()
                )
            )
        }

        /** 0.10.1 (TD-026): brings a saved translation draft
         *  back as the Done state when the tool opens idle. */
        fun restoreFromDraft(draft: TranslationDraft) {
            lastMediaUri = draft.mediaUri
            restoredFromDraft = true
            state.value = TranslateJobState.Done(
                draft.segments, draft.source, draft.numberFlags,
                draft.inputName, draft.passedSegments,
                draft.srcLang, draft.tgtLang
            )
        }

        fun start(
            context: Context, srt: String, name: String,
            title: String?, src: String, tgt: String
        ) {
            val intent = Intent(context, TranslateService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_SRT, srt)
                putExtra(EXTRA_NAME, name)
                putExtra(EXTRA_TITLE, title)
                putExtra(EXTRA_SRC, src)
                putExtra(EXTRA_TGT, tgt)
            }
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun cancel(context: Context) {
            val intent = Intent(context, TranslateService::class.java).apply {
                action = ACTION_CANCEL
            }
            context.startService(intent)
        }
    }
}
