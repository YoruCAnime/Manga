package com.yoruc.mangacracker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.ui.CROP_B
import com.yoruc.mangacracker.ui.CROP_L
import com.yoruc.mangacracker.ui.CROP_MOVE
import com.yoruc.mangacracker.ui.CROP_R
import com.yoruc.mangacracker.ui.CROP_T
import com.yoruc.mangacracker.ui.GifEditor
import com.yoruc.mangacracker.ui.GifSource
import com.yoruc.mangacracker.ui.cropHit
import com.yoruc.mangacracker.ui.dragCrop
import com.yoruc.mangacracker.ui.startCrop
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** The GIF editor's crop: the box (shapes, drags, edges), ffmpeg's cut, and the editor driven by clicks. */
class GifCropTest {
    private val W = 1600f
    private val H = 900f
    private fun inside(c: ClipRecorder.Crop) = c.x >= 0f && c.y >= 0f && c.x + c.w <= 1.0001f && c.y + c.h <= 1.0001f
    private fun shapeOf(c: ClipRecorder.Crop) = c.w * W / (c.h * H)

    @Test fun box() {
        // shapes start as big as the picture allows, centred
        for ((_, s) in com.yoruc.mangacracker.ui.CROP_SHAPES.drop(1)) {
            val c = startCrop(s, W / H)
            assertTrue(inside(c)); assertEquals(s, shapeOf(c), 0.01f)
            assertTrue("fills one side", c.w > 0.99f || c.h > 0.99f)
        }
        val free = startCrop(0f, W / H)
        assertEquals(ClipRecorder.Crop(0.1f, 0.1f, 0.8f, 0.8f), free)
        // what the mouse is on
        assertEquals(CROP_L or CROP_T, cropHit(free, 160f, 90f, W, H, 12f))
        assertEquals(CROP_R, cropHit(free, 1440f, 450f, W, H, 12f))
        assertEquals(CROP_MOVE, cropHit(free, 800f, 450f, W, H, 12f))
        assertEquals(0, cropHit(free, 50f, 50f, W, H, 12f))
        // moving stops at the picture's edge
        val moved = dragCrop(free, CROP_MOVE, 5000f, -5000f, W, H, 0f)
        assertEquals(0.2f, moved.x, 0.001f); assertEquals(0f, moved.y, 0.001f); assertEquals(0.8f, moved.w, 0.001f)
        // free corners / edges; never smaller than 6 % or outside
        val grown = dragCrop(free, CROP_R or CROP_B, 2000f, 2000f, W, H, 0f)
        assertEquals(0.9f, grown.w, 0.001f); assertEquals(0.9f, grown.h, 0.001f)
        val tiny = dragCrop(free, CROP_L, 5000f, 0f, W, H, 0f)
        assertTrue(tiny.w * W >= 0.06f * H - 1); assertTrue(inside(tiny))
        // a shape is kept for every pull, and the box stays inside
        for ((_, s) in com.yoruc.mangacracker.ui.CROP_SHAPES.drop(1)) {
            val c0 = startCrop(s, W / H).let { dragCrop(it, CROP_L or CROP_T, 200f, 120f, W, H, s) }
            for (what in listOf(CROP_L, CROP_R, CROP_T, CROP_B, CROP_L or CROP_T, CROP_R or CROP_B, CROP_R or CROP_T, CROP_L or CROP_B, CROP_MOVE))
                for ((dx, dy) in listOf(-3000f to -3000f, -80f to 30f, 40f to -90f, 3000f to 3000f, 300f to -10f)) {
                    val c = dragCrop(c0, what, dx, dy, W, H, s)
                    assertTrue("inside: $s $what $dx $dy $c", inside(c))
                    assertEquals("shape: $s $what $dx $dy $c", s, shapeOf(c), 0.02f * s)
                }
        }
    }

    /** ffmpeg's cut = that part of the uncropped picture (the bottom-right quarter of build/rife_test/clip.mp4, 640 x 360). */
    @Test fun ffmpegCut() = runBlocking {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        val input = listOf("-i", clip.absolutePath)
        val whole = ClipRecorder.frames(input, 5.0, 5.5, 640, 360, 30.0, 640, "croptest")
        val part = ClipRecorder.frames(input, 5.0, 5.5, 640, 360, 30.0, 320, "croptest", crop = ClipRecorder.Crop(0.5f, 0.5f, 0.5f, 0.5f))
        val sq = ClipRecorder.frames(input, 5.0, 5.5, 640, 360, 30.0, 480, "croptest", crop = startCrop(1f, 16f / 9))
        try {
            println("[crop] whole ${whole.width}x${whole.height}, quarter ${part.width}x${part.height}, square ${sq.width}x${sq.height}, frames ${whole.count}/${part.count}")
            assertEquals(320, part.width); assertEquals(180, part.height)
            assertEquals(sq.width, sq.height)
            val a = whole.frame(0); val b = part.frame(0)
            var diff = 0L; var far = 0L
            for (y in 0 until 180) for (x in 0 until 320) {
                val p = a[(y + 180) * 640 + x + 320]; val q = b[y * 320 + x]
                for (sh in intArrayOf(16, 8, 0)) diff += kotlin.math.abs(((p shr sh) and 255) - ((q shr sh) and 255))
                val o = a[y * 640 + x]     // the top-left quarter, for scale
                for (sh in intArrayOf(16, 8, 0)) far += kotlin.math.abs(((o shr sh) and 255) - ((q shr sh) and 255))
            }
            val mean = diff / (320.0 * 180 * 3); val other = far / (320.0 * 180 * 3)
            println("[crop] mean difference to the uncropped quarter %.2f (another quarter: %.2f)".format(mean, other))
            assertTrue("the cut is that quarter", mean < 4.0 && mean < other / 3)
        } finally { whole.close(); part.close(); sq.close() }
    }

    /** The editor: Crop on, 1:1, the box dragged, then a GIF made: square, from the box's part. */
    @Test fun editor() {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx); com.yoruc.mangacracker.core.Repo.init(ctx); com.yoruc.mangacracker.video.VideoRepo.init(ctx)
        // the GIF goes into build/, never the real Videos folder
        com.yoruc.mangacracker.core.Repo.updateSettings { it.copy(clipFolder = File("build/crop_gifs").absolutePath) }
        var make: (() -> Unit)? = null
        var made: File? = null
        com.yoruc.mangacracker.ui.gifActionsProbe = { _, m -> make = m }
        com.yoruc.mangacracker.ui.gifDoneProbe = { made = it }
        var t = 0L
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            GifEditor(GifSource(listOf("-i", clip.absolutePath), 640, 360, 30.0, 30.0, "croptest"), 20.0) {}
        }
        fun frames(k: Int, sleep: Long = 30) = repeat(k) { scene.render(t); t += 50_000_000L; Thread.sleep(sleep) }
        fun shot(name: String) { File("build/shots").mkdirs(); File("build/shots/$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes); println("[shot] $name") }
        fun at(p: String) = System.getProperty(p)?.split(',')?.map { it.trim().toFloat() }?.let { Offset(it[0], it[1]) }
        fun click(o: Offset) { scene.sendPointerEvent(PointerEventType.Press, o); frames(1); scene.sendPointerEvent(PointerEventType.Release, o); frames(5) }
        fun drag(a: Offset, b: Offset) {
            scene.sendPointerEvent(PointerEventType.Move, a); frames(1)
            scene.sendPointerEvent(PointerEventType.Press, a); frames(1)
            for (k in 1..8) { scene.sendPointerEvent(PointerEventType.Move, a + (b - a) * (k / 8f)); frames(1) }
            scene.sendPointerEvent(PointerEventType.Release, b); frames(5)
        }
        try {
            frames(120, 40)
            shot("gif_crop_0_open")
            // the clicks' places (from the pictures): -Pcrop.chip=x,y -Pcrop.square=x,y; the drags from the middle of the picture
            val chip = at("crop.chip") ?: return
            click(chip); frames(10)
            shot("gif_crop_1_free")
            // free box: its bottom-right corner pulled in, then the box moved left
            val br = at("crop.corner") ?: return
            drag(br, br - Offset(180f, 90f)); shot("gif_crop_2_corner")
            val mid = at("crop.middle") ?: return
            drag(mid, mid - Offset(150f, 0f)); shot("gif_crop_3_moved")
            val square = at("crop.square") ?: return
            click(square); frames(10); shot("gif_crop_4_square")
            drag(mid, mid + Offset(200f, 0f)); shot("gif_crop_5_square_moved")
            // a new box drawn outside the old one
            val outside = at("crop.outside") ?: return
            click(at("crop.free")!!); frames(5)
            drag(outside, outside + Offset(160f, 120f)); shot("gif_crop_6_new_box")
            click(square); frames(5)
            make!!.invoke()
            var waited = 0
            while (made == null && waited < 240) { frames(5, 200); waited++ }
            shot("gif_crop_7_made")
            val g = made ?: error("no GIF")
            val img = javax.imageio.ImageIO.read(g)
            println("[crop] GIF ${img.width}x${img.height}, ${g.length() / 1024} KB: $g")
            assertEquals("a square GIF", img.width, img.height)
            g.copyTo(File("build/shots/gif_crop_square.gif"), overwrite = true)
        } finally { scene.close(); com.yoruc.mangacracker.ui.gifActionsProbe = null; com.yoruc.mangacracker.ui.gifDoneProbe = null }
    }
}
