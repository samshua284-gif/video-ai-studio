package com.hilalsameer.videoaistudio.ui

import android.media.MediaPlayer
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hilalsameer.videoaistudio.R
import com.hilalsameer.videoaistudio.data.VideoInfo
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.MtEngine
import com.hilalsameer.videoaistudio.media.MtLanguages
import com.hilalsameer.videoaistudio.media.TranslationCorrection
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** One cue's re-translation state in the station: nothing,
 *  the engine working, a rendering on offer (never applied
 *  until «اعتماد»), or the engine's failure, stated verbatim. */
private sealed interface RtState {
    data object Working : RtState
    data class Offer(
        val offer: TranslationCorrection.RetranslateEvaluation.Offer
    ) : RtState
    data class Failed(val error: String?) : RtState
}

/**
 * 0.10.1 (TD-026) — the translation correction station, the
 * mirror of the transcription station (0.10.0) for a finished
 * translation: the offline translator Hilal asked to have
 * INSIDE the corrector.
 *
 * The screen's editing state is a list of TRANSLATED TEXTS
 * ONLY, one per cue. The [source] segments are displayed
 * read-only and key the memory and the re-translation; they
 * are never edited and never rebuilt. Saving rebuilds the
 * result through [TranslationCorrection.applyTextEdits] on
 * the original segments, so every timing in the saved result
 * is the segment's own value — the same structural lock as
 * the transcription station.
 *
 * «أعد ترجمة هذا السطر» runs the EXISTING [MtEngine] on the
 * cue's source text as a single unit — the unit rules do not
 * change. The outcome is judged by
 * [TranslationCorrection.evaluate] (TD-020 honesty per cue):
 * a real rendering is OFFERED in the card and replaces the
 * cue's text only on his «اعتماد» tap; a complete
 * pass-through or an engine error is shown verbatim and
 * offers nothing. The engine is the station's own instance,
 * released when the station closes (the service's engine is
 * released with its job and is not borrowed).
 *
 * [info] is the media the source was heard from, when known
 * (the source was the latest transcription); it powers only
 * the hear-the-cue preview, exactly as in the transcription
 * station. An imported SRT has no media: [info] is null and
 * the station works without preview.
 *
 * [memory] is the owner's translation correction memory
 * (source text to his rendering), read by the caller from
 * TranslateService.MEMORY_PREFS. [onSave] receives the
 * corrected segments and the pairs this session taught;
 * persistence is the caller's, as in the transcription
 * station.
 */
@Composable
fun TranslationCorrectionScreen(
    info: VideoInfo?,
    source: List<AsrSegment>,
    segments: List<AsrSegment>,
    srcLang: String,
    tgtLang: String,
    memory: Map<String, String>,
    onSave: (corrected: List<AsrSegment>, pairs: Map<String, String>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val texts = remember(segments) {
        mutableStateListOf(*segments.map { it.text }.toTypedArray())
    }
    fun current(): List<AsrSegment> =
        TranslationCorrection.applyTextEdits(
            segments, texts.mapIndexed { i, t -> i to t }.toMap()
        )
    val suggestions = TranslationCorrection.suggestions(source, current(), memory)
    var dismissed by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var appliedNote by remember { mutableStateOf<Int?>(null) }

    // ---- Re-translation (the station's own engine) ----
    val engine = remember { MtEngine(context.applicationContext) }
    DisposableEffect(engine) {
        onDispose {
            try { engine.release() } catch (e: Exception) { }
        }
    }
    val rtStates = remember { mutableStateMapOf<Int, RtState>() }
    val srcF = MtLanguages.flores(srcLang)
    val tgtF = MtLanguages.flores(tgtLang)

    fun retranslate(index: Int) {
        val s = srcF ?: return
        val t = tgtF ?: return
        if (rtStates[index] is RtState.Working) return
        rtStates[index] = RtState.Working
        val srcText = source[index].text
        scope.launch {
            rtStates[index] = try {
                val r = engine.translate(srcText, s, t)
                when (val ev = TranslationCorrection.evaluate(
                    TranslationCorrection.UnitOutcome(
                        r.text, r.unitsTotal, r.unitsOk, r.unitsPassed, r.firstError
                    )
                )) {
                    is TranslationCorrection.RetranslateEvaluation.Offer ->
                        RtState.Offer(ev)
                    is TranslationCorrection.RetranslateEvaluation.Failed ->
                        RtState.Failed(ev.error)
                }
            } catch (e: Exception) {
                RtState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    // ---- Hear-the-cue preview (the transcription station's
    // pattern: MediaPlayer for audio, a bounded VideoView
    // when the file's own inspection says it carries video) ----
    val isVideo = info?.videoMime != null
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var playerReady by remember { mutableStateOf(false) }
    var pendingStartMs by remember { mutableIntStateOf(0) }
    var videoView by remember { mutableStateOf<VideoView?>(null) }
    var activeCue by remember { mutableIntStateOf(-1) }
    var cueEndMs by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var previewFailed by remember { mutableStateOf(false) }

    fun pausePlayback() {
        try {
            if (isVideo) videoView?.pause() else player?.pause()
        } catch (e: Exception) { }
        playing = false
    }

    fun playCue(index: Int, seg: AsrSegment) {
        val inf = info ?: return
        if (previewFailed) return
        val startMs = (seg.startSec * 1000).toInt()
        cueEndMs = (seg.endSec * 1000).toInt().coerceAtLeast(startMs + 1)
        activeCue = index
        if (isVideo) {
            val vv = videoView ?: return
            try {
                vv.seekTo(startMs)
                vv.start()
                playing = true
            } catch (e: Exception) {
                previewFailed = true
            }
        } else {
            pendingStartMs = startMs
            val existing = player
            when {
                existing != null && playerReady -> {
                    try {
                        existing.seekTo(startMs)
                        existing.start()
                        playing = true
                    } catch (e: Exception) {
                        previewFailed = true
                    }
                }
                existing != null -> {
                    // A prepare is already in flight; its
                    // listener seeks to the latest request.
                }
                else -> {
                    try {
                        val mp = MediaPlayer()
                        mp.setDataSource(context, inf.uri)
                        mp.setOnPreparedListener { p ->
                            playerReady = true
                            try {
                                p.seekTo(pendingStartMs)
                                p.start()
                                playing = true
                            } catch (e: Exception) {
                                previewFailed = true
                            }
                        }
                        mp.setOnCompletionListener {
                            playing = false
                        }
                        mp.setOnErrorListener { _, _, _ ->
                            previewFailed = true
                            playing = false
                            true
                        }
                        mp.prepareAsync()
                        player = mp
                    } catch (e: Exception) {
                        previewFailed = true
                    }
                }
            }
        }
    }

    LaunchedEffect(playing, activeCue) {
        while (playing) {
            val pos = try {
                if (isVideo) videoView?.currentPosition ?: 0
                else player?.currentPosition ?: 0
            } catch (e: Exception) {
                0
            }
            if (activeCue >= 0 && pos >= cueEndMs) pausePlayback()
            delay(200)
        }
    }

    DisposableEffect(info?.uri) {
        onDispose {
            try { player?.release() } catch (e: Exception) { }
            player = null
            try { videoView?.stopPlayback() } catch (e: Exception) { }
            videoView = null
        }
    }

    Column {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.back)) }
        Text(
            text = stringResource(R.string.m5c_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.m5c_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        if (info != null && isVideo) {
            Spacer(Modifier.height(10.dp))
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).also { vv ->
                        videoView = vv
                        vv.setVideoURI(info.uri)
                        vv.setOnErrorListener { _, _, _ ->
                            previewFailed = true
                            playing = false
                            true
                        }
                        vv.setOnCompletionListener { playing = false }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(220.dp)
                    .background(Color.Black)
            )
        }
        if (previewFailed) {
            Text(
                stringResource(R.string.m4_preview_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
        }

        // ---- Find and replace across the translated cues ----
        Spacer(Modifier.height(14.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                OutlinedTextField(
                    value = find,
                    onValueChange = { find = it; appliedNote = null },
                    label = { Text(stringResource(R.string.m4c_find_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = replacement,
                    onValueChange = { replacement = it; appliedNote = null },
                    label = { Text(stringResource(R.string.m4c_replace_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                )
                Text(
                    stringResource(
                        R.string.m4c_will_change,
                        TranslationCorrection.countMatches(current(), find)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Button(
                    onClick = {
                        val r = TranslationCorrection.replaceAll(
                            current(), find, replacement
                        )
                        texts.clear()
                        texts.addAll(r.segments.map { it.text })
                        appliedNote = r.changedCues
                    },
                    enabled = find.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp)
                ) { Text(stringResource(R.string.m4c_replace_all)) }
                val note = appliedNote
                if (note != null) {
                    Text(
                        stringResource(R.string.m4c_changed, note),
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }
        }

        // ---- The cues themselves: source above, translation
        // below, the translator at hand ----
        Spacer(Modifier.height(14.dp))
        segments.forEachIndexed { i, seg ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row {
                        Text(
                            "${i + 1} · " +
                                formatDuration((seg.startSec * 1000).toLong()) +
                                " – " +
                                formatDuration((seg.endSec * 1000).toLong()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 10.dp)
                        )
                        if (info != null && !previewFailed) {
                            TextButton(
                                onClick = {
                                    if (activeCue == i && playing) {
                                        pausePlayback()
                                    } else {
                                        playCue(i, seg)
                                    }
                                }
                            ) {
                                Text(
                                    stringResource(
                                        if (activeCue == i && playing) {
                                            R.string.m4_preview_pause
                                        } else {
                                            R.string.m4c_play_from
                                        }
                                    )
                                )
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.m5c_source_label),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    // 0.10.2 (TD-027, the owner's added item):
                    // the source text is read-only but not
                    // inert — a SelectionContainer around the
                    // value alone lets him long-press, select
                    // and copy it. The container wraps only
                    // this Text: the edit field below keeps
                    // its own text-field selection, untouched.
                    SelectionContainer {
                        Text(
                            source.getOrNull(i)?.text ?: "",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                        )
                    }
                    OutlinedTextField(
                        value = texts[i],
                        onValueChange = { texts[i] = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (srcF != null && tgtF != null) {
                        when (val rt = rtStates[i]) {
                            is RtState.Working -> Text(
                                stringResource(R.string.m5c_retranslating),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                            is RtState.Offer -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth()
                                        .padding(top = 8.dp),
                                    colors = CardDefaults.cardColors(
                                        containerColor =
                                            MaterialTheme.colorScheme.primaryContainer
                                    )
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Text(
                                            stringResource(R.string.m5c_offer_label),
                                            style = MaterialTheme.typography.labelMedium
                                        )
                                        Text(
                                            rt.offer.text,
                                            style = MaterialTheme.typography.bodyMedium,
                                            modifier = Modifier.padding(top = 2.dp)
                                        )
                                        if (rt.offer.passedUnits > 0) {
                                            Text(
                                                stringResource(
                                                    R.string.m5c_passed_note,
                                                    rt.offer.passedUnits,
                                                    rt.offer.totalUnits
                                                ),
                                                color = MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.bodySmall,
                                                modifier = Modifier.padding(top = 4.dp)
                                            )
                                        }
                                        Row(modifier = Modifier.padding(top = 4.dp)) {
                                            Button(onClick = {
                                                texts[i] = rt.offer.text
                                                rtStates.remove(i)
                                            }) {
                                                Text(stringResource(R.string.m5c_accept))
                                            }
                                            TextButton(
                                                onClick = { rtStates.remove(i) }
                                            ) {
                                                Text(stringResource(R.string.m4c_dismiss))
                                            }
                                        }
                                    }
                                }
                            }
                            is RtState.Failed -> {
                                Text(
                                    stringResource(R.string.m5c_error_label),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(top = 6.dp)
                                )
                                Text(
                                    rt.error ?: stringResource(
                                        R.string.m5c_no_error_detail
                                    ),
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                TextButton(onClick = { rtStates.remove(i) }) {
                                    Text(stringResource(R.string.m4c_dismiss))
                                }
                            }
                            null -> OutlinedButton(
                                onClick = { retranslate(i) },
                                modifier = Modifier.padding(top = 6.dp)
                            ) {
                                Text(stringResource(R.string.m5c_retranslate))
                            }
                        }
                    }
                    val suggestion = suggestions[i]
                    if (suggestion != null && i !in dismissed) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor =
                                    MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp)) {
                                Text(
                                    stringResource(
                                        R.string.m4c_suggestion, suggestion
                                    ),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Row(modifier = Modifier.padding(top = 4.dp)) {
                                    Button(onClick = { texts[i] = suggestion }) {
                                        Text(stringResource(R.string.m4c_apply))
                                    }
                                    TextButton(
                                        onClick = { dismissed = dismissed + i }
                                    ) {
                                        Text(stringResource(R.string.m4c_dismiss))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        Button(
            onClick = {
                val corrected = current()
                onSave(
                    corrected,
                    TranslationCorrection.harvest(source, segments, corrected)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.m4c_save)) }
        Spacer(Modifier.height(8.dp))
    }
}
