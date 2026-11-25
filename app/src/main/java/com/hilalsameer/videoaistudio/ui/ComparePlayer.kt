package com.hilalsameer.videoaistudio.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.hilalsameer.videoaistudio.R
import java.io.File

/**
 * The ONE before/after comparison component for the whole app
 * (TD-005, owner's order 2026-11-25: "these fixes apply to every
 * tool that needs an original/processed preview"). Every tool
 * screen — noise, enhance, trim, compress, extract, and the
 * future transcription/translation/subtitle/enhancement tools —
 * compares through here, so a playback fix lands everywhere at
 * once instead of being re-invented per screen.
 *
 * Design, each point earned from a field report:
 * - PlayerView's own controller is DISABLED: its centre play
 *   circle never answered taps inside these scrollable Compose
 *   screens and stayed visible as a dead promise (0.4.2 report).
 * - Our own Play/Pause button lives OUTSIDE the AndroidView — a
 *   Compose button always receives its click.
 * - A finished video restarts: in STATE_ENDED play() alone does
 *   nothing ("plays only once", 0.4.3 report), so play seeks to
 *   0 first when ended.
 * - Switching Original/Processed passes a rising [playSignal]:
 *   the newly selected side starts playing immediately, so each
 *   side is heard/seen on its own.
 * - The selected tab is highlighted (primaryContainer), so the
 *   user can see which side is loaded.
 * - Full screen: the video opens in an external full-screen
 *   frame (owner's request, 0.4.4). The SAME player instance
 *   moves its surface into the dialog, so playback continues
 *   from the same position; closing hands the surface back.
 * - Any PlaybackException prints itself under the player, so a
 *   failure names itself on screen instead of staying invisible.
 */

@Composable
fun CompareTabs(processed: Boolean, onSelect: (Boolean) -> Unit) {
    Row(modifier = Modifier.padding(top = 6.dp)) {
        OutlinedButton(
            onClick = { onSelect(false) },
            modifier = Modifier.weight(1f),
            colors = if (!processed) ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) else ButtonDefaults.outlinedButtonColors()
        ) { Text(stringResource(R.string.m3_before)) }
        Spacer(Modifier.padding(6.dp))
        OutlinedButton(
            onClick = { onSelect(true) },
            modifier = Modifier.weight(1f),
            colors = if (processed) ButtonDefaults.outlinedButtonColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            ) else ButtonDefaults.outlinedButtonColors()
        ) { Text(stringResource(R.string.m3_after)) }
    }
}

@Composable
private fun rememberComparePlayer(file: File): ExoPlayer {
    val context = LocalContext.current
    val player = remember(file.absolutePath) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            prepare()
        }
    }
    DisposableEffect(player) { onDispose { player.release() } }
    return player
}

@Composable
private fun PlayPauseButton(player: ExoPlayer, playing: Boolean) {
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
}

@Composable
private fun useCompareState(
    player: ExoPlayer,
    fileKey: String,
    playSignal: Int
): Pair<Boolean, String?> {
    var errorText by remember(fileKey) { mutableStateOf<String?>(null) }
    var playing by remember(fileKey) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : androidx.media3.common.Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                errorText = error.errorCodeName + ": " + (error.message ?: "")
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
        }
        player.addListener(listener)
        onDispose { player.removeListener(listener) }
    }
    LaunchedEffect(playSignal) {
        if (playSignal > 0) {
            if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                player.seekTo(0)
            }
            player.play()
        }
    }
    return playing to errorText
}

@Composable
fun ComparePlayer(file: File, playSignal: Int) {
    val player = rememberComparePlayer(file)
    val (playing, errorText) = useCompareState(player, file.absolutePath, playSignal)
    var fullscreen by remember(file.absolutePath) { mutableStateOf(false) }

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
                    PlayPauseButton(player, playing)
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
            PlayPauseButton(player, playing)
            Spacer(Modifier.padding(6.dp))
            OutlinedButton(onClick = { fullscreen = true }) {
                Text(stringResource(R.string.m3_fullscreen))
            }
        }
        if (errorText != null) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

/**
 * Audio-only sibling for tools whose output has no picture
 * (MP3/WAV extraction): same behaviour — own Play/Pause button,
 * restart after end, autoplay on tab switch, errors on screen —
 * without a video surface.
 */
@Composable
fun AudioComparePlayer(file: File, playSignal: Int) {
    val player = rememberComparePlayer(file)
    val (playing, errorText) = useCompareState(player, file.absolutePath, playSignal)
    Column {
        Row(modifier = Modifier.padding(top = 6.dp)) {
            PlayPauseButton(player, playing)
        }
        if (errorText != null) {
            Text(
                errorText,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
