package com.yoruc.mangacracker

import androidx.test.core.app.ApplicationProvider
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSource
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Every source end to end against the real sites: lists, search, tags, details, chapters, pages, and EVERY cover of
 * the first page decoded through Coil + our OkHttp client like the grid does. Titles of 18+ sources are never printed.
 * Pick sources with -Dsources=id1,id2 (default: all of them).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class NewSourcesTest {
    private val pick = System.getProperty("sources").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private val problems = mutableListOf<String>()
    private fun log(s: String) = println("[src] $s")
    private fun bad(s: String) { problems += s; println("[src] PROBLEM $s") }

    private fun runOffMain(block: suspend () -> Unit) {
        var err: Throwable? = null
        val t = Thread { runCatching { runBlocking { block() } }.onFailure { err = it } }
        t.start()
        val until = System.currentTimeMillis() + 25 * 60_000
        while (t.isAlive && System.currentTimeMillis() < until) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        err?.let { throw it }
        println("[src] summary: ${problems.size} problems")
        problems.forEach { println("[src]   - $it") }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private val ctx get() = ApplicationProvider.getApplicationContext<App>()

    /** Decode every url through Coil (6 at a time, like a grid filling in); returns failures. */
    private suspend fun decode(urls: List<String>, w: Int, h: Int): List<String> = coroutineScope {
        val gate = Semaphore(6)
        urls.map { u ->
            async {
                gate.withPermit {
                    val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(u).size(w, h).build())
                    if (r is ErrorResult) "$u -> ${r.throwable.javaClass.simpleName} ${r.throwable.message?.take(80)}" else null
                }
            }
        }.mapNotNull { it.await() }
    }

    private fun head(client: okhttp3.OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535").build()).execute().use { r ->
            "${r.code} ${r.header("Content-Type")} ${r.body?.bytes()?.size}"
        }

    @Test fun manga() = runOffMain {
        for (s in Sources.all.filter { pick.isEmpty() || it.id in pick }) runCatching { manga(s) }.onFailure { bad("${s.id}: ${it.javaClass.simpleName} ${it.message}") }
    }

    @Test fun videos() = runOffMain {
        for (s in VideoSources.all.filter { pick.isEmpty() || it.id in pick }) runCatching { video(s) }.onFailure { bad("${s.id}: ${it.javaClass.simpleName} ${it.message}") }
    }

    private suspend fun manga(s: Source) {
        val pop = s.popular(1); val lat = s.latest(1)
        log("${s.id} popular ${pop.items.size} next=${pop.hasNext} · latest ${lat.items.size} next=${lat.hasNext}")
        if (pop.items.isEmpty()) bad("${s.id} popular empty")
        if (lat.items.isEmpty()) bad("${s.id} latest empty")
        val p2 = s.popular(2); log("${s.id} popular p2 ${p2.items.size} (same as p1: ${p2.items.map { it.id } == pop.items.map { it.id }})")
        if (p2.items.isEmpty() || p2.items.map { it.id } == pop.items.map { it.id }) bad("${s.id} page 2 broken")
        val leaked = (pop.items + lat.items).filter { Safety.blocked(it) }
        if (leaked.isNotEmpty()) log("${s.id} listing had ${leaked.size} blocked items (the app filters them)")
        val tags = s.tags(); log("${s.id} tags ${tags.size} minor-in-list=${tags.count { Safety.isMinorTag(it.label) }}")
        val sug = s.suggestTags("big").take(4); log("${s.id} suggest 'big': ${sug.map { "${it.label}(${it.count})" }}")
        val word = (pop.items.firstOrNull()?.title ?: "love").split(Regex("\\W+")).firstOrNull { it.length >= 4 } ?: "love"
        val txt = s.search(SearchQuery(text = word), 1); log("${s.id} text search: ${txt.items.size}")
        if (txt.items.isEmpty()) bad("${s.id} text search empty")
        val tag = (sug.firstOrNull() ?: tags.firstOrNull { !Safety.isMinorTag(it.label) })
        if (tag != null) {
            val tr = s.search(SearchQuery(include = listOf(tag)), 1); log("${s.id} tag search '${tag.label}': ${tr.items.size}")
            if (tr.items.isEmpty()) bad("${s.id} tag search empty")
        }
        for (sel in s.selects) for ((v, label) in sel.options.drop(1).take(2)) {
            val r = runCatching { s.search(SearchQuery(selects = mapOf(sel.key to v)), 1).items.size }.getOrElse { -1 }
            log("${s.id} select ${sel.key}=$label: $r"); if (r <= 0) bad("${s.id} select ${sel.key}=$label gave $r")
        }
        // every cover of the first popular + latest page
        val covers = (pop.items + lat.items).map { it.cover }.distinct()
        val t0 = System.currentTimeMillis()
        val failed = decode(covers, 300, 430)
        log("${s.id} covers ${covers.size - failed.size}/${covers.size} in ${System.currentTimeMillis() - t0} ms")
        failed.take(3).forEach { log("   $it") }
        if (failed.isNotEmpty()) bad("${s.id} ${failed.size} covers failed")
        if (covers.any { it.isBlank() }) bad("${s.id} empty cover urls")
        // two titles end to end
        for (ref in pop.items.filterNot { Safety.blocked(it) }.take(2)) walk(s, ref)
    }

    private suspend fun walk(s: Source, ref: MangaRef) {
        val d = s.details(ref)
        log("${s.id} details: cover=${d.ref.cover.isNotEmpty()} genres=${d.genres.size} groups=${d.tagGroups.size} authors=${d.authors.size}+${d.artists.size} status='${d.status}' desc=${d.description.length}")
        val dc = decode(listOf(d.ref.cover), 300, 430); if (dc.isNotEmpty()) bad("${s.id} details cover: ${dc.first()}")
        val ch = s.chapters(ref)
        log("${s.id} chapters ${ch.size} newest#=${ch.firstOrNull()?.number} oldest#=${ch.lastOrNull()?.number} dated=${ch.count { it.date > 0 }}")
        if (ch.isEmpty()) { bad("${s.id} no chapters"); return }
        if (s.hasChapters && ch.size > 2 && ch.first().number in 0f..<ch.last().number) bad("${s.id} chapters not newest first")
        val pages = s.pages(ref, ch.last())
        log("${s.id} pages ${pages.size}")
        if (pages.isEmpty()) { bad("${s.id} no pages"); return }
        val urls = listOf(pages.first(), pages[pages.size / 2], pages.last()).map { s.imageUrl(it) }
        val pf = decode(urls, 1080, 4096)
        log("${s.id} page images ${urls.size - pf.size}/${urls.size}"); pf.forEach { log("   $it") }
        if (pf.isNotEmpty()) bad("${s.id} ${pf.size} page images failed")
        val rel = runCatching { s.related(ref, d) }.getOrElse { emptyList() }; log("${s.id} related ${rel.size}")
    }

    private suspend fun video(s: VideoSource) {
        val lat = s.latest(1); val pop = s.popular(1)
        log("${s.id} latest ${lat.items.size} next=${lat.hasNext} · popular ${pop.items.size} next=${pop.hasNext}")
        if (lat.items.isEmpty()) bad("${s.id} latest empty")
        if (pop.items.isEmpty()) bad("${s.id} popular empty")
        val p2 = s.latest(2); if (p2.items.isEmpty() || p2.items.map { it.id } == lat.items.map { it.id }) bad("${s.id} page 2 broken")
        if ((lat.items + pop.items).any { VideoSafety.blocked(it) }) bad("${s.id} list let a blocked video through")
        val g = s.genres(); log("${s.id} genres ${g.size} minor=${g.count { VideoSafety.badTag(it.label) || VideoSafety.badTag(it.id) }}")
        val word = (lat.items.firstOrNull()?.title ?: "hentai").split(Regex("\\W+")).firstOrNull { it.length >= 4 } ?: "hentai"
        val txt = s.search(SearchQuery(text = word), 1); log("${s.id} text search: ${txt.items.size}")
        if (txt.items.isEmpty()) bad("${s.id} text search empty")
        g.firstOrNull { !VideoSafety.badTag(it.label) }?.let { t ->
            val r = s.search(SearchQuery(include = listOf(t)), 1); log("${s.id} genre '${t.label}': ${r.items.size}")
            if (r.items.isEmpty()) bad("${s.id} genre search empty")
        }
        val covers = (lat.items + pop.items).map { it.cover }.distinct()
        val failed = decode(covers, 540, 304)
        log("${s.id} covers ${covers.size - failed.size}/${covers.size}"); failed.take(3).forEach { log("   $it") }
        if (failed.isNotEmpty()) {
            // the card tries altCovers next: count only covers whose every address fails
            val all = (lat.items + pop.items).distinctBy { it.cover }.filter { r -> decode(listOf(r.cover) + r.altCovers, 540, 304).size == 1 + r.altCovers.size }
            if (all.isNotEmpty()) bad("${s.id} ${all.size} covers with no working address")
        }
        for (ref in lat.items.take(2)) {
            val d = s.details(ref)
            s.beforePlay()
            log("${s.id} details: kind=${d.stream.kind} subs=${d.stream.subs.size} eps=${d.episodes.size} tags=${d.tags.size}")
            val h = head(s.playerClient, d.stream.url); log("${s.id} stream head: $h")
            if (!(h.startsWith("200") || h.startsWith("206"))) bad("${s.id} stream answered $h")
            val ser = runCatching { s.series(ref).size }.getOrElse { -1 }; log("${s.id} series: $ser")
        }
    }
}
