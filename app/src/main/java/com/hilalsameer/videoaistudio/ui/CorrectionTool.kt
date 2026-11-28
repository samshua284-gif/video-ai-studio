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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.hilalsameer.videoaistudio.media.TranscriptionCorrection
import kotlinx.coroutines.delay

/**
 * 0.10.0 (TD-025) — the transcription correction station.
 *
 * The screen's editing state is a list of TEXTS ONLY, one
 * per cue, in cue order. It never holds a start or an end:
 * saving rebuilds the result through
 * [TranscriptionCorrection.applyTextEdits] on the original
 * segments, so every timing and speech span in the saved
 * result is the recognized segment's own value. The lock
 * is in the shape of the data, not in a read-only flag.
 *
 * [info] is the imported source file, used only for the
 * hear-the-cue preview (the InputPreview pattern of 0.9.2:
 * MediaPlayer for audio, a bounded VideoView when the
 * file's own inspection says it carries video). When the
 * screen was recreated and the file is forgotten, [info]
 * is null and the station simply works without preview —
 * correction never depended on it.
 *
 * [memory] is the owner's correction memory (recognized
 * text to his text), read by the caller from
 * TranscribeService.CORRECTION_PREFS. [onSave] receives
 * the corrected segments and the pairs this session
 * taught; persistence is the caller's, as in TranslateTool.
 */
@Composable
fun CorrectionScreen(
    info: VideoInfo?,
    segments: List<AsrSegment>,
    memory: Map<String, String>,
    onSave: (corrected: List<AsrSegment>, pairs: Map<String, String>) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current

    // The Layer-1 mechanism runs at the door (TD-025): the
    // shipped rule list is empty, so this is the identity
    // today — and the one place an evidenced future rule
    // would land. The owner's memory below is the live
    // source of fixes.
    val base = remember(segments) {
        TranscriptionCorrection.applyRules(
            segments, TranscriptionCorrection.BUILT_IN_RULES
        ).segments
    }
    val texts = remember(base) {
        mutableStateListOf(*base.map { it.text }.toTypedArray())
    }
    fun current(): List<AsrSegment> =
        TranscriptionCorrection.applyTextEdits(
            base, texts.mapIndexed { i, t -> i to t }.toMap()
        )
    val suggestions = TranscriptionCorrection.suggestions(current(), memory)
    var dismissed by remember { mutableStateOf<Set<Int>>(emptySet()) }

    var find by remember { mutableStateOf("") }
    var replacement by remember { mutableStateOf("") }
    var appliedNote by remember { mutableStateOf<Int?>(null) }

    // ---- Hear-the-cue preview (InputPreview pattern) ----
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

    // Auditioning stops at the cue's own end: the poll is
    // the same 200 ms rhythm the input preview uses for
    // its readout.
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
            text = stringResource(R.string.m4c_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            stringResource(R.string.m4c_hint),
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

        // ---- Find and replace across all cues ----
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
                        TranscriptionCorrection.countMatches(current(), find)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
                Button(
                    onClick = {
                        val r = TranscriptionCorrection.replaceAll(
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

        // ---- The cues themselves ----
        Spacer(Modifier.height(14.dp))
        base.forEachIndexed { i, seg ->
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
                    OutlinedTextField(
                        value = texts[i],
                        onValueChange = { texts[i] = it },
                        modifier = Modifier.fillMaxWidth()
                    )
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
                    TranscriptionCorrection.harvest(base, corrected)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.m4c_save)) }
        Spacer(Modifier.height(8.dp))
    }
}
