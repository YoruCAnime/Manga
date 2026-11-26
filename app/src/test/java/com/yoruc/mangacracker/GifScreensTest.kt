package com.yoruc.mangacracker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.gif.GifMaker
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.gif.ListFrames
import com.yoruc.mangacracker.ui.PlayerTestHooks
import com.yoruc.mangacracker.video.GifDone
import com.yoruc.mangacracker.video.VideoDetails
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoStream
import kotlinx.coroutines.delay
import com.yoruc.mangacracker.core.Repo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.math.sin

/**
 * The player's bottom controls, the seek bar while dragging and the GIF maker panel, in landscape (860 x 400 dp) as on
 * the phone. No video decodes here, so the clock and the GIF job are stand-ins (PlayerTestHooks); the finished GIF in the
 * panel is a real one made by GifMaker from drawn frames. Screenshots: _dev_tools/out/screens/5x_*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w860dp-h400dp-land-xhdpi")
class GifScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }
    private var fakePos = 83_000L

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun text(t: String, sub: Boolean = true) = rule.onAllNodesWithText(t, substring = sub, useUnmergedTree = true)
    private fun desc(t: String) = rule.onAllNodesWithContentDescription(t, useUnmergedTree = true)[0]

    @After fun clear() { PlayerTestHooks.clock = null; PlayerTestHooks.gifJob = null }

    /** A small real GIF from drawn frames (a ball over a gradient), for the finished-GIF view. */
    private fun sampleGif(): GifDone {
        val w = 480; val h = 270
        val frames = (0 until 36).map { k ->
            val cx = 60 + k * 10; val cy = 135 + (60 * sin(k / 5.0)).toInt()
            IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                val dx = x - cx; val dy = y - cy
                if (dx * dx + dy * dy < 900) 0xFF5C8A else ((30 + x / 6) shl 16) or ((40 + y / 4) shl 8) or 110
            }
        }
        val r = GifMaker(ListFrames(frames, w, h, 24.0), GifOptions(0.5f, true, GifSize.SMALL)).make()
        val f = File(out, "../gif/screen_sample.gif").apply { parentFile.mkdirs(); writeBytes(r.bytes) }
        return GifDone(f, r.bytes.size.toLong(), r.width, r.height, r.durationMs, r.fps)
    }

    private fun openPlayer(kind: String = "mp4") {
        rule.mainClock.autoAdvance = false
        PlayerTestHooks.clock = { longArrayOf(fakePos, 24 * 60_000L + 13_000, fakePos + 95_000) }
        val ref = VideoRef("hentaigasm", "gif-screen-test", "Test clip", series = "GIF test series", episode = "2")
        VideoRepo.remember(ref)
        VideoRepo.details[ref.key] = VideoDetails(ref, VideoStream("http://127.0.0.1:9/none.$kind", kind), episodes = listOf(ref))
        VideoRepo.save(ref, 60_000, 24 * 60_000L)
        pump(2500)
        desc("Library").performClick(); pump(1200)
        text("Watched")[0].performClick(); pump(1500)
        // tap the row's picture (its title opens the series page instead)
        val b = text("Episode 2")[0].fetchSemanticsNode().boundsInRoot
        rule.onRoot().performTouchInput { click(Offset(b.left - 60f, b.center.y)) }
        pump(3000)
    }

    @Test fun playerAndGif() {
        openPlayer()
        if (text("Pause").fetchSemanticsNodes().isEmpty() && text("Play").fetchSemanticsNodes().isEmpty()) {
            rule.onRoot().performClick(); pump(600)
        }
        shot("50_player_controls")
        // seek bar: hold the finger down two thirds along, then show the bubble
        desc("Seek bar").performTouchInput { down(Offset(width * 0.3f, height / 2f)); moveTo(Offset(width * 0.62f, height / 2f)) }
        pump(500)
        shot("51_seek_drag")
        desc("Seek bar").performTouchInput { up() }
        pump(600)
        // GIF: start capturing, 4.3 s later stop
        text("GIF", sub = false)[0].performClick(); pump(400)
        fakePos += 4300; pump(800)
        shot("52_gif_capture")
        text("Stop GIF", sub = false)[0].performClick(); pump(1500)
        shot("53_gif_editor")
        // drag the start handle to the right while holding, half speed, ping-pong
        text("0.5×", sub = false)[0].performClick(); pump(200)
        text("Ping-pong")[0].performClick(); pump(200)
        text("50", sub = false)[0].performClick(); pump(200)
        desc("GIF range").performTouchInput { down(Offset(width * 0.05f, height / 2f)); moveTo(Offset(width * 0.3f, height / 2f)) }
        pump(500)
        shot("54_gif_range_drag")
        desc("GIF range").performTouchInput { up() }
        pump(600)
        val done = sampleGif()
        var asked: com.yoruc.mangacracker.video.GifRequest? = null
        PlayerTestHooks.gifJob = { req, prog ->
            asked = req
            for (i in 0..20) { prog(if (i < 8) "Getting the clip" else "Making the GIF", i / 20f); delay(40) }
            done
        }
        text("Make GIF", sub = false)[0].performClick(); pump(1300)
        shot("55_gif_working")
        assertEquals("the picked frame rate reaches the maker", 50, asked?.opts?.fps)
        pump(4500)
        shot("56_gif_done")
        desc("Close GIF maker").performClick(); pump(1200)
        shot("57_player_after_gif")
    }

    /** Drawn frames for the strip: a ball moving across a gradient, the frame's number as a bar at the top. */
    private fun stripFrames(a: Long, b: Long, fps: Double): com.yoruc.mangacracker.video.GifFrames {
        val w = 320; val h = 180
        val n = ((b - a) / 1000.0 * fps).toInt()
        val f = com.yoruc.mangacracker.gif.FileFrames(File(out, "../gif/strip_test.bin").apply { parentFile.mkdirs() }, w, h, fps)
        for (k in 0 until n) {
            val cx = 20 + k * (w - 40) / n; val cy = 100 + (50 * sin(k / 8.0)).toInt()
            f.add(IntArray(w * h) { i ->
                val x = i % w; val y = i / w
                val dx = x - cx; val dy = y - cy
                if (y < 8 && x < k * w / n) 0xFFFFFF else if (dx * dx + dy * dy < 400) 0xFF5C8A else ((30 + x / 3) shl 16) or ((40 + y / 2) shl 8) or 110
            })
        }
        return com.yoruc.mangacracker.video.GifFrames(f, a, b, fps)
    }

    /** ✂ Frames: delete all before / after a frame, frames picked in between, undo; the GIF gets only what's left. */
    @Test fun gifFrames() {
        openPlayer()
        text("GIF", sub = false)[0].performClick(); pump(400)
        fakePos += 4300; pump(800)
        text("Stop GIF", sub = false)[0].performClick(); pump(1500)
        shot("53b_gif_frames_button")
        PlayerTestHooks.gifFrames = { a, b, fps -> delay(300); stripFrames(a, b, fps) }
        text("Frames")[0].performClick(); pump(150)
        shot("60_gif_frames_loading")
        pump(1500)
        shot("61_gif_frames")
        val strip = checkNotNull(text("Your GIF:").fetchSemanticsNodes().firstOrNull()) { "the strip shows" }
        println("[frames] header: " + strip.config.toString().take(120))
        // tap frame 6, delete everything before it
        desc("Frame 6").performClick(); pump(400)
        text("Delete all before #6")[0].performClick(); pump(500)
        shot("62_gif_frames_before_deleted")
        // frames 10 and 14 picked, everything between them, delete those
        desc("Frame 10").performClick(); pump(200)
        desc("Frame 14").performClick(); pump(200)
        text("Select between")[0].performClick(); pump(300)
        shot("63_gif_frames_between_picked")
        text("Delete 5 picked")[0].performClick(); pump(500)
        // the ✕ on frame 16 deletes just that one, then Undo brings it back
        desc("Delete frame 16").performClick(); pump(400)
        text("Undo", sub = false)[0].performClick(); pump(400)
        // scroll on to frame 100, delete all after it
        // frame #100 is the 90th left in the row (10 deleted before it)
        rule.onNodeWithContentDescription("GIF frames", useUnmergedTree = true).performScrollToIndex(87)
        pump(600)
        desc("Frame 100").performClick(); pump(300)
        text("Delete all after #100")[0].performClick(); pump(600)
        shot("64_gif_frames_after_deleted")
        var asked: com.yoruc.mangacracker.video.GifRequest? = null
        PlayerTestHooks.gifJob = { req, _ -> asked = req; delay(200); sampleGif() }
        text("Make GIF", sub = false)[0].performClick(); pump(2500)
        val cut = checkNotNull(asked).cut
        val n = ((asked!!.endMs - asked!!.startMs) / 1000.0 * 30).toInt()
        assertEquals("before #6", true, (0..4).all { it in cut })
        assertEquals("10..14 between", true, (9..13).all { it in cut })
        assertEquals("16 came back with Undo", false, 15 in cut)
        assertEquals("after #100", true, (100 until n).all { it in cut })
        assertEquals("nothing else", 5 + 5 + (n - 100), cut.size)
        println("[frames] strip $n frames, ${cut.size} deleted, ${n - cut.size} go into the GIF")
        shot("65_gif_frames_done")
    }

    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    @Test fun portraitControls() {
        openPlayer()
        shot("58_player_portrait")
    }

    /** Loop: the button turns the player's repeat-one on and off, and says so. */
    @Test fun loopButton() {
        openPlayer()
        desc("Loop").performClick(); pump(600)
        org.junit.Assert.assertEquals("repeat one", androidx.media3.common.Player.REPEAT_MODE_ONE, com.yoruc.mangacracker.ui.PlayerTestHooks.repeatMode)
        desc("Loop on").assertExists()
        shot("58b_player_loop_on")
        desc("Loop on").performClick(); pump(600)
        org.junit.Assert.assertEquals("repeat off", androidx.media3.common.Player.REPEAT_MODE_OFF, com.yoruc.mangacracker.ui.PlayerTestHooks.repeatMode)
        desc("Loop").assertExists()
        println("[loop] phone: Loop on = repeat one, off again = repeat off")
    }

    /** Middle play / skip buttons, the app's own volume (mute + slider, saved) and the quality menu (Best by default). */
    @Test fun volumeAndQuality() {
        Repo.updateSettings { it.copy(videoQuality = -1, videoVolume = 1f, videoMuted = false) }
        openPlayer("hls")
        if (text("Pause").fetchSemanticsNodes().isEmpty() && text("Play").fetchSemanticsNodes().isEmpty()) {
            rule.onRoot().performClick(); pump(600)
        }
        assertEquals(-1, Repo.settings.value.videoQuality)
        desc("Volume").performTouchInput { down(Offset(width * 0.05f, height / 2f)); moveTo(Offset(width * 0.4f, height / 2f)); up() }
        pump(600)
        val v = Repo.settings.value.videoVolume
        assertTrue("volume saved after the drag: $v", v in 0.25f..0.55f)
        shot("59_player_volume")
        desc("Mute").performClick(); pump(400)
        assertTrue(Repo.settings.value.videoMuted)
        shot("59b_player_muted")
        desc("Unmute").performClick(); pump(400)
        desc("Quality").performClick(); pump(600)
        shot("59c_player_quality")
        text("720p", sub = false)[0].performClick(); pump(400)
        assertEquals(720, Repo.settings.value.videoQuality)
        Repo.updateSettings { it.copy(videoQuality = -1, videoVolume = 1f, videoMuted = false) }
    }
}
