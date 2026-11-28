package com.hilalsameer.videoaistudio.ui

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.widget.VideoView
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.core.content.ContextCompat
import com.hilalsameer.videoaistudio.KEY_ASR_LANG
import com.hilalsameer.videoaistudio.R
import com.hilalsameer.videoaistudio.STUDIO_PREFS
import com.hilalsameer.videoaistudio.StudioState
import com.hilalsameer.videoaistudio.TranscribeJobState
import com.hilalsameer.videoaistudio.TranscribeService
import com.hilalsameer.videoaistudio.data.VideoInfo
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.data.formatSize
import com.hilalsameer.videoaistudio.data.inspectVideo
import com.hilalsameer.videoaistudio.media.ProjectKind
import com.hilalsameer.videoaistudio.media.ProjectsRepository
import com.hilalsameer.videoaistudio.media.SubtitleFormat
import com.hilalsameer.videoaistudio.media.exportsDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
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

/**
 * 0.9.2 (TD-020): listen to — or watch — the imported file
 * BEFORE transcribing it. The owner's field order: he imported
 * a WhatsApp voice note and had no way to hear what he had
 * picked. Audio plays through a plain MediaPlayer prepared
 * from the imported content URI; a file that carries a video
 * track — decided by its own inspection ([VideoInfo.videoMime]),
 * never by its file name — also shows its picture in a bounded
 * VideoView. One player at a time, owned by this composable:
 * released when the input changes or the screen leaves, and
 * paused when a transcription job starts. A file the platform
 * cannot decode gets one honest line and never blocks or
 * crashes the transcription path.
 */
@Composable
private fun InputPreview(info: VideoInfo, jobActive: Boolean) {
    val context = LocalContext.current
    val uri = info.uri
    val isVideo = info.videoMime != null
    var audioPlayer by remember(uri) { mutableStateOf<MediaPlayer?>(null) }
    var videoView by remember(uri) { mutableStateOf<VideoView?>(null) }
    var playing by remember(uri) { mutableStateOf(false) }
    var failed by remember(uri) { mutableStateOf(false) }
    var positionMs by remember(uri) { mutableIntStateOf(0) }

    fun pausePlayback() {
        try {
            if (isVideo) videoView?.pause() else audioPlayer?.pause()
        } catch (e: Exception) { }
        playing = false
    }

    fun stopPlayback() {
        try {
            if (isVideo) {
                videoView?.pause()
                videoView?.seekTo(0)
            } else {
                audioPlayer?.pause()
                audioPlayer?.seekTo(0)
            }
        } catch (e: Exception) { }
        playing = false
        positionMs = 0
    }

    fun toggle() {
        if (failed) return
        if (playing) {
            pausePlayback()
            return
        }
        if (isVideo) {
            val vv = videoView ?: return
            try {
                vv.start()
                playing = true
            } catch (e: Exception) {
                failed = true
            }
        } else {
            val existing = audioPlayer
            if (existing != null) {
                try {
                    existing.start()
                    playing = true
                } catch (e: Exception) {
                    failed = true
                }
            } else {
                try {
                    val mp = MediaPlayer()
                    mp.setDataSource(context, uri)
                    mp.setOnPreparedListener { p ->
                        try {
                            p.start()
                            playing = true
                        } catch (e: Exception) {
                            failed = true
                        }
                    }
                    mp.setOnCompletionListener { p ->
                        playing = false
                        positionMs = 0
                        try { p.seekTo(0) } catch (e: Exception) { }
                    }
                    mp.setOnErrorListener { _, _, _ ->
                        failed = true
                        playing = false
                        true
                    }
                    mp.prepareAsync()
                    audioPlayer = mp
                } catch (e: Exception) {
                    failed = true
                }
            }
        }
    }

    // A starting job takes the owner's ears: pause the preview.
    LaunchedEffect(jobActive) {
        if (jobActive) pausePlayback()
    }

    // Elapsed-time readout, refreshed while playing.
    LaunchedEffect(playing) {
        while (playing) {
            positionMs = try {
                if (isVideo) videoView?.currentPosition ?: 0
                else audioPlayer?.currentPosition ?: 0
            } catch (e: Exception) {
                0
            }
            delay(250)
        }
    }

    DisposableEffect(uri) {
        onDispose {
            try { audioPlayer?.release() } catch (e: Exception) { }
            audioPlayer = null
            try { videoView?.stopPlayback() } catch (e: Exception) { }
            videoView = null
        }
    }

    Column(modifier = Modifier.padding(top = 8.dp)) {
        if (isVideo) {
            AndroidView(
                factory = { ctx ->
                    VideoView(ctx).also { vv ->
                        videoView = vv
                        vv.setVideoURI(uri)
                        // Returning true also suppresses the
                        // platform's own "cannot play" dialog —
                        // the honest line below replaces it.
                        vv.setOnErrorListener { _, _, _ ->
                            failed = true
                            playing = false
                            true
                        }
                        vv.setOnCompletionListener {
                            playing = false
                            positionMs = 0
                            try { vv.seekTo(0) } catch (e: Exception) { }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth().height(220.dp)
                    .background(Color.Black)
            )
            Spacer(Modifier.height(8.dp))
        }
        if (failed) {
            Text(
                stringResource(R.string.m4_preview_failed),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            Row {
                Button(onClick = { toggle() }) {
                    Text(
                        stringResource(
                            if (playing) R.string.m4_preview_pause
                            else R.string.m4_preview_play
                        )
                    )
                }
                OutlinedButton(
                    onClick = { stopPlayback() },
                    modifier = Modifier.padding(start = 8.dp)
                ) {
                    Text(stringResource(R.string.m4_preview_stop))
                }
                Text(
                    "${formatDuration(positionMs.toLong())} / " +
                        formatDuration(info.durationMs),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(start = 12.dp, top = 10.dp)
                )
            }
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
    var pendingExportSource by remember { mutableStateOf("") }

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

    // 0.9.0 (TD-017): the audio import's first door is now an
    // in-app chooser over the device's own audio library
    // (MediaStore), because the 0.7.1 system-picker steering —
    // EXTRA_INITIAL_URI towards Downloads — did not land on his
    // audio files in the field. The system picker stays as the
    // visible secondary door («منتقي النظام») and as the
    // automatic fallback when the library permission is denied.
    var audioTracks by remember { mutableStateOf<List<AudioTrack>>(emptyList()) }
    var audioLibraryOpen by remember { mutableStateOf(false) }
    var audioLibraryLoaded by remember { mutableStateOf(false) }
    fun loadAudioLibrary() {
        scope.launch {
            val tracks = withContext(Dispatchers.IO) { queryAudioLibrary(context) }
            audioTracks = tracks
            audioLibraryLoaded = true
            audioLibraryOpen = true
        }
    }
    val audioPermission = if (Build.VERSION.SDK_INT >= 33) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
    val audioPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) loadAudioLibrary() else audioPicker.launch(audioTypes)
    }
    fun openAudioImport() {
        if (ContextCompat.checkSelfPermission(context, audioPermission) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            loadAudioLibrary()
        } else {
            audioPermLauncher.launch(audioPermission)
        }
    }

    @Composable
    fun AudioLibrarySection() {
        if (!audioLibraryOpen) return
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.m4_audio_library),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold
        )
        if (!audioLibraryLoaded) {
            Row(modifier = Modifier.padding(top = 8.dp)) { CircularProgressIndicator() }
        } else if (audioTracks.isEmpty()) {
            Text(
                stringResource(R.string.m4_audio_library_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
        } else {
            audioTracks.forEach { track ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp)
                        .clickable {
                            audioLibraryOpen = false
                            onPicked(track.uri)
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            track.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                        if (track.folder.isNotEmpty()) {
                            Text(
                                track.folder,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            formatDuration(track.durationMs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
                        context, ProjectKind.TRANSCRIPT, file.name,
                        dest.toString(), pendingExportSource
                    )
                } catch (e: Exception) { }
            }
        }
    }

    fun export(name: String, content: String) {
        pendingExportSource = state.currentInfo?.name
            ?: (job as? TranscribeJobState.Done)?.inputName ?: ""
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
            job is TranscribeJobState.LoadingEngine ||
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
                onAudio = { openAudioImport() }
            )
            TextButton(onClick = { audioPicker.launch(audioTypes) }) {
                Text(stringResource(R.string.m4_system_picker))
            }
            AudioLibrarySection()
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
                TextButton(onClick = { openAudioImport() }) {
                    Text(stringResource(R.string.m4_import_audio))
                }
                TextButton(onClick = { audioPicker.launch(audioTypes) }) {
                    Text(stringResource(R.string.m4_system_picker))
                }
            }
            // 0.9.2 (TD-020): hear — or watch — the imported
            // file before transcribing it (the owner's order
            // after importing a WhatsApp voice note).
            InputPreview(
                info = info,
                jobActive = job is TranscribeJobState.Preparing ||
                    job is TranscribeJobState.Extracting ||
                    job is TranscribeJobState.LoadingEngine ||
                    job is TranscribeJobState.Transcribing ||
                    job is TranscribeJobState.Cancelling
            )
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
                        onAudio = { openAudioImport() }
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
                    stringResource(
                        R.string.m4_extracting, (j.fraction * 100).roundToInt()
                    ),
                    modifier = Modifier.padding(top = 8.dp)
                )
                TextButton(onClick = { TranscribeService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranscribeJobState.LoadingEngine -> {
                // 0.9.4 (TD-022): the load has a name now, and an
                // indeterminate indicator — the native load
                // reports no percent and none is invented.
                Row {
                    CircularProgressIndicator()
                    Text("  " + stringResource(R.string.m4_loading_engine))
                }
                TextButton(onClick = { TranscribeService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranscribeJobState.Transcribing -> {
                val frac = if (j.total > 0) j.done.toFloat() / j.total else 0f
                LinearProgressIndicator(progress = { frac }, modifier = Modifier.fillMaxWidth())
                Text(
                    if (j.total > 0) stringResource(
                        R.string.m4_transcribing, j.done, j.total,
                        j.done * 100 / j.total
                    )
                    // TD-022: before the plan exists (VAD +
                    // planning) the label says what is actually
                    // happening, not the generic preparing text.
                    else stringResource(R.string.m4_analysing),
                    modifier = Modifier.padding(top = 8.dp)
                )
                if (j.total > 0) {
                    // 0.9.5 (TD-023): the live clock of the
                    // chunk being decoded. A chunk is one
                    // opaque native call with no internal
                    // progress — the counter cannot move inside
                    // it, but this number moves every second,
                    // so working never looks frozen.
                    Text(
                        stringResource(
                            R.string.m4_chunk_elapsed,
                            formatDuration(j.chunkElapsedSec * 1000L)
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = { TranscribeService.cancel(context) }) {
                    Text(stringResource(R.string.m2_cancel))
                }
            }
            is TranscribeJobState.Cancelling -> {
                Row {
                    CircularProgressIndicator()
                    Text("  " + stringResource(R.string.m4_cancelling))
                }
                // 0.9.5 (TD-023): the line under the spinner
                // states the new truth — the screen stops at
                // once; only the detached decode finishes in
                // the background, and its result is discarded.
                Text(
                    stringResource(R.string.m4_cancelling_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }

        AudioLibrarySection()

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
 * One row of the in-app audio chooser (0.9.0, TD-017). The
 * folder is shown under the name: the owner's audio lives
 * scattered across many folders, and the gathered list must
 * say where each file actually sits while he picks.
 */
private data class AudioTrack(
    val uri: Uri, val name: String, val durationMs: Long, val folder: String
)

/**
 * The device's audio library, newest first, from MediaStore —
 * the same index the system music apps read. Capped so a huge
 * library cannot flood the scrolling screen. Returns an empty
 * list on any failure; the caller then offers the system picker.
 */
private fun queryAudioLibrary(context: Context): List<AudioTrack> {
    val out = ArrayList<AudioTrack>()
    // RELATIVE_PATH exists only on API 29+; below it the folder
    // is derived from the legacy absolute-path column instead.
    val modern = Build.VERSION.SDK_INT >= 29
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.DISPLAY_NAME,
        MediaStore.Audio.Media.DURATION,
        if (modern) MediaStore.Audio.Media.RELATIVE_PATH else MediaStore.Audio.Media.DATA
    )
    try {
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            MediaStore.Audio.Media.DATE_ADDED + " DESC"
        )?.use { c ->
            val idI = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val nameI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val durI = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val pathI = c.getColumnIndex(projection[3])
            while (c.moveToNext()) {
                val name = c.getString(nameI) ?: continue
                val dur = if (c.isNull(durI)) 0L else c.getLong(durI)
                val raw = if (pathI >= 0 && !c.isNull(pathI)) c.getString(pathI) else null
                out.add(
                    AudioTrack(
                        ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, c.getLong(idI)
                        ),
                        name, dur, folderOf(raw, modern)
                    )
                )
                if (out.size >= 100) break
            }
        }
    } catch (e: Exception) { }
    return out
}

/**
 * The containing folder of one library row, as a short relative
 * path: RELATIVE_PATH arrives as one already ("Music/",
 * "WhatsApp/Media/WhatsApp Audio/"); a legacy absolute path is
 * reduced to the part below the shared-storage root, or to its
 * parent folder name when it lives somewhere else entirely.
 */
private fun folderOf(raw: String?, modern: Boolean): String {
    if (raw.isNullOrBlank()) return ""
    if (modern) return raw.trim().trimEnd('/')
    val parent = raw.substringBeforeLast('/', "")
    if (parent.isEmpty()) return ""
    val root = Environment.getExternalStorageDirectory().path
    return if (parent.startsWith(root)) {
        parent.removePrefix(root).trim('/')
    } else {
        parent.substringAfterLast('/')
    }
}

/**
 * OpenDocument that starts in the Downloads folder (0.7.1, the
 * owner's field request: «عند الاستيراد اجعله يوجهك تلقائيا الى
 * ملفات mp3» — his extracted MP3s save into Downloads since 0.7.1,
 * so the audio picker opens there). EXTRA_INITIAL_URI is a hint:
 * pickers that honor it start in Downloads; the audio mime-type
 * filter applies everywhere regardless. Since 0.9.0 this is the
 * secondary door («منتقي النظام»); the in-app MediaStore
 * chooser above is the primary one (TD-017).
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
