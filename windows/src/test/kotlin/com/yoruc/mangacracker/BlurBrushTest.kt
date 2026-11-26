package com.yoruc.mangacracker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.yoruc.mangacracker.gif.BlurBrush
import com.yoruc.mangacracker.gif.BlurStroke
import com.yoruc.mangacracker.gif.BlurredFrames
import com.yoruc.mangacracker.gif.FrameSource
import com.yoruc.mangacracker.ui.GifEditor
import com.yoruc.mangacracker.ui.GifSource
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The GIF editor's blur brush: the mask, the blur, its place in a crop, and the editor end to end (own test video). */
class BlurBrushTest {
    /** Detail in a box: mean difference between neighbouring pixels (a blur brings it down). */
    private fun detail(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Double {
        var s = 0L; var n = 0
        for (y in y0 until y1) for (x in x0 until x1 - 1) {
            val a = px[y * w + x]; val b = px[y * w + x + 1]
            for (sh in intArrayOf(16, 8, 0)) s += kotlin.math.abs(((a shr sh) and 255) - ((b shr sh) and 255))
            n++
        }
        return s / (n * 3.0)
    }
    /** Hard edges in a box: neighbouring pixels more than 120 apart (the bars' borders; a blur leaves none). */
    private fun sharp(px: IntArray, w: Int, x0: Int, y0: Int, x1: Int, y1: Int): Int {
        var n = 0
        for (y in y0 until y1) for (x in x0 until x1 - 1) {
            val a = px[y * w + x]; val b = px[y * w + x + 1]
            var d = 0
            for (sh in intArrayOf(16, 8, 0)) d += kotlin.math.abs(((a shr sh) and 255) - ((b shr sh) and 255))
            if (d / 3 > 120) n++
        }
        return n
    }
    private fun checker(w: Int, h: Int) = IntArray(w * h) { k -> if (((k % w) / 4 + (k / w) / 4) % 2 == 0) 0xFFFFFF else 0x202020 }

    @Test fun maskAndBlur() {
        val w = 320; val h = 180
        val px = checker(w, h)
        // a stroke across the left half, middle height; an eraser through its middle
        val paint = BlurStroke(floatArrayOf(0.05f, 0.45f), floatArrayOf(0.5f, 0.5f), 0.06f, false)
        val m = BlurBrush.mask(listOf(paint), w, h)!!
        val out = BlurBrush.apply(px, w, h, m, BlurBrush.STRONG)
        val inPaint = detail(out, w, 40, 80, 120, 100); val before = detail(px, w, 40, 80, 120, 100)
        val far = detail(out, w, 220, 10, 300, 40)
        println("[blur] detail in the stroke %.1f -> %.1f, far away %.1f (was %.1f)".format(before, inPaint, far, detail(px, w, 220, 10, 300, 40)))
        assertTrue("blurred where painted", inPaint < before * 0.25)
        assertTrue("untouched far away", (220 until 300).all { x -> (10 until 40).all { y -> out[y * w + x] == px[y * w + x] } })
        val erased = BlurBrush.mask(listOf(paint, BlurStroke(floatArrayOf(0.25f), floatArrayOf(0.5f), 0.04f, true)), w, h)!!
        assertTrue("erase clears the middle", erased[90 * w + 80] < 0.05f && erased[90 * w + 20] > 0.9f)
        assertEquals("nothing painted = no mask", null, BlurBrush.mask(listOf(BlurStroke(floatArrayOf(0.5f), floatArrayOf(0.5f), 0.1f, true)), w, h))
    }

    /** Frames showing the bottom-right quarter: a stroke at (0.75, 0.75) of the whole picture is in their middle. */
    @Test fun inACrop() {
        val w = 160; val h = 90
        val src = object : FrameSource {
            override val count = 2; override val width = w; override val height = h; override val fps = 25.0
            override fun frame(i: Int) = checker(w, h)
        }
        val b = BlurredFrames(src, listOf(BlurStroke(floatArrayOf(0.75f), floatArrayOf(0.75f), 0.04f, false)), floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f), BlurBrush.STRONG)
        val f = b.frame(0); val orig = src.frame(0)
        val mid = detail(f, w, 70, 40, 90, 50); val corner = detail(f, w, 5, 5, 30, 20)
        println("[blur] crop: detail in the middle %.1f (was %.1f), corner %.1f (was %.1f)".format(mid, detail(orig, w, 70, 40, 90, 50), corner, detail(orig, w, 5, 5, 30, 20)))
        assertTrue(mid < detail(orig, w, 70, 40, 90, 50) * 0.3)
        assertEquals(detail(orig, w, 5, 5, 30, 20), corner, 0.001)
    }

    /** The blurred frames themselves (before the GIF engine), as pictures: build/shots/blur_raw_<n>.png. */
    @Test fun rawFrames() = kotlinx.coroutines.runBlocking {
        val clip = File("build/blur_test/pattern.mp4")
        org.junit.Assume.assumeTrue(clip.isFile)
        val f = com.yoruc.mangacracker.platform.ClipRecorder.frames(listOf("-i", clip.absolutePath), 5.0, 6.0, 640, 360, 30.0, 480, "blurraw")
        try {
            val s = listOf(BlurStroke(floatArrayOf(0.05f, 0.95f), floatArrayOf(0.5f, 0.5f), 0.07f, false))
            val b = BlurredFrames(f, s, null, BlurBrush.STRONG)
            File("build/shots").mkdirs()
            for (i in listOf(0, 10)) {
                val px = b.frame(i)
                val img = java.awt.image.BufferedImage(b.width, b.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                img.setRGB(0, 0, b.width, b.height, px, 0, b.width)
                javax.imageio.ImageIO.write(img, "png", File("build/shots/blur_raw_$i.png"))
            }
            println("[blur] raw frames ${b.width}x${b.height}")
        } finally { f.close() }
    }

    /** A real anime scene (Frieren 1 on Anikoto, 2 s at 3:00) with a thick blur stroke, made into a GIF. -Psources=blurreal */
    @Test fun realScene() = kotlinx.coroutines.runBlocking {
        if (System.getProperty("sources") != "blurreal") return@runBlocking
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx); com.yoruc.mangacracker.core.Repo.init(ctx); com.yoruc.mangacracker.video.VideoRepo.init(ctx)
        val src = com.yoruc.mangacracker.video.VideoSources.byId("anikoto")!!
        val show = src.search(com.yoruc.mangacracker.core.SearchQuery(text = "Frieren"), 1).items.first { it.title.contains("Frieren", true) }
        val d = src.details(src.series(show).first { it.episode == "1" })
        val input = com.yoruc.mangacracker.platform.StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
        val f = com.yoruc.mangacracker.platform.ClipRecorder.frames(input, 455.0, 457.0, 1920, 1080, 24.0, 640, "blurreal")
        try {
            val s = listOf(BlurStroke(floatArrayOf(0.3f, 0.7f), floatArrayOf(0.45f, 0.45f), 0.07f, false))
            val b = BlurredFrames(f, s, null, BlurBrush.STRONG)
            File("build/shots").mkdirs()
            val px = b.frame(0)
            val img = java.awt.image.BufferedImage(b.width, b.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
            img.setRGB(0, 0, b.width, b.height, px, 0, b.width)
            javax.imageio.ImageIO.write(img, "png", File("build/shots/blur_real_raw.png"))
            val g = File("build/shots/blur_real.gif").apply { delete() }
            com.yoruc.mangacracker.platform.ClipRecorder.gif(b, com.yoruc.mangacracker.gif.GifOptions(), g) { _, _ -> }
            println("[blur] real scene GIF ${g.length() / 1024} KB")
        } finally { f.close() }
    }

    /** The editor with a made-up video: Blur brush on, a thick stroke, Make GIF; then the same with a crop. */
    @Test fun editor() {
        val dir = File("build/blur_test").apply { mkdirs() }
        val clip = File(dir, "pattern.mp4")
        val ff = com.yoruc.mangacracker.platform.Avif.ffmpeg!!.path
        if (!clip.isFile) ProcessBuilder(ff, "-hide_banner", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=30",
            "-t", "40", "-pix_fmt", "yuv420p", clip.path).start().waitFor()
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx); com.yoruc.mangacracker.core.Repo.init(ctx); com.yoruc.mangacracker.video.VideoRepo.init(ctx)
        com.yoruc.mangacracker.core.Repo.updateSettings { it.copy(clipFolder = File(dir, "gifs").absolutePath) }
        var delete: ((Set<Int>) -> Unit)? = null
        var make: (() -> Unit)? = null
        var made: File? = null
        com.yoruc.mangacracker.ui.gifActionsProbe = { d, m -> delete = d; make = m }
        com.yoruc.mangacracker.ui.gifDoneProbe = { made = it }
        var t = 0L
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            GifEditor(GifSource(listOf("-i", clip.absolutePath), 640, 360, 30.0, 40.0, "blurtest"), 5.0) {}
        }
        fun frames(k: Int, sleep: Long = 30) = repeat(k) { scene.render(t); t += 50_000_000L; Thread.sleep(sleep) }
        fun shot(name: String) { File("build/shots").mkdirs(); File("build/shots/$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes); println("[shot] $name") }
        fun at(name: String) = System.getenv("BLUR_" + name)?.split(',')?.map { it.trim().toFloat() }?.let { Offset(it[0], it[1]) }
        fun click(o: Offset) { scene.sendPointerEvent(PointerEventType.Move, o); scene.sendPointerEvent(PointerEventType.Press, o, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary); frames(1)
            scene.sendPointerEvent(PointerEventType.Release, o, button = PointerButton.Primary); frames(5) }
        fun drag(a: Offset, b: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, a); frames(1)
            scene.sendPointerEvent(PointerEventType.Press, a, buttons = PointerButtons(isPrimaryPressed = true), button = PointerButton.Primary); frames(1)
            for (k in 1..12) { scene.sendPointerEvent(PointerEventType.Move, a + (b - a) * (k / 12f), buttons = PointerButtons(isPrimaryPressed = true)); frames(1) }
            scene.sendPointerEvent(PointerEventType.Release, b, button = PointerButton.Primary); frames(5)
        }
        fun makeGif(): File {
            made = null
            make!!.invoke()
            var waited = 0
            while (made == null && waited < 300) { frames(5, 200); waited++ }
            return made ?: error("no GIF")
        }
        try {
            frames(100, 40)
            shot("blur_0_open")
            // a short GIF: the first 30 frames
            delete!!.invoke((30 until 900).toSet()); frames(10)
            val chip = at("CHIP") ?: return
            click(chip); frames(10)
            shot("blur_1_on")
            // a thick stroke across the preview's middle (BLUR_FROM / BLUR_TO), Thick chosen first
            at("THICK")?.let { click(it); frames(5) }
            drag(at("FROM")!!, at("TO")!!); frames(20)
            shot("blur_2_painted")
            val g = makeGif()
            shot("blur_3_made")
            val img = javax.imageio.ImageIO.read(g)
            val w = img.width; val h = img.height
            val px = IntArray(w * h) { img.getRGB(it % w, it / w) and 0xFFFFFF }
            // the stroke went across the middle: detail there against the top band (not painted)
            val mid = sharp(px, w, w / 4, h / 2 - h / 20, 3 * w / 4, h / 2 + h / 20)
            val top = sharp(px, w, w / 4, h / 2 - h / 4 - h / 20, 3 * w / 4, h / 2 - h / 4 + h / 20)
            println("[blur] GIF ${w}x$h: hard edges along the stroke $mid, in a band above it $top")
            g.copyTo(File("build/shots/blur_gif.gif"), overwrite = true)
            assertTrue("the stroke is blurred in the GIF", top > 20 && mid * 10 < top)
            // with the crop: Crop on (the default box, 80 % in the middle), then make again
            frames(10)
            at("ANOTHER")?.let { click(it); frames(20) }
            val crop = at("CROP") ?: return
            click(crop); frames(10)
            shot("blur_4_crop")
            val g2 = makeGif()
            val img2 = javax.imageio.ImageIO.read(g2)
            val w2 = img2.width; val h2 = img2.height
            val px2 = IntArray(w2 * h2) { img2.getRGB(it % w2, it / w2) and 0xFFFFFF }
            val mid2 = sharp(px2, w2, w2 / 4, h2 / 2 - h2 / 20, 3 * w2 / 4, h2 / 2 + h2 / 20)
            val top2 = sharp(px2, w2, w2 / 4, h2 / 2 - h2 / 4 - h2 / 20, 3 * w2 / 4, h2 / 2 - h2 / 4 + h2 / 20)
            println("[blur] cropped GIF ${w2}x$h2: hard edges along the stroke $mid2, in a band above it $top2")
            g2.copyTo(File("build/shots/blur_gif_crop.gif"), overwrite = true)
            assertTrue("the stroke is blurred in the cropped GIF", top2 > 20 && mid2 * 10 < top2)
        } finally { scene.close(); com.yoruc.mangacracker.ui.gifActionsProbe = null; com.yoruc.mangacracker.ui.gifDoneProbe = null }
    }
}
