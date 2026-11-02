package com.yoruc.mangacracker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The Videos tab with real data (every source's grid, covers included) and the player's controls (Robolectric
 * can't decode video, so the picture stays black). Screenshots: _dev_tools/out/screens/2x_*.png
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class VideoScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)

    @OptIn(ExperimentalTestApi::class)
    @Test fun videos() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        rule.onAllNodesWithContentDescription("Videos", useUnmergedTree = true)[0].performClick(); pump(30000)
        shot("20_videos_hentaigasm")
        text("MuchoHentai")[0].performClick(); pump(30000)
        shot("21_videos_mucho")
        text("Oppai.stream")[0].performClick(); pump(30000)
        shot("21b_videos_oppai")
        val box = rule.onAllNodes(hasSetTextAction())[0]
        box.performTextInput("van"); pump(1500)
        shot("22_videos_autocomplete")
        box.performTextClearance(); pump(500)
        text("Popular")[0].performClick(); pump(8000)
        // open the first video
        text("Episode")[0].performClick(); pump(9000)
        shot("23_player")
        text("1×")[0].performClick(); pump(600)
        shot("24_player_speeds")
    }
}
