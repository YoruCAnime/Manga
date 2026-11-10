package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.HistoryEntry
import com.yoruc.mangacracker.core.LibraryEntry
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Page
import com.yoruc.mangacracker.core.ReadMarks
import com.yoruc.mangacracker.core.ReadState
import com.yoruc.mangacracker.ui.ReaderItem
import com.yoruc.mangacracker.ui.readerRows
import com.yoruc.mangacracker.ui.screenOrder
import com.yoruc.mangacracker.ui.spreads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure logic: two-page pairing and order, read marks on covers, "hide titles I've finished". */
class ReaderLogicTest {
    @Test fun pairsPages() {
        val p = (1..9).toList()
        // cover alone, then two by two, odd last page alone
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5), listOf(6, 7), listOf(8, 9)), spreads(p, { false }))
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4, 5), listOf(6, 7), listOf(8)), spreads(p.take(8), { false }))
        // a wide page (already a spread) stays alone, and so does the page before it
        assertEquals(listOf(listOf(1), listOf(2, 3), listOf(4), listOf(5), listOf(6, 7), listOf(8, 9)), spreads(p, { it == 5 }))
        assertEquals(listOf(listOf(1), listOf(2), listOf(3, 4)), spreads(listOf(1, 2, 3, 4), { it == 2 }))
        // without the cover rule
        assertEquals(listOf(listOf(1, 2), listOf(3)), spreads(listOf(1, 2, 3), { false }, firstAlone = false))
        assertEquals(emptyList<List<Int>>(), spreads(emptyList<Int>(), { false }))
        assertEquals(listOf(listOf(1)), spreads(listOf(1), { true }))
    }

    @Test fun rightToLeft() {
        assertEquals(listOf(3, 2), screenOrder(listOf(2, 3), rtl = true))
        assertEquals(listOf(2, 3), screenOrder(listOf(2, 3), rtl = false))
        assertEquals(listOf(5), screenOrder(listOf(5), rtl = true))
    }

    @Test fun rowsPerChapter() {
        val a = Chapter("a", "A"); val b = Chapter("b", "B")
        fun pages(c: Chapter, n: Int) = (0 until n).map { ReaderItem.PageItem(c, Page(it, "u${c.id}$it"), n) }
        val items = listOf(ReaderItem.Header(a, true)) + pages(a, 4) + ReaderItem.Footer(a, b) + ReaderItem.Header(b, false) + pages(b, 3) + ReaderItem.Footer(b, null)
        val one = readerRows(items, false) { false }
        assertEquals(items.size, one.size); assertTrue(one.all { it.size == 1 })
        val two = readerRows(items, true) { false }
        // header | 1 | 2+3 | 4 | footer | header | 1 | 2+3 | footer: each chapter starts with its first page alone
        assertEquals(listOf(1, 1, 2, 1, 1, 1, 1, 2, 1), two.map { it.size })
        assertEquals(items, two.flatten())
        val wide = readerRows(items, true) { it.chapter.id == "a" && it.page.index == 1 }
        assertEquals(listOf(1, 1, 1, 2, 1, 1, 1, 2, 1), wide.map { it.size })
    }

    private fun ref(id: String, src: String = "atsu") = MangaRef(src, id, id)
    private fun hist(r: MangaRef, page: Int, pages: Int, count: Int = 0) =
        HistoryEntry(r, Chapter("c1", "1"), ReadState(page, pages, read = page >= pages - 1), 1L, count)

    @Test fun readMarks() {
        val lib = mapOf(
            "atsu|a" to LibraryEntry(ref("a"), 1, chapterCount = 40),
            "atsu|b" to LibraryEntry(ref("b"), 1, chapterCount = 3),
            "atsu|new" to LibraryEntry(ref("new"), 1, chapterCount = 10),
        )
        val hist = mapOf(
            "atsu|a" to hist(ref("a"), 3, 20),
            "nhentai|g1" to hist(ref("g1", "nhentai"), 19, 20),
            "nhentai|g2" to hist(ref("g2", "nhentai"), 5, 20),
            "atsu|c" to hist(ref("c"), 1, 10, count = 7),
        )
        val prog = HashMap<String, ReadState>()
        (1..12).forEach { prog["atsu|a#ch$it"] = ReadState(9, 10, read = true) }
        prog["atsu|a#ch13"] = ReadState(3, 20)
        (1..3).forEach { prog["atsu|b#x$it"] = ReadState(9, 10, read = true) }
        prog["nhentai|g1#1"] = ReadState(19, 20, read = true)
        // only marked read on the title page (no history): known from the loaded chapter list
        (1..5).forEach { prog["atsu|d#k$it"] = ReadState(0, 0, read = true) }
        // a chapter id with '#' in it still counts for its title
        prog["atsu|c#part#2"] = ReadState(9, 10, read = true)
        val m = ReadMarks.compute(lib, hist, prog, cached = { if (it == "atsu|d") 5 else 0 }, single = { it.startsWith("nhentai|") })

        assertEquals(12, m["atsu|a"]!!.read); assertEquals(40, m["atsu|a"]!!.total); assertFalse(m["atsu|a"]!!.finished)
        assertEquals(0.3f, m["atsu|a"]!!.fraction, 0.001f)
        assertTrue(m["atsu|b"]!!.finished)
        assertNull("in the library but never opened: no badge", m["atsu|new"])
        assertTrue("a gallery read to the end", m["nhentai|g1"]!!.finished)
        assertFalse(m["nhentai|g2"]!!.finished); assertEquals(0.3f, m["nhentai|g2"]!!.fraction, 0.01f)
        assertTrue(m["atsu|d"]!!.finished)
        assertEquals(1, m["atsu|c"]!!.read); assertEquals(7, m["atsu|c"]!!.total)

        // hide finished: Browse-style list loses b, g1, d; keeps the rest in order
        val list = listOf("atsu|a", "atsu|b", "nhentai|g1", "nhentai|g2", "atsu|new", "atsu|d", "atsu|zzz")
        assertEquals(listOf("atsu|a", "nhentai|g2", "atsu|new", "atsu|zzz"), ReadMarks.dropFinished(list, m) { it })
    }
}
