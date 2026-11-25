package com.hilalsameer.videoaistudio.ui

import android.content.Intent
import android.net.Uri
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
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
    var mode by remember { mutableStateOf(1) }
    var previewProcessed by remember { mutableStateOf(false) }

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
                val args = if (kind == AudioKind.NOISE && mode == 3) {
                    // Neural mode: materialize the bundled RNNoise
                    // model (assets are not real files for FFmpeg).
                    val modelFile = File(context.filesDir, "std.rnnn")
                    if (!modelFile.exists() || modelFile.length() != 302903L) {
                        context.assets.open("std.rnnn").use { inp ->
                            modelFile.outputStream().use { out -> inp.copyTo(out) }
                        }
                    }
                    MediaCommands.denoiseNeural(
                        input.absolutePath, outFile.absolutePath, modelFile.absolutePath,
                        preroll = info.durationMs >= 3000
                    )
                } else if (kind == AudioKind.NOISE) {
                    MediaCommands.denoise(input.absolutePath, outFile.absolutePath, mode)
                } else {
                    MediaCommands.enhanceAudio(input.absolutePath, outFile.absolutePath)
                }
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
            Text(stringResource(R.string.m3_mode), style = MaterialTheme.typography.titleSmall)
            listOf(
                R.string.m3_mode_light, R.string.m3_mode_balanced, R.string.m3_mode_strong,
                R.string.m3_mode_neural
            ).forEachIndexed { i, res ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { mode = i }.padding(vertical = 4.dp)
                ) {
                    RadioButton(selected = mode == i, onClick = { mode = i })
                    Text(stringResource(res), modifier = Modifier.padding(top = 12.dp))
                }
            }
            Text(
                stringResource(R.string.m3_engine_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
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
                TextButton(onClick = { engine.cancel() }) { Text(stringResource(R.string.m2_cancel)) }
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
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.m3_compare), style = MaterialTheme.typography.titleSmall)
                        Row(modifier = Modifier.padding(top = 6.dp)) {
                            OutlinedButton(
                                onClick = { previewProcessed = false },
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.m3_before)) }
                            Spacer(Modifier.padding(6.dp))
                            OutlinedButton(
                                onClick = { previewProcessed = true },
                                modifier = Modifier.weight(1f)
                            ) { Text(stringResource(R.string.m3_after)) }
                        }
                        Spacer(Modifier.height(8.dp))
                        ComparePlayer(file = if (previewProcessed) j.file else j.input)
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

@Composable
private fun ComparePlayer(file: File) {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    )
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
