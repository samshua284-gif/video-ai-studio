package com.hilalsameer.videoaistudio.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Publishes a VIDEO output into the Gallery collection
 * Movies/VideoAIStudio via MediaStore.Video — the place the
 * system Studio/Gallery apps show (0.9.0, TD-018).
 *
 * History: before 0.7.1 every save went to Gallery collections.
 * TD-012 then moved ALL saves to Downloads after the owner's
 * MP3 went invisible on him. His 2026-11-28 correction splits
 * the rule by output type: audio keeps Downloads (DownloadsSaver,
 * «حفظ في مدير الملفات»), while video returns to the Studio
 * («الحفظ في الاستوديو») — a video belongs where he watches
 * videos. Returns the published content URI on success (callers
 * register it in Projects, TD-016), or null on API < 29 or any
 * failure; callers then fall back to the document picker.
 */
fun saveVideoToGallery(context: Context, file: File): Uri? {
    if (Build.VERSION.SDK_INT < 29) return null
    return try {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(
                MediaStore.Video.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MOVIES + "/VideoAIStudio"
            )
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        resolver.openOutputStream(uri)?.use { out ->
            file.inputStream().use { it.copyTo(out, 1 shl 16) }
        } ?: return null
        val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        uri
    } catch (e: Exception) {
        null
    }
}
