package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.HistoryEntry
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.ReadState
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Settings
import com.yoruc.mangacracker.core.SourceMatch
import com.yoruc.mangacracker.core.UpdateItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Other sites" (offline): title similarity, chapter numbers (10.5, missing, Extra, IDs only, galleries), carrying
 * read marks and the continue spot over, and Repo's move + undo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SourceMoveTest {
    private fun ch(id: String, name: String, n: Float = -1f, pages: Int = 0) = Chapter(id, name, n, pages = pages)
    private fun read(t: Long = 1000L) = ReadState(9, 10, read = true, time = t)
    private val newRef = MangaRef("manhwa18cc", "new-title", "The Title")

    @Test fun titleSimilarity() {
        // same title, other spelling / case / punctuation
        assertTrue(SourceMatch.similarity("Solo Leveling", "solo leveling") > 0.99f)
        assertTrue(SourceMatch.similarity("Tomb Raider King", "Tomb Raider King (Official)") > 0.85f)
        assertTrue(SourceMatch.similarity("Kaguya-sama: Love Is War", "Kaguya sama Love is War") > 0.95f)
        // doujin style: groups / parody in brackets don't matter
        assertTrue(SourceMatch.similarity("[Circle (Artist)] Summer Holiday [English]", "Summer Holiday") > 0.95f)
        // accents
        assertTrue(SourceMatch.similarity("Pokémon Adventures", "Pokemon Adventures") > 0.99f)
        // different titles stay far apart, and a sequel is below the same title
        assertTrue(SourceMatch.similarity("Solo Leveling", "Tower of God") < 0.4f)
        val same = SourceMatch.similarity("Solo Leveling", "Solo Leveling")
        val sequel = SourceMatch.similarity("Solo Leveling", "Solo Leveling: Ragnarok")
        assertTrue("sequel $sequel < same $same", sequel < same && sequel >= 0.6f)
        // alt titles: the best one counts
        assertTrue(SourceMatch.best(listOf("Ore dake Level Up na Ken", "Solo Leveling"), "Solo Leveling") > 0.99f)
        // ranking
        val c = listOf("Tower of God", "Solo Leveling: Ragnarok", "Solo Leveling", "Leveling Solo").sortedByDescending { SourceMatch.similarity("Solo Leveling", it) }
        assertEquals("Solo Leveling", c.first())
    }

    @Test fun chapterNumbers() {
        assertEquals(12f, SourceMatch.number(ch("a", "Chapter 12")))
        assertEquals(10.5f, SourceMatch.number(ch("a", "Ch. 10.5 - Side")))
        assertEquals(3f, SourceMatch.number(ch("a", "Episode 3")))
        assertEquals(7f, SourceMatch.number(ch("a", "Vol. 2 Chapter 7")))
        assertEquals(42f, SourceMatch.number(ch("a", "42")))
        assertEquals(5f, SourceMatch.number(ch("a", "whatever", 5f)))
        assertNull(SourceMatch.number(ch("a", "Extra")))
        assertNull(SourceMatch.number(ch("a", "Side Story: The Beach")))
        assertEquals(12f, SourceMatch.numberFromId("chapter-12"))
        assertEquals(10.5f, SourceMatch.numberFromId("my-title/chapter-10-5"))
        assertNull(SourceMatch.numberFromId("a8f3k2"))
    }

    @Test fun carryByNumber() {
        // old site: 1, 2, 3, 10.5, Extra; read 1, 2, 10.5 and Extra; reading 3 (page 5 of 20)
        val old = listOf(ch("o3", "Chapter 3", 3f), ch("o105", "Chapter 10.5", 10.5f), ch("ox", "Extra"), ch("o2", "Chapter 2", 2f), ch("o1", "Chapter 1", 1f))
        val prog = mapOf("o1" to read(), "o2" to read(), "o105" to read(), "ox" to read(), "o3" to ReadState(4, 20, 120, read = false, time = 5000))
        val hist = HistoryEntry(MangaRef("atsu", "old", "The Title"), old[0], ReadState(4, 20, 120, false, 5000), 5000, 5)
        // new site: names only, no 2 (missing), 10.5 spelled differently, two uploads of chapter 1, and its own "Extra"
        val new = listOf(
            ch("n3", "Ch. 3", pages = 40), ch("n105", "Chapter 10.5"), ch("nextra", "Extra"),
            ch("n1a", "Chapter 1"), ch("n1b", "Chapter 1 (other group)"), ch("n4", "Chapter 4"),
        )
        val c = SourceMatch.carry(old, prog, hist, newRef, new, now = 9999)
        assertTrue(c.progress["n1a"]!!.read && c.progress["n1b"]!!.read)
        assertTrue(c.progress["n105"]!!.read)
        assertTrue("Extra matched by name", c.progress["nextra"]!!.read)
        assertNull("chapter 4 was never read", c.progress["n4"])
        assertEquals("chapter 2 is missing on the new site", 1, c.unmatched)
        // continue spot: chapter 3, same share of the pages (5 of 20 -> 10 of 40)
        val h = c.history!!
        assertEquals("n3", h.chapter.id)
        assertEquals(newRef, h.ref)
        assertEquals(8, h.state.page)
        assertEquals(40, h.state.pages)
        assertFalse(c.progress["n3"]!!.read)
        assertEquals(6, h.chapterCount)
    }

    @Test fun carryWithIdsOnly() {
        // the old site is broken: no chapter list, only progress keys (slugs) and the history chapter
        val prog = mapOf("my-title/chapter-1" to read(), "my-title/chapter-2" to read(), "x9z" to read())
        val hist = HistoryEntry(MangaRef("old", "t", "T"), ch("my-title/chapter-2", "Chapter 2"), ReadState(9, 10, read = true), 1, 0)
        val new = listOf(ch("c3", "Chapter 3", 3f), ch("c2", "Chapter 2", 2f), ch("c1", "Chapter 1", 1f))
        val c = SourceMatch.carry(emptyList(), prog, hist, newRef, new)
        assertTrue(c.progress["c1"]!!.read && c.progress["c2"]!!.read)
        assertNull(c.progress["c3"])
        assertEquals("an id with no number can't be matched", 1, c.unmatched)
        assertEquals("c2", c.history!!.chapter.id)
    }

    @Test fun carryGalleries() {
        // nhentai gallery -> hitomi gallery: the one chapter is the one chapter (ids and names differ)
        val old = listOf(ch("g", "Gallery · 24 pages", 1f, 24))
        val new = listOf(ch("123456", "Gallery", -1f, 26))
        val part = SourceMatch.carry(old, mapOf("g" to ReadState(11, 24, time = 7)), HistoryEntry(MangaRef("nhentai", "1", "x"), old[0], ReadState(11, 24, time = 7), 7, 1), newRef, new)
        assertEquals("123456", part.history!!.chapter.id)
        assertEquals(11, part.history!!.state.page)   // 11/24 of 26 pages
        val done = SourceMatch.carry(old, mapOf("g" to ReadState(23, 24, read = true)), null, newRef, new)
        assertTrue(done.progress["123456"]!!.read)
        // nothing on the new site: nothing carried
        val none = SourceMatch.carry(old, mapOf("g" to read()), null, newRef, emptyList())
        assertTrue(none.progress.isEmpty())
        assertEquals(1, none.unmatched)
    }

    @Test fun repoMoveAndUndo() {
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
        val oldRef = MangaRef("atsu", "old", "The Title", cover = "https://example.org/old.jpg")
        val chs = (5 downTo 1).map { ch("o$it", "Chapter $it", it.toFloat(), 20) }
        Repo.addToLibrary(oldRef, null, chs)
        val added = Repo.library.value[oldRef.key]!!.added
        Repo.setRead(oldRef, chs.takeLast(3), true)                 // 1..3 read
        Repo.saveRead(oldRef, chs[1], 6, 20, 0, 5)                  // reading 4
        Repo.applyChapterCheck(oldRef.key, listOf(ch("o6", "Chapter 6", 6f)) + chs)
        assertEquals(1, Repo.updates.value.count { it.ref.key == oldRef.key })
        val before = Triple(Repo.library.value, Repo.history.value, Repo.progress.value)
        Thread.sleep(5)

        val new = (6 downTo 1).map { ch("n$it", "Chapter $it", pages = 20) }
        val carry = SourceMatch.carry(chs, Repo.progress.value.filterKeys { it.startsWith(oldRef.key + "#") }.mapKeys { it.key.substringAfter('#') },
            Repo.history.value[oldRef.key], newRef, new)
        val undo = Repo.moveTitle(oldRef.key, newRef, null, new, carry)
        val lib = Repo.library.value
        assertFalse(oldRef.key in lib)
        val e = lib[newRef.key]!!
        assertEquals("added date kept", added, e.added)
        assertEquals(6, e.chapterCount)
        assertEquals("old cover kept when the new one is unknown", oldRef.cover, e.ref.cover)
        assertTrue((1..3).all { Repo.state(newRef.key, "n$it")?.read == true })
        assertNull(Repo.state(newRef.key, "n5"))
        assertEquals("n4", Repo.history.value[newRef.key]!!.chapter.id)
        assertEquals(6, Repo.state(newRef.key, "n4")!!.page)
        assertNull(Repo.history.value[oldRef.key])
        assertTrue(Repo.progress.value.keys.none { it.startsWith(oldRef.key + "#") })
        assertTrue(Repo.updates.value.none { it.ref.key == oldRef.key })

        Repo.undoMove(undo)
        assertEquals(before.first, Repo.library.value)
        assertEquals(before.second, Repo.history.value)
        assertEquals(before.third, Repo.progress.value)
        assertEquals(1, Repo.updates.value.count { it.ref.key == oldRef.key })
    }

    @Test fun moveOntoATitleAlreadyKept() {
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
        val oldRef = MangaRef("atsu", "old2", "The Title")
        val c1 = ch("o1", "Chapter 1", 1f)
        Repo.addToLibrary(oldRef, null, listOf(c1))
        Repo.setRead(oldRef, listOf(c1), true)
        // the new site's title is already in the library, chapter 2 read there
        val n = listOf(ch("n2", "Chapter 2"), ch("n1", "Chapter 1"))
        Repo.addToLibrary(newRef, null, n)
        Repo.setRead(newRef, listOf(n[0]), true)
        val carry = SourceMatch.carry(listOf(c1), mapOf("o1" to read()), null, newRef, n)
        val undo = Repo.moveTitle(oldRef.key, newRef, null, n, carry)
        assertTrue(Repo.state(newRef.key, "n1")!!.read && Repo.state(newRef.key, "n2")!!.read)
        assertEquals(1, Repo.library.value.size)
        Repo.undoMove(undo)
        assertNotNull(Repo.library.value[oldRef.key]); assertNotNull(Repo.library.value[newRef.key])
        assertNull("the new title's own marks are back as they were", Repo.state(newRef.key, "n1"))
        assertTrue(Repo.state(newRef.key, "n2")!!.read)
        @Suppress("UNUSED_VARIABLE") val unused: List<UpdateItem> = Repo.updates.value
    }
}
