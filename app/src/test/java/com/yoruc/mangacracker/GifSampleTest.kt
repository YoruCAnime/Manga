package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Dev helper for the GIF maker tests: saves the first few MB of one video per MP4 source into
 * _dev_tools/out/gif/src (git-ignored) so ffmpeg can pull real frames from it. Opt-in:
 * python _dev_tools\run_tests.py GifSampleTest -Psources=gifsample
 */
class GifSampleTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() { Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http")) }
    }

    @Test fun sample() = runBlocking {
        assumeTrue("gifsample" in System.getProperty("sources", ""))
        val dir = File("../_dev_tools/out/gif/src").apply { mkdirs() }
        for (id in listOf("hentaigasm", "sfmcompile")) {
            val src = VideoSources.byId(id)!!
            runCatching {
                val ref = src.latest(1).items.first()
                val d = src.details(ref)
                src.beforePlay()
                if (d.stream.kind == "hls") error("hls")
                val req = Request.Builder().url(d.stream.url).header("Range", "bytes=0-12000000").build()
                src.playerClient.newCall(req).execute().use { r ->
                    File(dir, "$id.${d.stream.kind}").outputStream().use { o -> r.body!!.byteStream().copyTo(o) }
                    println("[gif] $id ${r.code} ${d.stream.kind} ${File(dir, "$id.${d.stream.kind}").length()} bytes")
                }
            }.onFailure { println("[gif] $id failed: ${it.message}") }
        }
    }
}
