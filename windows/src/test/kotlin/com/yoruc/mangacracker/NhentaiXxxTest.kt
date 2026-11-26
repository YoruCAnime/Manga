package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.sources.NhentaiXxxSource
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Live: the nhentai.xxx source from the age question to the last page's picture. Counts only. -Psources=nhxxx runs it. */
class NhentaiXxxTest {
    private val s = NhentaiXxxSource
    private val hidden = setOf("nx:3", "nx:40", "nx:138", "nx:141", "nx:202", "nx:208", "nx:256", "nx:345", "nx:726")

    private fun status(url: String): Int = (URL(url).openConnection() as HttpURLConnection).run {
        setRequestProperty("User-Agent", "Mozilla/5.0")
        requestMethod = "GET"
        connectTimeout = 20_000; readTimeout = 20_000
        responseCode.also { disconnect() }
    }

    @Test fun whole() = runBlocking {
        if (System.getProperty("sources") != "nhxxx") return@runBlocking
        val dir = File("build/nhxxx").apply { deleteRecursively(); mkdirs() }
        val ctx = Context(dir)
        Http.init(ctx); Repo.init(ctx)

        // before the answer: nothing is read, and the source stays out of Home / search everywhere
        assertTrue(Sources.all.contains(s))
        assertFalse(Sources.enabled().contains(s))
        assertTrue(runCatching { s.latest(1) }.exceptionOrNull() is SourceException)
        Repo.updateSettings { it.copy(ageConfirmed = it.ageConfirmed + s.id) }
        assertTrue(Sources.enabled().contains(s))
        println("[nhxxx] age question ok")

        val latest = s.latest(1)
        val latest2 = s.latest(2)
        val pop = s.popular(1)
        println("[nhxxx] latest ${latest.items.size} next ${latest.hasNext} | page 2 ${latest2.items.size} | popular ${pop.items.size} next ${pop.hasNext}")
        assertTrue(latest.items.size in 15..25)
        assertTrue(latest.hasNext)
        assertNotEquals(latest.items.first().id, latest2.items.first().id)
        assertNotEquals(latest.items.first().id, pop.items.first().id)
        val all = latest.items + latest2.items + pop.items
        assertTrue("no hidden tag in a list", all.none { r -> r.tags.any { it in hidden } })
        assertTrue("titles", all.all { it.title.isNotBlank() })
        assertTrue("covers named", all.all { it.cover.startsWith("https://") })
        val covers = latest.items.count { status(it.cover) == 200 }
        println("[nhxxx] covers answering $covers of ${latest.items.size}")
        assertEquals(latest.items.size, covers)

        // a popular title with an artist, a tag and a page list
        var done = false
        for (r in pop.items.take(6)) {
            val d = s.details(r)
            delay(500)
            if (d.artists.isEmpty() || d.genres.isEmpty()) continue
            println("[nhxxx] title: pages ${d.pageCount}, artists ${d.artists.size}, circles ${d.authors.size}, tags ${d.genres.size}, groups ${d.tagGroups.map { it.name }}, cover ${d.ref.cover.isNotEmpty()}")
            assertTrue(d.pageCount > 0)
            val ch = s.chapters(r)
            assertEquals(1, ch.size)
            assertTrue("upload date read", ch[0].date > 0)
            val pages = s.pages(r, ch[0])
            println("[nhxxx] pages ${pages.size}; first ${status(pages.first().url)}, last ${status(pages.last().url)}, ext ${pages.first().url.substringAfterLast('.')}")
            assertEquals(d.pageCount, pages.size)
            assertEquals(200, status(pages.first().url))
            assertEquals(200, status(pages.last().url))
            val art = s.search(s.artistQuery(d.artists[0]), 1)
            println("[nhxxx] tap artist: ${art.items.size} works, same title ${art.items.any { it.key == r.key }}")
            assertTrue(art.items.isNotEmpty())
            val tag = s.search(s.tagQuery(d.genres[0]), 1)
            println("[nhxxx] tap tag: ${tag.items.size}")
            assertTrue(tag.items.isNotEmpty())
            d.authors.firstOrNull()?.let { c ->
                val g = s.search(s.groupQuery(c), 1)
                println("[nhxxx] tap circle: ${g.items.size} works, same title ${g.items.any { it.key == r.key }}")
                assertTrue(g.items.isNotEmpty())
            }
            d.tagGroups.firstOrNull { it.name == "Parody" || it.name == "Language" }?.let { tg ->
                val g = s.search(s.tagQuery(tg.tags[0]), 1)
                println("[nhxxx] tap ${tg.name}: ${g.items.size}")
                assertTrue(g.items.isNotEmpty())
            }
            val rel = s.related(r, d)
            println("[nhxxx] related ${rel.size}")
            assertTrue(rel.isNotEmpty())
            assertTrue(rel.none { x -> x.tags.any { it in hidden } })
            done = true
            break
        }
        assertTrue("found a title with an artist", done)

        // search
        val word = s.search(SearchQuery(text = "glasses"), 1)
        val wordPop = s.search(SearchQuery(text = "glasses", selects = mapOf("sort" to "popular")), 1)
        val two = s.search(SearchQuery(include = listOf(Tag("glasses", "glasses"), Tag("stockings", "stockings"))), 1)
        val tagPop = s.search(SearchQuery(include = listOf(Tag("glasses", "glasses")), selects = mapOf("sort" to "popular")), 1)
        val tag2 = s.search(SearchQuery(include = listOf(Tag("glasses", "glasses")), selects = mapOf("sort" to "popular")), 2)
        val lang = s.search(SearchQuery(selects = mapOf("lang" to "japanese")), 1)
        println("[nhxxx] word ${word.items.size} | popular ${wordPop.items.size} | two tags ${two.items.size} | tag popular ${tagPop.items.size}, page 2 ${tag2.items.size} | japanese ${lang.items.size}")
        listOf(word, wordPop, two, tagPop, tag2, lang).forEach { assertTrue(it.items.isNotEmpty()) }
        assertNotEquals(word.items.first().id, wordPop.items.first().id)
        assertNotEquals(tagPop.items.first().id, tag2.items.first().id)
        assertTrue(listOf(word, wordPop, two, tagPop, tag2, lang).flatMap { it.items }.none { r -> r.tags.any { it in hidden } })

        // hidden names never reach the site
        assertTrue(s.search(SearchQuery(text = "lolicon"), 1).items.isEmpty())
        assertTrue(s.search(s.tagQuery("shotacon"), 1).items.isEmpty())
        println("[nhxxx] all ok")
    }
}
