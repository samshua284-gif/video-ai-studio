package com.hilalsameer.videoaistudio.ui

import android.content.ActivityNotFoundException
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.compose.foundation.background
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.hilalsameer.videoaistudio.R
import com.hilalsameer.videoaistudio.media.ProjectEntry
import com.hilalsameer.videoaistudio.media.ProjectKind
import com.hilalsameer.videoaistudio.media.ProjectsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

/**
 * «مشاريعي» — the Projects page (0.9.0, TD-016). The registry of
 * completed works IS the history of what the studio produced, so
 * this page replaces the old M8 history placeholder: every row is
 * a real saved output that can be opened, shared, or removed.
 * A row whose file has since been deleted outside the app says
 * so in place instead of failing silently (the 0.6.2 lesson).
 */
@Composable
fun ProjectsScreen(onBack: () -> Unit, onSettings: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<ProjectEntry>>(emptyList()) }
    var missing by remember { mutableStateOf<Set<String>>(emptySet()) }
    var refreshTick by remember { mutableStateOf(0) }

    LaunchedEffect(refreshTick) {
        val loaded = withContext(Dispatchers.IO) { ProjectsRepository.load(context) }
        entries = loaded
        missing = withContext(Dispatchers.IO) {
            loaded.filter { !locatorExists(context, it.locator) }
                .map { it.id }.toSet()
        }
    }

    fun openEntry(entry: ProjectEntry) {
        val uri = resolveOpenUri(context, entry)
        if (uri == null) {
            missing = missing + entry.id
            return
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mimeOf(entry.title))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            missing = missing + entry.id
        } catch (e: Exception) {
            missing = missing + entry.id
        }
    }

    fun shareEntry(entry: ProjectEntry) {
        val uri = resolveOpenUri(context, entry)
        if (uri == null) {
            missing = missing + entry.id
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = mimeOf(entry.title)
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            context.startActivity(Intent.createChooser(intent, entry.title))
        } catch (e: Exception) {
            missing = missing + entry.id
        }
    }

    fun deleteEntry(entry: ProjectEntry) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val removed = ProjectsRepository.remove(context, entry.id)
                // The underlying file is deleted only where the
                // brief allows it: a plain path inside the public
                // Downloads folder that we can actually delete.
                // Everything else is an entry-only removal, and
                // the button said so before it was pressed.
                if (removed != null && canDeleteFile(removed.locator)) {
                    try {
                        File(removed.locator).delete()
                    } catch (e: Exception) { }
                }
            }
            refreshTick++
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
                    ProjectsNavItem(Icons.Filled.Home, stringResource(R.string.nav_home), false) {
                        onBack()
                    }
                    ProjectsNavItem(
                        Icons.Filled.Folder, stringResource(R.string.nav_projects), true
                    ) { }
                    ProjectsNavItem(
                        Icons.Filled.Settings, stringResource(R.string.tool_settings), false
                    ) { onSettings() }
                }
            }
        }
    ) { pad ->
        Column(modifier = Modifier.fillMaxSize().padding(pad).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
            Text(
                text = stringResource(R.string.proj_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = StudioTokens.Gold
            )
            Spacer(Modifier.height(12.dp))
            if (entries.isEmpty()) {
                Text(
                    text = stringResource(R.string.proj_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        ProjectRow(
                            entry = entry,
                            isMissing = missing.contains(entry.id),
                            onOpen = { openEntry(entry) },
                            onShare = { shareEntry(entry) },
                            onDelete = { deleteEntry(entry) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun ProjectsNavItem(
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
private fun ProjectRow(
    entry: ProjectEntry,
    isMissing: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val (icon, tint) = kindVisual(entry.kind)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, StudioTokens.CardBorder)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(42.dp).background(
                        tint.copy(alpha = 0.15f), RoundedCornerShape(10.dp)
                    ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = entry.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(kindLabelRes(entry.kind)) + " · " +
                            DateFormat.getDateTimeInstance(
                                DateFormat.MEDIUM, DateFormat.SHORT
                            ).format(Date(entry.createdAt)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (entry.sourceName.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.proj_from, entry.sourceName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            if (isMissing) {
                Text(
                    text = stringResource(R.string.proj_file_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            Row(modifier = Modifier.padding(top = 4.dp)) {
                TextButton(onClick = onOpen, enabled = !isMissing) {
                    Text(stringResource(R.string.proj_open))
                }
                TextButton(onClick = onShare, enabled = !isMissing) {
                    Text(stringResource(R.string.proj_share))
                }
                TextButton(onClick = onDelete) {
                    Text(
                        stringResource(
                            if (canDeleteFile(entry.locator)) R.string.proj_delete_file
                            else R.string.proj_remove_entry
                        )
                    )
                }
            }
        }
    }
}

private fun kindVisual(kind: ProjectKind): Pair<ImageVector, Color> = when (kind) {
    ProjectKind.MP3 -> Icons.Filled.MusicNote to Color(0xFFB388FF)
    ProjectKind.TRANSCRIPT -> Icons.Filled.Mic to Color(0xFFFFB74D)
    ProjectKind.TRANSLATION -> Icons.Filled.Translate to Color(0xFF64B5F6)
    ProjectKind.ENHANCED -> Icons.Filled.GraphicEq to Color(0xFF3DDC84)
    ProjectKind.TRIM -> Icons.Filled.ContentCut to Color(0xFFEF5350)
    ProjectKind.COMPRESS -> Icons.Filled.Compress to Color(0xFF3DDC84)
}

private fun kindLabelRes(kind: ProjectKind): Int = when (kind) {
    ProjectKind.MP3 -> R.string.proj_kind_mp3
    ProjectKind.TRANSCRIPT -> R.string.proj_kind_transcript
    ProjectKind.TRANSLATION -> R.string.proj_kind_translation
    ProjectKind.ENHANCED -> R.string.proj_kind_enhanced
    ProjectKind.TRIM -> R.string.proj_kind_trim
    ProjectKind.COMPRESS -> R.string.proj_kind_compress
}

/** Does the output behind a locator still exist? */
private fun locatorExists(context: Context, locator: String): Boolean {
    return try {
        if (locator.startsWith("content://")) {
            context.contentResolver.openInputStream(Uri.parse(locator))?.use { true } ?: false
        } else {
            File(locator).exists()
        }
    } catch (e: Exception) {
        false
    }
}

/**
 * A content URI for opening/sharing: content locators are used
 * as-is; a plain path is resolved through the Downloads
 * collection by display name (a raw file path cannot be handed
 * to another app on modern Android). Null when unresolvable.
 */
private fun resolveOpenUri(context: Context, entry: ProjectEntry): Uri? {
    val locator = entry.locator
    if (locator.startsWith("content://")) return Uri.parse(locator)
    val file = File(locator)
    if (!file.exists()) return null
    return try {
        context.contentResolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.MediaColumns._ID),
            MediaStore.MediaColumns.DISPLAY_NAME + "=?",
            arrayOf(file.name),
            null
        )?.use { c ->
            if (c.moveToFirst()) {
                ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)
                )
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

/**
 * True only for a plain file path inside the public Downloads
 * folder — the one case where deleting a Projects entry also
 * deletes the underlying file.
 */
private fun canDeleteFile(locator: String): Boolean {
    if (locator.startsWith("content://")) return false
    return try {
        val downloads = Environment.getExternalStoragePublicDirectory(
            Environment.DIRECTORY_DOWNLOADS
        ).canonicalFile
        val parent = File(locator).canonicalFile.parentFile ?: return false
        parent == downloads
    } catch (e: Exception) {
        false
    }
}

private fun mimeOf(title: String): String {
    val ext = title.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
