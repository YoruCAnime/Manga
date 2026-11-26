package com.yoruc.mangacracker

import android.net.Uri
import androidx.media3.datasource.DataSpec
import androidx.media3.exoplayer.hls.playlist.HlsMediaPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import androidx.test.core.app.ApplicationProvider
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.DownloadRunner
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Source
import com.yoruc.mangacracker.core.SourceCheck
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.OtherSitesVM
import com.yoruc.mangacracker.ui.ReaderItem
import com.yoruc.mangacracker.ui.ReaderVM
import com.yoruc.mangacracker.ui.SiteState
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.io.RandomAccessFile
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LIVE (gently): chapters downloaded through the real queue from a few sites, then read back with the network
 * blocked (ReaderVM + Coil from disk); "Other sites" for a real title (the match table is printed); a short MP4 and
 * an HLS video downloaded (capped to a few MB) and checked as playable data with no network.
 * -Psources=nhentai,atsu,manhwa18cc picks the manga sites; titles of 18+ sites are never printed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class DownloadLiveTest {
    private val ctx get() = ApplicationProvider.getApplicationContext<android.app.Application>()

    @Before fun setup() {
        Downloads.autoStart = false; Downloads.queue.cancelAll()
        SourceCheck.unavailable = mapOf("comix" to "Can't test here: comix needs a real WebView (works on the phone)")
    }

    @After fun clean() {
        Http.blockNetwork = false
        SourceCheck.unavailable = emptyMap()
        VideoDownloads.testByteCap = null
        Downloads.queue.cancelAll()
        Downloads.titles.value.keys.toList().forEach { Downloads.deleteTitle(it) }
        VideoDownloads.videos.value.keys.toList().forEach { VideoDownloads.delete(it) }
        Downloads.root.deleteRecursively()
    }

    /** Work off the main thread while the main looper keeps going (ViewModels, Coil). */
    private fun <T> off(block: suspend () -> T): T {
        var r: Result<T>? = null
        val th = Thread { r = runCatching { runBlocking { block() } } }
        th.start()
        while (th.isAlive) { Thread.sleep(30); ShadowLooper.idleMainLooper() }
        return r!!.getOrThrow()
    }

    private fun waitMain(ms: Long, done: () -> Boolean) {
        val until = System.currentTimeMillis() + ms
        while (!done() && System.currentTimeMillis() < until) { Thread.sleep(40); ShadowLooper.idleMainLooper() }
    }

    private fun pagesOf(subtitle: String) = Regex("(\\d+) pages").find(subtitle)?.groupValues?.get(1)?.toIntOrNull() ?: 999

    /** A short, allowed chapter of [s]: the smallest gallery on the first page, or a title's first chapter. */
    private suspend fun pick(s: Source): Pair<MangaRef, Chapter> {
        val items = Safety.filter(s.latest(1).items)
        if (!s.hasChapters) {
            val r = items.filter { pagesOf(it.subtitle) in 3..400 }.minByOrNull { pagesOf(it.subtitle) } ?: items.first()
            val d = s.details(r)
            check(!Safety.blocked(d)) { "picked a hidden title" }
            Repo.detailsCache[r.key] = d
            return r to s.chapters(r).first()
        }
        for (r in items.take(4)) {
            val d = s.details(r)
            if (Safety.blocked(d)) continue
            Repo.detailsCache[r.key] = d
            val chs = s.chapters(r)
            if (chs.isEmpty()) continue
            Repo.chaptersCache[r.key] = chs
            return r to chs.last()
        }
        error("no title to try on ${s.id}")
    }

    @Test fun chaptersDownloadAndReadOffline() {
        val ids = System.getProperty("sources").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }
            .ifEmpty { listOf("nhentai", "atsu", "manhwa18cc") }
        val srcs = ids.mapNotNull { Sources.byId(it) }
        val picked = srcs.map { s -> s to off { pick(s) } }
        picked.forEach { (s, p) -> println("[pick] ${s.id}: chapter '${if (s.nsfw) "…" else p.second.name}'") }
        picked.forEach { (_, p) -> Downloads.enqueueChapters(null, p.first, listOf(p.second), Repo.detailsCache[p.first.key]) }
        assertEquals(picked.size, Downloads.queue.tasks.value.size)

        // the real queue (two at a time, one per site)
        val t0 = System.currentTimeMillis()
        off { withTimeout(8 * 60_000) { DownloadRunner.run(ctx) } }
        val left = Downloads.queue.tasks.value
        assertTrue("queue not empty: ${left.map { it.site + " " + it.state + " " + it.error }}", left.isEmpty())
        println("[queue] ${picked.size} chapters in ${(System.currentTimeMillis() - t0) / 1000} s, ${Downloads.size(Downloads.usedBytes())}")

        // no network from here on
        Http.blockNetwork = true
        for ((s, p) in picked) {
            val (ref, ch) = p
            val saved = Downloads.titles.value[ref.key]!!.saved[ch.id]!!
            // the reader: a fresh view model, chapter list from the download (nothing cached in memory)
            Repo.chaptersCache.remove(ref.key)
            val vm = ReaderVM(s, ref, ch.id)
            waitMain(15_000) { vm.items.isNotEmpty() || vm.error != null }
            assertNull("${s.id}: reader error ${vm.error}", vm.error)
            val pages = vm.items.filterIsInstance<ReaderItem.PageItem>()
            assertEquals("${s.id}: every page in the reader", saved.files.size, pages.size)
            assertTrue(pages.all { it.page.url.startsWith(Downloads.LOCAL) })
            // decode every page through Coil from disk (caches off, network blocked)
            var px = 0L
            var firstError: String? = null
            val ok = off {
                pages.count { p ->
                    val r = ctx.imageLoader.execute(
                        ImageRequest.Builder(ctx).data(Downloads.model(p.page.url)).size(720, 4096)
                            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED).build()
                    )
                    if (r is coil.request.ErrorResult && firstError == null) firstError = "${File(p.page.url.removePrefix(Downloads.LOCAL)).name}: ${r.throwable}"
                    (r as? SuccessResult)?.drawable?.let { px += it.intrinsicWidth.toLong() * it.intrinsicHeight; true } ?: false
                }
            }
            firstError?.let { println("[offline] ${s.id}: first decode error $it") }
            assertEquals("${s.id}: pages decoded from disk", pages.size, ok)
            println("[offline] ${s.id}: ${pages.size} pages read back with no network · ${Downloads.size(saved.bytes)} · ${px / 1_000_000} Mpx")
        }
    }

    @Test fun otherSitesMatchTable() {
        // an adult manhwa that is on several of the manhwa sites: the first popular one on manhwa18cc
        val s = Sources.byId(System.getProperty("sources").orEmpty().ifEmpty { "manhwa18cc" }.split(',').first())!!
        val ref = off { Safety.filter(s.popular(1).items).first() }
        Repo.remember(ref)
        off { runCatching { Repo.detailsCache[ref.key] = s.details(ref) } }
        val t0 = System.currentTimeMillis()
        val vm = OtherSitesVM(ref.key)
        waitMain(150_000) { vm.sites.values.none { it.state == SiteState.SEARCHING || (it.state == SiteState.DONE && it.matches.take(2).any { m -> m.score >= 0.6f && m.chapters == null && m.chapterNote.isEmpty() }) } }
        println("[other] ${s.name} title searched on ${vm.sites.size} sites in ${(System.currentTimeMillis() - t0) / 1000} s (names: ${vm.names.size})")
        for (r in vm.ordered()) {
            val best = r.matches.firstOrNull()
            val ch = best?.chapters
            println(
                "[other] %-14s %-9s %s".format(r.src.name, r.state.name,
                    when {
                        best == null -> r.note.take(90)
                        else -> "best %3d%%".format((best.score * 100).toInt()) +
                            (if (ch != null) " · ${ch.size} ch · latest '${(ch.maxByOrNull { com.yoruc.mangacracker.core.SourceMatch.number(it) ?: -1f } ?: ch.first()).name.take(30)}'" else " · ${best.chapterNote.ifEmpty { "chapters not fetched" }}") +
                            " · ${r.matches.size} candidate(s)" + (if (!r.src.nsfw) " · '${best.ref.title.take(40)}'" else "")
                    })
            )
        }
        assertTrue("every site answered", vm.sites.values.none { it.state == SiteState.SEARCHING })
    }

    // ---------- videos ----------

    private fun boxAt(f: RandomAccessFile, at: Long): Pair<Long, String>? {
        if (at + 8 > f.length()) return null
        f.seek(at)
        val b = ByteArray(16); f.readFully(b, 0, 8)
        var size = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
        val type = String(b, 4, 4, Charsets.ISO_8859_1)
        if (size == 1L) { f.readFully(b, 8, 8); size = (8..15).fold(0L) { a, i -> (a shl 8) or (b[i].toLong() and 0xFF) } }
        return size to type
    }

    /** Top-level boxes of the (maybe capped) MP4; past the end of the file, box headers come by Range from [url]. */
    private fun mp4Boxes(file: File, url: String, client: okhttp3.OkHttpClient): List<String> {
        val out = ArrayList<String>()
        RandomAccessFile(file, "r").use { f ->
            var at = 0L
            repeat(40) {
                val h = boxAt(f, at) ?: run {
                    val r = client.newCall(Http.request(url, mapOf("Range" to "bytes=$at-${at + 15}"))).execute()
                    val b = r.use { it.body!!.bytes() }
                    if (r.code != 206 || b.size < 8) return out
                    var size = ((b[0].toLong() and 0xFF) shl 24) or ((b[1].toLong() and 0xFF) shl 16) or ((b[2].toLong() and 0xFF) shl 8) or (b[3].toLong() and 0xFF)
                    if (size == 1L && b.size >= 16) size = (8..15).fold(0L) { a, i -> (a shl 8) or (b[i].toLong() and 0xFF) }
                    size to String(b, 4, 4, Charsets.ISO_8859_1)
                }
                out += h.second
                if (h.first <= 0) return out
                at += h.first
            }
        }
        return out
    }

    private fun shortest(list: List<VideoRef>): VideoRef {
        fun secs(d: String) = d.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { a, x -> a * 60 + x }.takeIf { it > 0 } ?: 99_999
        return list.minBy { secs(it.duration) }
    }

    @Test fun videosDownloadAndCheckOffline() {
        val wanted = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }
            .ifEmpty { listOf("sfmcompile", "porcore", "muchohentai") }
        for (id in wanted) {
            val s = VideoSources.byId(id) ?: continue
            val list = off { s.latest(1).items.filterNot { VideoSafety.blocked(it) } }
            // a short one, whose details pass the filter
            val v = off {
                list.sortedBy { r -> r.duration.split(':').mapNotNull { it.trim().toIntOrNull() }.fold(0) { a, x -> a * 60 + x }.takeIf { it > 0 } ?: 99_999 }
                    .take(8).firstOrNull { r ->
                        runCatching {
                            val dd = s.details(r).also { VideoSafety.check(it) }
                            s.beforePlay()
                            // a stream that answers (a few old posts point at a removed library)
                            s.playerClient.newCall(Http.request(dd.stream.url, mapOf("Range" to "bytes=0-1023"))).execute().use { it.isSuccessful }
                        }.getOrDefault(false)
                    }
            } ?: shortest(list)
            val d = off { s.details(v) }
            println("[video] ${s.name}: ${d.stream.kind}, ${v.duration.ifEmpty { "?" }} long")
            // a few MB are enough to check the data (a long video is stopped there)
            VideoDownloads.testByteCap = 24L * 1024 * 1024
            Downloads.queue.cancelAll()
            assertTrue(Downloads.enqueueVideo(null, v).startsWith("Downloading"))
            off { withTimeout(6 * 60_000) { DownloadRunner.run(ctx) } }
            val left = Downloads.queue.tasks.value
            assertTrue("${s.id}: ${left.map { it.state.name + " " + it.error }}", left.isEmpty())
            val o = VideoDownloads.offline(v.key)
            assertNotNull("${s.id}: downloaded", o)
            o!!
            val stream = VideoDownloads.stream(o)
            assertEquals(v.key, stream.local)
            if (o.saved.kind != "hls") {
                val f = File(o.dir, o.saved.file)
                val boxes = mp4Boxes(f, o.saved.streamUrl, s.playerClient)
                println("[video] ${s.name}: ${Downloads.size(f.length())} on disk, boxes ${boxes.joinToString(",")}")
                assertEquals("ftyp", boxes.firstOrNull())
                assertTrue("${s.id}: has a moov box", "moov" in boxes)
                // offline: the player's data source reads the file with no network
                Http.blockNetwork = true
                val ds = VideoDownloads.dataSourceFactory(ctx, s, stream).createDataSource()
                ds.open(DataSpec(Uri.parse(stream.url)))
                val head = ByteArray(12); var n = 0
                while (n < 12) { val k = ds.read(head, n, 12 - n); if (k < 0) break; n += k }
                ds.close()
                assertEquals("ftyp", String(head, 4, 4, Charsets.ISO_8859_1))
                Http.blockNetwork = false
            } else checkHls(s.name, o.saved.streamUrl, v.key, o.saved.height)
            VideoDownloads.delete(v.key)
            assertNull(VideoDownloads.offline(v.key))
        }
    }

    private fun readAll(ds: androidx.media3.datasource.DataSource, uri: String, max: Int = 64 * 1024 * 1024): ByteArray {
        ds.open(DataSpec(Uri.parse(uri)))
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        try {
            while (out.size() < max) { val k = ds.read(buf, 0, buf.size); if (k < 0) break; out.write(buf, 0, k) }
        } finally { ds.close() }
        return out.toByteArray()
    }

    /** With the network blocked: playlists, the key and the first segment come from the cache, and the segment decrypts to video. */
    private fun checkHls(name: String, master: String, key: String, height: Int) {
        Http.blockNetwork = true
        val ds = VideoDownloads.offlineReader(key)!!
        val parser = HlsPlaylistParser()
        var url = master
        var pl = parser.parse(Uri.parse(url), readAll(ds, url).inputStream())
        if (pl is HlsMultivariantPlaylist) {
            val saved = VideoDownloads.videos.value[key]!!.saved
            val vi = saved.streamKeys.firstOrNull { it[0] == HlsMultivariantPlaylist.GROUP_INDEX_VARIANT }?.get(1) ?: 0
            url = pl.variants[vi].url.toString()
            pl = parser.parse(Uri.parse(url), readAll(ds, url).inputStream())
        }
        val media = pl as HlsMediaPlaylist
        val seg = media.segments.first()
        val segUrl = androidx.media3.common.util.UriUtil.resolve(media.baseUri, seg.url)
        var data = readAll(ds, segUrl)
        var how = "plain"
        val keyUri = seg.fullSegmentEncryptionKeyUri
        if (keyUri != null) {
            val k = readAll(ds, androidx.media3.common.util.UriUtil.resolve(media.baseUri, keyUri))
            assertEquals("AES-128 key", 16, k.size)
            val iv = seg.encryptionIV?.removePrefix("0x")?.removePrefix("0X")?.padStart(32, '0')?.chunked(2)?.map { it.toInt(16).toByte() }?.toByteArray()
                ?: ByteArray(16).also { b -> var n = media.mediaSequence; for (i in 15 downTo 8) { b[i] = (n and 0xFF).toByte(); n = n shr 8 } }
            data = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(iv)) }.doFinal(data)
            how = "AES-128 decrypted"
        }
        val ts = data.size > 376 && data[0] == 0x47.toByte() && data[188] == 0x47.toByte()
        val fmp4 = data.size > 8 && String(data, 4, 4, Charsets.ISO_8859_1) in setOf("styp", "moof", "ftyp", "sidx")
        val init = seg.initializationSegment?.let { readAll(ds, androidx.media3.common.util.UriUtil.resolve(media.baseUri, it.url)) }
        println("[video] $name: HLS ${if (height > 0) "${height}p" else "single variant"}, ${media.segments.size} segments, first ${Downloads.size(data.size.toLong())} $how -> ${if (ts) "MPEG-TS" else if (fmp4) "fMP4" else "??"}" +
            (init?.let { " (init ${String(it, 4, 4, Charsets.ISO_8859_1)})" } ?: "") +
            " · cache ${Downloads.size(VideoDownloads.videos.value[key]!!.bytes)}")
        assertTrue("$name: first segment is video data", ts || fmp4)
        Http.blockNetwork = false
    }
}
