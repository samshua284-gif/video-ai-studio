package com.hilalsameer.videoaistudio.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hilalsameer.videoaistudio.KEY_ASR_LANG
import com.hilalsameer.videoaistudio.R
import com.hilalsameer.videoaistudio.STUDIO_PREFS
import com.hilalsameer.videoaistudio.StudioState
import com.hilalsameer.videoaistudio.TranscribeJobState
import com.hilalsameer.videoaistudio.TranscribeService
import com.hilalsameer.videoaistudio.TranslateJobState
import com.hilalsameer.videoaistudio.TranslateService
import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.CorrectionMemory
import com.hilalsameer.videoaistudio.media.MtLanguages
import com.hilalsameer.videoaistudio.media.ProjectKind
import com.hilalsameer.videoaistudio.media.ProjectsRepository
import com.hilalsameer.videoaistudio.media.SrtParser
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.SubtitleMerge
import com.hilalsameer.videoaistudio.media.exportsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** One part in the TD-009 merge list, in the owner's pick order. */
private data class MergePart(
    val name: String,
    val segments: List<AsrSegment>,
    var durationText: String
)

private fun parseDurationSec(text: String): Double? {
    val t = text.trim()
    if (t.isEmpty()) return null
    return if (t.contains(':')) {
        val bits = t.split(':')
        if (bits.size != 2) return null
        val m = bits[0].trim().toDoubleOrNull() ?: return null
        val s = bits[1].trim().toDoubleOrNull() ?: return null
        val total = m * 60 + s
        if (total > 0) total else null
    } else {
        t.toDoubleOrNull()?.takeIf { it > 0 }
    }
}

@Composable
fun TranslateToolScreen(state: StudioState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val asrJob by TranscribeService.state.collectAsState()
    val job by TranslateService.state.collectAsState()

    val studioPrefs = remember {
        context.getSharedPreferences(STUDIO_PREFS, Context.MODE_PRIVATE)
    }
    var srcLang by remember {
        val asr = studioPrefs.getString(KEY_ASR_LANG, "") ?: ""
        mutableStateOf(if (MtLanguages.codes.containsKey(asr)) asr else "en")
    }
    var tgtLang by remember { mutableStateOf(if (srcLang == "ar") "en" else "ar") }

    // The working source: latest transcription, an imported SRT,
    // or merged parts — whichever the owner picked last.
    var sourceSegments by remember { mutableStateOf<List<AsrSegment>?>(null) }
    var sourceName by remember { mutableStateOf("") }
    var sourceNote by remember { mutableStateOf("") }
    val mergeParts = remember { mutableStateListOf<MergePart>() }
    var importError by remember { mutableStateOf(false) }

    var editedText by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val doneState = job as? TranslateJobState.Done
    LaunchedEffect(doneState) {
        if (doneState != null) editedText = SubtitleFormat.buildTxt(doneState.segments)
    }
    val lastAsr = asrJob as? TranscribeJobState.Done
    LaunchedEffect(lastAsr) {
        if (lastAsr != null && sourceSegments == null && lastAsr.segments.isNotEmpty()) {
            sourceSegments = lastAsr.segments
            sourceName = lastAsr.inputName
        }
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    fun readSrt(uri: Uri, onOk: (String, List<AsrSegment>) -> Unit) {
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val name = context.contentResolver.query(
                        uri, null, null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) {
                            val ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                            if (ni >= 0) c.getString(ni) else null
                        } else {
                            null
                        }
                    } ?: "subtitles.srt"
                    val text = context.contentResolver.openInputStream(uri)?.use {
                        it.readBytes().toString(Charsets.UTF_8)
                    } ?: ""
                    name to SrtParser.parse(text)
                } catch (e: Exception) {
                    null
                }
            }
            if (parsed == null || parsed.second.isEmpty()) {
                importError = true
            } else {
                importError = false
                onOk(parsed.first, parsed.second)
            }
        }
    }

    var pickerMode by remember { mutableStateOf(0) } // 0 = source, 1 = merge part
    val srtPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            readSrt(uri) { name, segs ->
                if (pickerMode == 1) {
                    mergeParts.add(MergePart(name, segs, ""))
                } else {
                    sourceSegments = segs
                    sourceName = name
                    sourceNote = "file"
                }
            }
        }
    }

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { dest: Uri? ->
        val file = pendingExport
        pendingExport = null
        if (dest != null && file != null) {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(dest)?.use { out ->
                        file.inputStream().use { it.copyTo(out, 1 shl 16) }
                    }
                    // 0.9.0 (TD-016): the copy finished — the work
                    // joins Projects under the picked document.
                    ProjectsRepository.register(
                        context, ProjectKind.TRANSLATION, file.name,
                        dest.toString(), sourceName
                    )
                } catch (e: Exception) { }
            }
        }
    }

    fun harvestMemory() {
        val d = doneState ?: return
        val pairs = CorrectionMemory.harvest(d.source, d.segments, editedText)
        if (pairs.isNotEmpty()) {
            val prefs = context.getSharedPreferences(
                TranslateService.MEMORY_PREFS, Context.MODE_PRIVATE
            )
            prefs.edit().apply {
                pairs.forEach { (k, v) -> putString(k, v) }
                apply()
            }
        }
    }

    fun export(name: String, content: String) {
        harvestMemory()
        scope.launch {
            val f = withContext(Dispatchers.IO) {
                File(exportsDir(context), name).apply {
                    writeText(content, Charsets.UTF_8)
                }
            }
            pendingExport = f
            saveLauncher.launch(f.name)
        }
    }

    fun startJob() {
        val segs = sourceSegments ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TranslateService.start(
            context,
            SubtitleFormat.buildSrt(segs),
            sourceName.ifEmpty { "subtitles" },
            state.currentInfo?.title,
            srcLang, tgtLang
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(
            text = stringResource(R.string.tool_translate),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        HowToCard(
            steps = listOf(
                stringResource(R.string.m5_howto_1),
                stringResource(R.string.m5_howto_2),
                stringResource(R.string.m5_howto_3),
                stringResource(R.string.m5_howto_4),
                stringResource(R.string.m5_howto_5)
            )
        )
        Spacer(Modifier.height(12.dp))

        // ---- Source selection ----
        if (lastAsr != null && lastAsr.segments.isNotEmpty()) {
            TextButton(onClick = {
                sourceSegments = lastAsr.segments
                sourceName = lastAsr.inputName
                sourceNote = "asr"
            }) { Text(stringResource(R.string.m5_source_last, lastAsr.segments.size)) }
        }
        OutlinedButton(
            onClick = { pickerMode = 0; srtPicker.launch(arrayOf("*/*")) },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(R.string.m5_import_srt)) }
        if (sourceSegments != null) {
            Text(
                stringResource(
                    R.string.m5_source_file, sourceName, sourceSegments!!.size
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
        if (importError) {
            Text(
                stringResource(R.string.m5_err_empty),
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ---- Merge (TD-009) ----
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.m5_merge_title),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        mergeParts.forEachIndexed { i, part ->
            Card(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        stringResource(R.string.m5_merge_part, i + 1, part.name, part.segments.size),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = part.durationText,
                        onValueChange = { v ->
                            mergeParts[i] = part.copy(durationText = v)
                        },
                        label = { Text(stringResource(R.string.m5_merge_duration_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                    )
                }
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp)) {
            OutlinedButton(onClick = { pickerMode = 1; srtPicker.launch(arrayOf("*/*")) }) {
                Text(stringResource(R.string.m5_merge_add))
            }
            val durations = mergeParts.map { parseDurationSec(it.durationText) }
            Button(
                onClick = {
                    val merged = SubtitleMerge.merge(
                        mergeParts.map { it.segments },
                        durations.map { it!! }
                    )
                    sourceSegments = merged
                    sourceName = mergeParts.firstOrNull()?.name ?: "merged"
                    sourceNote = "merged"
                },
                enabled = mergeParts.size >= 2 && durations.all { it != null },
                modifier = Modifier.padding(start = 8.dp)
            ) { Text(stringResource(R.string.m5_merge_do)) }
        }
        if (sourceNote == "merged" && sourceSegments != null) {
            Text(
                stringResource(R.string.m5_merge_done, sourceSegments!!.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        // ---- Languages ----
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(R.string.m5_src_lang),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Row(modifier = Modifier.padding(top = 6.dp)) {
            MtLanguages.codes.keys.forEach { code ->
                OutlinedButton(
                    onClick = { srcLang = code },
                    modifier = Modifier.padding(end = 6.dp),
                    colors = if (srcLang == code) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()
                ) { Text(langLabel(code)) }
            }
        }
        Text(
            stringResource(R.string.m5_tgt_lang),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 10.dp)
        )
        Row(modifier = Modifier.padding(top = 6.dp)) {
            MtLanguages.codes.keys.forEach { code ->
                OutlinedButton(
                    onClick = { tgtLang = code },
                    modifier = Modifier.padding(end = 6.dp),
                    colors = if (tgtLang == code) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()
                ) { Text(langLabel(code)) }
            }
        }

        // ---- Job control + states ----
        Spacer(Modifier.height(20.dp))
        when (val j = job) {
            is TranslateJobState.Idle, is TranslateJobState.Done,
            is TranslateJobState.Failed, is TranslateJobState.Cancelled -> {
                when {
                    sourceSegments == null -> Text(stringResource(R.string.m5_no_source))
                    srcLang == tgtLang -> Text(
                        stringResource(R.string.m5_same_lang),
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Button(
                        onClick = { startJob() },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.m5_start)) }
                }
            }
            is TranslateJobState.LoadingModel -> {
                // TD-019: real per-file progress — the load phase
                // is where the owner actually waits, and in 0.9.0
                // it showed him nothing but a spinner.
                val frac = if (j.filesTotal > 0) {
                    j.filesDone.toFloat() / j.filesTotal
                } else {
                    0f
                }
                LinearProgressIndicator(
                    progress = { frac }, modifier = Modifier.fillMaxWidth()
                )
                Text(
                    stringResource(R.string.m5_loading_model),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Text(
                    stringResource(
                        R.string.m5_loading_progress, j.filesDone, j.filesTotal
                    ) + (j.currentFile?.let { " — $it" } ?: ""),
                    modifier = Modifier.padding(top = 4.dp)
                )
                TextButton(onClick = { TranslateService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranslateJobState.Translating -> {
                val frac = if (j.total > 0) j.done.toFloat() / j.total else 0f
                val pct = if (j.total > 0) j.done * 100 / j.total else 0
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.m5_translating, j.done, j.total, pct),
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { TranslateService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranslateJobState.Cancelling -> {
                Row {
                    CircularProgressIndicator()
                    Text("  " + stringResource(R.string.m4_cancelling))
                }
            }
        }

        when (val j = job) {
            is TranslateJobState.Done -> {
                Spacer(Modifier.height(16.dp))
                if (j.numberFlags.isNotEmpty()) {
                    Text(
                        stringResource(
                            R.string.m5_flags, j.numberFlags.size,
                            j.numberFlags.joinToString(", ") { (it + 1).toString() }
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                }
                if (j.passedSegments > 0) {
                    // TD-020: pass-throughs are stated, never
                    // left for the owner to discover by reading.
                    Text(
                        stringResource(
                            R.string.m5_passed_warning,
                            j.passedSegments, j.segments.size
                        ),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(Modifier.height(6.dp))
                }
                Text(
                    stringResource(R.string.m5_memory_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth().height(220.dp),
                    label = { Text(stringResource(R.string.m4_result)) }
                )
                Spacer(Modifier.height(10.dp))
                val base = SubtitleFormat.exportBaseName(
                    j.inputName, state.currentInfo?.title
                ) + "_" + tgtLang
                Button(
                    onClick = { export(base + "_transcript.txt", editedText) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.m5_export_txt)) }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = {
                        export(base + "_subtitles.srt", SubtitleFormat.buildSrt(j.segments))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.m5_export_srt)) }
                if (j.autoSaved) {
                    Text(
                        stringResource(R.string.m5_autosave_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
            is TranslateJobState.Failed -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_failed), color = MaterialTheme.colorScheme.error)
                Text(
                    j.message,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 6.dp)
                )
            }
            is TranslateJobState.Cancelled -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_cancelled))
            }
            else -> { }
        }
    }
}

private fun langLabel(code: String): String = when (code) {
    "ar" -> "العربية"
    "en" -> "English"
    "hi" -> "हिन्दी"
    "ur" -> "اردو"
    "tr" -> "Türkçe"
    else -> code
}
