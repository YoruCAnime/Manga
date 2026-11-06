package com.yoruc.mangacracker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.yoruc.mangacracker.gif.GifMaker
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.gif.ListFrames
import com.yoruc.mangacracker.gif.PickedFrames
import com.yoruc.mangacracker.platform.Rife
import com.yoruc.mangacracker.ui.GifEditor
import com.yoruc.mangacracker.ui.GifSource
import com.yoruc.mangacracker.ui.fillBetween
import com.yoruc.mangacracker.ui.framesByNumber
import com.yoruc.mangacracker.ui.gifFramesProbe
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** A Java key event as the window would hand it to Compose (Compose's own converter, internal, so by reflection). */
private val toCompose = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java)
private fun java.awt.event.KeyEvent.asCompose() = androidx.compose.ui.input.key.KeyEvent(toCompose.invoke(null, this)!!)

/** The GIF editor's frame list: picking by number / runs, frames left out, no in-betweens across a deleted gap. */
class GifFramesTest {
    @Test fun numbersAndRuns() {
        val gone = setOf(13, 14, 18, 19)                          // window frames deleted before (numbers 14, 15, 19, 20)
        assertEquals(setOf(10, 11, 12, 15), framesByNumber(30, gone, 11, 16))   // numbers 11-16 = frames 10-15, minus deleted
        assertEquals(setOf(10, 11, 12, 15), framesByNumber(30, gone, 16, 11))   // either order
        assertEquals(setOf(28, 29), framesByNumber(30, gone, 29, 99))           // past the end: up to the last
        assertEquals(setOf(0), framesByNumber(30, gone, 0, 1))
        assertEquals(emptySet<Int>(), framesByNumber(30, gone, 40, 50))        // wholly past the end: nothing (not the last frame)
        assertEquals(emptySet<Int>(), framesByNumber(30, gone, 0, 0))
        assertEquals(setOf(12, 15, 16, 17, 20), fillBetween(setOf(12, 20), gone))
        assertEquals(setOf(7), fillBetween(setOf(7), gone))
    }

    @Test fun bigFramesByTime() {
        // window at 10 s, 24 fps; GIF from window frame 24 (11 s); full-size frames at 48 fps (two per window frame)
        val keep = com.yoruc.mangacracker.ui.keptBigFrames(12, 48.0, 11.0, 10.0, 24.0, setOf(26, 27))
        assertEquals(listOf(0, 1, 2, 3, 8, 9, 10, 11), keep.toList())
        assertEquals("0:11.04", com.yoruc.mangacracker.ui.clockC(11.0417))
    }

    @Test fun pickedFramesGaps() {
        val src = ListFrames(List(10) { IntArray(4) { _ -> it } }, 2, 2, 24.0)
        val p = PickedFrames(src, intArrayOf(0, 1, 2, 6, 7, 9))
        assertEquals(6, p.count)
        assertEquals(6, p.frame(3)[0])
        assertEquals(listOf(false, false, false, true, false, true), (0 until 6).map { p.gapBefore(it) })
    }

    /** A square gliding over a gradient: smooth motion, so the only places with no in-betweens are the deleted gaps. */
    private fun glide(n: Int): ListFrames {
        val w = 160; val h = 90
        return ListFrames(List(n) { f ->
            IntArray(w * h) { k ->
                val x = k % w; val y = k / w
                val sx = 10 + f * 2; val sy = 30
                if (x in sx until sx + 24 && y in sy until sy + 24) 0xF0C030
                else ((x * 255 / w) shl 16) or ((y * 255 / h) shl 8) or 0x40
            }
        }, w, h, 24.0)
    }

    @Test fun noInBetweensAcrossAGap() {
        val src = glide(48)
        val keep = ((0 until 48) - (20 until 28)).toIntArray()     // 8 frames deleted in the middle
        for (useRife in listOf(false, true)) {
            if (useRife && !Rife.available) continue
            val opts = GifOptions(speed = 0.5f, size = GifSize.SMALL, fps = 24)
            val whole = GifMaker(src, opts).apply { drawer = if (useRife) Rife.open() else null }.make()
            val cut = GifMaker(PickedFrames(src, keep), opts).apply { drawer = if (useRife) Rife.open() else null }.make()
            println("[frames] ${if (useRife) "RIFE" else "built-in"}: whole ${whole.frames} frames, ${whole.inBetweens} in-betweens, " +
                "${whole.fallbacks} real-frame fall-backs; 8 deleted: ${cut.frames} frames, ${cut.inBetweens} in-betweens, ${cut.fallbacks} fall-backs")
            assertEquals("smooth motion: no fall-backs without a gap", 0, whole.fallbacks)
            assertTrue("the gap gives real frames, not in-betweens", cut.fallbacks > 0)
            assertTrue("shorter GIF", cut.durationMs < whole.durationMs)
        }
    }

    /** Average colour difference of two frames (b's size may differ). */
    private fun diff(a: IntArray, aw: Int, ah: Int, b: IntArray, bw: Int, bh: Int): Double {
        var sum = 0L; var n = 0
        for (y in 0 until ah step 4) for (x in 0 until aw step 4) {
            val p = a[y * aw + x]; val q = b[(y * bh / ah) * bw + (x * bw / aw)]
            sum += kotlin.math.abs((p shr 16 and 255) - (q shr 16 and 255)) + kotlin.math.abs((p shr 8 and 255) - (q shr 8 and 255)) + kotlin.math.abs((p and 255) - (q and 255))
            n += 3
        }
        return sum.toDouble() / n
    }

    /**
     * What's left in the row is what's in the GIF: frames deleted before, after and in the middle; every GIF frame must look
     * like a kept frame near its place, the first like the first kept, the last like the last kept.
     */
    private fun madeFromKept(name: String, input: List<String>, w: Int, h: Int, dur: Double, at: Double) {
        var kept = emptyList<Int>()
        var del: ((Set<Int>) -> Unit)? = null; var make: (() -> Unit)? = null
        var saved: File? = null
        gifFramesProbe = { k, _, _ -> kept = k }
        com.yoruc.mangacracker.ui.gifActionsProbe = { d, m -> del = d; make = m }
        com.yoruc.mangacracker.ui.gifDoneProbe = { saved = it }
        var t = 0L
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            GifEditor(GifSource(input, w, h, 24.0, dur, "test $name"), at) {}
        }
        fun frames(k: Int, sleep: Long = 30) = repeat(k) { scene.render(t); t += 50_000_000L; Thread.sleep(sleep) }
        try {
            var waited = 0
            while (kept.isEmpty() && waited++ < 1200) frames(1, 50)
            assertTrue("$name: frames read", kept.isNotEmpty())
            val n = kept.size
            println("[frames] $name: row of $n frames")
            val first = n / 4; val last = n * 3 / 4; val gapA = n / 2 - 5; val gapB = n / 2 + 5
            del!!((0 until first).toSet()); frames(3)
            del!!((last + 1 until n).toSet()); frames(3)
            del!!((gapA..gapB).toSet()); frames(3)
            val want = (first..last) - (gapA..gapB)
            assertEquals("$name: kept", want, kept)
            make!!()
            waited = 0
            while (saved == null && waited++ < 2400) frames(1, 50)
            val gif = saved
            assertTrue("$name: GIF made", gif != null && gif.isFile)
            val row = kotlinx.coroutines.runBlocking {
                com.yoruc.mangacracker.platform.ClipRecorder.frames(input, at, (at + 30.0).coerceAtMost(dur), w, h, 24.0, 480, "check")
            }
            try {
                val codec = org.jetbrains.skia.Codec.makeFromData(org.jetbrains.skia.Data.makeFromBytes(gif!!.readBytes()))
                val bmp = org.jetbrains.skia.Bitmap().apply { allocN32Pixels(codec.width, codec.height) }
                fun gifFrame(i: Int): IntArray {
                    codec.readPixels(bmp, i, if (i > 0) i - 1 else -1)
                    val px = bmp.readPixels(org.jetbrains.skia.ImageInfo.makeN32(codec.width, codec.height, org.jetbrains.skia.ColorAlphaType.PREMUL), codec.width * 4, 0, 0)!!
                    return IntArray(codec.width * codec.height) { k ->
                        val o = k * 4
                        ((px[o + 2].toInt() and 255) shl 16) or ((px[o + 1].toInt() and 255) shl 8) or (px[o].toInt() and 255)
                    }
                }
                // walk through the GIF: replay frame by frame so each picture is complete
                val pics = ArrayList<IntArray>()
                for (i in 0 until codec.frameCount) pics += gifFrame(i)
                fun best(pic: IntArray, among: List<Int>) = among.minByOrNull { diff(row.frame(it), row.width, row.height, pic, codec.width, codec.height) }!!
                val all = (0 until row.count).toList()
                val b0 = best(pics.first(), all); val b1 = best(pics.last(), all)
                println("[frames] $name: kept #${want.first() + 1}..#${want.last() + 1} without #${gapA + 1}..#${gapB + 1}, GIF ${codec.frameCount} frames: first looks like #${b0 + 1}, last like #${b1 + 1}")
                // the frame that should be there (+-2: the two readings can be that far apart), against the nearest in the whole row
                fun near(pic: IntArray, i: Int) = ((i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(row.count - 1)).minOf { diff(row.frame(it), row.width, row.height, pic, codec.width, codec.height) }
                fun dist(pic: IntArray, i: Int) = diff(row.frame(i), row.width, row.height, pic, codec.width, codec.height)
                val s0 = near(pics.first(), want.first()); val s1 = near(pics.last(), want.last())
                println("[frames] $name: difference at the first kept frame %.1f (nearest %.1f), at the last %.1f (nearest %.1f)".format(java.util.Locale.US, s0, dist(pics.first(), b0), s1, dist(pics.last(), b1)))
                assertTrue("$name: starts on the first kept frame", s0 <= dist(pics.first(), b0) + 3.0)
                assertTrue("$name: ends on the last kept frame", s1 <= dist(pics.last(), b1) + 3.0)
                assertEquals("$name: one GIF frame per kept frame", want.size, pics.size)
                // each GIF frame against the kept frame at its own place; only if that's far off, against the whole row
                var worst = 0.0; var wrong = 0
                pics.forEachIndexed { k, pic ->
                    val own = listOf(k - 1, k, k + 1).filter { it in want.indices }.minOf { near(pic, want[it]) }
                    if (own > worst) worst = own
                    if (own > 4.0 && own > all.minOf { dist(pic, it) } + 3.0) {
                        wrong++
                        val w0 = want[k]; val b = best(pic, all)
                        val around = (w0 - 6..w0 + 6).filter { it in 0 until row.count }.joinToString(" ") { "#${it + 1}:%.0f".format(dist(pic, it)) }
                        val prev = if (k > 0) "%.1f".format(diff(pics[k - 1], codec.width, codec.height, pic, codec.width, codec.height)) else "-"
                        println("[frames] $name: GIF frame ${k + 1} should be #${w0 + 1}, difference %.1f; nearest #${b + 1} %.1f; to the GIF frame before $prev; around: $around".format(java.util.Locale.US, own, dist(pic, b)))
                        val out = java.awt.image.BufferedImage(codec.width, codec.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                        out.setRGB(0, 0, codec.width, codec.height, pic, 0, codec.width)
                        File("build/shots").mkdirs(); javax.imageio.ImageIO.write(out, "png", File("build/shots/gif_off_${name}_gif.png"))
                        val o2 = java.awt.image.BufferedImage(row.width, row.height, java.awt.image.BufferedImage.TYPE_INT_RGB)
                        o2.setRGB(0, 0, row.width, row.height, row.frame(w0), 0, row.width)
                        javax.imageio.ImageIO.write(o2, "png", File("build/shots/gif_off_${name}_row.png"))
                    }
                }
                println("[frames] $name: ${pics.size} GIF frames against their own kept frames: worst difference %.1f, wrong frames $wrong".format(java.util.Locale.US, worst))
                assertEquals("$name: every GIF frame is its own kept frame", 0, wrong)
            } finally { row.close(); saved?.delete() }
        } finally {
            scene.close(); gifFramesProbe = null; com.yoruc.mangacracker.ui.gifActionsProbe = null; com.yoruc.mangacracker.ui.gifDoneProbe = null
        }
    }

    @Test fun whatsLeftIsTheGif() {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        madeFromKept("clip", listOf("-i", clip.absolutePath), 640, 360, 30.0, 12.0)
    }

    /** The same on live videos: -Psources=anikoto,hentaigasm */
    @Test fun whatsLeftIsTheGifLive() = kotlinx.coroutines.runBlocking {
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }
        assumeTrue(pick.isNotEmpty())
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx); com.yoruc.mangacracker.core.Repo.init(ctx); com.yoruc.mangacracker.video.VideoRepo.init(ctx)
        for (id in pick) {
            val src = com.yoruc.mangacracker.video.VideoSources.byId(id)!!
            val d = src.details(src.latest(1).items.first())
            src.beforePlay()
            val input = com.yoruc.mangacracker.platform.StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
            madeFromKept(id, input, 1280, 720, 600.0, 100.0)
        }
    }

    /** The real clicks: pick frames, every delete button, Select between, typed numbers, Undo, Restore all. */
    @Test fun panel() {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        var kept = emptyList<Int>(); var deleted = emptySet<Int>(); var sel = emptySet<Int>(); var head = 0
        gifFramesProbe = { k, d, s -> kept = k; deleted = d; sel = s }
        com.yoruc.mangacracker.ui.gifHeadProbe = { h -> head = h }
        var t = 0L
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            GifEditor(GifSource(listOf("-i", clip.absolutePath), 640, 360, 24.0, 30.0, "test"), 12.0) {}
        }
        fun frames(k: Int, sleep: Long = 30) = repeat(k) { scene.render(t); t += 50_000_000L; Thread.sleep(sleep) }
        fun shot(name: String) { File("build/shots").mkdirs(); File("build/shots/$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        // an off-screen scene counts as an unfocused window and drops key presses: mark it focused, as a real window is
        val info = ImageComposeScene::class.java.getDeclaredField("_windowInfo").apply { isAccessible = true }.get(scene)
        info.javaClass.getMethod("setWindowFocused", Boolean::class.javaPrimitiveType).invoke(info, true)
        try {
            frames(200, 50)
            shot("gif_frames_open")
            println("[frames] open: ${kept.size} kept, first ${kept.firstOrNull()}")
            assertEquals("opens on the paused frame: the rest of the 30 s clip (18 s), all kept", 432, kept.size)
            assertEquals(0, kept.first())
            // layout at 1280 x 720: the buttons at y 471 (the first always at x 90), number boxes at y 476, boxes at y 535, the
            // k-th visible one centred at 78 + (k - 1) x 116, its ✕ 40 px right of that at y 515
            fun card(k: Int) = Offset(78f + (k - 1) * 116f, 535f)
            fun cross(k: Int) = Offset(78f + (k - 1) * 116f + 40f, 515f)
            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, at); frames(1)
                scene.sendPointerEvent(PointerEventType.Release, at); frames(5)
            }
            val comp = java.awt.Canvas()
            fun key(code: Int, ctrl: Boolean = false) {
                val mods = if (ctrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0
                for (type in listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED))
                    scene.sendKeyEvent(java.awt.event.KeyEvent(comp, type, 0, mods, code, java.awt.event.KeyEvent.CHAR_UNDEFINED).asCompose())
                frames(3)
            }
            fun typeText(s: String) { for (c in s) { scene.sendKeyEvent(java.awt.event.KeyEvent(comp, java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, c).asCompose()); frames(1) } }
            val n = kept.size

            // click two frames, Select between, delete the run in the middle: the GIF is everything else
            click(card(5)); click(card(8))
            assertEquals(setOf(4, 7), sel)
            shot("gif_frames_picked")
            click(Offset(457f, 471f))                            // Select between
            assertEquals(setOf(4, 5, 6, 7), sel)
            click(Offset(216f, 471f))                            // Delete 4 picked
            println("[frames] Select between + Delete: deleted ${deleted.sorted()}, ${kept.size} kept")
            assertEquals(setOf(4, 5, 6, 7), deleted); assertTrue(sel.isEmpty()); assertEquals(n - 4, kept.size)
            shot("gif_frames_middle")
            // the ✕ on a box
            click(cross(2))
            assertEquals(setOf(1, 4, 5, 6, 7), deleted)
            // Ctrl+Z twice: everything back
            key(java.awt.event.KeyEvent.VK_Z, ctrl = true); key(java.awt.event.KeyEvent.VK_Z, ctrl = true)
            assertTrue("Ctrl+Z", deleted.isEmpty())

            // boxes clicked just to look at them: the cut goes from the one clicked LAST, not the earliest picked
            click(card(2)); click(card(6)); click(card(4)); assertEquals(setOf(1, 5, 3), sel); assertEquals(3, head)
            click(Offset(90f, 471f))                             // Delete all before #4
            println("[frames] looked at #2, #6, then #4, Delete all before: deleted ${deleted.sorted()}, picks ${sel.sorted()}")
            assertEquals(setOf(0, 1, 2), deleted); assertTrue("picks cleared", sel.isEmpty())
            key(java.awt.event.KeyEvent.VK_Z, ctrl = true)
            assertTrue(deleted.isEmpty())

            // both ends: click a frame, Delete all before; then Delete all after a later one
            click(card(3)); val s = sel.single(); assertEquals(s, head)   // (the row may sit a few frames along by now)
            click(Offset(90f, 471f))                             // Delete all before #3
            assertEquals((0 until s).toSet(), deleted); assertEquals(s, kept.first())
            click(Offset(300f, 471f))                            // Delete all after #3
            println("[frames] both ends: ${kept.size} kept, ${kept.firstOrNull()}..${kept.lastOrNull()}")
            assertEquals(listOf(s), kept)
            key(java.awt.event.KeyEvent.VK_Z, ctrl = true); key(java.awt.event.KeyEvent.VK_Z, ctrl = true)
            assertEquals(n, kept.size)

            // Delete frames [20] to [30]
            click(Offset(662f, 476f)); frames(20); typeText("20"); click(Offset(750f, 476f)); frames(20); typeText("30"); frames(10); click(Offset(814f, 476f))
            println("[frames] typed 20 to 30: deleted ${deleted.sorted()}")
            assertEquals((19..29).toSet(), deleted); assertEquals(n - 11, kept.size)
            shot("gif_frames_final")

            // the Delete key: what's picked (the one picked above), then the shown frame
            val h = head
            key(java.awt.event.KeyEvent.VK_DELETE)
            println("[frames] Delete key: shown #${h + 1} deleted: ${h in deleted}")
            assertTrue(h in deleted)
        } finally {
            scene.close(); gifFramesProbe = null; com.yoruc.mangacracker.ui.gifHeadProbe = null
        }
    }
}
