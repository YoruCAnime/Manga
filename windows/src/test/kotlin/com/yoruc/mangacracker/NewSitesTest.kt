package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Test
import java.io.File

/**
 * Live: the sources named in -Psources=a,b from the first list to a page's picture / a video's first bytes, the way
 * the app uses them. Counts and yes / no only (no titles). A step that fails says so and the rest goes on.
 */
class NewSitesTest {
    private fun picture(url: String): Boolean =
        runCatching { runBlocking { org.jetbrains.skia.Image.makeFromEncoded(Http.bytes(url)).use { it.width > 0 } } }.getOrDefault(false)

    private suspend fun <T> step(id: String, what: String, block: suspend () -> T): T? =
        runCatching { block() }.onFailure { println("[new] $id $what: FAIL ${it::class.simpleName} ${it.message?.take(140)}") }.getOrNull()

    @Test fun whole() = runBlocking {
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        if (pick.isEmpty()) return@runBlocking
        val ctx = Context(File("build/newsites").apply { deleteRecursively(); mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        Repo.updateSettings { it.copy(ageConfirmed = it.ageConfirmed + pick) }

        Sources.all.filter { it.id in pick }.forEach { s ->
            val id = s.id
            val latest = step(id, "latest") { s.latest(1) }
            val latest2 = step(id, "latest page 2") { s.latest(2) }
            val pop = step(id, "popular") { s.popular(1) }
            val found = step(id, "search") { s.search(SearchQuery(text = "love"), 1) }
            println("[new] $id lists: latest ${latest?.items?.size} next ${latest?.hasNext} | page 2 ${latest2?.items?.size} new ${latest2?.items?.count { a -> latest?.items?.none { it.id == a.id } == true }} | popular ${pop?.items?.size} | search ${found?.items?.size}")
            val all = listOfNotNull(latest, latest2, pop, found).flatMap { it.items }
            println("[new] $id hidden in lists: ${all.count { Safety.blocked(it) }} of ${all.size}; blank titles ${all.count { it.title.isBlank() }}")
            val shown = Safety.filter(pop?.items ?: latest?.items.orEmpty())
            println("[new] $id covers: ${shown.take(6).count { picture(it.cover) }} of ${shown.take(6).size} load")
            val tags = step(id, "tags") { Safety.cleanTags(s.tags()) }.orEmpty()
            val sug = step(id, "suggest") { Safety.cleanTags(s.suggestTags("sch")) }.orEmpty()
            println("[new] $id tags ${tags.size}, suggestions for 'sch' ${sug.size}, selects ${s.selects.size}")
            (tags.firstOrNull { it.label.equals("romance", true) } ?: tags.firstOrNull() ?: sug.firstOrNull())?.let { t ->
                val r = step(id, "tag search") { s.search(SearchQuery(include = listOf(t)), 1) }
                println("[new] $id tag search: ${r?.items?.size} titles")
            }
            // a title with chapters to read here (the most popular ones can be official-only links)
            val ref = shown.take(5).firstOrNull { r -> !s.hasChapters || step(id, "chapters of one") { s.chapters(r) }.orEmpty().isNotEmpty() } ?: shown.firstOrNull() ?: return@forEach
            val d = step(id, "details") { s.details(ref) } ?: return@forEach
            println("[new] $id details: title ${d.ref.title.isNotBlank()} desc ${d.description.length} chars authors ${d.authors.size} artists ${d.artists.size} genres ${d.genres.size} groups ${d.tagGroups.size} pages ${d.pageCount} blocked ${Safety.blocked(d)}")
            (d.artists + d.authors).firstOrNull()?.let { a ->
                val q = if (s.authorsAreGroups && a in d.authors && a !in d.artists) s.groupQuery(a) else s.artistQuery(a)
                val r = step(id, "artist search") { s.search(q, 1) }
                println("[new] $id artist search: ${r?.items?.size} titles, this one in it ${r?.items?.any { it.id == ref.id }}")
            }
            val rel = step(id, "related") { s.related(ref, d) }
            println("[new] $id related: ${rel?.size}")
            val chapters = step(id, "chapters") { s.chapters(ref) } ?: return@forEach
            val newest = step(id, "latest chapters") { s.latestChapters(ref) }
            println("[new] $id chapters: ${chapters.size} (update check ${newest?.size}), named ${chapters.count { it.name.isNotBlank() }}, dated ${chapters.count { it.date > 0 }}")
            val ch = chapters.lastOrNull() ?: return@forEach
            val pages = step(id, "pages") { s.pages(ref, ch) } ?: return@forEach
            val urls = pages.take(2).mapNotNull { p -> step(id, "page address") { p.url.ifEmpty { s.imageUrl(p) } } }
            println("[new] $id pages: ${pages.size}, first pictures load ${urls.count { picture(it) }} of ${urls.size}")
        }

        VideoSources.all.filter { it.id in pick }.forEach { s ->
            val id = s.id
            val latest = step(id, "latest") { s.latest(1) }
            val latest2 = step(id, "latest page 2") { s.latest(2) }
            val pop = step(id, "popular") { s.popular(1) }
            val found = step(id, "search") { s.search(SearchQuery(text = "love"), 1) }
            println("[new] $id lists: latest ${latest?.items?.size} next ${latest?.hasNext} | page 2 ${latest2?.items?.size} new ${latest2?.items?.count { a -> latest?.items?.none { it.id == a.id } == true }} | popular ${pop?.items?.size} shows ${pop?.items?.count { it.show }} | search ${found?.items?.size}")
            val all = listOfNotNull(latest, latest2, pop, found).flatMap { it.items }
            println("[new] $id hidden in lists: ${all.count { VideoSafety.blocked(it) }} of ${all.size}")
            val shown = latest?.items.orEmpty().filterNot { VideoSafety.blocked(it) }
            println("[new] $id covers: ${shown.take(6).count { v -> (listOf(v.cover) + v.altCovers).any { it.isNotBlank() && picture(it) } }} of ${shown.take(6).size} load")
            val genres = step(id, "genres") { s.genres() }.orEmpty().filterNot { VideoSafety.badTag(it.label) }
            genres.firstOrNull()?.let { g ->
                val r = step(id, "genre search") { s.search(SearchQuery(include = listOf(g)), 1) }
                println("[new] $id genres ${genres.size}, genre search: ${r?.items?.size}")
            }
            var v = shown.firstOrNull() ?: return@forEach
            if (v.show) v = step(id, "show episodes") { s.series(v) }?.also { println("[new] $id show: ${it.size} episodes, langs ${it.map { e -> e.lang }.distinct()}") }?.firstOrNull() ?: return@forEach
            val d = step(id, "details") { s.details(v) } ?: return@forEach
            println("[new] $id details: kind ${d.stream.kind} subs ${d.stream.subs.size} episodes ${d.episodes.size} tags ${d.tags.size} series ${d.ref.series.isNotBlank()} episode '${d.ref.episode}'")
            step(id, "before play") { s.beforePlay() }
            step(id, "stream") {
                s.playerClient.newCall(Request.Builder().url(d.stream.url).header("Range", "bytes=0-2047").build()).execute().use { r ->
                    val b = r.body?.bytes() ?: ByteArray(0)
                    val head = String(b.take(7).toByteArray(), Charsets.ISO_8859_1)
                    println("[new] $id stream: HTTP ${r.code} ${r.header("Content-Type")} ${b.size} bytes, playlist ${head.startsWith("#EXTM3U")}, mp4 ${String(b.drop(4).take(4).toByteArray(), Charsets.ISO_8859_1) == "ftyp"}")
                }
            }
        }
    }
}
