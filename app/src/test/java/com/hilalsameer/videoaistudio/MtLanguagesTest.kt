package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.MtLanguages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** TD-021 regression lock: the UI→FLORES map is part of the
 *  NLLB model's contract. The five codes below were verified
 *  against the model's own tokenizer (Xenova/
 *  nllb-200-distilled-600M): each is present in its
 *  vocabulary. 0.6.0–0.9.2 shipped a wrong Arabic code —
 *  one the model does not contain (its Arabic code is
 *  "arb_Arab"; the full story is TD-021) — so every Arabic
 *  request threw inside the worker and, until 0.9.2's
 *  diagnostics surfaced the real error, the failure was
 *  invisible. If this test fails, someone edited the map
 *  away from the model's codes: check the tokenizer, not
 *  this test. */
class MtLanguagesTest {

    @Test
    fun mapEqualsTheModelVerifiedCodesExactly() {
        assertEquals(
            linkedMapOf(
                "ar" to "arb_Arab",
                "en" to "eng_Latn",
                "hi" to "hin_Deva",
                "ur" to "urd_Arab",
                "tr" to "tur_Latn"
            ),
            MtLanguages.codes
        )
    }

    @Test
    fun arabicResolvesToArbArab() {
        assertEquals("arb_Arab", MtLanguages.flores("ar"))
    }

    @Test
    fun everyServedLanguageResolves() {
        assertEquals("eng_Latn", MtLanguages.flores("en"))
        assertEquals("hin_Deva", MtLanguages.flores("hi"))
        assertEquals("urd_Arab", MtLanguages.flores("ur"))
        assertEquals("tur_Latn", MtLanguages.flores("tr"))
    }

    @Test
    fun unknownLanguageResolvesToNull() {
        assertNull(MtLanguages.flores("xx"))
    }
}
