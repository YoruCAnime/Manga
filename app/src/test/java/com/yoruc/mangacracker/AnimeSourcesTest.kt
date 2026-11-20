package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.video.VideoSource
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Live checks of the anime sites: latest, popular, search, a show's episodes in sub and dub, and each stream's best
 * quality (HLS: the master playlist's heights; MP4: the first 64 KB), never a whole video.
 * Run: python _dev_tools\run_tests.py AnimeSourcesTest   (-Psources=animeheaven,... to pick sites)
 */
class AnimeSourcesTest {
    companion object {
        val ANIME = listOf("animeheaven", "anikoto", "reanime")
        @BeforeClass @JvmStatic fun setup() {
            Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http"))
        }
    }

    private fun log(s: String) = println("[anime] $s")

    private fun text(client: OkHttpClient, url: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code to (it.body?.string() ?: "") }

    private fun head(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535").build()).execute().use { r ->
            "${r.code} ${r.header("Content-Type")} ${r.header("Content-Range")} ${r.body?.bytes()?.size}"
        }

    private fun resolve(base: String, u: String) = java.net.URI(base).resolve(u.replace(" ", "%20")).toString()

    /** (height, url) of each variant of a master playlist */
    private fun variants(master: String, base: String): List<Pair<Int, String>> {
        val l = master.lines()
        return l.indices.filter { l[it].startsWith("#EXT-X-STREAM-INF") }.mapNotNull { i ->
            val h = Regex("RESOLUTION=\\d+x(\\d+)").find(l[i])?.groupValues?.get(1)?.toInt() ?: 0
            l.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { h to resolve(base, it.trim()) }
        }
    }

    private suspend fun stream(src: VideoSource, label: String, d: com.yoruc.mangacracker.video.VideoDetails) {
        val client = src.playerClient
        log("${src.id} $label: ep=${d.ref.episode} lang=${d.ref.lang} kind=${d.stream.kind} subs=${d.stream.subs.map { it.lang }} episodes=${d.episodes.size}")
        if (d.stream.kind == "hls") {
            val (code, master) = text(client, d.stream.url)
            val vs = variants(master, d.stream.url)
            log("${src.id} $label master $code heights=${vs.map { it.first }}")
            assertTrue("${src.id} $label master $code", code == 200)
            val best = vs.maxByOrNull { it.first }?.second ?: d.stream.url
            val (vc, vText) = text(client, best)
            val seg = vText.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.let { resolve(best, it.trim()) }
            log("${src.id} $label variant $vc, first piece: ${seg?.let { head(client, it) }}")
            assertTrue("${src.id} $label variant", vc == 200 && seg != null)
        } else {
            val h = head(client, d.stream.url)
            log("${src.id} $label head: $h")
            assertTrue(h.startsWith("206") || h.startsWith("200"))
        }
        d.stream.subs.firstOrNull()?.let { s -> log("${src.id} $label subtitle ${s.lang}: ${text(client, s.url).let { "${it.first} ${it.second.take(6)}" }}") }
    }

    private suspend fun check(src: VideoSource) {
        val latest = src.latest(1)
        log("${src.id} latest: ${latest.items.size} items, next=${latest.hasNext}, langs=${latest.items.groupingBy { it.lang }.eachCount()}, covers=${latest.items.count { it.cover.startsWith("http") }}")
        assertTrue("${src.id} latest empty", latest.items.isNotEmpty())
        for (c in latest.items.take(3).map { it.cover }) log("${src.id} cover: ${head(Http.imageClient, c)}")
        val pop = src.popular(1)
        log("${src.id} popular: ${pop.items.size} items, next=${pop.hasNext}, shows=${pop.items.count { it.show }}")
        assertTrue("${src.id} popular empty", pop.items.isNotEmpty())
        val genres = src.genres()
        genres.firstOrNull { it.label == "Fantasy" }?.let { g ->
            log("${src.id} genre Fantasy: ${src.search(SearchQuery(include = listOf(g)), 1).items.size} items (${genres.size} genres)")
        }
        val found = src.search(SearchQuery(text = "frieren"), 1)
        log("${src.id} search frieren: ${found.items.size} items: ${found.items.take(4).map { "${it.title} [${it.id}]" }}")
        assertTrue("${src.id} search empty", found.items.isNotEmpty())
        val show = found.items.first()
        val series = src.series(show)
        val langs = series.groupingBy { it.lang }.eachCount()
        log("${src.id} series: ${series.size} videos, ${series.map { it.episode }.distinct().size} episodes, langs=$langs")
        assertTrue("${src.id} series empty", series.isNotEmpty())
        for (lang in listOf("SUB", "DUB")) {
            val ep = series.firstOrNull { it.lang == lang } ?: continue
            stream(src, lang, src.details(ep))
        }
        val first = latest.items.first()
        stream(src, "latest", src.details(first))
    }

    @Test fun anime() = runBlocking {
        val pick = System.getProperty("sources")?.split(',')?.filter { it.isNotBlank() } ?: ANIME
        val fails = mutableListOf<String>()
        for (id in pick) {
            val src = VideoSources.byId(id)
            if (src == null) { log("$id: not a source"); fails += id; continue }
            runCatching { check(src) }.onFailure { log("$id FAILED: $it"); fails += id }
        }
        assertTrue("failed: $fails", fails.isEmpty())
    }
}
