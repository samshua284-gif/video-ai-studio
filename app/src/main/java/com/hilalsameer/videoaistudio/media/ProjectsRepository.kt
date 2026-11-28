package com.hilalsameer.videoaistudio.media

import android.content.Context
import java.io.File
import java.util.UUID

/**
 * Android side of the Projects registry (0.9.0, TD-016): persists
 * the pure [ProjectStore] as JSON in internal storage
 * (filesDir/projects.json). Every write is a full rewrite of a
 * small file — the registry holds pointers to works, not the
 * works themselves, so it stays tiny.
 *
 * Registration is best-effort by design: a registry write failure
 * must never fail a save that already succeeded (the work exists;
 * the index can miss it, never the reverse).
 */
object ProjectsRepository {
    private const val FILE_NAME = "projects.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun load(context: Context): List<ProjectEntry> {
        return read(context).all()
    }

    /** Register a completed save; returns the entry (or null). */
    @Synchronized
    fun register(
        context: Context,
        kind: ProjectKind,
        title: String,
        locator: String,
        sourceName: String
    ): ProjectEntry? {
        if (locator.isEmpty() || title.isEmpty()) return null
        return try {
            val store = read(context)
            val entry = ProjectEntry(
                id = UUID.randomUUID().toString(),
                kind = kind,
                title = title,
                locator = locator,
                sourceName = sourceName,
                createdAt = System.currentTimeMillis()
            )
            store.add(entry)
            write(context, store)
            entry
        } catch (e: Exception) {
            null
        }
    }

    /** Remove an entry; returns it so the caller can offer to
     *  delete the underlying file where that is possible. */
    @Synchronized
    fun remove(context: Context, id: String): ProjectEntry? {
        return try {
            val store = read(context)
            val removed = store.remove(id)
            if (removed != null) write(context, store)
            removed
        } catch (e: Exception) {
            null
        }
    }

    private fun read(context: Context): ProjectStore {
        val f = file(context)
        if (!f.exists()) return ProjectStore()
        return try {
            ProjectStore.decode(f.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            ProjectStore()
        }
    }

    private fun write(context: Context, store: ProjectStore) {
        val f = file(context)
        val tmp = File(context.filesDir, FILE_NAME + ".tmp")
        tmp.writeText(store.encode(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            // Same-filesystem rename should not fail; fall back to
            // a direct write rather than losing the registry.
            f.writeText(store.encode(), Charsets.UTF_8)
            tmp.delete()
        }
    }
}
