package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.sources.AtsuSource
import com.yoruc.mangacracker.sources.ComixSource
import com.yoruc.mangacracker.sources.DanbooruSource
import com.yoruc.mangacracker.sources.EHentaiSource
import com.yoruc.mangacracker.sources.GelbooruSource
import com.yoruc.mangacracker.sources.NhentaiSource
import com.yoruc.mangacracker.ui.rankTags
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Live: animated posts on the image boards play the original GIF (not the still sample), and the search box's
 * autocomplete ("smi" -> smile, smirk ...) on every source. Only tag names are printed, never titles.
 */
class GifAndTagsTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() {
            Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http"))
        }
    }

    private fun head(url: String): String =
        Http.imageClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            val b = r.body!!.source().apply { request(6) }.buffer.snapshot(6).utf8()
            "${r.code} ${r.header("Content-Type")} magic=$b"
        }

    @Test fun animatedPostsPlayTheGif() = runBlocking {
        for (src in listOf<Source>(DanbooruSource, GelbooruSource)) {
            val tag = Tag("animated_gif", "animated gif")
            val r = src.search(SearchQuery(include = listOf(tag, Tag("rating:general", "general"))), 1).items
            assertTrue("${src.id}: no animated posts", r.isNotEmpty())
            var gifs = 0
            for (ref in r.take(3)) {
                val pages = runCatching { src.pages(ref, src.chapters(ref).first()) }.getOrNull() ?: continue
                val url = pages.first().url
                val h = head(url)
                println("[gif] ${src.id} ${url.substringAfterLast('.')} $h")
                if ("magic=GIF8" in h) gifs++
            }
            assertTrue("${src.id}: no GIF served", gifs > 0)
        }
    }

    @Test fun autocomplete() = runBlocking {
        val cases = listOf<Pair<Source, String>>(
            DanbooruSource to "smi", GelbooruSource to "smi", AtsuSource to "rom", ComixSource to "rom",
            NhentaiSource to "big", EHentaiSource to "glas",
        )
        for ((src, typed) in cases) {
            val got = runCatching { rankTags(typed, src.suggestTags(typed).filter { it.group != "typed" }).take(8) }
                .onFailure { println("[tags] ${src.id} '$typed' FAILED ${it.message}") }.getOrDefault(emptyList())
            println("[tags] ${src.id} '$typed' -> " + got.joinToString { it.label + (if (it.count > 0) " (${it.count})" else "") + (if (it.group.isNotBlank()) " [${it.group}]" else "") })
            assertTrue("${src.id}: nothing for '$typed'", got.isNotEmpty())
            assertTrue("${src.id}: first suggestion doesn't start with '$typed'", got.first().label.startsWith(typed, true) || got.first().id.substringAfter(':').startsWith(typed, true))
        }
    }
}
