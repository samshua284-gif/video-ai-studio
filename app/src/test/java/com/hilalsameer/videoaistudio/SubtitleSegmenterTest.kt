package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.AsrSegment
import com.hilalsameer.videoaistudio.media.SubtitleSegmenter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.6.4: window segments become sentence cues (owner's field report). */
class SubtitleSegmenterTest {

    @Test
    fun dialogueBlockSplitsIntoSentences() {
        val block = "What are you doing later? Do you want to go watch a movie? " +
            "I actually have plans with somebody tonight. You never have plans. " +
            "Okay, I have plans, alright? Oh, okay, big shot. I have many plans. " +
            "I actually have a date. A date? Yep. Is this the first date? " +
            "It's the third date. Third? Mm-hmm."
        val cues = SubtitleSegmenter.resegment(listOf(AsrSegment(0.0, 29.0, block)))
        assertEquals(14, cues.size)
        assertEquals(0.0, cues.first().startSec, 1e-9)
        assertEquals(29.0, cues.last().endSec, 1e-9)
        assertTrue(cues.zipWithNext().all { (a, b) -> b.startSec == a.endSec })
        assertTrue(cues.all { it.endSec > it.startSec })
        assertEquals("What are you doing later?", cues.first().text)
        assertEquals("Mm-hmm.", cues.last().text)
    }

    @Test
    fun arabicAndDecimalAndEllipsis() {
        assertEquals(3, SubtitleSegmenter.splitSentences("مرحبا بك. كيف حالك؟ أنا بخير!").size)
        val dec = SubtitleSegmenter.splitSentences("The price is 3.5 dollars. Cheap.")
        assertEquals(2, dec.size)
        assertTrue(dec[0].contains("3.5"))
        val ell = SubtitleSegmenter.splitSentences("Wait... what happened?")
        assertEquals(listOf("Wait...", "what happened?"), ell)
    }

    @Test
    fun cuesSitOnVoiceSpansOnly() {
        // Voice in 0..4 and 6..10 of a 29 s window; 4..6 is silence.
        val seg = AsrSegment(
            0.0, 29.0, "One here. Two here. Three go. Four go.",
            listOf(0.0 to 4.0, 6.0 to 10.0)
        )
        val cues = SubtitleSegmenter.resegment(listOf(seg))
        assertEquals(4, cues.size)
        assertTrue(cues.none { it.startSec < 6.0 && it.endSec > 4.0 })
        assertEquals(4.0, cues[1].endSec, 1e-9)
        assertEquals(6.0, cues[2].startSec, 1e-9)
        assertTrue(cues.all { it.endSec > it.startSec })
    }

    @Test
    fun unpunctuatedStaysWhole() {
        val out = SubtitleSegmenter.resegment(listOf(AsrSegment(5.0, 9.0, "no punctuation here")))
        assertEquals(1, out.size)
        assertEquals(5.0, out[0].startSec, 1e-9)
        assertEquals(9.0, out[0].endSec, 1e-9)
    }

    // ---- 0.9.6 (TD-024): clause splitting + minimum cue floor ----

    @Test
    fun clauseSplitPreservesTextExactly() {
        val samples = listOf(
            // Arabic commas
            "قال الرجل كلاما طويلا جدا، ثم جلس يستريح قليلا، وبعد ذلك قام ومشى في " +
                "الحديقة الواسعة، وهو يفكر في أمور كثيرة تشغل باله منذ الصباح الباكر",
            // English commas and a semicolon
            "He walked into the room, looked around at everyone gathered there; " +
                "then he sat down quietly, and began to speak about the long journey",
            // Arabic semicolon and em dash
            "الكلام الأول هنا طويل بما يكفي ليحتاج تقسيما واضحا؛ والكلام الثاني " +
                "يتبعه مباشرة — ثم يأتي جزء ثالث أخير يكمل الجملة الطويلة هذه",
            // The conjunction ' و ' is the only boundary kind present
            "ذهب سالم إلى المدرسة في الصباح و قابل أصدقاءه هناك و لعب معهم في " +
                "الساحة الكبيرة و درس الدرس الأول كاملا و كتب الواجب و عاد إلى البيت"
        )
        for (s in samples) {
            val pieces = SubtitleSegmenter.splitClauses(s)
            assertEquals(s, pieces.joinToString(""))
            assertTrue("expected a split for: $s", pieces.size >= 2)
            assertTrue(pieces.all { it.length <= 84 })
        }
        // The conjunction opens the FOLLOWING piece, as Arabic reads.
        val waw = SubtitleSegmenter.splitClauses(samples[3])
        assertTrue(waw.drop(1).any { it.startsWith('و') })
        // No usable boundary: stays whole, never cut mid-word.
        val noBoundary = "كلمة".repeat(30)
        assertEquals(listOf(noBoundary), SubtitleSegmenter.splitClauses(noBoundary))
        // Under the cap: untouched even with a boundary inside.
        val short = "جملة قصيرة، فيها فاصلة"
        assertEquals(listOf(short), SubtitleSegmenter.splitClauses(short))
    }

    @Test
    fun overlapSpanCuesRespectFloor() {
        // One unbroken 25 s voice span (overlapped dialogue leaves
        // no pauses) carrying alternating short sentences, three of
        // them tiny interjections whose proportional share is < 0.8 s.
        val text = "نعم. The plan is ready now. لا. We go at dawn tomorrow. حسنا. " +
            "أنا موافق على كل شيء. Go. سنبدأ العمل فورا هنا. Yes we can. انتهى الكلام الآن."
        val seg = AsrSegment(0.0, 25.0, text, listOf(0.0 to 25.0))
        val cues = SubtitleSegmenter.resegment(listOf(seg))
        assertEquals(10, cues.size)
        assertEquals(0.0, cues.first().startSec, 1e-9)
        assertTrue(cues.all { it.startSec >= 0.0 && it.endSec <= 25.0 })
        assertTrue(cues.all { it.endSec - it.startSec >= 0.8 - 1e-9 })
        assertTrue(cues.zipWithNext().all { (a, b) -> b.startSec >= a.endSec - 1e-9 })
    }

    @Test
    fun runOnSentenceSplitsIntoCues() {
        // No sentence enders at all — one run-on over a 20 s span.
        val text = "ذهب الرجل إلى السوق في الصباح الباكر، واشترى الخبز والجبن والبيض " +
            "والحليب، ثم عاد إلى البيت وهو يحمل الأكياس الثقيلة على كتفه، وجلس مع " +
            "أسرته حول المائدة الكبيرة، وتحدثوا طويلا عن يومهم وعن خطط الغد وعن " +
            "العمل والمدرسة والأصدقاء والجيران، وبعد ذلك خرجوا جميعا إلى الحديقة " +
            "للعب والمرح والحديث حتى غروب الشمس"
        assertTrue(text.length in 200..400)
        val cues = SubtitleSegmenter.resegment(listOf(AsrSegment(0.0, 20.0, text)))
        assertTrue("expected >= 3 cues, got ${cues.size}", cues.size >= 3)
        assertEquals(text, cues.joinToString("") { it.text })
        assertTrue(cues.all { it.endSec > it.startSec })
        assertTrue(cues.zipWithNext().all { (a, b) -> b.startSec >= a.endSec - 1e-9 })
    }
}
