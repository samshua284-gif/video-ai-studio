package com.hilalsameer.videoaistudio.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
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

enum class ToolKind { EXTRACT, TRIM, COMPRESS }

private sealed interface JobUi {
    data object Idle : JobUi
    data object Preparing : JobUi
    data class Running(val fraction: Float) : JobUi
    data class Done(val file: File, val report: ValidationReport) : JobUi
    data class Failed(val logTail: String) : JobUi
    data object Cancelled : JobUi
}

@Composable
fun MediaToolScreen(kind: ToolKind, state: StudioState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val engine = remember { MediaEngine() }
    var job by remember { mutableStateOf<JobUi>(JobUi.Idle) }
    var rangeError by remember { mutableStateOf(false) }

    // Tool options
    var mp3 by remember { mutableStateOf(true) }
    var bitrate by remember { mutableStateOf(192) }
    var startText by remember { mutableStateOf("0") }
    var endText by remember { mutableStateOf("") }
    var quality by remember { mutableStateOf(1) } // 0 high, 1 balanced, 2 small

    val titleRes = when (kind) {
        ToolKind.EXTRACT -> R.string.tool_mp3
        ToolKind.TRIM -> R.string.tool_trim
        ToolKind.COMPRESS -> R.string.tool_compress
    }
    val runRes = when (kind) {
        ToolKind.EXTRACT -> R.string.m2_run_extract
        ToolKind.TRIM -> R.string.m2_run_trim
        ToolKind.COMPRESS -> R.string.m2_run_compress
    }

    // Prefill the trim end with the source duration whenever a new
    // input is loaded, so an untouched form never means "re-encode
    // the whole file" by accident.
    androidx.compose.runtime.LaunchedEffect(state.currentInfo?.name, kind) {
        val inf = state.currentInfo
        if (kind == ToolKind.TRIM && inf != null) {
            endText = (inf.durationMs / 1000).toString()
        }
    }

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
        val done = job as? JobUi.Done ?: return@rememberLauncherForActivityResult
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
        rangeError = false
        val durationSec = info.durationMs / 1000.0
        // Validate trim input before touching the engine.
        var startSec = 0.0
        var endSec = durationSec
        if (kind == ToolKind.TRIM) {
            startSec = startText.toDoubleOrNull() ?: -1.0
            endSec = endText.toDoubleOrNull() ?: durationSec
            if (MediaCommands.trimRangeError(startSec, endSec, durationSec) != null) {
                rangeError = true
                return
            }
        }
        job = JobUi.Preparing
        scope.launch {
            try {
                val input = withContext(Dispatchers.IO) {
                    prepareInputFile(context, uri, info.name)
                }
                val base = info.name.substringBeforeLast('.', info.name)
                val outDir = exportsDir(context)
                val (outFile, args) = when (kind) {
                    ToolKind.EXTRACT -> {
                        val f = File(outDir, base + if (mp3) "_audio.mp3" else "_audio.wav")
                        f to MediaCommands.extractAudio(
                            input.absolutePath, f.absolutePath, mp3, bitrate
                        )
                    }
                    ToolKind.TRIM -> {
                        val f = File(outDir, base + "_trim.mp4")
                        f to MediaCommands.trim(input.absolutePath, f.absolutePath, startSec, endSec)
                    }
                    ToolKind.COMPRESS -> {
                        val (crf, maxH) = when (quality) {
                            0 -> 20 to 1080
                            2 -> 32 to 480
                            else -> 26 to 720
                        }
                        val f = File(outDir, base + "_compressed.mp4")
                        f to MediaCommands.compress(
                            input.absolutePath, f.absolutePath, crf, maxH, info.displayHeight
                        )
                    }
                }
                job = JobUi.Running(0f)
                val result = engine.run(args) { processedMs ->
                    val frac = if (info.durationMs > 0) {
                        (processedMs.toFloat() / info.durationMs).coerceIn(0f, 1f)
                    } else 0f
                    // Progress for a trim is relative to the segment.
                    val adj = if (kind == ToolKind.TRIM && endSec > startSec) {
                        (processedMs / 1000.0 / (endSec - startSec)).toFloat().coerceIn(0f, 1f)
                    } else frac
                    job = JobUi.Running(adj)
                }
                when {
                    result.cancelled -> job = JobUi.Cancelled
                    result.success -> {
                        val report = withContext(Dispatchers.IO) { validateExport(outFile) }
                        job = JobUi.Done(outFile, report)
                    }
                    else -> job = JobUi.Failed(result.output.takeLast(600))
                }
            } catch (e: Exception) {
                job = JobUi.Failed(e.message ?: "error")
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

        when (kind) {
            ToolKind.EXTRACT -> {
                Text(stringResource(R.string.m2_format), style = MaterialTheme.typography.titleSmall)
                RadioRow("MP3", mp3) { mp3 = true }
                RadioRow("WAV", !mp3) { mp3 = false }
                if (mp3) {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.m2_bitrate), style = MaterialTheme.typography.titleSmall)
                    listOf(128, 192, 320).forEach { b ->
                        RadioRow("$b kbps", bitrate == b) { bitrate = b }
                    }
                }
            }
            ToolKind.TRIM -> {
                Text(
                    stringResource(R.string.m2_trim_hint, formatDuration(info.durationMs)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = startText,
                    onValueChange = { startText = it },
                    label = { Text(stringResource(R.string.m2_trim_start)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = endText,
                    onValueChange = { endText = it },
                    label = { Text(stringResource(R.string.m2_trim_end)) },
                    placeholder = { Text((info.durationMs / 1000).toString()) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )
                if (rangeError) {
                    Text(
                        stringResource(R.string.m2_range_error),
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            ToolKind.COMPRESS -> {
                Text(stringResource(R.string.m2_quality), style = MaterialTheme.typography.titleSmall)
                RadioRow(stringResource(R.string.m2_quality_high), quality == 0) { quality = 0 }
                RadioRow(stringResource(R.string.m2_quality_balanced), quality == 1) { quality = 1 }
                RadioRow(stringResource(R.string.m2_quality_small), quality == 2) { quality = 2 }
            }
        }

        Spacer(Modifier.height(20.dp))
        when (val j = job) {
            is JobUi.Idle, is JobUi.Done, is JobUi.Failed, is JobUi.Cancelled -> {
                Button(onClick = { startJob() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(runRes))
                }
            }
            is JobUi.Preparing -> {
                Row { CircularProgressIndicator(); Text("  " + stringResource(R.string.m2_preparing)) }
            }
            is JobUi.Running -> {
                LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.m2_running, (j.fraction * 100).toInt()),
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { engine.cancel() }) { Text(stringResource(R.string.m2_cancel)) }
            }
        }

        when (val j = job) {
            is JobUi.Done -> {
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
                        ResultRow(stringResource(R.string.meta_file_name), j.report.fileName)
                        ResultRow(stringResource(R.string.m2_result_size), formatSize(j.report.sizeBytes))
                        ResultRow(stringResource(R.string.m2_result_duration), formatDuration(j.report.durationMs))
                        ResultRow(stringResource(R.string.m2_result_tracks), j.report.trackCount.toString())
                        ResultRow(stringResource(R.string.m2_result_path), j.file.absolutePath)
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
            is JobUi.Failed -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_failed), color = MaterialTheme.colorScheme.error)
                Text(j.logTail, style = MaterialTheme.typography.bodySmall)
            }
            is JobUi.Cancelled -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_cancelled))
            }
            else -> { }
        }

        // A second change-video affordance at the bottom: the top one
        // scrolls away on the result screen (owner field report).
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = { picker.launch(videoPickerRequest) }) {
            Text(stringResource(R.string.m2_change_video))
        }
    }
}

@Composable
private fun RadioRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable { onClick() }.padding(vertical = 4.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Text(label, modifier = Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun ResultRow(label: String, value: String) {
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
