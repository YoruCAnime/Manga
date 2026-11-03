package com.yoruc.mangacracker

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
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

    @Config(qualifiers = "w400dp-h860dp-xhdpi")
    @Test fun portraitControls() {
        openPlayer()
        shot("58_player_portrait")
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
