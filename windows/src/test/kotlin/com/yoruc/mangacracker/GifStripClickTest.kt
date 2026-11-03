package com.yoruc.mangacracker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import com.yoruc.mangacracker.ui.GifEditor
import com.yoruc.mangacracker.ui.GifSource
import com.yoruc.mangacracker.ui.gifRangeProbe
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * Clicks on the GIF editor's strip as a real mouse makes them (the pointer moves a pixel or two while pressed): only
 * the picked end (Start / End) may move. Uses build/rife_test/clip.mp4 (30 s), skipped when missing.
 */
class GifStripClickTest {
    private var a = -1; private var b = -1; private var active = -1
    private var t = 0L

    private fun ImageComposeScene.frames(k: Int, sleep: Long = 30) = repeat(k) { render(t); t += 50_000_000L; Thread.sleep(sleep) }

    /** Press, wobble [wobble] px, release (one frame between each, as a mouse does). */
    private fun ImageComposeScene.click(x: Float, y: Float, wobble: Float = 2f) {
        sendPointerEvent(PointerEventType.Press, Offset(x, y)); frames(1)
        sendPointerEvent(PointerEventType.Move, Offset(x + wobble, y + 1f)); frames(1)
        sendPointerEvent(PointerEventType.Move, Offset(x + wobble * 2, y)); frames(1)
        sendPointerEvent(PointerEventType.Release, Offset(x + wobble * 2, y)); frames(3)
    }

    private fun ImageComposeScene.drag(x0: Float, x1: Float, y: Float) {
        sendPointerEvent(PointerEventType.Press, Offset(x0, y)); frames(1)
        for (k in 1..10) { sendPointerEvent(PointerEventType.Move, Offset(x0 + (x1 - x0) * k / 10, y)); frames(1) }
        sendPointerEvent(PointerEventType.Release, Offset(x1, y)); frames(3)
    }

    @Test fun pickedEndOnly() {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        gifRangeProbe = { na, nb, act -> a = na; b = nb; active = act }
        val scene = ImageComposeScene(1280, 560, Density(1f)) {
            GifEditor(GifSource(listOf("-i", clip.absolutePath), 640, 360, 24.0, 30.0, "test"), 20.0) {}
        }
        try {
            scene.frames(200, 50)
            // the strip (see gif_deleted.png): x 85..1202, y 377..460; 720 frames (0:00-0:30)
            val n = 720
            fun x(frame: Int) = 85f + (1202f - 85f) * frame / (n - 1)
            val stripY = 420f
            val startTag = Offset(55f, 356f); val endTag = Offset(147f, 356f)
            println("[strip] opened: start $a end $b picked $active")
            assertEquals("End is picked at first", 1, active)
            val a0 = a

            // 1. End picked: a wobbly click a bit after Start (where the old code grabbed Start) moves End only
            scene.click(x(a0 + 30), stripY)
            println("[strip] End click near Start: start $a end $b picked $active")
            assertEquals("Start must not move", a0, a)
            assertTrue("End moved to the click (got $b)", abs(b - (a0 + 30)) <= 3)
            assertEquals("End stays picked", 1, active)

            // 2. pick Start, wobbly click earlier: Start moves only
            scene.sendPointerEvent(PointerEventType.Press, startTag); scene.frames(1)
            scene.sendPointerEvent(PointerEventType.Release, startTag); scene.frames(3)
            assertEquals("Start picked", 0, active)
            val b1 = b
            scene.click(x(200), stripY)
            println("[strip] Start click: start $a end $b picked $active")
            assertTrue("Start moved to the click (got $a)", abs(a - 200) <= 3)
            assertEquals("End must not move", b1, b)

            // 3. pick End, drag it from 650 to 100 (past Start): End follows and stops just after Start
            scene.sendPointerEvent(PointerEventType.Press, endTag); scene.frames(1)
            scene.sendPointerEvent(PointerEventType.Release, endTag); scene.frames(3)
            assertEquals("End picked", 1, active)
            val a2 = a
            scene.drag(x(650), x(100), stripY)
            println("[strip] End dragged past Start: start $a end $b picked $active")
            assertEquals("Start must not move while dragging End", a2, a)
            assertEquals("End stops just after Start", a2 + 1, b)

            // 4. ten wobbly End clicks around the part: Start never moves
            for (f in listOf(250, 300, 420, 500, 260, 610, 380, 330, 700, 280)) {
                scene.click(x(f), stripY, wobble = 3f)
                assertEquals("Start must not move (End click at $f)", a2, a)
                // the press point plus the 6 px the mouse wobbled while held (~4 frames)
                assertTrue("End at the click $f (got $b)", abs(b - f) <= 8)
            }
            println("[strip] 10 End clicks: start stayed $a, end $b")
            File("build/shots").mkdirs()
            File("build/shots/gif_strip_clicks.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        } finally {
            scene.close(); gifRangeProbe = null
        }
    }
}
