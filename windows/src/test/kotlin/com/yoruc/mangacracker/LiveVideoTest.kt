package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Live: the live-action video sources (LIVE_IDS=a,b or all of the group), counts and answers only. -Psources=livevid */
class LiveVideoTest {
    private fun status(url: String, referer: String): Int = runCatching {
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "Mozilla/5.0"); setRequestProperty("Referer", referer)
            connectTimeout = 15_000; readTimeout = 15_000
            responseCode.also { disconnect() }
        }
    }.getOrDefault(-1)

    @Test fun sources() = runBlocking {
        if (System.getProperty("sources") != "livevid") return@runBlocking
        val ctx = Context(File("build/livevid").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val ids = System.getenv("LIVE_IDS")?.split(',')?.filter { it.isNotBlank() }
            ?: VideoSources.all.filter { VideoSources.category(it) == "Live action" }.map { it.id }
        var bad = 0
        for (id in ids) {
            val s = VideoSources.byId(id) ?: error("no source $id")
            fun line(what: String, r: Result<*>) = println("[live] $id $what: " + r.fold({ it.toString() }, { "FAIL ${it.javaClass.simpleName}: ${it.message?.take(160)}" }))
            val latest = runCatching { s.latest(1) }
            line("latest", latest.map { "${it.items.size} videos, next ${it.hasNext}, first '${it.items.firstOrNull()?.title?.take(40)}' ${it.items.firstOrNull()?.duration}" })
            val l2 = runCatching { s.latest(2) }
            line("latest page 2", l2.map { "${it.items.size} videos, same first as page 1: ${it.items.firstOrNull()?.id == latest.getOrNull()?.items?.firstOrNull()?.id}" })
            val pop = runCatching { s.popular(1) }
            line("popular", pop.map { "${it.items.size} videos" })
            val p2 = runCatching { s.popular(2) }
            line("popular page 2", p2.map { "${it.items.size} videos" })
            val genres = runCatching { s.genres() }
            line("genres", genres.map { "${it.size} (${it.take(4).joinToString { g -> g.label }})" })
            val search = runCatching { s.search(SearchQuery(text = "glasses"), 1) }
            line("search 'glasses'", search.map { "${it.items.size} videos, next ${it.hasNext}" })
            val search2 = runCatching { s.search(SearchQuery(text = "glasses"), 2) }
            line("search page 2", search2.map { "${it.items.size} videos" })
            val g = genres.getOrNull()?.firstOrNull()
            if (g != null) line("genre '${g.label}'", runCatching { s.search(SearchQuery(include = listOf(g)), 1) }.map { "${it.items.size} videos" })
            line("tag 'blonde'", runCatching { s.search(SearchQuery(include = listOf(Tag("blonde", "blonde"))), 1) }.map { "${it.items.size} videos" })
            val items = latest.getOrNull()?.items.orEmpty()
            val covers = items.take(8).count { status(it.cover, s.baseUrl + "/") == 200 }
            println("[live] $id covers answering $covers of ${minOf(8, items.size)}")
            items.firstOrNull()?.let { v ->
                val d = runCatching { s.details(v) }
                line("video page ${v.url}", d.map { "stream ${it.stream.kind} ${it.stream.url.take(60)}..., tags ${it.tags.size}, related ${it.episodes.size - 1}, by '${it.ref.series}'" })
                d.getOrNull()?.let { dd -> println("[live] $id stream answers ${status(dd.stream.url, v.url)}") }
            }
            if (latest.getOrNull()?.items.isNullOrEmpty() || search.getOrNull()?.items.isNullOrEmpty()) bad++
            delay(1500)
        }
        assertTrue("every source lists and searches", bad == 0)
    }
}
