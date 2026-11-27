package com.hilalsameer.videoaistudio.media

/**
 * TD-006 Layer 1 — the safe, deterministic end of translation
 * quality: numbers. Digit conversion between scripts is exact
 * arithmetic, so it may be automated; everything semantic is only
 * ever DETECTED and flagged for the owner, never silently
 * rewritten (a corrector that invents is worse than an error
 * that shows — the voice translator's documented lesson).
 */
object TranslateFix {

    /** All number tokens in [text], normalised to Western digits
     *  with grouping separators removed ("١٬٢٥٠" and "1,250" both
     *  become "1250"; "٣.٥" and "3.5" both become "3.5"). */
    fun numbersIn(text: String): List<String> {
        val western = toWesternDigits(text)
        return Regex("""\d+(?:\.\d+)?""")
            .findAll(western.replace(",", "").replace("٬", ""))
            .map { it.value.trimStart('0').ifEmpty { "0" } }
            .toList()
    }

    /** True when every number in the source survives in the
     *  translation (as a multiset — a repeated number must repeat). */
    fun numbersPreserved(source: String, translation: String): Boolean {
        val src = numbersIn(source).groupingBy { it }.eachCount()
        if (src.isEmpty()) return true
        val tgt = numbersIn(translation).groupingBy { it }.eachCount()
        return src.all { (n, c) -> (tgt[n] ?: 0) >= c }
    }

    /** Converts every digit in [text] to Western (0-9) form. */
    fun toWesternDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) sb.append(westernOf(ch))
        return sb.toString()
    }

    /** Converts Western digits in [text] to Arabic-Indic (٠-٩). */
    fun toArabicIndicDigits(text: String): String {
        val sb = StringBuilder(text.length)
        for (ch in text) {
            sb.append(if (ch in '0'..'9') ('\u0660' + (ch - '0')) else ch)
        }
        return sb.toString()
    }

    private fun westernOf(ch: Char): Char = when (ch) {
        in '\u0660'..'\u0669' -> '0' + (ch - '\u0660')   // Arabic-Indic ٠-٩
        in '\u06F0'..'\u06F9' -> '0' + (ch - '\u06F0')   // Eastern Arabic ۰-۹
        else -> ch
    }
}
