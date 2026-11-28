package com.hilalsameer.videoaistudio.media

import android.content.Context
import java.io.File

/**
 * Android side of the work drafts (0.10.1, TD-026): persists
 * the pure [WorkDrafts] models as JSON in internal storage
 * (filesDir/transcription_draft.json and
 * filesDir/translation_draft.json), mirroring
 * [ProjectsRepository]'s shape and discipline.
 *
 * THE BOUNDARY (TD-026): a draft is app-internal working
 * state, nothing more. This repository writes ONLY inside
 * filesDir — never to Downloads or MediaStore, never through
 * the export launchers, and it never calls
 * [ProjectsRepository]: no Project is registered and no file
 * the owner can see is created. Saving a work still happens
 * exclusively at his own export tap (0.7.0 / TD-022).
 *
 * Every operation is best-effort: a draft write failure must
 * never fail a job that already succeeded (the result is on
 * screen; the draft is a safety net, not the work itself),
 * and a corrupt draft file reads as no draft at all.
 */
object DraftsRepository {
    private const val TRANSCRIPTION_FILE = "transcription_draft.json"
    private const val TRANSLATION_FILE = "translation_draft.json"

    @Synchronized
    fun saveTranscription(context: Context, draft: TranscriptionDraft) {
        try {
            write(context, TRANSCRIPTION_FILE, WorkDrafts.encodeTranscription(draft))
        } catch (e: Exception) { }
    }

    @Synchronized
    fun loadTranscription(context: Context): TranscriptionDraft? {
        return try {
            val f = File(context.filesDir, TRANSCRIPTION_FILE)
            if (!f.exists()) null
            else WorkDrafts.decodeTranscription(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun clearTranscription(context: Context) {
        try {
            File(context.filesDir, TRANSCRIPTION_FILE).delete()
        } catch (e: Exception) { }
    }

    @Synchronized
    fun saveTranslation(context: Context, draft: TranslationDraft) {
        try {
            write(context, TRANSLATION_FILE, WorkDrafts.encodeTranslation(draft))
        } catch (e: Exception) { }
    }

    @Synchronized
    fun loadTranslation(context: Context): TranslationDraft? {
        return try {
            val f = File(context.filesDir, TRANSLATION_FILE)
            if (!f.exists()) null
            else WorkDrafts.decodeTranslation(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    fun clearTranslation(context: Context) {
        try {
            File(context.filesDir, TRANSLATION_FILE).delete()
        } catch (e: Exception) { }
    }

    private fun write(context: Context, name: String, json: String) {
        val f = File(context.filesDir, name)
        val tmp = File(context.filesDir, name + ".tmp")
        tmp.writeText(json, Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            f.writeText(json, Charsets.UTF_8)
            tmp.delete()
        }
    }
}
