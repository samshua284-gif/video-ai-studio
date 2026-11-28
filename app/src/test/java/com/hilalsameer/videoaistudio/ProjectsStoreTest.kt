package com.hilalsameer.videoaistudio

import com.hilalsameer.videoaistudio.media.ProjectEntry
import com.hilalsameer.videoaistudio.media.ProjectKind
import com.hilalsameer.videoaistudio.media.ProjectStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** TD-016: the Projects registry store, exactly as shipped. */
class ProjectsStoreTest {

    private fun entry(
        id: String, at: Long, title: String = "work_$id.mp3"
    ) = ProjectEntry(id, ProjectKind.MP3, title, "content://media/$id", "src_$id.mp4", at)

    @Test
    fun addAndListNewestFirst() {
        val s = ProjectStore()
        s.add(entry("a", 100))
        s.add(entry("b", 300))
        s.add(entry("c", 200))
        assertEquals(listOf("b", "c", "a"), s.all().map { it.id })
        assertEquals(3, s.size())
    }

    @Test
    fun addSameIdReplaces() {
        val s = ProjectStore()
        s.add(entry("a", 100, "old.mp3"))
        s.add(entry("a", 100, "new.mp3"))
        assertEquals(1, s.size())
        assertEquals("new.mp3", s.all()[0].title)
    }

    @Test
    fun removeReturnsEntryAndShrinks() {
        val s = ProjectStore()
        s.add(entry("a", 100))
        s.add(entry("b", 200))
        val removed = s.remove("a")
        assertEquals("a", removed?.id)
        assertEquals(listOf("b"), s.all().map { it.id })
        assertNull(s.remove("a"))
    }

    @Test
    fun jsonRoundTripPreservesEverything() {
        val s = ProjectStore()
        s.add(
            ProjectEntry(
                "id-1", ProjectKind.TRANSLATION,
                "فيلم \"الأصدقاء\" — الجزء ١.srt",
                "content://com.android.providers.downloads.documents/document/42",
                "friends - short film.mp4", 1_700_000_000_000L
            )
        )
        s.add(
            ProjectEntry(
                "id-2", ProjectKind.TRANSCRIPT,
                "back\\slash\nnewline.txt", "/storage/emulated/0/Download/x.txt",
                "", 5L
            )
        )
        val back = ProjectStore.decode(s.encode())
        assertEquals(s.all(), back.all())
    }

    @Test
    fun decodeOfGarbageIsEmptyNotCrash() {
        assertEquals(0, ProjectStore.decode("").size())
        assertEquals(0, ProjectStore.decode("not json").size())
        assertEquals(0, ProjectStore.decode("[{").size())
        assertEquals(0, ProjectStore.decode("[{\"id\": 7}]").size())
    }

    @Test
    fun decodeSkipsUnknownKindAndKeepsValid() {
        val json = "[" +
            "{\"id\":\"x\",\"kind\":\"NOPE\",\"title\":\"t\",\"locator\":\"l\",\"source\":\"\",\"createdAt\":1}," +
            "{\"id\":\"y\",\"kind\":\"TRIM\",\"title\":\"t2\",\"locator\":\"l2\",\"source\":\"s\",\"createdAt\":2}" +
            "]"
        val s = ProjectStore.decode(json)
        assertEquals(listOf("y"), s.all().map { it.id })
    }

    @Test
    fun unknownKeysAreIgnored() {
        val json = "[{\"id\":\"a\",\"kind\":\"MP3\",\"title\":\"t\",\"locator\":\"l\"," +
            "\"source\":\"\",\"createdAt\":3,\"future\":{\"nested\":[1,2,{\"x\":\"y\"}]}}]"
        val s = ProjectStore.decode(json)
        assertEquals(1, s.size())
        assertEquals(3L, s.all()[0].createdAt)
        assertTrue(s.encode().contains("\"createdAt\":3"))
    }
}
