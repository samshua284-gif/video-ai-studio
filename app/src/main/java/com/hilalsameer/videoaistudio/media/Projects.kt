package com.hilalsameer.videoaistudio.media

/**
 * The Projects registry model (0.9.0, TD-016): a durable record of
 * the works the tools actually produced and saved — the owner's
 * «مشاريعي». Pure Kotlin with hand-rolled JSON, no Android
 * dependencies, so the whole store is host-testable exactly as it
 * ships (the same discipline as SpeechChunks and SpeechGate).
 *
 * An entry is written ONLY after a save has really completed
 * (a Downloads publish that returned its URI, or a document-picker
 * copy that finished without error) — never at job start, never
 * on a hopeful button press (Rule 1).
 *
 * The locator is where the output lives: a content URI string
 * (MediaStore Downloads insert, or the document the owner picked)
 * or a plain file path. The store itself never touches the file
 * system; existence checks and opening live in the UI layer.
 */
enum class ProjectKind { MP3, TRANSCRIPT, TRANSLATION, ENHANCED, TRIM, COMPRESS }

data class ProjectEntry(
    val id: String,
    val kind: ProjectKind,
    val title: String,
    val locator: String,
    val sourceName: String,
    val createdAt: Long
)

class ProjectStore {
    private val entries = ArrayList<ProjectEntry>()

    /** Add an entry; an existing id is replaced, never duplicated. */
    fun add(entry: ProjectEntry) {
        entries.removeAll { it.id == entry.id }
        entries.add(entry)
    }

    /** Remove by id; returns the removed entry or null. */
    fun remove(id: String): ProjectEntry? {
        val idx = entries.indexOfFirst { it.id == id }
        if (idx < 0) return null
        return entries.removeAt(idx)
    }

    fun find(id: String): ProjectEntry? = entries.firstOrNull { it.id == id }

    /** Newest first; the id breaks ties so the order is stable. */
    fun all(): List<ProjectEntry> = entries.sortedWith(
        compareByDescending<ProjectEntry> { it.createdAt }.thenByDescending { it.id }
    )

    fun size(): Int = entries.size

    /** Serialize the whole registry as a JSON array of objects. */
    fun encode(): String {
        val sb = StringBuilder()
        sb.append('[')
        entries.forEachIndexed { i, e ->
            if (i > 0) sb.append(',')
            sb.append('{')
            field(sb, "id", e.id, true)
            field(sb, "kind", e.kind.name, false)
            field(sb, "title", e.title, false)
            field(sb, "locator", e.locator, false)
            field(sb, "source", e.sourceName, false)
            sb.append(",\"createdAt\":").append(e.createdAt)
            sb.append('}')
        }
        sb.append(']')
        return sb.toString()
    }

    private fun field(sb: StringBuilder, name: String, value: String, first: Boolean) {
        if (!first) sb.append(',')
        sb.append('"').append(name).append("\":")
        appendJsonString(sb, value)
    }

    companion object {
        /**
         * Parse a registry written by [encode]. Any malformation —
         * a truncated file, a hand-edited value, an unknown kind —
         * yields whatever valid entries could be read, or an empty
         * store; a corrupt registry must never crash the app.
         */
        fun decode(json: String): ProjectStore {
            val store = ProjectStore()
            try {
                val p = JsonParser(json)
                p.skipWs()
                p.expect('[')
                p.skipWs()
                if (p.tryConsume(']')) return store
                while (true) {
                    // A null entry (unknown kind, missing id) is
                    // SKIPPED, not fatal: its object was fully
                    // consumed, so the next entry still parses.
                    // A malformed entry throws, and the outer
                    // catch keeps whatever was read before it.
                    val e = parseEntry(p)
                    if (e != null) store.add(e)
                    p.skipWs()
                    if (p.tryConsume(']')) break
                    p.expect(',')
                }
            } catch (e: Exception) {
                // Fall through with whatever was read so far.
            }
            return store
        }

        private fun parseEntry(p: JsonParser): ProjectEntry? {
            p.skipWs()
            p.expect('{')
            var id: String? = null
            var kind: ProjectKind? = null
            var title = ""
            var locator = ""
            var source = ""
            var createdAt = 0L
            p.skipWs()
            if (p.tryConsume('}')) return null
            while (true) {
                p.skipWs()
                val key = p.parseString()
                p.skipWs()
                p.expect(':')
                p.skipWs()
                when (key) {
                    "id" -> id = p.parseString()
                    "kind" -> kind = try {
                        ProjectKind.valueOf(p.parseString())
                    } catch (e: Exception) {
                        null
                    }
                    "title" -> title = p.parseString()
                    "locator" -> locator = p.parseString()
                    "source" -> source = p.parseString()
                    "createdAt" -> createdAt = p.parseLong()
                    else -> p.skipValue()
                }
                p.skipWs()
                if (p.tryConsume('}')) break
                p.expect(',')
            }
            val i = id ?: return null
            val k = kind ?: return null
            if (i.isEmpty()) return null
            return ProjectEntry(i, k, title, locator, source, createdAt)
        }
    }
}

private fun appendJsonString(sb: StringBuilder, v: String) {
    sb.append('"')
    for (ch in v) {
        when (ch) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n' -> sb.append("\\n")
            '\r' -> sb.append("\\r")
            '\t' -> sb.append("\\t")
            '\b' -> sb.append("\\b")
            '\u000C' -> sb.append("\\f")
            else -> {
                if (ch < ' ') {
                    sb.append("\\u")
                    sb.append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    sb.append(ch)
                }
            }
        }
    }
    sb.append('"')
}

/** Minimal JSON reader for the fixed registry schema. */
private class JsonParser(private val s: String) {
    private var pos = 0

    fun skipWs() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' ||
                s[pos] == '\n' || s[pos] == '\r')
        ) pos++
    }

    fun expect(c: Char) {
        if (pos >= s.length || s[pos] != c) {
            throw IllegalArgumentException("expected $c at $pos")
        }
        pos++
    }

    fun tryConsume(c: Char): Boolean {
        if (pos < s.length && s[pos] == c) {
            pos++
            return true
        }
        return false
    }

    fun parseString(): String {
        expect('"')
        val sb = StringBuilder()
        while (pos < s.length) {
            val ch = s[pos++]
            when (ch) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= s.length) break
                    when (val esc = s[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'u' -> {
                            if (pos + 4 > s.length) {
                                throw IllegalArgumentException("bad unicode escape")
                            }
                            val hex = s.substring(pos, pos + 4)
                            pos += 4
                            sb.append(hex.toInt(16).toChar())
                        }
                        else -> throw IllegalArgumentException("bad escape $esc")
                    }
                }
                else -> sb.append(ch)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    fun parseLong(): Long {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        while (pos < s.length && s[pos].isDigit()) pos++
        if (pos == start) throw IllegalArgumentException("expected number at $pos")
        return s.substring(start, pos).toLong()
    }

    /** Skip any JSON value (used for unknown keys). */
    fun skipValue() {
        skipWs()
        if (pos >= s.length) throw IllegalArgumentException("eof in value")
        when (s[pos]) {
            '"' -> parseString()
            '{' -> {
                pos++
                skipWs()
                if (tryConsume('}')) return
                while (true) {
                    skipWs()
                    parseString()
                    skipWs()
                    expect(':')
                    skipValue()
                    skipWs()
                    if (tryConsume('}')) return
                    expect(',')
                }
            }
            '[' -> {
                pos++
                skipWs()
                if (tryConsume(']')) return
                while (true) {
                    skipValue()
                    skipWs()
                    if (tryConsume(']')) return
                    expect(',')
                }
            }
            else -> {
                while (pos < s.length && s[pos] != ',' && s[pos] != '}' &&
                    s[pos] != ']' && s[pos] != ' ' && s[pos] != '\n'
                ) pos++
            }
        }
    }
}
