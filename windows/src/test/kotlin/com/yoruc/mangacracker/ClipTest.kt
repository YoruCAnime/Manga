package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.platform.StreamProxy
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Record (5 s MP4) and GIF (3 s) from a live video of each picked source, as the player makes them. */
class ClipTest {
    @Test fun clips() = runBlocking {
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }.ifEmpty { listOf("hentaigasm", "muchohentai") }
        for (id in pick) {
            val src = VideoSources.byId(id)!!
            val d = src.details(src.latest(1).items.first())
            src.beforePlay()
            val input = StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
            val out = File("build/clips/$id.mp4").apply { parentFile.mkdirs(); delete() }
            var t = System.currentTimeMillis()
            val r = runCatching { ClipRecorder.cut(input, 20.0, 25.0, out) {} }
            println("[clip] $id (${d.stream.kind}) MP4: " + r.fold({ "${it.length() / 1024} KB in ${System.currentTimeMillis() - t} ms" }, { "FAIL ${it.message}" }))
            assertTrue("$id clip", r.isSuccess && out.length() > 50_000)
            val g = File("build/clips/$id.gif").apply { delete() }
            t = System.currentTimeMillis()
            val rg = runCatching {
                val f = ClipRecorder.frames(input, 30.0, 33.0, 1280, 720, 24.0, 480, "test")
                try { ClipRecorder.gif(f, GifOptions(), g) { _, _ -> } } finally { f.close() }
            }
            println("[clip] $id GIF: " + rg.fold({ "${it.length() / 1024} KB in ${System.currentTimeMillis() - t} ms" }, { "FAIL ${it.message}" }))
            assertTrue("$id gif", rg.isSuccess && g.length() > 10_000)
            // long and smooth: 30 s at Big, and 4 s at half speed, 50 fps (every frame of the video, in-betweens)
            for ((name, case) in listOf("30s" to Triple(30.0, 1f, 0), "slow50" to Triple(4.0, 0.5f, 50))) {
                val (len, speed, fps) = case
                val gl = File("build/clips/${id}_$name.gif").apply { delete() }
                t = System.currentTimeMillis()
                val keep = if (speed < 1f || fps > 30) 60.0 else 30.0
                val rl = runCatching {
                    val f = ClipRecorder.frames(input, 40.0, 40.0 + len, 1280, 720, 24.0, 480, "test", keep)
                    try { ClipRecorder.gif(f, GifOptions(speed, false, com.yoruc.mangacracker.gif.GifSize.BIG, fps), gl) { _, _ -> } } finally { f.close() }
                }
                println("[clip] $id GIF $name: " + rl.fold({ "${it.length() / 1024} KB in ${System.currentTimeMillis() - t} ms" }, { "FAIL ${it.message}" }))
                assertTrue("$id gif $name", rl.isSuccess && gl.length() in 10_000..10_000_000)
            }
        }
    }
}
