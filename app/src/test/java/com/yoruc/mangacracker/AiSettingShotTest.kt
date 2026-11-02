package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Settings › Hidden tags › "Hide AI-generated": on by default, and the switch reaches Safety. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class AiSettingShotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)

    @Test fun aiSwitch() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        assertTrue("on by default", Repo.settings.value.hideAi && Safety.hideAi)
        rule.onAllNodesWithContentDescription("More", useUnmergedTree = true)[0].performClick(); pump(1500)
        text("Reader, updates, sources")[0].performClick(); pump(1500)
        repeat(40) {
            if (runCatching { text("Hide AI-generated")[0].assertIsDisplayed(); text("Add a tag")[0].assertIsDisplayed() }.isSuccess) return@repeat
            rule.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f, durationMillis = 300) }
            pump(600)
        }
        pump(2500) // let the fling stop: a tap on a moving list only stops it
        shot("42_settings_hide_ai")
        text("Hide AI-generated")[0].performClick(); pump(800)
        assertTrue("switch turns it off", !Repo.settings.value.hideAi && !Safety.hideAi)
        shot("43_settings_hide_ai_off")
        text("Hide AI-generated")[0].performClick(); pump(800)
        assertTrue("and back on", Repo.settings.value.hideAi && Safety.hideAi)
    }
}
