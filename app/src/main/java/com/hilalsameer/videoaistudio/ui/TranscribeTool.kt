package com.hilalsameer.videoaistudio.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
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
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.data.formatSize
import com.hilalsameer.videoaistudio.data.inspectVideo
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.exportsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The two import boxes (owner's design, 0.6.1): side by side but
 * fully separate — video goes to the gallery, audio goes to the
 * audios.
 */
@Composable
fun ImportRow(onVideo: () -> Unit, onAudio: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = onVideo,
            modifier = Modifier.weight(1f).height(64.dp)
        ) {
            Text(
                stringResource(R.string.import_video),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.padding(6.dp))
        Button(
            onClick = onAudio,
            modifier = Modifier.weight(1f).height(64.dp)
        ) {
            Text(
                stringResource(R.string.m4_import_audio),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/** Whisper language choices: "" = automatic detection (Feature E). */
private val ASR_LANGS = listOf(
    "" to "تلقائي",
    "ar" to "العربية",
    "en" to "English",
    "hi" to "हिन्दी",
    "ur" to "اردو"
)

@Composable
fun TranscribeToolScreen(state: StudioState, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // The job lives in TranscribeService (0.5.1): this screen only
    // starts it and observes its state, so leaving the screen — or
    // the screen sleeping — never touches the work.
    val job by TranscribeService.state.collectAsState()
    // The spoken-language choice is one of the video's settings: it
    // persists across app closes like the video itself (0.5.5).
    val prefs = remember {
        context.getSharedPreferences(STUDIO_PREFS, Context.MODE_PRIVATE)
    }
    var lang by remember { mutableStateOf(prefs.getString(KEY_ASR_LANG, "") ?: "") }
    LaunchedEffect(lang) { prefs.edit().putString(KEY_ASR_LANG, lang).apply() }
    var editedText by remember { mutableStateOf("") }
    var pendingExport by remember { mutableStateOf<File?>(null) }

    val doneSegments = (job as? TranscribeJobState.Done)?.segments
    LaunchedEffect(doneSegments) {
        if (doneSegments != null) editedText = SubtitleFormat.buildTxt(doneSegments)
    }

    val notifPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* best effort: the job runs either way */ }

    // 0.6.1 (owner's field correction of 0.6.0): TWO separate
    // import boxes side by side — video opens the GALLERY (Photo
    // Picker, as before), audio opens the system's AUDIO view
    // directly (OpenDocument filtered to audio/*). The single
    // combined picker of 0.6.0 landed in the file manager and
    // imported nothing on his phone.
    // A failed inspection must SAY so (0.6.2): the 0.6.0/0.6.1
    // silence — pick a file, screen stays empty — is what the
    // owner reported twice. Never swallow an import failure again.
    var importError by remember { mutableStateOf(false) }
    fun onPicked(uri: Uri?) {
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
                if (info != null) {
                    state.currentInfo = info
                    importError = false
                } else {
                    importError = true
                }
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        onPicked(uri)
    }
    val videoPickerRequest = androidx.activity.result.PickVisualMediaRequest(
        ActivityResultContracts.PickVisualMedia.VideoOnly
    )
    val audioPicker = rememberLauncherForActivityResult(OpenAudioAtDownloads()) { uri ->
        onPicked(uri)
    }
    val audioTypes = arrayOf("audio/*")

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
                } catch (e: Exception) { }
            }
        }
    }

    fun export(name: String, content: String) {
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
        val uri = state.currentUri ?: return
        val info = state.currentInfo ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        TranscribeService.start(
            context, uri.toString(), info.name, info.durationMs, lang, info.title
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp).verticalScroll(rememberScrollState())
    ) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(
            text = stringResource(R.string.tool_transcribe),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        HowToCard(
            steps = listOf(
                stringResource(R.string.m4_howto_1),
                stringResource(R.string.m4_howto_2),
                stringResource(R.string.m4_howto_3),
                stringResource(R.string.m4_howto_4),
                stringResource(R.string.m4_howto_5)
            )
        )
        Spacer(Modifier.height(12.dp))

        val info = state.currentInfo
        // 0.5.3: a job outlives this screen — the foreground service
        // keeps working even when the app is closed completely. When
        // a job is running or finished, never hide it behind the
        // import gate just because the recreated screen forgot which
        // video was imported.
        val jobOwnsScreen = job is TranscribeJobState.Preparing ||
            job is TranscribeJobState.Extracting ||
            job is TranscribeJobState.Transcribing ||
            job is TranscribeJobState.Cancelling ||
            job is TranscribeJobState.Done ||
            job is TranscribeJobState.Failed
        if ((state.currentUri == null || info == null) && !jobOwnsScreen) {
            if (importError) {
                Text(
                    text = stringResource(R.string.m_import_failed),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(12.dp))
            }
            // Owner's order (0.6.1): no explanatory text here — the
            // two import boxes side by side speak for themselves,
            // and «طريقة الاستخدام» above stays.
            ImportRow(
                onVideo = { picker.launch(videoPickerRequest) },
                onAudio = { audioPicker.launch(audioTypes) }
            )
            return@Column
        }

        if (info != null) {
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
            // 0.6.3 (owner): the audio import sits next to
            // «another video» in the loaded state too — not only
            // on the empty screen.
            Row {
                TextButton(onClick = { picker.launch(videoPickerRequest) }) {
                    Text(stringResource(R.string.m2_change_video))
                }
                TextButton(onClick = { audioPicker.launch(audioTypes) }) {
                    Text(stringResource(R.string.m4_import_audio))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Text(
            stringResource(R.string.m4_language),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        Row(modifier = Modifier.padding(top = 6.dp)) {
            ASR_LANGS.forEach { (code, label) ->
                val selected = lang == code
                OutlinedButton(
                    onClick = { lang = code },
                    modifier = Modifier.padding(end = 6.dp),
                    colors = if (selected) ButtonDefaults.outlinedButtonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ) else ButtonDefaults.outlinedButtonColors()
                ) { Text(label) }
            }
        }
        Text(
            stringResource(R.string.m4_engine_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(Modifier.height(20.dp))
        when (val j = job) {
            is TranscribeJobState.Idle, is TranscribeJobState.Done,
            is TranscribeJobState.Failed, is TranscribeJobState.Cancelled -> {
                if (info != null) {
                    Button(onClick = { startJob() }, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(R.string.m4_run))
                    }
                } else {
                    // The screen forgot the video but a finished job's
                    // result is below: re-importing the same file
                    // re-attaches everything (0.5.3).
                    ImportRow(
                        onVideo = { picker.launch(videoPickerRequest) },
                        onAudio = { audioPicker.launch(audioTypes) }
                    )
                }
            }
            is TranscribeJobState.Preparing -> {
                Row { CircularProgressIndicator(); Text("  " + stringResource(R.string.m2_preparing)) }
                TextButton(onClick = { TranscribeService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranscribeJobState.Extracting -> {
                LinearProgressIndicator(progress = { j.fraction }, modifier = Modifier.fillMaxWidth())
                Text(
                    stringResource(R.string.m4_extracting),
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { TranscribeService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranscribeJobState.Transcribing -> {
                val frac = if (j.total > 0) j.done.toFloat() / j.total else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (j.total > 0) stringResource(R.string.m4_transcribing, j.done, j.total)
                    else stringResource(R.string.m2_preparing),
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { TranscribeService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranscribeJobState.Cancelling -> {
                Row {
                    CircularProgressIndicator()
                    Text("  " + stringResource(R.string.m4_cancelling))
                }
            }
        }

        when (val j = job) {
            is TranscribeJobState.Done -> {
                Spacer(Modifier.height(16.dp))
                if (j.segments.isEmpty()) {
                    Text(stringResource(R.string.m4_no_speech), style = MaterialTheme.typography.bodyLarge)
                } else {
                    Text(
                        stringResource(
                            R.string.m4_segments, j.segments.size,
                            formatDuration((j.segments.last().endSec * 1000).toLong())
                        ),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = editedText,
                        onValueChange = { editedText = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        label = { Text(stringResource(R.string.m4_result)) }
                    )
                    Text(
                        stringResource(R.string.m4_edit_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    val base = SubtitleFormat.exportBaseName(
                        info?.name ?: j.inputName, info?.title ?: j.title
                    )
                    Button(
                        onClick = { export(base + "_transcript.txt", editedText) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.m4_export_txt)) }
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { export(base + "_subtitles.srt", SubtitleFormat.buildSrt(j.segments)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(R.string.m4_export_srt)) }
                }
            }
            is TranscribeJobState.Failed -> {
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
            is TranscribeJobState.Cancelled -> {
                Spacer(Modifier.height(16.dp))
                Text(stringResource(R.string.m2_cancelled))
            }
            else -> { }
        }
    }
}

/**
 * OpenDocument that starts in the Downloads folder (0.7.1, the
 * owner's field request: «عند الاستيراد اجعله يوجهك تلقائيا الى
 * ملفات mp3» — his extracted MP3s save into Downloads since 0.7.1,
 * so the audio picker opens there). EXTRA_INITIAL_URI is a hint:
 * pickers that honor it start in Downloads; the audio/* type
 * filter applies everywhere regardless.
 */
private class OpenAudioAtDownloads : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent {
        val intent = super.createIntent(context, input)
        try {
            val downloads = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents", "primary:Download"
            )
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, downloads)
        } catch (e: Exception) { }
        return intent
    }
}
