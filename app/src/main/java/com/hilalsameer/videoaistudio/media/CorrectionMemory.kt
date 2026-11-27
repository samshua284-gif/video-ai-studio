package com.hilalsameer.videoaistudio.media

/**
 * TD-006 Layer 3 — personal correction memory, segment level
 * (translation-memory style): when the owner edits a segment's
 * translation, the (source segment → his translation) pair is
 * stored; a future identical source segment is served his own
 * words verbatim. It learns only from him and invents nothing.
 * Storage lives in the UI layer (SharedPreferences); this object
 * is the pure lookup/application core.
 */
object CorrectionMemory {

    /** Normalised lookup key for a source segment. */
    fun key(sourceText: String): String =
        sourceText.trim().replace(Regex("\\s+"), " ")

    /** The owner's stored translation for this exact source
     *  segment, or null when he never corrected one like it. */
    fun lookup(sourceText: String, memory: Map<String, String>): String? =
        memory[key(sourceText)]?.takeIf { it.isNotBlank() }

    /**
     * Builds new memory pairs from an edited export: the edited
     * plain text is line-aligned with the segments (the TXT form
     * is one segment per line). A pair is recorded only where the
     * line count still matches AND the owner's line differs from
     * the machine translation — anything else is ambiguous and is
     * skipped, never guessed.
     */
    fun harvest(
        source: List<AsrSegment>,
        machine: List<AsrSegment>,
        editedText: String
    ): Map<String, String> {
        val lines = editedText.split("\n")
        if (lines.size != source.size || machine.size != source.size) return emptyMap()
        val out = LinkedHashMap<String, String>()
        source.forEachIndexed { i, seg ->
            val edited = lines[i].trim()
            if (edited.isNotEmpty() && edited != machine[i].text.trim()) {
                out[key(seg.text)] = edited
            }
        }
        return out
    }
}
