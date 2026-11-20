package com.yoruc.mangacracker

import android.content.Context
import com.sun.net.httpserver.HttpServer
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.platform.Avif
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.platform.StreamProxy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.atomic.AtomicInteger

/** A local stand-in for a video site that drops connections and refuses for a while (made-up colour-bars video). */
class FlakySiteTest {
    private class Site(val data: ByteArray) {
        val hits = AtomicInteger()
        @Volatile var refuseFirst = 0
        @Volatile var cutAt = -1L
        @Volatile var cuts = 0
        val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 16).apply {
            executor = java.util.concurrent.Executors.newCachedThreadPool()
            createContext("/v.mp4") { ex ->
                runCatching {
                    if (hits.incrementAndGet() <= refuseFirst) { ex.sendResponseHeaders(503, -1); ex.close(); return@runCatching }
                    val m = Regex("bytes=(\\d+)-(\\d*)").find(ex.requestHeaders.getFirst("Range").orEmpty())
                    val from = m?.groupValues?.get(1)?.toLong() ?: 0L
                    val to = m?.groupValues?.get(2)?.toLongOrNull() ?: (data.size - 1L)
                    val n = (to - from + 1).toInt()
                    ex.responseHeaders.add("Content-Type", "video/mp4")
                    ex.responseHeaders.add("Accept-Ranges", "bytes")
                    if (m != null) ex.responseHeaders.add("Content-Range", "bytes $from-$to/${data.size}")
                    ex.sendResponseHeaders(if (m != null) 206 else 200, n.toLong())
                    val out = ex.responseBody
                    if (cuts > 0 && from + n > cutAt && from < cutAt) {
                        cuts--
                        out.write(data, from.toInt(), (cutAt - from).toInt()); out.flush()
                        ex.close() // the connection just ends, short of what was promised
                    } else { out.write(data, from.toInt(), n); out.close() }
                }
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/v.mp4"
    }

    private fun sample(): File {
        val f = File("build/flaky/bars.mp4").apply { parentFile.mkdirs() }
        if (!f.isFile) {
            val p = ProcessBuilder(Avif.ffmpeg!!.path, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=24:duration=20",
                "-c:v", "libx264", "-preset", "veryfast", "-pix_fmt", "yuv420p", f.path).inheritIO().start()
            assertEquals(0, p.waitFor())
        }
        return f
    }

    private fun setup() {
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx)
    }

    @Test fun relayPicksUpADroppedConnection() {
        setup()
        val data = ByteArray(9_000_000) { (it * 31 % 251).toByte() }
        val site = Site(data).apply { cutAt = 3_000_000; cuts = 2 }
        try {
            // whole file, then a part that crosses the cut
            val whole = URI(StreamProxy.url("nobody", site.url)).toURL().openStream().use { it.readBytes() }
            assertArrayEquals(data, whole)
            site.cuts = 1
            val c = URI(StreamProxy.url("nobody", site.url)).toURL().openConnection()
            c.setRequestProperty("Range", "bytes=2500000-3500000")
            val part = c.getInputStream().use { it.readBytes() }
            assertArrayEquals(data.copyOfRange(2_500_000, 3_500_001), part)
            println("[flaky] relay: both reads complete after dropped connections, ${site.hits.get()} requests")
        } finally { site.server.stop(0) }
    }

    @Test fun gifReadSurvivesARefusingSite() = runBlocking {
        setup()
        val bytes = sample().readBytes()
        for (refused in listOf(0, 3, 6)) {
            val site = Site(bytes).apply { refuseFirst = refused }
            try {
                val input = StreamProxy.ffmpegInput("nobody", site.url, "mp4")
                val t = System.currentTimeMillis()
                val f = ClipRecorder.frames(input, 5.0, 8.0, 640, 360, 24.0, 480, "flaky")
                println("[flaky] refused first $refused requests: ${f.count} frames in ${System.currentTimeMillis() - t} ms, ${site.hits.get()} requests")
                assertTrue("frames ${f.count}", f.count in 70..74)
                f.close()
            } finally { site.server.stop(0) }
        }
        // a site that never answers: the message says so, nothing hangs
        val dead = Site(bytes).apply { refuseFirst = Int.MAX_VALUE }
        try {
            val r = runCatching { ClipRecorder.frames(StreamProxy.ffmpegInput("nobody", dead.url, "mp4"), 5.0, 8.0, 640, 360, 24.0, 480, "flaky") }
            println("[flaky] dead site: ${r.exceptionOrNull()?.message}")
            assertTrue(r.isFailure && "didn't send the video" in r.exceptionOrNull()!!.message!!)
        } finally { dead.server.stop(0) }
    }

    /** The real file: -Psources=real (frames are counted, nothing is kept). */
    @Test fun realFile() = runBlocking {
        if (System.getProperty("sources") != "real") return@runBlocking
        setup()
        val url = "https://hgasm2.com/.Harem%20Shima%20E%20Youkoso%202%20Subbed.mp4"
        val input = StreamProxy.ffmpegInput("hentaigasm", url, "mp4")
        for (at in listOf(12.0, 300.0)) {
            val t = System.currentTimeMillis()
            val f = ClipRecorder.frames(input, at, at + 3, 1280, 720, 24.0, 480, "flaky")
            println("[flaky] real direct at $at: ${f.count} frames in ${System.currentTimeMillis() - t} ms"); f.close()
        }
    }
}
