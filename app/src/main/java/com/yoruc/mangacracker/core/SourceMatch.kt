package com.yoruc.mangacracker.core

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * "Other sites": how alike two titles are, and how reading progress carries over from one site's chapter list to
 * another's (by chapter NUMBER: chapter 12 read here = chapter 12 read there). Pure Kotlin, tested in SourceMoveTest.
 */
object SourceMatch {

    // ---------- titles ----------

    private val BRACKETS = Regex("[\\[(（【{][^\\])）】}]*[\\])）】}]")
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
    /** Words that say nothing about which title it is. */
    private val NOISE = setOf("the", "a", "an", "of", "no", "wa", "ga", "to", "manga", "manhwa", "manhua", "webtoon", "comic",
        "english", "eng", "raw", "official", "translated", "uncensored", "decensored", "digital", "color", "colored", "full")

    fun norm(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFKD).replace(Regex("\\p{M}+"), "").lowercase()
            .replace('’', '\'').replace("'", "").replace(NOT_WORD, " ").trim()

    private fun words(s: String) = norm(s).split(' ').filter { it.isNotEmpty() && it !in NOISE }

    /** The title without [group] / (parody) / {tags} parts, as doujin sites write them. */
    fun core(s: String): String = s.replace(BRACKETS, " ").trim().ifEmpty { s }

    private fun lev(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val c = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + c)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    private fun score(a: String, b: String): Float {
        val wa = words(a); val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0f
        val sa = wa.joinToString(" "); val sb = wb.joinToString(" ")
        if (sa == sb) return 1f
        // shared words (Dice) and spelling (edit distance) count half each
        val setA = wa.toSet(); val setB = wb.toSet()
        val dice = 2f * setA.intersect(setB).size / (setA.size + setB.size)
        val edit = 1f - lev(sa, sb).toFloat() / maxOf(sa.length, sb.length)
        var s = 0.5f * dice + 0.5f * edit
        // one title is the other plus a subtitle ("Solo Leveling" / "Solo Leveling: Ragnarok" is NOT the same,
        // but "Title" / "Title (Official)" is): a whole-word prefix of 2+ words scores at least 0.8
        val shorter = if (wa.size <= wb.size) wa else wb
        val longer = if (shorter === wa) wb else wa
        if (shorter.size >= 2 && longer.take(shorter.size) == shorter) s = maxOf(s, 0.8f - 0.05f * (longer.size - shorter.size).coerceAtMost(4))
        return s.coerceIn(0f, 1f)
    }

    /** 0..1: how alike two titles are, also comparing them without their bracketed parts. */
    fun similarity(a: String, b: String): Float = maxOf(score(a, b), score(core(a), core(b)))

    /** The best similarity of [candidate] to any of [names] (a title and its alt titles). */
    fun best(names: List<String>, candidate: String): Float = names.filter { it.isNotBlank() }.maxOfOrNull { similarity(it, candidate) } ?: 0f

    // ---------- chapter numbers ----------

    private val CH_WORD = Regex("(?:\\bch(?:apter|ap)?|\\bep(?:isode)?|\\bepisodio|\\bcapitulo|第|#)\\.?\\s*(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
    private val ID_WORD = Regex("(?:chapter|chap|ch|episode|ep)[-_ .]?(\\d+)(?:[-_.](\\d{1,2}))?(?![\\d])", RegexOption.IGNORE_CASE)
    private val LONE = Regex("^\\s*(?:vol(?:ume)?\\.?\\s*\\d+\\s*)?(\\d+(?:\\.\\d+)?)\\s*(?:[:\\-–].*)?$", RegexOption.IGNORE_CASE)

    /** Chapter number from the source's number, its name ("Chapter 10.5", "Ep. 3", "12"), else null ("Extra"). */
    fun number(c: Chapter): Float? {
        if (c.number >= 0f) return c.number
        CH_WORD.find(c.name)?.let { return it.groupValues[1].replace(',', '.').toFloatOrNull() }
        LONE.find(c.name)?.let { return it.groupValues[1].toFloatOrNull() }
        return null
    }

    /** A number read from a chapter ID ("chapter-12", "ch_10-5"), for old chapters known only by their IDs. */
    fun numberFromId(id: String): Float? {
        val m = ID_WORD.findAll(id).lastOrNull() ?: return null
        val whole = m.groupValues[1].toFloatOrNull() ?: return null
        val part = m.groupValues[2]
        return if (part.isNotEmpty()) "$whole".substringBefore('.').let { "$it.$part".toFloatOrNull() } else whole
    }

    private fun nkey(n: Float) = (n * 100).roundToInt()

    /** What a move carries over: read marks + the history spot, in the NEW title's chapter ids. */
    data class Carry(
        val progress: Map<String, ReadState>,
        val history: HistoryEntry?,
        /** New chapters marked read. */
        val read: Int,
        /** Old read chapters that have no partner on the new site (missing, or no number). */
        val unmatched: Int,
    )

    /**
     * Carries reading over from the old title to [newRef]. [oldChapters] = what is known of the old list (may be
     * partial when the old site is broken; chapters only known from the progress map are added by their IDs).
     * [oldProgress] = chapter id -> state for the old title. Galleries (one chapter each side) map one to one.
     */
    fun carry(
        oldChapters: List<Chapter>,
        oldProgress: Map<String, ReadState>,
        oldHistory: HistoryEntry?,
        newRef: MangaRef,
        newChapters: List<Chapter>,
        now: Long = System.currentTimeMillis(),
    ): Carry {
        if (newChapters.isEmpty()) return Carry(emptyMap(), null, 0, oldProgress.count { it.value.read })
        // every old chapter we know of: the list, the history's chapter, and IDs only seen in the progress map
        val known = LinkedHashMap<String, Chapter>()
        oldChapters.forEach { known[it.id] = it }
        oldHistory?.chapter?.let { known.putIfAbsent(it.id, it) }
        oldProgress.keys.forEach { id -> known.putIfAbsent(id, Chapter(id, id, numberFromId(id) ?: -1f)) }
        fun num(c: Chapter): Float? = number(c) ?: numberFromId(c.id)

        // galleries / single chapters: the one chapter is the one chapter
        val single = newChapters.size == 1 && known.size <= 1
        val out = HashMap<String, ReadState>()
        var unmatched = 0
        val newByNum = HashMap<Int, MutableList<Chapter>>()
        val newByName = HashMap<String, MutableList<Chapter>>()
        newChapters.forEach { c ->
            num(c)?.let { newByNum.getOrPut(nkey(it)) { ArrayList() } += c }
            newByName.getOrPut(norm(c.name)) { ArrayList() } += c
        }
        fun partners(old: Chapter): List<Chapter> {
            if (single) return newChapters
            val n = num(old)
            if (n != null) return newByNum[nkey(n)].orEmpty()
            // no number ("Extra", "Side story"): only the same name counts
            return newByName[norm(old.name)].orEmpty()
        }
        for ((id, st) in oldProgress) {
            if (!st.read) continue
            val old = known[id] ?: continue
            val p = partners(old)
            if (p.isEmpty()) { unmatched++; continue }
            p.forEach { c -> out[c.id] = ReadState(0, c.pages.takeIf { it > 0 } ?: st.pages, 0, read = true, time = st.time) }
        }

        // the "continue" spot: the same chapter number; part-way = the same share of the pages
        val hist = oldHistory?.let { h ->
            val p = partners(h.chapter).firstOrNull() ?: return@let null
            val oldSt = oldProgress[h.chapter.id] ?: h.state
            val pages = p.pages.takeIf { it > 0 } ?: oldSt.pages
            val page = when {
                oldSt.read || pages <= 0 -> (pages - 1).coerceAtLeast(0)
                oldSt.pages > 0 && pages != oldSt.pages -> (oldSt.page.toFloat() * pages / oldSt.pages).toInt().coerceIn(0, pages - 1)
                else -> oldSt.page.coerceIn(0, (pages - 1).coerceAtLeast(0))
            }
            val st = ReadState(page, pages, if (pages == oldSt.pages) oldSt.offset else 0, read = oldSt.read, time = oldSt.time.takeIf { it > 0 } ?: now)
            if (!st.read || out[p.id] == null) out[p.id] = st
            HistoryEntry(newRef, p, h.state.copy(page = page, pages = pages, read = h.state.read), h.time, newChapters.size)
        }
        return Carry(out, hist, out.count { it.value.read }, unmatched)
    }

    /** Is [n] the number of one of [chapters]? (tests / UI: "chapter 12 is on the new site") */
    fun hasNumber(chapters: List<Chapter>, n: Float) = chapters.any { c -> number(c)?.let { abs(it - n) < 0.005f } == true }
}
