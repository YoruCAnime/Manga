package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.gif.FileFrames
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.platform.StreamProxy
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live: GIF frames and Record clips start where the player (mpv) is, also in the middle of a stream's piece
 * (Exos 2026-11-14: Prison School dub episode 1 on Anikoto, row from 982.32, frames #289-#362 made something else: the
 * row came out 10.08 s early). Numbers only, no pictures kept. -Psources=gifseek runs it; MPV_EXE = an mpv.exe.
 */
class GifSeekTest {
    private fun shrink(px: IntArray, w: Int, h: Int): FloatArray {
        val bw = w / 8; val bh = h / 8
        val out = FloatArray(bw * bh)
        for (y in 0 until bh * 8) for (x in 0 until bw * 8) {
            val c = px[y * w + x]
            out[(y / 8) * bw + x / 8] += (((c shr 16) and 255) + ((c shr 8) and 255) + (c and 255)) / 3f / 64f
        }
        return out
    }
    private fun small(f: FileFrames, i: Int) = shrink(f.frame(i), f.width, f.height)
    private fun png(f: File): FloatArray {
        val img = javax.imageio.ImageIO.read(f) ?: error("no picture in $f")
        return shrink(IntArray(img.width * img.height) { img.getRGB(it % img.width, it / img.width) }, img.width, img.height)
    }
    private fun diff(a: FloatArray, b: FloatArray): Double { var s = 0.0; for (k in a.indices) { val d = a[k] - b[k]; s += d * d }; return Math.sqrt(s / a.size) }

    /** One editor case as the log has it: row from AT, kept #FIRST-#LAST made as FROM..TO; each against mpv. -Psources=gifcase */
    @Test fun oneCase() = runBlocking {
        if (System.getProperty("sources") != "gifcase") return@runBlocking
        val mpv = System.getenv("MPV_EXE") ?: error("MPV_EXE")
        val at = System.getenv("CASE_AT").toDouble(); val from = System.getenv("CASE_FROM").toDouble(); val to = System.getenv("CASE_TO").toDouble()
        val first = System.getenv("CASE_FIRST").toInt()
        val dir = File("build/gifseek").apply { mkdirs() }
        val ctx = Context(dir)
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val src = VideoSources.byId("anikoto")!!
        val show = src.search(SearchQuery(text = "Prison School"), 1).items.first { it.title.contains("Prison School", true) }
        val d = src.details(src.series(show).first { it.episode == "1" && it.lang == "DUB" })
        val input = StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
        fun mpvAt(t: Double): FloatArray {
            val f = File(dir, "mpv.png").apply { delete() }
            val p = ProcessBuilder(mpv, "--no-config", "--msg-level=all=error", "--ytdl=no", "--audio=no", "--hr-seek=yes", "--start=$t", "--frames=1",
                "--vf=scale=480:270", "--o=${f.absolutePath}", input.last()).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText(); p.waitFor()
            return png(f)
        }
        val row = ClipRecorder.frames(input, at, at + 30.0, 1920, 1080, 25.0, 480, "caserow")
        val make = ClipRecorder.frames(input, from, to, 1920, 1080, 25.0, 480, "casemake", 30.0)
        try {
            val rows = (0 until row.count).map { small(row, it) }
            val m0 = small(make, 0)
            val best = rows.indices.minBy { diff(rows[it], m0) }
            println("[case] row from $at: ${row.count} frames (jump ${ClipRecorder.jumpFor(input, at)}), make $from-$to: ${make.count} frames at ${make.width}x${make.height} (jump ${ClipRecorder.jumpFor(input, from)})")
            println("[case] make #1 = row #${best + 1} (picked #$first), diff %.1f".format(diff(rows[best], m0)))
            println("[case] row #1 vs mpv at $at: %.1f; row #$first vs mpv at $from: %.1f; make #1 vs mpv at $from: %.1f".format(
                diff(rows[0], mpvAt(at)), diff(rows[first - 1], mpvAt(from)), diff(m0, mpvAt(from))))
        } finally { row.close(); make.close() }
    }

    /** Where ffmpeg's input jump lands for "piece start + d": the first picture's real time, by a long row from 940 (mpv-true). -Psources=gifland */
    @Test fun whereJumpsLand() = runBlocking {
        if (System.getProperty("sources") != "gifland") return@runBlocking
        val dir = File("build/gifseek").apply { mkdirs() }
        val ctx = Context(dir)
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val src = VideoSources.byId("anikoto")!!
        val show = src.search(SearchQuery(text = "Prison School"), 1).items.first { it.title.contains("Prison School", true) }
        val d = src.details(src.series(show).first { it.episode == "1" && it.lang == "DUB" })
        val input = StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
        val ff = com.yoruc.mangacracker.platform.Avif.ffmpeg!!.path
        val base = 940.0
        val ref = ClipRecorder.frames(input, base, base + 70.0, 1920, 1080, 25.0, 480, "landref")
        try {
            val rows = (0 until ref.count).map { small(ref, it) }
            fun landAt(t: Double): Pair<Double, Double> {
                val out = File(dir, "land.raw").apply { delete() }
                val args = listOf(ff, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", "%.3f".format(java.util.Locale.US, t)) + input +
                    listOf("-frames:v", "1", "-an", "-vf", "scale=480:270", "-f", "rawvideo", "-pix_fmt", "rgb24", "-y", out.path)
                ProcessBuilder(args).redirectErrorStream(true).start().also { it.inputStream.readAllBytes(); it.waitFor() }
                val b = out.readBytes()
                val px = IntArray(480 * 270) { k -> ((b[3 * k].toInt() and 255) shl 16) or ((b[3 * k + 1].toInt() and 255) shl 8) or (b[3 * k + 2].toInt() and 255) }
                val s = shrink(px, 480, 270)
                val best = rows.indices.minBy { diff(rows[it], s) }
                return (base + best / ref.fps) to diff(rows[best], s)
            }
            for (piece in listOf(960.92, 965.36, 966.80, 969.88, 972.24, 983.68, 986.64, 987.64, 991.08, 993.88, 999.12)) {
                val line = listOf(0.01, 0.1, 0.14, 0.2, 0.3, 0.5).joinToString { dd ->
                    val (real, df) = landAt(piece + dd)
                    "+$dd -> %.2f (%.1f)".format(java.util.Locale.US, real, df)
                }
                println("[land] piece $piece: $line")
            }
        } finally { ref.close() }
    }

    /** For OFFSET_AT: the GIF reading's and Record's first frames against mpv a few frames either side. -Psources=gifoffset */
    @Test fun offset() = runBlocking {
        if (System.getProperty("sources") != "gifoffset") return@runBlocking
        val mpv = System.getenv("MPV_EXE") ?: error("MPV_EXE")
        val at = System.getenv("OFFSET_AT")?.toDouble() ?: 993.84
        val dir = File("build/gifseek").apply { mkdirs() }
        val ctx = Context(dir)
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val src = VideoSources.byId("anikoto")!!
        val show = src.search(SearchQuery(text = "Prison School"), 1).items.first { it.title.contains("Prison School", true) }
        val d = src.details(src.series(show).first { it.episode == "1" && it.lang == "DUB" })
        val input = StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
        val ff = com.yoruc.mangacracker.platform.Avif.ffmpeg!!.path
        fun mpvAt(t: Double): FloatArray {
            val f = File(dir, "mpv.png").apply { delete() }
            val p = ProcessBuilder(mpv, "--no-config", "--msg-level=all=error", "--ytdl=no", "--audio=no", "--hr-seek=yes", "--start=$t", "--frames=1",
                "--vf=scale=480:270", "--o=${f.absolutePath}", input.last()).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText(); p.waitFor()
            return png(f)
        }
        val g = ClipRecorder.frames(input, at, at + 1.0, 1920, 1080, 25.0, 480, "offset")
        val mp4 = File(dir, "offset.mp4").apply { delete() }
        ClipRecorder.cut(input, at, at + 1.0, mp4) {}
        val recs = (0 until 3).map { k ->
            val png = File(dir, "rec$k.png").apply { delete() }
            ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y", "-i", mp4.path, "-vf", "select=eq(n\\,$k),scale=480:270", "-frames:v", "1", png.path).start().waitFor()
            png(png)
        }
        try {
            val ms = (-4..6).associateWith { k -> mpvAt(at + k * 0.04) }
            for (i in 0 until 3) {
                val gi = small(g, i)
                val bestG = ms.minBy { diff(it.value, gi) }
                val bestR = ms.minBy { diff(it.value, recs[i]) }
                println("[offset] frame ${i + 1}: GIF = mpv %+d frames (%.1f), Record = mpv %+d frames (%.1f)".format(bestG.key, diff(bestG.value, gi), bestR.key, diff(bestR.value, recs[i])))
            }
        } finally { g.close() }
    }

    @Test fun startsWhereThePlayerIs() = runBlocking {
        if (System.getProperty("sources") != "gifseek") return@runBlocking
        val mpv = System.getenv("MPV_EXE") ?: error("MPV_EXE")
        val dir = File("build/gifseek").apply { mkdirs() }
        val ctx = Context(dir)
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val src = VideoSources.byId("anikoto")!!
        val show = src.search(SearchQuery(text = "Prison School"), 1).items.first { it.title.contains("Prison School", true) }
        val d = src.details(src.series(show).first { it.episode == "1" && it.lang == "DUB" })
        val input = StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
        val ff = com.yoruc.mangacracker.platform.Avif.ffmpeg!!.path

        fun mpvAt(t: Double): FloatArray {
            val f = File(dir, "mpv.png").apply { delete() }
            val p = ProcessBuilder(mpv, "--no-config", "--msg-level=all=error", "--ytdl=no", "--audio=no", "--hr-seek=yes", "--start=$t", "--frames=1",
                "--vf=scale=480:270", "--o=${f.absolutePath}", input.last()).redirectErrorStream(true).start()
            p.inputStream.bufferedReader().readText(); p.waitFor()
            return png(f)
        }

        // the reading from 982.32 (the broken one) and the GIF part 993.84: same pictures where the times meet
        val row = ClipRecorder.frames(input, 982.32, 1012.32, 1920, 1080, 25.0, 480, "seekrow")
        val make = ClipRecorder.frames(input, 993.84, 996.80, 1920, 1080, 25.0, 480, "seekmake", 30.0)
        try {
            val m0 = small(make, 0)
            val best = (0 until row.count).minBy { diff(small(row, it), m0) }
            println("[gifseek] row ${row.count} frames, make ${make.count}; make #1 = row #${best + 1} (should be #289), jump ${ClipRecorder.jumpFor(input, 982.32)}")
            assertEquals(750, row.count)
            assertEquals(74, make.count)
            assertTrue("make #1 is row #289 (±1)", kotlin.math.abs(best - 288) <= 1)
        } finally { row.close(); make.close() }

        // GIF frames and Record clips against mpv, also mid-piece and near piece starts
        var worst = 0.0
        for (t in listOf(982.32, 983.84, 985.0, 993.84, 975.0, 1005.5, 1200.4, 1400.2, 300.7, 600.33, 72.1)) {
            val m = mpvAt(t)
            val f = ClipRecorder.frames(input, t, t + 2.0, 1920, 1080, 25.0, 480, "seekcheck")
            val g = try { (0 until minOf(3, f.count)).map { diff(small(f, it), m) } } finally { f.close() }
            val mp4 = File(dir, "clip.mp4").apply { delete() }
            ClipRecorder.cut(input, t, t + 2.0, mp4) {}
            val first = File(dir, "clip.png").apply { delete() }
            ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y", "-i", mp4.path, "-frames:v", "1", "-vf", "scale=480:270", first.path).start().waitFor()
            val c = diff(png(first), m)
            println("[gifseek] at $t: jump ${ClipRecorder.jumpFor(input, t)}, GIF first frames vs mpv %s, Record first frame vs mpv %.1f".format(g.joinToString { "%.1f".format(it) }, c))
            worst = maxOf(worst, g.min(), c)
        }
        println("[gifseek] worst difference %.1f (a different moment is ~40-60)".format(worst))
        assertTrue("every reading starts at mpv's picture", worst < 15.0)
    }
}
