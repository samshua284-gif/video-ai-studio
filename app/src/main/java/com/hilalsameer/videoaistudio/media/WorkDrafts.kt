package com.hilalsameer.videoaistudio.media

/**
 * TD-026 — the work drafts (0.10.1): the working result of a
 * finished transcription or translation, kept INSIDE the app
 * so that leaving a tool — or the whole app — can never again
 * throw the owner's work away (his field report on 0.10.0: he
 * exited the correction station, and everything was gone).
 *
 * A draft is not a save. It lives only in app-private storage
 * (see [DraftsRepository]), it is never written to Downloads
 * or MediaStore, it never registers a Project, and it exports
 * nothing. The owner's manual-save rule (0.7.0 / TD-022) is
 * untouched: a file exists only where his own export tap put
 * it. The draft merely means the RESULT survives until he
 * decides what to do with it.
 *
 * Pure Kotlin with hand-rolled JSON, no Android dependencies —
 * the same discipline as [ProjectStore], so the round trip is
 * host-testable exactly as it ships. Doubles are written with
 * [Double.toString] and read back with [String.toDouble]:
 * that pair round-trips every finite double exactly, so a
 * restored draft's timings are bit-identical to the job's.
 */
data class TranscriptionDraft(
    val segments: List<AsrSegment>,
    val inputName: String,
    val title: String?,
    val mediaUri: String?,
    val language: String,
    val savedAt: Long
)

data class TranslationDraft(
    val segments: List<AsrSegment>,
    val source: List<AsrSegment>,
    val numberFlags: List<Int>,
    val inputName: String,
    val passedSegments: Int,
    val srcLang: String,
    val tgtLang: String,
    val mediaUri: String?,
    val savedAt: Long
)

object WorkDrafts {

    /**
     * A deterministic fingerprint of everything about a cue
     * list that is NOT its text: every start, every end and
     * every speech span, in order. Two lists with the same
     * fingerprint place every cue at the same instants.
     */
    fun timingFingerprint(segments: List<AsrSegment>): String {
        val sb = StringBuilder()
        for (seg in segments) {
            sb.append(seg.startSec).append('|').append(seg.endSec).append('|')
            for ((a, b) in seg.spans) {
                sb.append(a).append('-').append(b).append(',')
            }
            sb.append(';')
        }
        return sb.toString()
    }

    fun encodeTranscription(d: TranscriptionDraft): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"tool\":\"transcription\"")
        field(sb, "inputName", d.inputName)
        if (d.title != null) field(sb, "title", d.title)
        if (d.mediaUri != null) field(sb, "mediaUri", d.mediaUri)
        field(sb, "language", d.language)
        sb.append(",\"savedAt\":").append(d.savedAt)
        sb.append(",\"segments\":")
        appendSegments(sb, d.segments)
        sb.append('}')
        return sb.toString()
    }

    fun decodeTranscription(json: String): TranscriptionDraft? {
        val root = parseRoot(json, "transcription") ?: return null
        val segments = parseSegments(root["segments"]) ?: return null
        return TranscriptionDraft(
            segments = segments,
            inputName = root["inputName"] as? String ?: "",
            title = root["title"] as? String,
            mediaUri = root["mediaUri"] as? String,
            language = root["language"] as? String ?: "",
            savedAt = (root["savedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    fun encodeTranslation(d: TranslationDraft): String {
        val sb = StringBuilder()
        sb.append('{')
        sb.append("\"tool\":\"translation\"")
        field(sb, "inputName", d.inputName)
        if (d.mediaUri != null) field(sb, "mediaUri", d.mediaUri)
        field(sb, "srcLang", d.srcLang)
        field(sb, "tgtLang", d.tgtLang)
        sb.append(",\"passedSegments\":").append(d.passedSegments)
        sb.append(",\"savedAt\":").append(d.savedAt)
        sb.append(",\"numberFlags\":[")
        d.numberFlags.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append(f)
        }
        sb.append(']')
        sb.append(",\"segments\":")
        appendSegments(sb, d.segments)
        sb.append(",\"source\":")
        appendSegments(sb, d.source)
        sb.append('}')
        return sb.toString()
    }

    fun decodeTranslation(json: String): TranslationDraft? {
        val root = parseRoot(json, "translation") ?: return null
        val segments = parseSegments(root["segments"]) ?: return null
        val source = parseSegments(root["source"]) ?: return null
        val flagsRaw = root["numberFlags"] as? List<*> ?: emptyList<Any?>()
        val flags = flagsRaw.map { (it as? Number)?.toInt() ?: return null }
        return TranslationDraft(
            segments = segments,
            source = source,
            numberFlags = flags,
            inputName = root["inputName"] as? String ?: "",
            passedSegments = (root["passedSegments"] as? Number)?.toInt() ?: 0,
            srcLang = root["srcLang"] as? String ?: "",
            tgtLang = root["tgtLang"] as? String ?: "",
            mediaUri = root["mediaUri"] as? String,
            savedAt = (root["savedAt"] as? Number)?.toLong() ?: 0L
        )
    }

    // ---- encoding helpers ----

    private fun field(sb: StringBuilder, name: String, value: String) {
        sb.append(",\"").append(name).append("\":")
        appendJsonString(sb, value)
    }

    private fun appendSegments(sb: StringBuilder, segments: List<AsrSegment>) {
        sb.append('[')
        segments.forEachIndexed { i, seg ->
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"s\":").append(seg.startSec)
            sb.append(",\"e\":").append(seg.endSec)
            sb.append(",\"t\":")
            appendJsonString(sb, seg.text)
            if (seg.spans.isNotEmpty()) {
                sb.append(",\"sp\":[")
                seg.spans.forEachIndexed { j, (a, b) ->
                    if (j > 0) sb.append(',')
                    sb.append('[').append(a).append(',').append(b).append(']')
                }
                sb.append(']')
            }
            sb.append('}')
        }
        sb.append(']')
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

    // ---- decoding helpers ----

    /**
     * Parses the draft envelope. A draft is all-or-nothing —
     * a half-broken draft must read as NO draft, never as a
     * result with silently mangled cues — so any malformation
     * or a foreign tool tag yields null.
     */
    private fun parseRoot(json: String, tool: String): Map<String, Any?>? {
        return try {
            val v = JsonReader(json).read()
            val m = v as? Map<*, *> ?: return null
            if (m["tool"] != tool) return null
            @Suppress("UNCHECKED_CAST")
            m as Map<String, Any?>
        } catch (e: Exception) {
            null
        }
    }

    private fun parseSegments(v: Any?): List<AsrSegment>? {
        val list = v as? List<*> ?: return null
        val out = ArrayList<AsrSegment>(list.size)
        for (item in list) {
            val m = item as? Map<*, *> ?: return null
            val s = (m["s"] as? Number)?.toDouble() ?: return null
            val e = (m["e"] as? Number)?.toDouble() ?: return null
            val t = m["t"] as? String ?: ""
            val spans = ArrayList<Pair<Double, Double>>()
            val sp = m["sp"]
            if (sp != null) {
                val spList = sp as? List<*> ?: return null
                for (pair in spList) {
                    val p = pair as? List<*> ?: return null
                    if (p.size != 2) return null
                    val a = (p[0] as? Number)?.toDouble() ?: return null
                    val b = (p[1] as? Number)?.toDouble() ?: return null
                    spans.add(a to b)
                }
            }
            out.add(AsrSegment(s, e, t, spans))
        }
        return out
    }
}

/** Minimal JSON reader producing Maps/Lists/Strings/Numbers —
 *  just enough for the fixed draft schema, in the spirit of
 *  the registry reader in Projects.kt. */
private class JsonReader(private val s: String) {
    private var pos = 0

    fun read(): Any? {
        val v = readValue()
        skipWs()
        if (pos != s.length) throw IllegalArgumentException("trailing data")
        return v
    }

    private fun skipWs() {
        while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' ||
                s[pos] == '\n' || s[pos] == '\r')
        ) pos++
    }

    private fun readValue(): Any? {
        skipWs()
        if (pos >= s.length) throw IllegalArgumentException("eof")
        return when (s[pos]) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            't' -> {
                expectWord("true")
                true
            }
            'f' -> {
                expectWord("false")
                false
            }
            'n' -> {
                expectWord("null")
                null
            }
            else -> readNumber()
        }
    }

    private fun expectWord(w: String) {
        if (!s.startsWith(w, pos)) throw IllegalArgumentException("bad literal")
        pos += w.length
    }

    private fun readObject(): Map<String, Any?> {
        pos++
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (pos < s.length && s[pos] == '}') {
            pos++
            return out
        }
        while (true) {
            skipWs()
            val key = readString()
            skipWs()
            if (pos >= s.length || s[pos] != ':') {
                throw IllegalArgumentException("expected :")
            }
            pos++
            out[key] = readValue()
            skipWs()
            if (pos < s.length && s[pos] == '}') {
                pos++
                return out
            }
            if (pos >= s.length || s[pos] != ',') {
                throw IllegalArgumentException("expected ,")
            }
            pos++
        }
    }

    private fun readArray(): List<Any?> {
        pos++
        val out = ArrayList<Any?>()
        skipWs()
        if (pos < s.length && s[pos] == ']') {
            pos++
            return out
        }
        while (true) {
            out.add(readValue())
            skipWs()
            if (pos < s.length && s[pos] == ']') {
                pos++
                return out
            }
            if (pos >= s.length || s[pos] != ',') {
                throw IllegalArgumentException("expected ,")
            }
            pos++
        }
    }

    private fun readString(): String {
        if (pos >= s.length || s[pos] != '"') {
            throw IllegalArgumentException("expected string")
        }
        pos++
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

    private fun readNumber(): Number {
        val start = pos
        if (pos < s.length && s[pos] == '-') pos++
        while (pos < s.length && (s[pos].isDigit() || s[pos] == '.' ||
                s[pos] == 'e' || s[pos] == 'E' || s[pos] == '+' || s[pos] == '-')
        ) pos++
        if (pos == start) throw IllegalArgumentException("expected number at $pos")
        val raw = s.substring(start, pos)
        return try {
            if (raw.any { it == '.' || it == 'e' || it == 'E' }) {
                raw.toDouble()
            } else {
                raw.toLong()
            }
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("bad number $raw")
        }
    }
}
