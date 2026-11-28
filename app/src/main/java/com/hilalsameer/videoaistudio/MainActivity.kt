package com.hilalsameer.videoaistudio

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assignment
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Hd
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.hilalsameer.videoaistudio.data.VideoInfo
import com.hilalsameer.videoaistudio.data.aspectRatio
import com.hilalsameer.videoaistudio.data.formatDuration
import com.hilalsameer.videoaistudio.data.formatSize
import com.hilalsameer.videoaistudio.data.inspectVideo
import com.hilalsameer.videoaistudio.ui.AudioKind
import com.hilalsameer.videoaistudio.ui.AudioToolScreen
import com.hilalsameer.videoaistudio.ui.MediaToolScreen
import com.hilalsameer.videoaistudio.ui.ProjectsScreen
import com.hilalsameer.videoaistudio.ui.ToolKind
import com.hilalsameer.videoaistudio.ui.TranscribeToolScreen
import com.hilalsameer.videoaistudio.ui.TranslateToolScreen
import com.hilalsameer.videoaistudio.ui.GoldButton
import com.hilalsameer.videoaistudio.ui.StudioTokens
import com.hilalsameer.videoaistudio.ui.VideoAIStudioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A dashboard tool and the milestone that implements it (master prompt, Section 13). */
data class Tool(val titleRes: Int, val milestone: Int)

val TOOLS = listOf(
    Tool(R.string.tool_enhance, 7),
    Tool(R.string.tool_4k, 7),
    Tool(R.string.tool_noise, 3),
    Tool(R.string.tool_mp3, 2),
    Tool(R.string.tool_transcribe, 4),
    Tool(R.string.tool_translate, 5),
    Tool(R.string.tool_subtitles, 6),
    Tool(R.string.tool_compress, 2),
    Tool(R.string.tool_trim, 2),
    Tool(R.string.tool_audio, 3),
    Tool(R.string.tool_batch, 8),
    Tool(R.string.tool_queue, 8),
    // 0.9.0 (TD-016): the history card became «مشاريعي» — the
    // registry of completed works is the history of what was
    // produced, and it is real now, so the milestone tag is gone
    // (milestone 0 renders no tag).
    Tool(R.string.proj_title, 0),
    Tool(R.string.tool_settings, 9)
)

/** Shared in-memory state for the foundation milestone (Room arrives in Milestone 8). */
class StudioState {
    var currentUri: Uri? by mutableStateOf(null)
    var currentInfo: VideoInfo? by mutableStateOf(null)
}

/** What must survive the app being closed completely (0.5.5): the
 *  imported video (restored by re-inspection) and the ASR language. */
const val STUDIO_PREFS = "studio"
const val KEY_CURRENT_URI = "current_uri"
const val KEY_ASR_LANG = "asr_lang"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoAIStudioTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    StudioApp()
                }
            }
        }
    }
}

@Composable
fun StudioApp() {
    val nav = rememberNavController()
    val state = remember { StudioState() }
    val context = LocalContext.current
    var restored by remember { mutableStateOf(false) }
    // 0.5.5: closing the app no longer loses the imported video.
    // Every picker already takes a persistable URI grant; here the
    // URI is stored, and on the next start the video is re-inspected
    // from the file itself and restored with all its real data.
    LaunchedEffect(Unit) {
        val saved = context.getSharedPreferences(STUDIO_PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CURRENT_URI, null)
        if (saved != null && state.currentUri == null) {
            val uri = Uri.parse(saved)
            val info = withContext(Dispatchers.IO) {
                try { inspectVideo(context, uri) } catch (e: Exception) { null }
            }
            if (info != null) {
                state.currentUri = uri
                state.currentInfo = info
            }
        }
        restored = true
    }
    LaunchedEffect(state.currentUri) {
        if (restored) {
            context.getSharedPreferences(STUDIO_PREFS, Context.MODE_PRIVATE)
                .edit().putString(KEY_CURRENT_URI, state.currentUri?.toString()).apply()
        }
    }
    NavHost(navController = nav, startDestination = "dashboard") {
        composable("dashboard") {
            DashboardScreen(
                state = state,
                onVideoReady = { nav.navigate("video") },
                onTool = { idx -> nav.navigate("tool/$idx") }
            )
        }
        composable("video") {
            VideoDetailScreen(state = state, onBack = { nav.popBackStack() })
        }
        composable("tool/{idx}") { entry ->
            val idx = entry.arguments?.getString("idx")?.toIntOrNull() ?: 0
            // Milestone 2 tools are real screens; the rest keep the
            // honest milestone-status screen until their milestone lands.
            when (idx) {
                3 -> MediaToolScreen(ToolKind.EXTRACT, state, onBack = { nav.popBackStack() })
                7 -> MediaToolScreen(ToolKind.COMPRESS, state, onBack = { nav.popBackStack() })
                8 -> MediaToolScreen(ToolKind.TRIM, state, onBack = { nav.popBackStack() })
                2 -> AudioToolScreen(AudioKind.NOISE, state, onBack = { nav.popBackStack() })
                9 -> AudioToolScreen(AudioKind.ENHANCE, state, onBack = { nav.popBackStack() })
                4 -> TranscribeToolScreen(state, onBack = { nav.popBackStack() })
                5 -> TranslateToolScreen(state, onBack = { nav.popBackStack() })
                12 -> ProjectsScreen(
                    onBack = { nav.popBackStack() },
                    onSettings = { nav.navigate("tool/13") }
                )
                else -> ToolStatusScreen(tool = TOOLS[idx], onBack = { nav.popBackStack() })
            }
        }
    }
}

/**
 * Per-tool icon and tint for the dashboard grid (0.8.0, TD-014):
 * drawn vector icons in the template's per-tool colours — no
 * emoji, whose rendering changes from phone to phone.
 * Index-aligned with TOOLS.
 */
val TOOL_ICONS: List<ImageVector> = listOf(
    Icons.Filled.AutoAwesome,
    Icons.Filled.Hd,
    Icons.Filled.GraphicEq,
    Icons.Filled.MusicNote,
    Icons.Filled.Mic,
    Icons.Filled.Translate,
    Icons.Filled.Subtitles,
    Icons.Filled.Compress,
    Icons.Filled.ContentCut,
    Icons.Filled.VolumeUp,
    Icons.Filled.Layers,
    Icons.Filled.Assignment,
    Icons.Filled.Folder,
    Icons.Filled.Settings
)

val TOOL_TINTS: List<Color> = listOf(
    Color(0xFF4FC3F7), Color(0xFFFFC52F), Color(0xFF3DDC84), Color(0xFFB388FF),
    Color(0xFFFFB74D), Color(0xFF64B5F6), Color(0xFFBA68C8), Color(0xFF3DDC84),
    Color(0xFFEF5350), Color(0xFF42A5F5), Color(0xFF3DDC84), Color(0xFF4DB6AC),
    Color(0xFFB0A58F), Color(0xFFAAAAAA)
)

@Composable
fun DashboardScreen(state: StudioState, onVideoReady: () -> Unit, onTool: (Int) -> Unit) {
    val context = LocalContext.current
    var inspecting by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }

    // Gallery (photo) picker restricted to videos — the owner asked
    // for the Studio gallery, not the file manager (2026-11-25).
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (e: Exception) {
                // Some providers do not offer persistable grants; the
                // session grant still lets us inspect and preview now.
            }
            state.currentUri = uri
            inspecting = true
            failed = false
        }
    }

    LaunchedEffect(state.currentUri, inspecting) {
        val uri = state.currentUri
        if (inspecting && uri != null) {
            val info = withContext(Dispatchers.IO) {
                try { inspectVideo(context, uri) } catch (e: Exception) { null }
            }
            inspecting = false
            if (info != null) {
                state.currentInfo = info
                onVideoReady()
            } else {
                failed = true
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Column(modifier = Modifier.background(StudioTokens.Surface)) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(StudioTokens.CardBorder))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BottomNavItem(Icons.Filled.Home, stringResource(R.string.nav_home), true) {}
                    BottomNavItem(
                        Icons.Filled.Folder, stringResource(R.string.nav_projects), false
                    ) { onTool(12) }
                    BottomNavItem(
                        Icons.Filled.Settings, stringResource(R.string.tool_settings), false
                    ) { onTool(13) }
                }
            }
        }
    ) { pad ->
        Column(
            modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)
        ) {
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Filled.Movie, contentDescription = null,
                    tint = StudioTokens.Gold, modifier = Modifier.size(30.dp)
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = StudioTokens.Gold
                )
            }
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth()
                    .background(
                        brush = Brush.horizontalGradient(
                            listOf(Color(0xFF211507), StudioTokens.Background, Color(0xFF35230A))
                        ),
                        shape = RoundedCornerShape(24.dp)
                    )
                    .border(1.dp, StudioTokens.GoldDark, RoundedCornerShape(24.dp))
                    .padding(18.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.PlayCircle, contentDescription = null,
                        tint = StudioTokens.Gold, modifier = Modifier.size(54.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.home_hero_a),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.home_hero_b),
                            style = MaterialTheme.typography.headlineSmall,
                            fontWeight = FontWeight.Bold,
                            color = StudioTokens.Gold
                        )
                        Text(
                            text = stringResource(R.string.home_hero_sub),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
            GoldButton(
                text = stringResource(R.string.import_video),
                onClick = {
                    picker.launch(
                        androidx.activity.result.PickVisualMediaRequest(
                            ActivityResultContracts.PickVisualMedia.VideoOnly
                        )
                    )
                },
                modifier = Modifier.height(64.dp)
            )
            Text(
                text = stringResource(R.string.import_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            if (inspecting) {
                Row(modifier = Modifier.padding(top = 12.dp)) {
                    CircularProgressIndicator()
                }
            }
            if (failed) {
                Text(
                    text = stringResource(R.string.inspect_failed),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }
            Spacer(Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).height(1.dp).background(StudioTokens.GoldDark))
                Text(
                    text = "  " + stringResource(R.string.home_tools_section) + "  ",
                    color = StudioTokens.Gold,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(Modifier.weight(1f).height(1.dp).background(StudioTokens.GoldDark))
            }
            Spacer(Modifier.height(12.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(TOOLS.indices.toList()) { idx ->
                    val tool = TOOLS[idx]
                    val tint = TOOL_TINTS[idx]
                    Card(
                        modifier = Modifier.fillMaxWidth().clickable { onTool(idx) },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        ),
                        border = BorderStroke(1.dp, StudioTokens.CardBorder)
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Box(
                                modifier = Modifier.size(42.dp).background(
                                    tint.copy(alpha = 0.15f), RoundedCornerShape(10.dp)
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    TOOL_ICONS[idx], contentDescription = null,
                                    tint = tint, modifier = Modifier.size(24.dp)
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = stringResource(tool.titleRes),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            if (tool.milestone > 0) {
                                Text(
                                    text = "M${tool.milestone}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.secondary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun BottomNavItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier.clickable { onClick() },
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            icon, contentDescription = null,
            tint = if (selected) StudioTokens.Gold else StudioTokens.TextSecondary,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = label,
            color = if (selected) StudioTokens.Gold else StudioTokens.TextSecondary,
            style = MaterialTheme.typography.labelSmall
        )
    }
}

@Composable
fun VideoDetailScreen(state: StudioState, onBack: () -> Unit) {
    val info = state.currentInfo
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        if (info == null) {
            Text(stringResource(R.string.inspect_failed), color = MaterialTheme.colorScheme.error)
            return@Column
        }
        VideoPreview(uri = info.uri)
        Spacer(Modifier.height(16.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { MetaRow(stringResource(R.string.meta_file_name), info.name) }
            item { MetaRow(stringResource(R.string.meta_duration), formatDuration(info.durationMs)) }
            item { MetaRow(stringResource(R.string.meta_size), formatSize(info.sizeBytes)) }
            item {
                MetaRow(
                    stringResource(R.string.meta_resolution),
                    "${info.displayWidth} × ${info.displayHeight}"
                )
            }
            item {
                MetaRow(
                    stringResource(R.string.meta_fps),
                    info.fps?.let { stringResource(R.string.meta_fps_value, it) }
                        ?: stringResource(R.string.meta_unknown)
                )
            }
            item { MetaRow(stringResource(R.string.meta_video_codec), info.videoMime ?: stringResource(R.string.meta_unknown)) }
            item { MetaRow(stringResource(R.string.meta_audio_codec), info.audioMime ?: stringResource(R.string.meta_unknown)) }
            item { MetaRow(stringResource(R.string.meta_audio_tracks), info.audioTrackCount.toString()) }
            item { MetaRow(stringResource(R.string.meta_subtitle_tracks), info.subtitleTrackCount.toString()) }
            item {
                MetaRow(
                    stringResource(R.string.meta_aspect),
                    aspectRatio(info.displayWidth, info.displayHeight)
                )
            }
            item {
                MetaRow(
                    stringResource(R.string.meta_bitrate),
                    info.bitrate?.let { "%,d bps".format(it) } ?: stringResource(R.string.meta_unknown)
                )
            }
            item {
                Text(
                    stringResource(R.string.meta_tracks),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 10.dp)
                )
            }
            items(info.tracks) { t ->
                MetaRow(
                    t.kind,
                    listOfNotNull(t.mime, t.language, t.details.takeIf { it.isNotBlank() }).joinToString(" · ")
                )
            }
        }
    }
}

@Composable
fun MetaRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1.4f)
        )
    }
}

@Composable
fun VideoPreview(uri: Uri) {
    val context = LocalContext.current
    val player = remember(uri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
        }
    }
    DisposableEffect(player) {
        onDispose { player.release() }
    }
    AndroidView(
        factory = { ctx -> PlayerView(ctx).apply { this.player = player } },
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)
    )
}

@Composable
fun ToolStatusScreen(tool: Tool, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(tool.titleRes),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tool_arrives, tool.milestone),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
