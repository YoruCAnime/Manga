package com.yoruc.mangacracker

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.DlState
import com.yoruc.mangacracker.core.DlTask
import com.yoruc.mangacracker.core.DownloadQueue
import com.yoruc.mangacracker.core.DownloadRunner
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Settings
import com.yoruc.mangacracker.ui.chaptersToDownload
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoRef
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File

/** Downloads with no network: the queue's rules, the folders on disk, the reader's lookup, sizes, deleting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DownloadLogicTest {
    private var clock = 1_000_000L
    private fun task(id: String, site: String = "atsu") =
        DlTask(id, manga = MangaRef(site, "t-$id", "Title $id"), chapter = Chapter("c-$id", "Chapter $id", 1f))

    @After fun clean() { Downloads.freeOverride = null; Downloads.autoStart = true }

    @Test fun queueRules() {
        val q = DownloadQueue { clock }
        var saves = 0
        q.onChange = { saves++ }
        assertEquals(3, q.add(listOf(task("a"), task("b"), task("c", "nhentai"))))
        assertEquals("no doubles", 0, q.add(listOf(task("a"))))
        // FIFO, one per site at a time
        val a = q.take(emptySet())!!
        assertEquals("a", a.id); assertEquals(DlState.RUNNING, q["a"]!!.state)
        assertEquals("b waits: atsu is busy", "c", q.take(setOf("atsu"))!!.id)
        assertNull(q.take(setOf("atsu", "nhentai")))
        // progress, then a failure: back in the queue after 15 s, then 1 min, 4 min, then failed for good
        q.progress("a", 3, 10, 1234)
        assertEquals(3, q["a"]!!.done)
        q.failed("a", "HTTP 503")
        assertEquals(DlState.QUEUED, q["a"]!!.state)
        assertEquals(clock + 15_000, q["a"]!!.retryAt)
        assertEquals(clock + 15_000, q.nextRetry())
        assertEquals("not due yet: b goes first", "b", q.take(emptySet())!!.id)
        clock += 15_000
        assertEquals("a", q.take(setOf("x"))!!.id)
        q.failed("a", "HTTP 503"); assertEquals(clock + 60_000, q["a"]!!.retryAt)
        clock += 60_000; q.take(emptySet()); q.failed("a", "HTTP 503"); assertEquals(clock + 240_000, q["a"]!!.retryAt)
        clock += 240_000; q.take(emptySet()); q.failed("a", "HTTP 503")
        assertEquals(DlState.FAILED, q["a"]!!.state); assertEquals(4, q["a"]!!.attempts)
        // retry = a fresh start
        q.resume("a"); assertEquals(DlState.QUEUED, q["a"]!!.state); assertEquals(0, q["a"]!!.attempts)
        // permanent errors don't retry
        assertEquals("a", q.take(emptySet())!!.id)
        q.failed("a", "This title is hidden", permanent = true); assertEquals(DlState.FAILED, q["a"]!!.state)
        // pause / resume / cancel; a paused running task isn't turned into a failure or a retry
        q.pause("b"); assertEquals(DlState.PAUSED, q["b"]!!.state)
        q.failed("b", "cancelled"); assertEquals(DlState.PAUSED, q["b"]!!.state)
        q.interrupted("b"); assertEquals(DlState.PAUSED, q["b"]!!.state)
        q.resume("b"); assertEquals(DlState.QUEUED, q["b"]!!.state)
        q.take(emptySet()); q.interrupted("b"); assertEquals("worker stopped: waits again", DlState.QUEUED, q["b"]!!.state)
        q.pauseAll(); assertTrue(q.tasks.value.none { it.state == DlState.QUEUED || it.state == DlState.RUNNING })
        q.resumeAll(); assertTrue(q.tasks.value.all { it.state == DlState.QUEUED })
        q.cancel("c"); assertNull(q["c"])
        q.finished("b"); assertNull(q["b"])
        // re-adding a failed / paused one queues it again
        q.pause("a"); assertEquals(1, q.add(listOf(task("a")))); assertEquals(DlState.QUEUED, q["a"]!!.state)
        // a saved queue: running ones wait again
        val q2 = DownloadQueue { clock }
        q2.load(listOf(task("z").copy(state = DlState.RUNNING), task("y").copy(state = DlState.PAUSED)))
        assertEquals(DlState.QUEUED, q2["z"]!!.state); assertEquals(DlState.PAUSED, q2["y"]!!.state)
        assertTrue(saves > 10)
        assertEquals(listOf(15_000L, 60_000L, 240_000L), (1..3).map { DownloadQueue.backoff(it) })
    }

    private fun jpeg(color: Int, w: Int = 40, h: Int = 60): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    /** Puts a finished chapter on disk the way the worker does. */
    private fun fakeChapter(ref: MangaRef, c: Chapter, pages: Int): Long {
        val dir = Downloads.chapterDir(ref, c.id).apply { mkdirs() }
        val names = (1..pages).map { i -> "%04d.jpg".format(i).also { File(dir, it).writeBytes(jpeg(0xFF000000.toInt() or (i * 40))) } }
        val bytes = names.sumOf { File(dir, it).length() }
        Downloads.chapterFinished(ref, c, names, bytes)
        return bytes
    }

    @Test fun diskLayoutAndOfflineLookup() {
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
        // ids with slashes, '#' and spaces still make separate, safe folders
        val ref = MangaRef("atsu", "series/abc def#1", "A Title", cover = "https://example.org/c.jpg")
        val chs = listOf(Chapter("ch/2#x", "Chapter 2", 2f), Chapter("ch/1", "Chapter 1", 1f))
        Repo.chaptersCache[ref.key] = chs
        assertNull(Downloads.localPages(ref.key, "ch/1"))
        // half done (no chapter.json): not downloaded
        Downloads.chapterDir(ref, "ch/1").apply { mkdirs() }.resolve("0001.jpg").writeBytes(jpeg(0))
        Downloads.rescan()
        assertFalse(Downloads.isDownloaded(ref.key, "ch/1"))
        val b1 = fakeChapter(ref, chs[1], 3)
        val b2 = fakeChapter(ref, chs[0], 2)
        assertTrue(Downloads.isDownloaded(ref.key, "ch/1") && Downloads.isDownloaded(ref.key, "ch/2#x"))
        assertTrue(Downloads.chapterDir(ref, "ch/1").name.matches(Regex("[A-Za-z0-9._-]+")))
        assertTrue(Downloads.chapterDir(ref, "ch/1") != Downloads.chapterDir(ref, "ch_1"))
        // the reader's pages: files in order, readable as pictures (Coil gets a File)
        val pages = Downloads.localPages(ref.key, "ch/1")!!
        assertEquals(3, pages.size)
        assertEquals(listOf(0, 1, 2), pages.map { it.index })
        pages.forEach { p ->
            val f = Downloads.model(p.url) as File
            assertTrue(f.exists())
            val bmp = BitmapFactory.decodeFile(f.path)
            assertEquals(40, bmp.width)
        }
        assertEquals("not a local page", "https://x/1.jpg", Downloads.model("https://x/1.jpg"))
        // the title page offline: chapters + details saved with it; the index survives a restart
        Downloads.rescan()
        assertEquals(chs, Downloads.savedChapters(ref.key))
        assertEquals(b1 + b2, Downloads.titles.value[ref.key]!!.bytes)
        assertEquals(b1 + b2, Downloads.usedBytes())
        // delete one chapter, then the title
        Downloads.deleteChapter(ref.key, "ch/2#x")
        assertFalse(Downloads.isDownloaded(ref.key, "ch/2#x"))
        assertEquals(b1, Downloads.usedBytes())
        Downloads.deleteTitle(ref.key)
        assertNull(Downloads.titles.value[ref.key])
        assertFalse(Downloads.mangaDir(ref).exists())
        assertEquals(0L, Downloads.usedBytes())
    }

    @Test fun enqueueRules() {
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
        Downloads.autoStart = false
        Downloads.queue.cancelAll()
        val ref = MangaRef("atsu", "q1", "Queue Title")
        val chs = (10 downTo 1).map { Chapter("c$it", "Chapter $it", it.toFloat()) }
        // reading order: oldest first
        Downloads.enqueueChapters(null, ref, chs.take(3))
        assertEquals(listOf("c8", "c9", "c10"), Downloads.queue.tasks.value.map { it.chapter!!.id })
        assertEquals("Already in the queue", Downloads.enqueueChapters(null, ref, chs.take(1)))
        // hidden titles never queue (minor tag)
        val bad = MangaRef("nhentai", "b", "Bad", tags = listOf("tag:lolicon"))
        assertTrue(Downloads.enqueueChapters(null, bad, listOf(Chapter("g", "Gallery", 1f))).contains("hidden"))
        assertTrue(Downloads.queue.tasks.value.none { it.manga?.key == bad.key })
        assertTrue(Downloads.enqueueVideo(null, VideoRef("hentaigasm", "v", "Some loli title")).contains("hidden"))
        // too little space
        Downloads.freeOverride = { 100L * 1024 * 1024 }
        assertTrue(Downloads.enqueueChapters(null, ref, chs.drop(5).take(1)).contains("Not enough free space"))
        Downloads.freeOverride = null
        // "next 5" from the reading spot, "all unread", "all"
        Repo.setRead(ref, chs.takeLast(4), true)          // 1-4 read
        val next = chaptersToDownload(0, chs, ref.key, chs[5]).map { it.id }   // reading chapter 5
        assertEquals(listOf("c5", "c6", "c7", "c8", "c9"), next)
        assertEquals(6, chaptersToDownload(1, chs, ref.key, null).size)
        assertEquals(10, chaptersToDownload(2, chs, ref.key, null).size)
        // cancel removes it; cancel on a waiting one deletes a half-done folder
        val t = Downloads.queue.tasks.value.first()
        Downloads.chapterDir(ref, t.chapter!!.id).apply { mkdirs() }.resolve("0001.jpg.part").writeText("x")
        Downloads.cancel(t.id)
        assertNull(Downloads.queue[t.id])
        assertFalse(Downloads.chapterDir(ref, t.chapter!!.id).exists())
        Downloads.queue.cancelAll()
    }

    @Test fun runnerStopsWhenTheDiskIsFull() {
        Downloads.autoStart = false
        Downloads.queue.cancelAll()
        Downloads.queue.add(listOf(task("full")))
        Downloads.freeOverride = { 10L * 1024 * 1024 }
        kotlinx.coroutines.runBlocking { DownloadRunner.run(null) }
        assertEquals(DlState.PAUSED, Downloads.queue["full"]!!.state)
        assertTrue(DownloadRunner.lowSpace)
        Downloads.queue.cancelAll()
    }

    @Test fun pictureTypes() {
        assertEquals("jpg", DownloadRunner.imageExt(jpeg(0)))
        assertEquals("png", DownloadRunner.imageExt(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10, 0)))
        assertEquals("webp", DownloadRunner.imageExt("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray(Charsets.ISO_8859_1)))
        assertEquals("gif", DownloadRunner.imageExt("GIF89a\u0001\u0000".toByteArray(Charsets.ISO_8859_1)))
        assertNull("an HTML error page is not a picture", DownloadRunner.imageExt("<!DOCTYPE html><html>".toByteArray()))
    }

    @Test fun hlsVariantPick() {
        val m3u8 = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Japanese",DEFAULT=YES,URI="audio/ja.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360,AUDIO="aud"
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2800000,RESOLUTION=1280x720,AUDIO="aud"
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080,AUDIO="aud"
            1080/index.m3u8
        """.trimIndent()
        val p = HlsPlaylistParser().parse(Uri.parse("https://example.org/master.m3u8"), m3u8.byteInputStream()) as HlsMultivariantPlaylist
        val (k720, h720) = VideoDownloads.pickVariant(p, 720)
        assertEquals(720, h720)
        assertEquals(listOf(0 to 1, 1 to 0), k720.map { it.groupIndex to it.streamIndex })
        assertEquals(1080, VideoDownloads.pickVariant(p, 0).second)
        assertEquals("nothing small enough: the smallest", 360, VideoDownloads.pickVariant(p, 240).second)
        assertNotNull(VideoDownloads.pickVariant(p, 480).first.firstOrNull())
    }
}
