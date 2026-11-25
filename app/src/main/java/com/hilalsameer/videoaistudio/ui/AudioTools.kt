package com.hilalsameer.videoaistudio.ui

import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.foundation.background
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hilalsameer.videoaistudio.R
import com.hilalsameer.videoaistudio.StudioState
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.data.formatSize
import com.hilalsameer.videoaistudio.data.inspectVideo
import com.hilalsameer.videoaistudio.media.GtcrnEngine
import com.hilalsameer.videoaistudio.media.MediaCommands
import com.hilalsameer.videoaistudio.media.MediaEngine
import com.hilalsameer.videoaistudio.media.ValidationReport
import com.hilalsameer.videoaistudio.media.exportsDir
import com.hilalsameer.videoaistudio.media.prepareInputFile
import com.hilalsameer.videoaistudio.media.saveToGallery
import com.hilalsameer.videoaistudio.media.validateExport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

enum class AudioKind { NOISE, ENHANCE }

private sealed interface AudioJob {
    data object Idle : AudioJob
    data object Preparing : AudioJob
    data class Running(val fraction: Float) : AudioJob
    data class Done(val input: File, val file: File, val report: ValidationReport) : AudioJob
    data class Failed(val excerpt: String, val logTail: String) : AudioJob
    data object Cancelled : AudioJob
}

@Composable
fun AudioToolScreen(kind: AudioKind, state: StudioState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { MediaEngine() }
    var job by remember { mutableStateOf<AudioJob>(AudioJob.Idle) }
    var softenStart by remember { mutableStateOf(true) }
    var preClean by remember { mutableStateOf(true) }
    var muteStart by remember { mutableStateOf(1.0f) }
    var previewProcessed by remember { mutableStateOf(false) }
    var comparePlaySignal by remember { mutableStateOf(0) }
    val gtcrnCancel = remember { AtomicBoolean(false) }

    val titleRes = if (kind == AudioKind.NOISE) R.string.tool_noise else R.string.tool_audio
    val runRes = if (kind == AudioKind.NOISE) R.string.m3_run_noise else R.string.m3_run_enhance

    // Gallery (photo) picker, videos only — owner requirement.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) { }
            state.currentUri = uri
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    try { inspectVideo(context, uri) } catch (e: Exception) { null }
                }
                if (info != null) state.currentInfo = info
            }
        }
    }

    val videoPickerRequest = androidx.activity.result.PickVisualMediaRequest(
        ActivityResultContracts.PickVisualMedia.VideoOnly
    )

    var savedToGallery by remember { mutableStateOf(false) }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { dest: Uri? ->
        val done = job as? AudioJob.Done ?: return@rememberLauncherForActivityResult
        if (dest != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(dest)?.use { out ->
                        done.file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    }
                } catch (e: Exception) { }
            }
        }
    }

    /** Save = publish into the Gallery; document picker is the fallback. */
    fun saveExport(file: File) {
        scope.launch {
            val ok = withContext(Dispatchers.IO) { saveToGallery(context, file) }
            if (ok) savedToGallery = true else saveLauncher.launch(file.name)
        }
    }

    fun startJob() {
        val uri = state.currentUri ?: return
        val info = state.currentInfo ?: return
        savedToGallery = false
        job = AudioJob.Preparing
        scope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    prepareInputFile(context, uri, info.name)
                }
                val base = info.name.substringBeforeLast('.', info.name)
                val outFile = File(
                    exportsDir(context),
                    base + if (kind == AudioKind.NOISE) "_denoised.mp4" else "_enhanced.mp4"
                )
                // The RNNoise model is still used by enhance-with-
                // pre-clean; materialize it once (assets are not
                // real files for FFmpeg).
                val needsModel = kind == AudioKind.ENHANCE && preClean
                val modelFile = if (needsModel) {
                    val mf = File(context.filesDir, "std.rnnn")
                    if (!mf.exists() || mf.length() != 302903L) {
                        context.assets.open("std.rnnn").use { inp ->
                            mf.outputStream().use { out -> inp.copyTo(out) }
                        }
                    }
                    mf
                } else null
                if (kind == AudioKind.NOISE) {
                    // GTCRN engine (TD-004): extract a 16 kHz WAV,
                    // enhance it on-device via ONNX Runtime, then
                    // remux the enhanced audio with the untouched
                    // video stream.
                    gtcrnCancel.set(false)
                    val gModel = withContext(Dispatchers.IO) { materializeGtcrn(context) }
                    val wavIn = File(context.cacheDir, "gtcrn_in.wav")
                    val wavOut = File(context.cacheDir, "gtcrn_out.wav")
                    fun fracOf(ms: Long) = if (info.durationMs > 0) {
                        (ms.toFloat() / info.durationMs).coerceIn(0f, 1f)
                    } else 0f
                    job = AudioJob.Running(0f)
                    val extract = engine.run(
                        MediaCommands.extractWav16k(input.absolutePath, wavIn.absolutePath)
                    ) { ms -> job = AudioJob.Running(0.05f * fracOf(ms)) }
                    if (extract.cancelled) {
                        job = AudioJob.Cancelled
                        return@launch
                    }
                    if (!extract.success) {
                        job = AudioJob.Failed(
                            MediaCommands.errorExcerpt(extract.output), extract.output.takeLast(600)
                        )
                        return@launch
                    }
                    withContext(Dispatchers.IO) {
                        GtcrnEngine(gModel).use { eng ->
                            eng.enhanceFile(
                                wavIn, wavOut,
                                { p -> job = AudioJob.Running(0.05f + 0.8f * p) },
                                { gtcrnCancel.get() }
                            )
                        }
                    }
                    if (gtcrnCancel.get()) {
                        job = AudioJob.Cancelled
                        return@launch
                    }
                    val remux = engine.run(
                        MediaCommands.remuxEnhanced(
                            input.absolutePath, wavOut.absolutePath, outFile.absolutePath,
                            softenStart, muteStart
                        )
                    ) { ms -> job = AudioJob.Running(0.85f + 0.15f * fracOf(ms)) }
                    when {
                        remux.cancelled -> job = AudioJob.Cancelled
                        remux.success -> {
                            val report = withContext(Dispatchers.IO) { validateExport(outFile) }
                            previewProcessed = true
                            job = AudioJob.Done(input, outFile, report)
                        }
                        else -> job = AudioJob.Failed(
                            MediaCommands.errorExcerpt(remux.output), remux.output.takeLast(600)
                        )
                    }
                    return@launch
                }
                // NOISE always returns above via the GTCRN flow
                // (the spectral modes were removed at the owner's
                // order, 0.4.1 — the neural mode earned the screen).
                val args = MediaCommands.enhanceAudio(
                    input.absolutePath, outFile.absolutePath,
                    modelFile?.absolutePath, preClean, softenStart
                )
                job = AudioJob.Running(0f)
                val result = engine.run(args) { processedMs ->
                    val frac = if (info.durationMs > 0) {
                        (processedMs.toFloat() / info.durationMs).coerceIn(0f, 1f)
                    } else 0f
                    job = AudioJob.Running(frac)
                }
                when {
                    result.cancelled -> job = AudioJob.Cancelled
                    result.success -> {
                        val report = withContext(Dispatchers.IO) { validateExport(outFile) }
                        previewProcessed = true
                        job = AudioJob.Done(input, outFile, report)
                    }
                    else -> job = AudioJob.Failed(MediaCommands.errorExcerpt(result.output), result.output.takeLast(600))
                }
            } catch (e: Exception) {
                job = AudioJob.Failed(e.message ?: "error", e.message ?: "error")
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        val info = state.currentInfo
        if (state.currentUri == null || info == null) {
            Text(
                text = stringResource(R.string.m2_need_import),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { picker.launch(videoPickerRequest) },
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.import_video)) }
            return@Column
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(stringResource(R.string.m2_input_file), style = MaterialTheme.typography.labelMedium)
                Text(info.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    "${formatDuration(info.durationMs)} · ${formatSize(info.sizeBytes)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        TextButton(onClick = { picker.launch(videoPickerRequest) }) {
            Text(stringResource(R.string.m2_change_video))
        }
        Spacer(Modifier.height(8.dp))

        if (kind == AudioKind.NOISE) {
            // 0.4.1 (owner's order): the spectral modes (Light /
            // Balanced / Strong) are deleted — the GTCRN neural
            // engine earned the screen outright ("الوضع الباقي جيد
            // الان"). What remains: the engine note, soften-start,
            // and the start mute.
            Text(
                stringResource(R.string.m3_mode_neural),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Row(
                modifier = Modifier.fillMaxWidth().clickable { softenStart = !softenStart }
                    .padding(vertical = 4.dp)
            ) {
                androidx.compose.material3.Checkbox(
                    checked = softenStart,
                    onCheckedChange = { softenStart = it }
                )
                Text(stringResource(R.string.m3_soften), modifier = Modifier.padding(top = 12.dp))
            }
            Text(
                stringResource(R.string.m3_mute),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(modifier = Modifier.padding(top = 4.dp)) {
                listOf(0f, 0.5f, 1f, 1.5f, 2f).forEach { sec ->
                    val selected = muteStart == sec
                    OutlinedButton(
                        onClick = { muteStart = sec },
                        modifier = Modifier.padding(end = 6.dp),
                        colors = if (selected) ButtonDefaults.outlinedButtonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) else ButtonDefaults.outlinedButtonColors()
                    ) { Text(if (sec == 0f) "0" else sec.toString()) }
                }
            }
            Text(
                stringResource(R.string.m3_engine_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().clickable { preClean = !preClean }
                    .padding(vertical = 4.dp)
            ) {
                androidx.compose.material3.Checkbox(
                    checked = preClean,
                    onCheckedChange = { preClean = it }
                )
                Text(stringResource(R.string.m3_preclean), modifier = Modifier.padding(top = 12.dp))
            }
            Row(
                modifier = Modifier.fillMaxWidth().clickable { softenStart = !softenStart }
                    .padding(vertical = 4.dp)
            ) {
                androidx.compose.material3.Checkbox(
                    checked = softenStart,
                    onCheckedChange = { softenStart = it }
                )
                Text(stringResource(R.string.m3_soften), modifier = Modifier.padding(top = 12.dp))
            }
            Text(
                stringResource(R.string.m3_enhance_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(Modifier.height(20.dp))
        when (val j = job) {
            is AudioJob.Idle, is AudioJob.Done, is AudioJob.Failed, is AudioJob.Cancelled -> {
                Button(onClick = { startJob() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(runRes))
                }
            }
            is AudioJob.Preparing -> {
                Row { CircularProgressIndicator(); Text("  " + stringResource(R.string.m2_preparing)) }
            }
            is AudioJob.Running -> {
                LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.m2_running, (j.fraction * 100).toInt()),
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { gtcrnCancel.set(true); engine.cancel() }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
        }

        when (val j = job) {
            is AudioJob.Done -> {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            stringResource(if (j.report.valid) R.string.m2_done else R.string.m2_invalid),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (j.report.valid) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.height(8.dp))
                        ARow(stringResource(R.string.m2_result_size), formatSize(j.report.sizeBytes))
                        ARow(stringResource(R.string.m2_result_duration), formatDuration(j.report.durationMs))
                        ARow(stringResource(R.string.m2_result_tracks), j.report.trackCount.toString())
                        ARow(stringResource(R.string.m2_chk_exists), if (j.report.exists) "✓" else "✗")
                        ARow(stringResource(R.string.m2_chk_size), if (j.report.sizeBytes > 0) "✓" else "✗")
                        ARow(stringResource(R.string.m2_chk_duration), if (j.report.durationMs > 0) "✓" else "✗")
                        ARow(stringResource(R.string.m2_chk_tracks), if (j.report.trackCount > 0) "✓" else "✗")
                        ARow(stringResource(R.string.m2_chk_sample), if (j.report.firstSampleReadable) "✓" else "✗")
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.m3_compare), style = MaterialTheme.typography.titleSmall)
                        Row(modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(
                                onClick = { previewProcessed = false; comparePlaySignal++ },
                                modifier = Modifier.weight(1f),
                                colors = if (!previewProcessed) ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ) else ButtonDefaults.outlinedButtonColors()
                            ) { Text(stringResource(R.string.m3_before)) }
                            Spacer(Modifier.padding(6.dp))
                            OutlinedButton(
                                onClick = { previewProcessed = true; comparePlaySignal++ },
                                modifier = Modifier.weight(1f),
                                colors = if (previewProcessed) ButtonDefaults.outlinedButtonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                ) else ButtonDefaults.outlinedButtonColors()
                            ) { Text(stringResource(R.string.m3_after)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        ComparePlayer(
                            file = if (previewProcessed) j.file else j.input,
                            playSignal = comparePlaySignal
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(
                            onClick = { saveExport(j.file) },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(stringResource(R.string.m2_save)) }
                        if (savedToGallery) {
                            Text(
                                stringResource(R.string.m2_saved_gallery),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                }
            }
            is AudioJob.Failed -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_failed), color = MaterialTheme.colorScheme.error)
                Text(
                    j.excerpt,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
                Text(j.logTail, style = MaterialTheme.typography.bodySmall)
            }
            is AudioJob.Cancelled -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_cancelled))
            }
            else -> { }
        }

        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { picker.launch(videoPickerRequest) }) {
            Text(stringResource(R.string.m2_change_video))
        }
    }
}

/**
 * The GTCRN model ships base64-encoded inside the assets (the
 * project's source channel carries text only); decode it once into
 * internal storage and verify its exact size.
 */
private fun materializeGtcrn(context: android.content.Context): File {
    val f = File(context.filesDir, "gtcrn.onnx")
    if (!f.exists() || f.length() != 535638L) {
        val b64 = context.assets.open("gtcrn.onnx.b64")
            .readBytes().toString(Charsets.US_ASCII)
        f.writeBytes(Base64.decode(b64, Base64.DEFAULT))
    }
    return f
}

@Composable
private fun ComparePlayer(file: File, playSignal: Int) {
    val context = LocalContext.current
    // 0.4.2: the owner reported the preview shows a frame but the
    // PlayerView controller's play button never starts playback
    // (both Original and Processed). The compare player therefore
    // carries its own explicit Play/Pause button OUTSIDE the
    // AndroidView — a Compose button always receives its click —
    // and any PlaybackException is printed under the player, so a
    // failure names itself on screen instead of staying invisible.
    var errorText by remember(file.absolutePath) { mutableStateOf<String?>(null) }
    var playing by remember(file.absolutePath) { mutableStateOf(false) }
    var fullscreen by remember(file.absolutePath) { mutableStateOf(false) }
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorText = error.errorCodeName + ": " + (error.message ?: "")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener); player.release() }
    }
    // 0.4.3 (owner's field report on 0.4.2): three polish fixes.
    // (a) PlayerView's own controller is disabled — its centre
    //     play circle never answered taps inside this scrollable
    //     Compose screen and stayed on screen as a dead promise;
    //     our own button below is the only control now.
    // (b) Tapping Original/Processed (playSignal changes) starts
    //     that side playing immediately, so each side is heard on
    //     its own — the comparison he asked for.
    // (c) A finished video restarts: in STATE_ENDED, play() alone
    //     does nothing ("plays only once"), so the button seeks
    //     back to 0 first.
    LaunchedEffect(playSignal) {
        if (playSignal > 0) {
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
    }
    // 0.4.4 (owner's request): while playing, the video can open
    // in an external full-screen frame — the inline box is small
    // for a portrait phone video. The SAME player instance moves
    // its surface into the dialog, so playback continues from the
    // same position; closing the frame hands the surface back to
    // the inline view (the update block re-attaches it).
    if (fullscreen) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false)
        ) {
            Column(
                modifier = Modifier.fillMaxSize().background(Color.Black)
                    .padding(12.dp)
            ) {
                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply { this.player = player; useController = false }
                    },
                    modifier = Modifier.fillMaxWidth().weight(1f)
                )
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Button(onClick = {
                        if (playing) player.pause() else {
                            if (player.playbackState ==
                                androidx.media3.common.Player.STATE_ENDED
                            ) player.seekTo(0)
                            player.play()
                        }
                    }) {
                        Text(stringResource(if (playing) R.string.m3_pause else R.string.m3_play))
                    }
                    Spacer(Modifier.padding(6.dp))
                    OutlinedButton(onClick = { fullscreen = false }) {
                        Text(stringResource(R.string.m3_close))
                    }
                }
            }
        }
    }
    Column {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply { this.player = player; useController = false }
            },
            update = { view -> if (!fullscreen) view.player = player },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
        )
        Row(modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = {
                if (playing) player.pause() else {
                    if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        player.seekTo(0)
                    }
                    player.play()
                }
            }) {
                Text(stringResource(if (playing) R.string.m3_pause else R.string.m3_play))
            }
            Spacer(Modifier.padding(6.dp))
            OutlinedButton(onClick = { fullscreen = true }) {
                Text(stringResource(R.string.m3_fullscreen))
            }
        }
        if (errorText != null) {
            Text(
                errorText!!,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun ARow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(value, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1.6f))
    }
}
