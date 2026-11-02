package com.yoruc.mangacracker

import com.yoruc.mangacracker.gif.FrameWriter
import com.yoruc.mangacracker.gif.GifMaker
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.GifParams
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.gif.ListFrames
import com.yoruc.mangacracker.gif.Motion
import com.yoruc.mangacracker.gif.Palette
import com.yoruc.mangacracker.gif.Par
import com.yoruc.mangacracker.gif.Pick
import com.yoruc.mangacracker.gif.Resample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.log10

/**
 * The GIF maker on the PC JVM. The always-on tests use made-up frames; the measuring ones read real frames from
 * _dev_tools/out/gif/frames/<clip>.rgb (rgb24 frames made with ffmpeg, git-ignored) and skip when they are missing.
 * Run: python _dev_tools\run_tests.py GifEngineTest
 */
class GifEngineTest {
    private val root = File("../_dev_tools/out/gif")
    private fun log(s: String) = println("[gif] $s")

    init { Par.threads = 2 } // gentle on the PC

    // ---------------- helpers ----------------
    private fun load(clip: String, max: Int = 10_000): ListFrames? = GifTestIo.load(File(root, "frames"), clip, max)

    private fun png(px: IntArray, w: Int, h: Int, name: String) = GifTestIo.png(px, w, h, File(root, "look/$name.png"))

    private fun psnr(a: IntArray, b: IntArray): Double {
        var s = 0.0
        for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) { val d = (a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF); s += d * d }
        val mse = s / (a.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    private fun decode(bytes: ByteArray): Triple<Int, Int, List<Pair<Int, IntArray>>> = GifTestIo.decodeGif(bytes).let { Triple(it.w, it.h, it.frames) }

    private fun shownAt(frames: List<Pair<Int, IntArray>>, cs: Double): IntArray = frames.last { it.first <= cs + 1e-6 }.second

    // ---------------- always-on checks ----------------
    @Test fun writerRoundTrip() {
        // gradient + noise + flat block: LZW must survive table resets (random noise fills the 4096 table fast)
        val w = 200; val h = 120
        val rnd = java.util.Random(1)
        val f1 = IntArray(w * h) { i -> val x = i % w; val y = i / w; if (x < 100) (x * 2 shl 16) or (y * 2 shl 8) or 128 else rnd.nextInt(0xFFFFFF) }
        val f2 = f1.copyOf().also { for (y in 30 until 60) for (x in 20 until 80) it[y * w + x] = 0xFF2040 }
        val pal = Palette.build(listOf(f1, f2), 256)
        val fw = FrameWriter(w, h, pal, 0)
        val q1 = GifMaker.quantize(f1, w, h, pal, 0f); val q2 = GifMaker.quantize(f2, w, h, pal, 0f)
        fw.add(q1, 4); fw.add(q1, 4); fw.add(q2, 3)
        val bytes = fw.finish()
        val (dw, dh, dec) = decode(bytes)
        assertEquals(w, dw); assertEquals(h, dh)
        assertEquals("unchanged frame merged", 2, dec.size)
        assertEquals("delays merged", 8, dec[1].first)
        val exp1 = IntArray(w * h) { pal.rgb[q1[it].toInt() and 0xFF] }; val exp2 = IntArray(w * h) { pal.rgb[q2[it].toInt() and 0xFF] }
        assertTrue("frame 1 exact", exp1.contentEquals(dec[0].second))
        assertTrue("frame 2 exact (cropped + transparent)", exp2.contentEquals(dec[1].second))
        assertTrue("loops", GifTestIo.decodeGif(bytes).loops)
        // independent decoder: ffmpeg (when on PATH) must see the same two pictures
        val f = File(root, "roundtrip.gif").apply { parentFile.mkdirs(); writeBytes(bytes) }
        GifTestIo.ffmpegDecode(f, w, h)?.let { ff ->
            assertEquals(2, ff.size)
            assertTrue("ffmpeg frame 1", ff[0].contentEquals(exp1)); assertTrue("ffmpeg frame 2", ff[1].contentEquals(exp2))
            log("ffmpeg decodes it the same")
        }
        log("round trip ok, ${bytes.size} bytes")
    }

    @Test fun timing() {
        val d30 = GifMaker.delays(30, 30.0)
        assertEquals(100, d30.sum()); assertTrue(d30.all { it == 3 || it == 4 })
        assertEquals(400, GifMaker.delays(100, 25.0).sum())
        assertEquals(listOf(0, 1, 2, 3, 2, 1).map { Pick(it) }, GifMaker.pingPong((0..3).map { Pick(it) }))
        // 2x of 24 fps under a 25 fps cap: every 2nd frame at 24 fps; 1x of 30 fps under 25: 25 fps
        val src = ListFrames(List(48) { IntArray(16 * 16) { c -> c * it } }, 16, 16, 24.0)
        val (p2, f2) = GifMaker(src, GifOptions(speed = 2f)).timeline(25, true)
        assertEquals(24.0, f2, 1e-9); assertEquals(24, p2.size); assertEquals((0 until 48 step 2).toList(), p2.map { it.a })
        val src30 = ListFrames(List(30) { IntArray(16 * 16) { c -> c * it } }, 16, 16, 30.0)
        assertEquals(25.0, GifMaker(src30, GifOptions()).timeline(25, true).second, 1e-9)
        // slow motion: 0.5x makes one in-between per pair, at t = 0.5
        val (ps, fs) = GifMaker(src, GifOptions(speed = 0.5f)).timeline(24, true)
        assertEquals(24.0, fs, 1e-9); assertEquals(96, ps.size)
        assertTrue(ps.count { it.t in 0.45f..0.55f } > 40)
    }

    // ---------------- measurements on real frames ----------------
    @Test fun holdsAndCuts() {
        val talk = load("hg_talk") ?: return assumeTrue(false)
        val action = load("hg_action") ?: return assumeTrue(false)
        for ((name, src) in listOf("hg_talk" to talk, "hg_action" to action)) {
            val gm = GifMaker(src, GifOptions(speed = 0.5f))
            val keys = gm.keys()
            log("$name: ${src.count} frames, ${keys.size} key frames (drawings)")
            // cut stats over consecutive key frames
            val cuts = ArrayList<Int>()
            val stats = StringBuilder()
            for (k in 0 until keys.size - 1) {
                val a = Motion.plane(src.frame(keys[k]), src.width, src.height); val b = Motion.plane(src.frame(keys[k + 1]), src.width, src.height)
                val f = Motion.estimate(a, b)
                if (Motion.isCut(a, b, f)) cuts += keys[k + 1]
                if (k < 400) stats.append(String.format("%d:%.1f/%.1f ", keys[k + 1], Motion.diff(a, b), f.meanErr))
            }
            log("$name cuts at $cuts")
            log("$name diff/err: $stats")
        }
        // a made-up cut: logo frames then talk frames
        val logo = load("hg_logo") ?: return
        val a = Motion.plane(logo.frame(60), logo.width, logo.height); val b = Motion.plane(talk.frame(10), talk.width, talk.height)
        val f = Motion.estimate(a, b)
        log(String.format("joined clips: diff %.1f err %.1f cut=%s", Motion.diff(a, b), f.meanErr, Motion.isCut(a, b, f)))
        assertTrue(Motion.isCut(a, b, f))
    }

    /** In-betweens against the real frame they replace: drop every other frame, rebuild it. */
    @Test fun interpolation() {
        for (clip in listOf("pan60", "testsrc60", "sfm", "hg_action")) {
            val src = load(clip, 72) ?: continue
            val w = src.width; val h = src.height
            var pN = 0.0; var pB = 0.0; var pO = 0.0; var cnt = 0; var nulls = 0; var ms = 0L; var flowMs = 0L
            var shown = false
            var i = 0
            while (i + 2 < src.count) {
                val a = src.frame(i); val b = src.frame(i + 2); val truth = src.frame(i + 1)
                if (a.contentEquals(truth) || b.contentEquals(truth)) { i += 2; continue } // held drawing: nothing to rebuild
                val t0 = System.nanoTime()
                val pa = Motion.plane(a, w, h); val pb = Motion.plane(b, w, h)
                val fw = Motion.estimate(pa, pb); val bw = Motion.estimate(pb, pa)
                val cut = Motion.isCut(pa, pb, fw)
                val t1 = System.nanoTime()
                val mid = if (cut) null else Motion.inBetween(a, b, w, h, fw, bw, 0.5f)
                ms += (System.nanoTime() - t0) / 1_000_000; flowMs += (t1 - t0) / 1_000_000
                val blend = IntArray(a.size) { k -> var o = 0; for (sh in intArrayOf(16, 8, 0)) o = o or ((((a[k] shr sh and 0xFF) + (b[k] shr sh and 0xFF) + 1) / 2) shl sh); o }
                if (mid == null) nulls++
                val ours = mid ?: a
                pN += psnr(a, truth); pB += psnr(blend, truth); pO += psnr(ours, truth); cnt++
                if (!shown && mid != null && i >= 8) {
                    shown = true
                    // side by side: real | plain blend | ours
                    val sb = IntArray(w * 3 * h)
                    for (y in 0 until h) for (x in 0 until w) { sb[y * w * 3 + x] = truth[y * w + x]; sb[y * w * 3 + w + x] = blend[y * w + x]; sb[y * w * 3 + 2 * w + x] = ours[y * w + x] }
                    png(sb, w * 3, h, "interp_$clip")
                }
                i += 2
            }
            if (cnt > 0) log(String.format("interp %s (%dx%d): %d frames, PSNR vs the real frame: repeat %.2f, blend %.2f, ours %.2f dB; %d fell back; %.0f ms per in-between (flow both ways %.0f + warp)",
                clip, w, h, cnt, pN / cnt, pB / cnt, pO / cnt, nulls, ms.toDouble() / cnt, flowMs.toDouble() / cnt))
        }
    }

    /** Size / quality of single encodes over dither and threshold, at 480 px and 24/30 fps, 1x. */
    @Test fun sweep() {
        for (clip in listOf("hg_logo", "hg_talk", "hg_action", "sfm")) {
            val full = load(clip) ?: continue
            for (width in listOf(480)) for ((dith, thr) in listOf(0f to 0, 0f to 6, 6f to 6, 10f to 6, 6f to 3, 6f to 10)) {
                val gm = GifMaker(full, GifOptions())
                val t0 = System.nanoTime()
                val r = gm.encode(GifParams(width, 30, 256, thr, dith))
                val ms = (System.nanoTime() - t0) / 1_000_000
                val q = quality(full, r.bytes, GifOptions(), 30)
                log(String.format("sweep %s w%d dither %.0f thr %d: %.2f MB, %d frames %.1f fps, PSNR %.2f dB, %d ms (%.1f ms/frame)",
                    clip, r.width, dith, thr, r.bytes.size / 1e6, r.frames, r.fps, q, ms, ms.toDouble() / r.frames))
                if (clip == "hg_logo" && thr == 6) File(root, "ours_${clip}_d${dith.toInt()}.gif").writeBytes(r.bytes)
            }
        }
    }

    /** PSNR of the decoded GIF against the source frames (scaled the same) at each output frame's time, speed 1. */
    private fun quality(src: ListFrames, bytes: ByteArray, opts: GifOptions, fpsCap: Int): Double {
        val (w, h, dec) = decode(bytes)
        val (picks, fps) = GifMaker(src, opts).timeline(fpsCap, true)
        var s = 0.0
        val step = maxOf(1, picks.size / 40)
        var c = 0
        val delays = GifMaker.delays(picks.size, fps)
        var t = 0
        for ((k, p) in picks.withIndex()) {
            if (k % step == 0) {
                val ref = Resample.resize(src.frame(p.a), src.width, src.height, w, h)
                s += psnr(shownAt(dec, t.toDouble()), ref); c++
            }
            t += delays[k]
        }
        return s / c
    }

    /** Source frame vs decoded GIF frame at output frame [k], side by side (crop x, y, w, h of the GIF picture). */
    private fun look(src: ListFrames, bytes: ByteArray, opts: GifOptions, fpsCap: Int, k: Int, name: String, crop: IntArray? = null) {
        val (w, h, dec) = decode(bytes)
        val (picks, fps) = GifMaker(src, opts).timeline(fpsCap, true)
        val t = GifMaker.delays(picks.size, fps).take(k).sum()
        val ref = Resample.resize(src.frame(picks[k].a), src.width, src.height, w, h)
        val got = shownAt(dec, t.toDouble())
        val (cx, cy, cw, ch) = crop?.toList() ?: listOf(0, 0, w, h)
        val sb = IntArray(cw * 2 * ch)
        for (y in 0 until ch) for (x in 0 until cw) { sb[y * cw * 2 + x] = ref[(cy + y) * w + cx + x]; sb[y * cw * 2 + cw + x] = got[(cy + y) * w + cx + x] }
        png(sb, cw * 2, ch, name)
    }

    @Test fun paletteSpaces() {
        for (clip in listOf("hg_talk", "sfm")) {
            val src = load(clip) ?: continue
            val gm = GifMaker(src, GifOptions())
            val (dw, dh) = gm.dims(480)
            val frames = (0 until src.count step 12).map { Resample.resize(src.frame(it), src.width, src.height, dw, dh) }
            val t0 = System.nanoTime()
            val pal = Palette.build(frames, 256, 1)
            val t1 = System.nanoTime()
            var ps = 0.0
            for (f in frames) { val q = GifMaker.quantize(f, dw, dh, pal, 0f); ps += psnr(IntArray(f.size) { pal.rgb[q[it].toInt() and 0xFF] }, f) }
            log(String.format("palette %s: %.2f dB over %d frames (palette %d ms, mapping %.1f ms/frame)", clip, ps / frames.size, frames.size,
                (t1 - t0) / 1_000_000, (System.nanoTime() - t1) / 1e6 / frames.size))
        }
    }

    /** Ours against ffmpeg's best GIF recipes on the same frames, same size and rate. */
    @Test fun versusFfmpeg() {
        val recipes = listOf(
            "ff_sierra" to "palettegen=stats_mode=diff[p];[b][p]paletteuse=diff_mode=rectangle",
            "ff_bayer" to "palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4:diff_mode=rectangle",
            "ff_none" to "palettegen=stats_mode=diff[p];[b][p]paletteuse=dither=none:diff_mode=rectangle",
            "ff_local" to "palettegen=stats_mode=single[p];[b][p]paletteuse=new=1:dither=bayer:bayer_scale=4:diff_mode=rectangle",
        )
        val only = System.getProperty("sources", "").removePrefix("ours")
        for (clip in listOf("hg_talk", "hg_action", "sfm", "hg_logo")) {
            val src = load(clip) ?: continue
            val gm = GifMaker(src, GifOptions())
            val (dw, dh) = gm.dims(480)
            val opts = GifOptions()
            for ((thr, dith) in listOf(6 to 0f, 6 to 16f, 6 to 24f, 6 to 40f, 10 to 24f)) {
                val r = gm.encode(GifParams(480, 30, 256, thr, dith))
                log(String.format("vs %s ours thr %d dither %.0f: %.2f MB, PSNR %.2f dB", clip, thr, dith, r.bytes.size / 1e6, quality(src, r.bytes, opts, 30)))
                if (thr == 6) look(src, r.bytes, opts, 30, 30, "vs_${clip}_ours_d${dith.toInt()}")
            }
            if (System.getProperty("sources", "") == "ours") continue
            for ((name, graph) in recipes) {
                val out = File(root, "${name}_$clip.gif")
                val cmd = listOf("ffmpeg", "-v", "error", "-y", "-threads", "2", "-framerate", src.fps.toString(), "-i", File(root, "frames/$clip/%04d.png").path,
                    "-filter_complex", "[0:v]scale=$dw:$dh:flags=lanczos,split[a][b];[a]$graph", out.path)
                val pr = ProcessBuilder(cmd).redirectErrorStream(true).start(); pr.inputStream.use { it.readBytes() }; pr.waitFor()
                if (!out.exists()) { log("ffmpeg missing"); return }
                val bytes = out.readBytes()
                log(String.format("vs %s %s: %.2f MB, PSNR %.2f dB", clip, name, bytes.size / 1e6, quality(src, bytes, opts, 30)))
                if (name == "ff_sierra") look(src, bytes, opts, 30, 30, "vs_${clip}_$name")
            }
        }
    }

    /** Time per step at Small size (480 x 270 from 640 x 360 frames), one thread (a phone core is ~3-5x slower) and two. */
    @Test fun speed() {
        val src = load("hg_talk", 40) ?: return assumeTrue(false)
        val gm = GifMaker(src, GifOptions())
        val (dw, dh) = gm.dims(480)
        for (threads in listOf(1, 2)) {
            Par.threads = threads
            fun time(name: String, reps: Int, f: (Int) -> Unit) {
                repeat(3) { f(it) }                       // warm up the JIT
                val t0 = System.nanoTime(); for (i in 0 until reps) f(i)
                log(String.format("speed %d thread(s) %-22s %6.1f ms", threads, name, (System.nanoTime() - t0) / 1e6 / reps))
            }
            val small = (0 until 20).map { Resample.resize(src.frame(it + 10), src.width, src.height, dw, dh) }
            time("resize 640->480", 10) { Resample.resize(src.frame(it), src.width, src.height, dw, dh) }
            time("palette (16 frames)", 3) { Palette.build(small.take(16), 256, 1) }
            val pal = Palette.build(small.take(16), 256, 1)
            time("quantize + dither", 10) { GifMaker.quantize(small[it], dw, dh, pal, 24f) }
            val q = small.map { GifMaker.quantize(it, dw, dh, pal, 24f) }
            time("LZW frame (with diff)", 10) { val fw = FrameWriter(dw, dh, pal, 6); fw.add(q[it], 4); fw.add(q[it + 1], 4); fw.finish() }
            time("flow both ways", 10) {
                val pa = Motion.plane(small[it], dw, dh); val pb = Motion.plane(small[it + 1], dw, dh)
                Motion.estimate(pa, pb); Motion.estimate(pb, pa)
            }
            val pa = Motion.plane(small[5], dw, dh); val pb = Motion.plane(small[6], dw, dh)
            val fw = Motion.estimate(pa, pb); val bw = Motion.estimate(pb, pa)
            time("in-between warp", 10) { Motion.inBetween(small[5], small[6], dw, dh, fw, bw, 0.5f) }
        }
        Par.threads = 2
    }

    /** The whole maker: every size, a few speeds, ping-pong. */
    @Test fun makeAll() {
        for (clip in listOf("hg_talk", "hg_action", "sfm")) {
            val src = load(clip) ?: continue
            for ((speed, pp) in listOf(1f to false, 0.5f to false, 0.25f to false, 2f to false, 1f to true, 0.5f to true)) for (size in GifSize.entries) {
                if (size != GifSize.SMALL && !(speed == 1f && !pp) && !(speed == 0.5f && !pp)) continue
                val gm = GifMaker(src, GifOptions(speed, pp, size))
                val t0 = System.nanoTime()
                val r = gm.make()
                val ms = (System.nanoTime() - t0) / 1_000_000
                log(String.format("make %s %s %.2fx%s: %.2f MB (limit %.0f) %dx%d %.1f fps %d colours, %d frames, %.1f s, %d tries, %d in-betweens, %d fell back, %d ms",
                    clip, size.label, speed, if (pp) " ping-pong" else "", r.bytes.size / 1e6, size.maxBytes / 1e6, r.width, r.height, r.fps, r.params.colours,
                    r.frames, r.durationMs / 1000.0, r.tries, r.inBetweens, r.fallbacks, ms))
                assertTrue(r.bytes.size <= size.maxBytes)
                File(root, "make_${clip}_${size.label}_${speed}${if (pp) "_pp" else ""}.gif").writeBytes(r.bytes)
            }
        }
    }
}
