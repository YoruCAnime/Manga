package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.cutout.Cutter
import com.yoruc.mangacracker.cutout.Mask
import com.yoruc.mangacracker.cutout.OrtNets
import com.yoruc.mangacracker.cutout.Panels
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Character cutout.
 *  - rules(): the panel finder and the mask tools on made-up pictures (offline, runs on CI).
 *  - models(): the app's whole pipeline with the real models (desktop ONNX Runtime) on study pages, compared with the
 *    study's own result. Needs -PcutoutData=<folder with parity/ (pages, taps, reference alphas) and app_models/>.
 */
class CutoutTest {
    private fun log(s: String) = println("[cutout] $s")

    @Test fun rules() {
        // a page: white, two panels split by a white gutter, black frames; a manhwa-like strip of three panels
        val w = 400; val h = 600
        val g = IntArray(w * h) { 255 }
        fun box(x0: Int, y0: Int, x1: Int, y1: Int, v: Int) { for (y in y0 until y1) for (x in x0 until x1) g[y * w + x] = v }
        box(20, 20, 380, 280, 0); box(24, 24, 376, 276, 128)    // panel 1 (frame + grey art)
        box(20, 300, 380, 580, 0); box(24, 304, 376, 576, 90)   // panel 2
        val p = Panels.find(g, w, h, 200, 400)
        assertTrue("panel 2 found: $p", p.y0 in 295..306 && p.y1 in 574..590 && p.x0 in 15..26 && p.x1 in 374..385)
        val q = Panels.find(g, w, h, 100, 100)
        assertTrue("panel 1 found: $q", q.y0 in 15..26 && q.y1 in 270..285)
        // art right across the page (no gutters): the whole page
        val full = Panels.find(IntArray(w * h) { (it * 7919) % 256 }, w, h, 200, 300)
        assertEquals(Panels.Box(0, 0, w, h), full)

        // components / distance / inner point
        val m = FloatArray(100 * 100)
        for (y in 10 until 40) for (x in 10 until 40) m[y * 100 + x] = 1f
        for (y in 60 until 90) for (x in 50 until 95) m[y * 100 + x] = 1f
        val (lab, sizes) = Mask.components(m, 100, 100)
        assertEquals(3, sizes.size)
        assertEquals(900, sizes[lab[20 * 100 + 20]])
        val inside = BooleanArray(m.size) { lab[it] == lab[70 * 100 + 70] }
        val d = Mask.distanceTo(inside, 100, 100)
        assertEquals(0f, d[70 * 100 + 70], 0f)
        assertEquals(10f, d[70 * 100 + 40], 0.6f)
        val (ix, iy) = Mask.innerPoint(inside, 100, 100)
        assertTrue("inner point $ix,$iy", ix in 60..85 && iy in 70..80)
        val r = Mask.resize(floatArrayOf(0f, 1f, 2f, 3f), 2, 2, 4, 4)
        assertEquals(0f, r[0], 1e-4f); assertEquals(3f, r[15], 1e-4f)

        // brush: a stroke from (10, 50) to (90, 50), radius 5, erases a band and nothing else
        val a = FloatArray(100 * 100) { 1f }
        Mask.paint(a, 100, 100, floatArrayOf(10f, 50f, 90f, 50f), 5f, 0f)
        assertEquals(0f, a[50 * 100 + 50], 0f)
        assertEquals(0f, a[54 * 100 + 30], 0f)
        assertEquals(1f, a[58 * 100 + 50], 0f)
        assertEquals(1f, a[50 * 100 + 97], 0f)
        // a single dot, then the same spot brought back
        Mask.paint(a, 100, 100, floatArrayOf(20f, 20f), 3f, 0f)
        assertEquals(0f, a[20 * 100 + 20], 0f)
        Mask.paint(a, 100, 100, floatArrayOf(20f, 20f), 3f, 1f)
        assertEquals(1f, a[20 * 100 + 20], 0f)
    }

    @Test fun models() {
        val dir = System.getProperty("cutoutData").orEmpty()
        assumeTrue("needs -PcutoutData", dir.isNotEmpty() && File(dir, "parity/cases.json").exists())
        val out = File(dir, "kotlin").apply { mkdirs() }
        val cases = Http.json.parseToJsonElement(File(dir, "parity/cases.json").readText()).jsonArray
        OrtNets(File(dir, "app_models"), threads = 1).use { nets ->
            val cutter = Cutter(nets)
            for (c in cases) {
                val o = c.jsonObject
                val i = o["case"]!!.jsonPrimitive.int
                val name = "case%02d".format(i)
                val pw = o["w"]!!.jsonPrimitive.int; val ph = o["h"]!!.jsonPrimitive.int
                val raw = File(dir, "parity/$name.rgb").readBytes()
                val px = IntArray(pw * ph) { i ->
                    (0xFF shl 24) or ((raw[3 * i].toInt() and 255) shl 16) or ((raw[3 * i + 1].toInt() and 255) shl 8) or (raw[3 * i + 2].toInt() and 255)
                }
                val t0 = System.currentTimeMillis()
                val job = cutter.start(px, pw, ph, o["x"]!!.jsonPrimitive.int, o["y"]!!.jsonPrimitive.int)
                val a = job.alpha()
                val ms = System.currentTimeMillis() - t0
                val box = o["box"]!!.jsonArray.map { it.jsonPrimitive.int }
                assertEquals("$name panel", box, listOf(job.box.x0, job.box.y0, job.box.x1, job.box.y1))
                val ref = File(dir, "parity/${name}_alpha.a8").readBytes()
                val refA = FloatArray(job.w * job.h) { (ref[it].toInt() and 255) / 255f }
                val iou = Mask.iou(BooleanArray(a.size) { a[it] > 0.5f }, BooleanArray(a.size) { refA[it] > 0.5f })
                log("$name ${job.w}x${job.h} IoU with the study $iou, ${ms} ms (1 thread, PC)")
                // the cutout over a grey checkerboard, to look at
                job.render(a)?.let { (pix, w, h) ->
                    val shown = IntArray(w * h) { i ->
                        val al = (pix[i] ushr 24) / 255f
                        val bg = if (((i % w) / 16 + (i / w) / 16) % 2 == 0) 200 else 240
                        fun ch(sh: Int) = ((((pix[i] shr sh) and 255) * al) + bg * (1 - al)).toInt()
                        (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
                    }
                    GifTestIo.png(shown, w, h, File(out, "${name}_cutout.png"))
                }
                assertTrue("$name matches the study (IoU $iou)", iou >= 0.95f)
            }
        }
    }
}
