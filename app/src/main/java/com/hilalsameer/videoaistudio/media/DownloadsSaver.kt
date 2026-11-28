package com.hilalsameer.videoaistudio.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes an export into the public Downloads folder via
 * MediaStore — the place the file manager shows first.
 *
 * History: the owner's 2026-11-25 requirement sent saves to the
 * Gallery collections (audio → Music/VideoAIStudio). In the field
 * (2026-11-27) he extracted an MP3, pressed save, got the ✓ — and
 * could not find the file anywhere he looks: not in Downloads,
 * not in the file manager's folders he browses. His order
 * replaced the rule the same day: the button reads
 * «حفظ في مدير الملفات» and the file lands in Downloads, where he
 * actually looks. On API 29+ this needs no permission for the
 * app's own inserts. Returns the published content URI on
 * success (0.9.0: callers register it in Projects, TD-016), or
 * null on API < 29 or any failure; callers then fall back to the
 * system document picker.
 */
fun saveToDownloads(context: Context, file: File): android.net.Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    return try {
        val mime = when (file.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: return null
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        uri
    } catch (e: Exception) {
        null
    }
}
