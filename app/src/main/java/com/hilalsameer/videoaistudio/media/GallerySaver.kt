package com.hilalsameer.videoaistudio.media

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.provider.MediaStore
import java.io.File

/**
 * Publishes an export into the device Gallery ("الاستيديو") via
 * MediaStore — owner requirement (2026-11-25): saving must land in
 * the Gallery directly, not in a file-manager flow. Videos go to
 * Movies/VideoAIStudio, audio to Music/VideoAIStudio. On API 29+
 * this needs no permission for the app's own inserts. Returns false
 * on API < 29 or any failure; callers then fall back to the system
 * document picker.
 */
fun saveToGallery(context: Context, file: File): Boolean {
    if (Build.VERSION.SDK_INT < 29) return false
    return try {
        val ext = file.extension.lowercase()
        val isAudio = ext == "mp3" || ext == "wav"
        val collection = if (isAudio) {
            MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            else -> "video/mp4"
        }
        val pathKey = if (isAudio) MediaStore.Audio.Media.RELATIVE_PATH
        else MediaStore.Video.Media.RELATIVE_PATH
        val pendingKey = if (isAudio) MediaStore.Audio.Media.IS_PENDING
        else MediaStore.Video.Media.IS_PENDING
        val nameKey = if (isAudio) MediaStore.Audio.Media.DISPLAY_NAME
        else MediaStore.Video.Media.DISPLAY_NAME
        val mimeKey = if (isAudio) MediaStore.Audio.Media.MIME_TYPE
        else MediaStore.Video.Media.MIME_TYPE
        val values = ContentValues().apply {
            put(nameKey, file.name)
            put(mimeKey, mime)
            put(pathKey, if (isAudio) "Music/VideoAIStudio" else "Movies/VideoAIStudio")
            put(pendingKey, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(collection, values) ?: return false
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: return false
        val done = ContentValues().apply { put(pendingKey, 0) }
        resolver.update(uri, done, null, null)
        true
    } catch (e: Exception) {
        false
    }
}
