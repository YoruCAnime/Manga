package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.sources.AtsuSource
import com.yoruc.mangacracker.sources.ComixSource
import com.yoruc.mangacracker.sources.DanbooruSource
import com.yoruc.mangacracker.sources.EHentaiSource
import com.yoruc.mangacracker.sources.GelbooruSource
import com.yoruc.mangacracker.sources.NhentaiSource
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Live checks against the real sites (run on demand: gradlew :app:testDebugUnitTest --tests '*LiveSourcesTest*').
 * Gentle: a handful of calls per source. Titles of 18+ sources are never printed, only counts.
 */
class LiveSourcesTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() {
            Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http"))
        }
    }

    private fun log(s: String) = println("[live] $s")

    private fun image(url: String): String {
        Http.imageClient.newCall(Request.Builder().url(url).build()).execute().use { r ->
            return "${r.code} ${r.header("Content-Type")} ${r.body?.contentLength()}"
        }
    }

    private fun names(src: Source, l: List<MangaRef>) = if (src.nsfw) "(${l.size} items)" else l.take(3).joinToString(" | ") { it.title }

    private suspend fun walk(src: Source, pick: MangaRef) {
        val d = src.details(pick)
        log("${src.id} details: cover=${d.ref.cover.isNotEmpty()} genres=${d.genres.size} authors=${d.authors.size + d.artists.size} blocked=${Safety.blocked(d)} hits=${(d.genres + d.ref.tags + d.tagGroups.flatMap { it.tags }).filter { Safety.isMinorTag(it) }}")
        val ch = src.chapters(pick)
        log("${src.id} chapters: ${ch.size} first=${ch.firstOrNull()?.name?.take(40)}")
        assertTrue("${src.id} no chapters", ch.isNotEmpty())
        val pages = src.pages(pick, ch.last())
        log("${src.id} pages: ${pages.size}")
        assertTrue("${src.id} no pages", pages.isNotEmpty())
        val u = src.imageUrl(pages.first())
        log("${src.id} image: ${image(u)}")
        log("${src.id} cover: ${image(d.ref.cover)}")
        val rel = runCatching { src.related(pick, d) }.getOrElse { log("${src.id} related failed: ${it.message}"); emptyList() }
        log("${src.id} related: ${rel.size}")
    }

    @Test fun atsu() = runBlocking {
        val s = AtsuSource
        val pop = s.popular(1); log("atsu popular: ${pop.items.size} next=${pop.hasNext} ${names(s, pop.items)}")
        val lat = s.latest(1); log("atsu latest: ${lat.items.size}")
        val tags = s.tags(); log("atsu tags: ${tags.size}")
        val q = SearchQuery(include = listOf(Tag("g:39", "Action"), Tag("g:36", "Fantasy")), exclude = listOf(Tag("g:9", "Romance")), selects = mapOf("type" to "Manwha"))
        val r = s.search(q, 1); log("atsu search action+fantasy-romance manhwa: ${r.items.size} next=${r.hasNext} ${names(s, r.items)}")
        val t = s.search(SearchQuery(text = "solo leveling"), 1); log("atsu text: ${names(s, t.items)}")
        assertTrue(pop.items.isNotEmpty() && r.items.isNotEmpty())
        walk(s, t.items.first())
        val d = s.details(t.items.first())
        val a = s.search(s.artistQuery(d.authors.first()), 1); log("atsu author '${d.authors.first()}': ${a.items.size} ${names(s, a.items)}")
    }

    @Test fun comixPlain() = runBlocking {
        val s = ComixSource
        val pop = s.popular(1); log("comix popular: ${pop.items.size} ${names(s, pop.items)}")
        val lat = s.latest(1); log("comix latest: ${lat.items.size}")
        assertTrue(pop.items.isNotEmpty())
        val d = s.details(pop.items.first())
        log("comix details: ${d.ref.title} genres=${d.genres} authors=${d.authors} cover=${image(d.ref.cover)}")
        log("comix grid cover: ${image(pop.items.first().cover)}")
        val rel = s.related(pop.items.first(), d); log("comix related: ${rel.size}")
        log("comix tag suggest 'vamp': ${s.suggestTags("vamp").take(3)}")
    }

    @Test fun nhentai() = runBlocking {
        val s = NhentaiSource
        val lat = s.latest(1); log("nhentai latest: ${lat.items.size} next=${lat.hasNext}")
        val q = s.search(SearchQuery(include = listOf(Tag("big breasts", "big breasts")), exclude = listOf(Tag("netorare", "netorare")), selects = mapOf("sort" to "popular-week", "lang" to "english")), 1)
        log("nhentai search: ${q.items.size} next=${q.hasNext}")
        assertTrue(lat.items.isNotEmpty() && q.items.isNotEmpty())
        walk(s, q.items.first())
    }

    @Test fun ehentai() = runBlocking {
        val s = EHentaiSource
        val l = runCatching { s.latest(1) }
        log("ehentai latest: ${l.map { "${it.items.size}" }.getOrElse { "error: " + it.message }}")
        // a Non-H gallery by link (works from the UK)
        val r = s.search(SearchQuery(text = "https://e-hentai.org/g/4281254/30a6774456/"), 1)
        log("ehentai by link: ${r.items.size} ${r.items.firstOrNull()?.title}")
        assertTrue(r.items.isNotEmpty())
        val pick = r.items.first()
        val ch = s.chapters(pick); log("ehentai chapters: ${ch.map { it.name }}")
        val pages = s.pages(pick, ch.first()); log("ehentai pages: ${pages.size}")
        log("ehentai page1: ${image(s.imageUrl(pages.first()))}")
        log("ehentai page25 (2nd thumb page): ${image(s.imageUrl(pages[24]))}")
        val d = s.details(pick); log("ehentai details: artists=${d.artists} groups=${d.tagGroups.map { it.name }} cover=${image(d.ref.cover)}")
    }

    @Test fun danbooru() = runBlocking {
        val s = DanbooruSource
        val r = s.search(SearchQuery(include = listOf(Tag("scenery", "scenery")), selects = mapOf("rating" to "rating:g,s", "sort" to "order:score")), 1)
        log("danbooru scenery safe top: ${r.items.size} next=${r.hasNext}")
        assertTrue(r.items.isNotEmpty())
        log("danbooru grid cover: ${image(r.items.first().cover)}")
        walk(s, r.items.first())
        val pools = s.search(SearchQuery(text = "colorful", selects = mapOf("kind" to "pools")), 1)
        log("danbooru pools 'colorful': ${pools.items.size} covers=${pools.items.count { it.cover.isNotEmpty() }}")
        log("danbooru autocomplete 'scen': ${s.suggestTags("scen").take(3).map { it.label }}")
        val tooMany = runCatching { s.search(SearchQuery(include = listOf(Tag("a", "a"), Tag("b", "b"), Tag("c", "c"))), 1) }
        log("danbooru 3 tags: ${tooMany.exceptionOrNull()?.message}")
    }

    @Test fun gelbooru() = runBlocking {
        val s = GelbooruSource
        val r = s.search(SearchQuery(include = listOf(Tag("scenery", "scenery")), selects = mapOf("rating" to "rating:general")), 1)
        log("gelbooru scenery general: ${r.items.size} next=${r.hasNext}")
        assertTrue(r.items.isNotEmpty())
        log("gelbooru grid cover (sample): ${image(r.items.first().cover)}")
        walk(s, r.items.first())
        log("gelbooru autocomplete 'scen': ${s.suggestTags("scen").take(3).map { it.label }}")
    }
}
