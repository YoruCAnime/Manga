package com.yoruc.mangacracker

import com.yoruc.mangacracker.gif.GifMaker
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.gif.ListFrames
import com.yoruc.mangacracker.gif.Motion
import com.yoruc.mangacracker.platform.Rife
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.log10

/**
 * RIFE in-betweens against the built-in ones, on the phone tests' real frames (the phone app's
 * _dev_tools/out/gif/frames, made by its _dev_tools/gif_frames.py; system property gifFrames, skipped when missing).
 * Pictures and GIFs go to build/rife_test.
 */
class RifeTest {
    private val frames = File(System.getProperty("gifFrames")?.takeIf { it.isNotEmpty() } ?: "../_dev_tools/out/gif/frames")
    private val out = File(System.getProperty("rifeOut")?.takeIf { it.isNotEmpty() } ?: "build/rife_test").apply { mkdirs() }
    private fun log(s: String) = println("[rife] $s")

    private fun load(clip: String, max: Int): ListFrames? {
        val raw = File(frames, "$clip.rgb"); val info = File(frames, "$clip.txt")
        if (!raw.exists() || !info.exists()) return null
        val (w, h, fps) = info.readText().trim().split(" ").map { it.toDouble() }
        val fw = w.toInt(); val fh = h.toInt(); val size = fw * fh * 3
        val list = ArrayList<IntArray>()
        raw.inputStream().buffered(1 shl 20).use { inp ->
            val buf = ByteArray(size)
            while (list.size < max) {
                var got = 0
                while (got < size) { val r = inp.read(buf, got, size - got); if (r < 0) break; got += r }
                if (got < size) break
                list += IntArray(fw * fh) { i -> ((buf[3 * i].toInt() and 0xFF) shl 16) or ((buf[3 * i + 1].toInt() and 0xFF) shl 8) or (buf[3 * i + 2].toInt() and 0xFF) }
            }
        }
        return ListFrames(list, fw, fh, fps)
    }

    private fun psnr(a: IntArray, b: IntArray): Double {
        var s = 0.0
        for (i in a.indices) for (sh in intArrayOf(16, 8, 0)) { val d = (a[i] shr sh and 0xFF) - (b[i] shr sh and 0xFF); s += d * d }
        val mse = s / (a.size * 3)
        return if (mse == 0.0) 99.0 else 10 * log10(255.0 * 255.0 / mse)
    }

    private fun png(px: IntArray, w: Int, h: Int, name: String) =
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, px, 0, w) }, "png", File(out, "$name.png"))

    /** The middle of two frames two apart, against the real frame between them. */
    @Test fun middles() {
        assumeTrue("RIFE not found", Rife.available)
        for (clip in listOf("hg_action", "hg_talk", "pan60", "sfm")) {
            val src = load(clip, 96) ?: continue
            val w = src.width; val h = src.height
            // pairs (i, i + 2) whose middle frame is a new drawing (not a held copy of either end)
            val keys = ArrayList<Int>()
            var i = 0
            while (i + 2 < src.count) { keys += i; i += 2 }
            keys += i
            val rife = Rife.open()!!
            val t0 = System.nanoTime()
            check(rife.prepare(keys.toIntArray(), { src.frame(it) }, w, h, 2, {}, { false }))
            val rifeMs = (System.nanoTime() - t0) / 1_000_000
            var pR = 0.0; var pO = 0.0; var pN = 0.0; var cnt = 0; var shown = false
            for (j in 0 until keys.size - 1) {
                val a = src.frame(keys[j]); val b = src.frame(keys[j + 1]); val truth = src.frame(keys[j] + 1)
                if (a.contentEquals(truth) || b.contentEquals(truth)) continue
                val pa = Motion.plane(a, w, h); val pb = Motion.plane(b, w, h)
                val fw = Motion.estimate(pa, pb)
                if (Motion.isCut(pa, pb, fw)) continue
                val ours = Motion.inBetween(a, b, w, h, fw, Motion.estimate(pb, pa), 0.5f) ?: a
                val r = rife.get(j, 1)
                pR += psnr(r, truth); pO += psnr(ours, truth); pN += psnr(a, truth); cnt++
                if (!shown && j >= 4) {
                    shown = true
                    // real | built-in | RIFE
                    val sb = IntArray(w * 3 * h)
                    for (y in 0 until h) for (x in 0 until w) { sb[y * w * 3 + x] = truth[y * w + x]; sb[y * w * 3 + w + x] = ours[y * w + x]; sb[y * w * 3 + 2 * w + x] = r[y * w + x] }
                    png(sb, w * 3, h, "middle_$clip")
                }
            }
            rife.close()
            if (cnt > 0) log(String.format("%s (%dx%d): %d middles, PSNR vs the real frame: repeat %.2f, built-in %.2f, RIFE %.2f dB; RIFE run %d ms for %d pairs",
                clip, w, h, cnt, pN / cnt, pO / cnt, pR / cnt, rifeMs, keys.size - 1))
        }
    }

    /** A whole slow-motion GIF both ways: time, size, in-betweens. */
    @Test fun slowGif() {
        assumeTrue("RIFE not found", Rife.available)
        for ((clip, max) in listOf("hg_action" to 96, "hg_long" to 240)) {
            val src = load(clip, max) ?: continue
            for (fps in listOf(0, 50)) for (useRife in listOf(false, true)) {
                val opts = GifOptions(speed = 0.5f, size = GifSize.SMALL, fps = fps)
                val rife = if (useRife) Rife.open() else null
                // time spent in RIFE's run and in reading its frames back
                var prepMs = 0L; var getMs = 0L; var steps = 0; var keys = 0
                val timed = rife?.let { rf -> object : com.yoruc.mangacracker.gif.InBetweens {
                    override fun prepare(k: IntArray, frame: (Int) -> IntArray, w: Int, h: Int, s: Int, progress: (Float) -> Unit, cancelled: () -> Boolean): Boolean {
                        val t = System.nanoTime(); keys = k.size; steps = s
                        return rf.prepare(k, frame, w, h, s, progress, cancelled).also { prepMs += (System.nanoTime() - t) / 1_000_000 }
                    }
                    override fun get(pair: Int, step: Int): IntArray { val t = System.nanoTime(); return rf.get(pair, step).also { getMs += (System.nanoTime() - t) / 1_000_000 } }
                } }
                val t0 = System.nanoTime()
                val r = GifMaker(src, opts).apply { drawer = timed }.make()
                val ms = (System.nanoTime() - t0) / 1_000_000
                rife?.close()
                if (rife != null) log("  RIFE: $keys keys x $steps steps, prepare $prepMs ms, reading back $getMs ms")
                val name = "slow_${clip}_${if (fps == 0) "auto" else "${fps}fps"}_${if (useRife) "rife" else "builtin"}"
                File(out, "$name.gif").writeBytes(r.bytes)
                log(String.format("%s: %d src frames -> %d frames %dx%d at %.0f fps, %.2f MB, %d in-betweens, %d fell back, %d tries, %d ms",
                    name, src.count, r.frames, r.width, r.height, r.fps, r.bytes.size / 1e6, r.inBetweens, r.fallbacks, r.tries, ms))
            }
        }
    }
}
