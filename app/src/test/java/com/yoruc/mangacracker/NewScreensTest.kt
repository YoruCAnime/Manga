package com.yoruc.mangacracker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
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

/** The 1.4.0 sources in the real app: Sources page, a few grids, a title page, the reader, the new video sites. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class NewScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)
    private fun desc(t: String) = rule.onAllNodesWithContentDescription(t, useUnmergedTree = true)[0]
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump(1200) }

    /** Swipes (up, or left along the row of [rowText]) until [t] is on screen. No waitForIdle: the app animates forever. */
    private fun scrollTo(t: String, rowText: String? = null) {
        repeat(30) {
            if (runCatching { text(t)[0].assertIsDisplayed() }.isSuccess) return
            rule.onRoot().performTouchInput {
                if (rowText == null) swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f, durationMillis = 300)
                else {
                    val y = text(rowText).fetchSemanticsNodes().firstOrNull()?.boundsInRoot?.center?.y ?: centerY
                    swipe(Offset(width * 0.8f, y), Offset(width * 0.3f, y), 300)
                }
            }
            pump(700)
        }
    }

    private fun tapIn(t: String, rowText: String? = null) { scrollTo(t, rowText); text(t)[0].performClick() }

    @OptIn(ExperimentalTestApi::class)
    @Test fun newSources() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        desc("Browse").performClick(); pump(1500)
        scrollTo("Manga18.club")
        shot("30_sources_new")
        tapIn("hitomi.la"); pump(15000)
        shot("31_browse_hitomi")
        back()
        tapIn("Hentai2Read"); pump(12000)
        shot("32_browse_hentai2read")
        back()
        tapIn("Manhwa18.cc"); pump(12000)
        shot("33_browse_manhwa18cc")
        // a title page and its first chapter
        text("Chapter")[0].performClick()
        pump(12000)
        shot("34_details_manhwa18cc")
        back(); back()
        tapIn("HentaiFox"); pump(12000)
        shot("35_browse_hentaifox")
        back()
        // videos
        desc("Videos").performClick(); pump(25000)
        shot("36_videos_first")
        for ((name, shotName) in listOf("MuchoHentai" to "", "HentaiMama" to "37_videos_hentaimama", "NSFW R34" to "38_videos_nsfwr34",
                "Porcore" to "39_videos_porcore", "SFM Compile" to "40_videos_sfmcompile", "Rule34Video" to "41_videos_rule34video_uk")) {
            rule.onAllNodesWithText(name)[0].performSemanticsAction(SemanticsActions.OnClick); pump(if (shotName.isEmpty()) 2000 else 20000)
            if (shotName.isNotEmpty()) shot(shotName)
        }
    }
}
