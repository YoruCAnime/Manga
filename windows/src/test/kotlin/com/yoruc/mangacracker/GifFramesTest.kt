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

    /** The editor with the frame list open: click a frame, Shift+click a run, Delete, Undo (build/rife_test/clip.mp4). */
    @Test fun panel() {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        var kept = emptyList<Int>(); var deleted = emptySet<Int>(); var sel = emptySet<Int>(); var a = 0; var b = 0; var head = 0
        gifFramesProbe = { k, d, s -> kept = k; deleted = d; sel = s }
        com.yoruc.mangacracker.ui.gifRangeProbe = { na, nb, h -> a = na; b = nb; head = h }
        var t = 0L
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            GifEditor(GifSource(listOf("-i", clip.absolutePath), 640, 360, 24.0, 30.0, "test"), 20.0) {}
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
            if (File("build/layout_only").exists()) return
            // layout (gif_frames_boxes.png): row 1 at y 435 - Set Start 53, Set End 120, Delete all before 238, Delete all
            // after 394, Undo 1154, Restore all 1220; row 2 at y 476 - first # 144, last # 232, Delete 296, Pick only 361,
            // Select between 642, All 714, None 760, Delete picked 836; frame squares at y 545, the k-th visible one
            // centred at 78 + (k - 1) x 116, its ✕ 40 px right of that at y 515; the strip x 85..1202 at y 376
            fun btn(x: Float) = Offset(x, 476f)
            fun top(x: Float) = Offset(x, 435f)
            fun card(k: Int) = Offset(78f + (k - 1) * 116f, 545f)
            fun cross(k: Int) = Offset(78f + (k - 1) * 116f + 40f, 515f)
            fun click(at: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, at); frames(1)
                scene.sendPointerEvent(PointerEventType.Release, at); frames(5)
            }
            fun drag(from: Offset, to: Offset) {
                scene.sendPointerEvent(PointerEventType.Press, from); frames(1)
                for (k in 1..8) { scene.sendPointerEvent(PointerEventType.Move, Offset(from.x + (to.x - from.x) * k / 8, from.y)); frames(1) }
                scene.sendPointerEvent(PointerEventType.Release, to); frames(5)
            }
            val comp = java.awt.Canvas()
            fun key(code: Int, ctrl: Boolean = false) {
                val mods = if (ctrl) java.awt.event.InputEvent.CTRL_DOWN_MASK else 0
                for (type in listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED))
                    scene.sendKeyEvent(java.awt.event.KeyEvent(comp, type, 0, mods, code, java.awt.event.KeyEvent.CHAR_UNDEFINED).asCompose())
                frames(3)
            }
            fun typeText(s: String) { for (c in s) { scene.sendKeyEvent(java.awt.event.KeyEvent(comp, java.awt.event.KeyEvent.KEY_TYPED, 0, 0, java.awt.event.KeyEvent.VK_UNDEFINED, c).asCompose()); frames(1) }; frames(4) }
            val a0 = a; val b0 = b

            // a click picks / unpicks; sliding across squares picks the run
            click(card(5)); val f1 = head
            assertEquals("a click picks and shows", setOf(f1), sel)
            click(card(5))
            assertTrue("clicking it again unpicks", sel.isEmpty())
            drag(card(5), card(8))
            println("[frames] dragged across squares 5-8: picked ${sel.sorted()}")
            assertEquals((f1..f1 + 3).toSet(), sel)
            shot("gif_frames_picked")
            click(btn(836f))                                    // Delete picked
            println("[frames] Delete picked: deleted ${deleted.sorted()}, start ${a + 1} end ${b + 1}")
            assertEquals((f1..f1 + 3).toSet(), deleted); assertTrue(sel.isEmpty()); assertEquals(a0, a)
            click(top(1154f))                                    // Undo
            assertTrue("Undo brings them back", deleted.isEmpty())

            // the ✕ on the START square: that frame goes, Start moves to the next one
            click(cross(3))
            println("[frames] ✕ on #${a0 + 1} (START): deleted ${deleted.sorted()}, start ${a + 1}")
            assertEquals(setOf(a0), deleted); assertEquals(a0 + 1, a)
            click(top(1154f))
            assertEquals(a0, a); assertTrue(deleted.isEmpty())

            // Delete frames 1 to 600: all 600 go, the GIF moves to what's left after them
            click(btn(144f)); typeText("1"); click(btn(232f)); typeText("600"); click(btn(296f))
            println("[frames] Delete frames 1 to 600: ${deleted.size} deleted (${deleted.min() + 1}-${deleted.max() + 1}), GIF now #${a + 1}-#${b + 1}")
            assertEquals((0..599).toSet(), deleted); assertEquals(600, a); assertEquals(600 + (b0 - a0), b)
            shot("gif_frames_deleted")
            key(java.awt.event.KeyEvent.VK_Z, ctrl = true)
            assertTrue("Ctrl+Z", deleted.isEmpty()); assertEquals(a0, a); assertEquals(b0, b)

            // nothing picked: the Delete key deletes the shown frame, again deletes the next one
            click(card(6)); click(btn(760f))                    // show square 6, None
            val h0 = head
            key(java.awt.event.KeyEvent.VK_DELETE); key(java.awt.event.KeyEvent.VK_DELETE)
            println("[frames] Delete key twice on #${h0 + 1}: deleted ${deleted.sorted()}")
            assertEquals(setOf(h0, h0 + 1), deleted)
            key(java.awt.event.KeyEvent.VK_Z, ctrl = true); key(java.awt.event.KeyEvent.VK_Z, ctrl = true)
            assertTrue(deleted.isEmpty())

            // Delete all before / after the shown frame (Start / End follow)
            // a frame inside the part: the Start time (shows the START frame), then three frames on
            click(btn(760f)); click(Offset(55f, 329f)); repeat(3) { key(java.awt.event.KeyEvent.VK_RIGHT) }; val m = head
            assertEquals(a0 + 3, m)
            click(top(238f))
            println("[frames] Delete all before #${m + 1}: ${deleted.size} deleted, GIF #${a + 1}-#${b + 1}")
            assertEquals((0 until m).toSet(), deleted); assertEquals(m, a)
            click(top(1154f)); assertTrue(deleted.isEmpty())
            click(top(394f))
            println("[frames] Delete all after #${m + 1}: ${deleted.size} deleted, GIF #${a + 1}-#${b + 1}")
            assertEquals(m, deleted.min() - 1); assertEquals(m, b)
            click(top(1154f)); assertTrue(deleted.isEmpty()); assertEquals(a0, a); assertEquals(b0, b)

            // Set Start / Set End at the shown frame; the arrows step the shown frame
            click(card(3)); click(btn(760f)); val s = head; click(top(53f))
            println("[frames] Set Start at #${s + 1}: start ${a + 1} end ${b + 1}")
            assertEquals(s, a); assertEquals(b0, b)
            click(card(9)); key(java.awt.event.KeyEvent.VK_RIGHT); key(java.awt.event.KeyEvent.VK_RIGHT)
            val e = head; click(top(120f))
            println("[frames] two arrows on, Set End at #${e + 1}: start ${a + 1} end ${b + 1}")
            assertEquals(e, b)

            // the mouse wheel scrolls the row
            click(btn(760f))
            click(card(1)); val firstBefore = head; click(card(1))
            scene.sendPointerEvent(PointerEventType.Scroll, card(4), scrollDelta = Offset(0f, 6f)); frames(8)
            click(card(1)); val firstAfter = head; click(card(1))
            println("[frames] wheel: first square #${firstBefore + 1} -> #${firstAfter + 1}")
            assertTrue("the wheel scrolls the row", firstAfter > firstBefore + 2)

            // the strip only jumps: Start / End stay
            val sa = a; val sb = b
            click(Offset(85f + (1202f - 85f) * 0.8f, 376f))
            println("[frames] strip click at 80 %: shown #${head + 1}, start ${a + 1} end ${b + 1}")
            assertEquals(sa, a); assertEquals(sb, b); assertTrue("jumped far along", head > 500)
            frames(10)
            shot("gif_frames_final")

            // the crash Exos hit: new frames read while the squares were still drawing old ones (Later / Earlier
            // quickly, scrolling as they load). Must not close the app: any error in the scene fails here.
            val later = Offset(1238f, 376f); val earlier = Offset(45f, 376f)
            repeat(3) {
                click(later); frames(2, 10)
                scene.sendPointerEvent(PointerEventType.Scroll, card(4), scrollDelta = Offset(0f, 12f)); frames(2, 10)
                click(earlier); frames(2, 10)
            }
            frames(200, 50)
            println("[frames] Later / Earlier stress: still running, ${kept.size} frames in the part")
        } finally {
            scene.close(); gifFramesProbe = null; com.yoruc.mangacracker.ui.gifRangeProbe = null
        }
    }
}
