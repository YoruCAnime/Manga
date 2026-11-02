package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.video.MuchoHentaiSource
import com.yoruc.mangacracker.video.VideoSafety
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
 * Live checks of the video sites: lists, search, a genre, details (stream + episodes) and the first 64 KB of the
 * stream (never a whole video). Only counts / codes are printed, never titles.
 * Run: python _dev_tools\run_tests.py VideoSourcesTest
 */
class VideoSourcesTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() {
            Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http"))
        }
    }

    private fun log(s: String) = println("[video] $s")

    private fun head(client: OkHttpClient, url: String): String =
        client.newCall(Request.Builder().url(url).header("Range", "bytes=0-65535").build()).execute().use { r ->
            val b = r.body!!.source().apply { request(16) }.buffer.snapshot(minOf(16L, r.body!!.source().buffer.size).toInt())
            "${r.code} ${r.header("Content-Type")} first=${b.hex().take(16)}"
        }

    private fun text(client: OkHttpClient, url: String): Pair<Int, String> =
        client.newCall(Request.Builder().url(url).build()).execute().use { it.code to (it.body?.string() ?: "") }

    private suspend fun check(src: VideoSource) {
        val latest = src.latest(1)
        log("${src.id} latest: ${latest.items.size} items, next=${latest.hasNext}, covers=${latest.items.count { it.cover.startsWith("http") }}, blocked=${latest.items.count { VideoSafety.blocked(it) }}")
        assertTrue("${src.id} latest empty", latest.items.isNotEmpty())
        val cover = latest.items.first().cover
        log("${src.id} cover ${cover.substringAfterLast('.')}: ${head(Http.imageClient, cover)}")
        val pop = src.popular(1)
        log("${src.id} popular: ${pop.items.size} items, views on first=${pop.items.firstOrNull()?.views}")
        assertTrue("${src.id} popular empty", pop.items.isNotEmpty())
        val genres = src.genres()
        val g = genres.first { it.label.equals("Vanilla", true) }
        val byGenre = src.search(SearchQuery(include = listOf(g)), 1)
        log("${src.id} genre ${g.label}: ${byGenre.items.size} items (${genres.size} genres)")
        assertTrue("${src.id} genre empty", byGenre.items.isNotEmpty())
        val text = src.search(SearchQuery(text = "sister"), 1)
        log("${src.id} text search: ${text.items.size} items")

        val first = (latest.items + pop.items).first()
        val d = src.details(first)
        log("${src.id} details: kind=${d.stream.kind} subs=${d.stream.subs.size} episodes=${d.episodes.size} tags=${d.tags.size} lang=${d.ref.lang} ep=${d.ref.episode}")
        src.beforePlay()
        val client = src.playerClient
        if (d.stream.kind == "hls") {
            val (code, master) = text(client, d.stream.url)
            val variant = master.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
            log("${src.id} master $code, variants=${master.lines().count { it.startsWith("#EXT-X-STREAM-INF") }}")
            assertTrue(code == 200 && variant != null)
            val vUrl = d.stream.url.substringBeforeLast('/') + "/" + variant
            val (vc, vText) = text(client, vUrl)
            val key = Regex("URI=\"([^\"]+)\"").find(vText.lines().first { it.startsWith("#EXT-X-KEY") })!!.groupValues[1]
            val keyBytes = client.newCall(Request.Builder().url(key).build()).execute().use { it.code to (it.body?.bytes()?.size ?: 0) }
            log("${src.id} variant $vc, key ${keyBytes.first} (${keyBytes.second} bytes), unlock cookies=${MuchoHentaiSource.lastAuthCookies.size}, " +
                "sent to key host=${com.yoruc.mangacracker.core.WebCookieJar.loadForRequest(okhttp3.HttpUrl.Builder().scheme("https").host("muchohentai.com").build()).size}")
            assertTrue("key not unlocked", keyBytes.first == 200 && keyBytes.second == 16)
        } else {
            val h = head(client, d.stream.url)
            log("${src.id} stream head: $h")
            assertTrue(h.startsWith("206") || h.startsWith("200"))
        }
        d.stream.subs.forEach { s -> log("${src.id} subs ${s.lang}: ${text(client, s.url).let { "${it.first} webvtt=${it.second.startsWith("WEBVTT") || "WEBVTT" in it.second.take(20)}" }}") }
    }

    @Test fun hentaigasm() = runBlocking {
        for (ua in listOf(null, "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36")) {
            val req = Request.Builder().url("https://hentaigasm.com/wp-json/wp/v2/posts?per_page=2&categories=1475&_fields=id,content")
                .apply { if (ua != null) header("User-Agent", ua) }.build()
            val (code, body) = OkHttpClient().newCall(req).execute().use { it.code to it.body!!.string() }
            log("hentaigasm raw ua=${ua?.take(24) ?: "okhttp"} $code len=${body.length} poster=${"posterImage" in body}")
        }
        check(VideoSources.byId("hentaigasm")!!)
    }
    @Test fun oppaistream() = runBlocking { check(VideoSources.byId("oppaistream")!!) }
    @Test fun muchohentai() = runBlocking { check(MuchoHentaiSource) }
}
