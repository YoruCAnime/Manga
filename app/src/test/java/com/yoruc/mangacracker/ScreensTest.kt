package com.yoruc.mangacracker

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.sources.AtsuSource
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * Renders the real app (real network) on the JVM and saves screenshots to _dev_tools/out/screens.
 * Run: gradlew :app:testDebugUnitTest --tests '*ScreensTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class ScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    /** Let real network work finish while time moves on (the app has endless animations, so never wait for idle). */
    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) {
            Thread.sleep(50); t += 50
            ShadowLooper.idleMainLooper()
            rule.mainClock.advanceTimeByFrame()
        }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) {
        captureScreenRoboImage(File(out, "$name.png").path)
        println("[shot] $name")
    }

    private fun text(t: String, i: Int = 0): SemanticsNodeInteraction = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)[i]
    private fun desc(t: String, i: Int = 0): SemanticsNodeInteraction = rule.onAllNodesWithContentDescription(t, useUnmergedTree = true)[i]
    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun back() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        pump(800)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test fun screens() {
        rule.mainClock.autoAdvance = false
        // seed: Solo Leveling in the library with 2 "new" chapters and a half-read chapter
        runBlocking {
            Repo.clearHistory(); Repo.clearUpdates()
            Repo.library.value.keys.forEach { Repo.removeFromLibrary(it) }
            val sl = AtsuSource.search(SearchQuery(text = "solo leveling"), 1).items.first()
            val d = AtsuSource.details(sl)
            val ch = AtsuSource.chapters(sl)
            Repo.remember(d.ref)
            Repo.addToLibrary(d.ref, d, ch.drop(2))
            Repo.applyChapterCheck(d.ref.key, ch)
            Repo.saveRead(d.ref, ch[40], 4, 12, 0)
            val wh = AtsuSource.search(SearchQuery(text = "witch hat atelier"), 1).items.first()
            val wd = AtsuSource.details(wh)
            val wc = AtsuSource.chapters(wh)
            Repo.remember(wd.ref); Repo.addToLibrary(wd.ref, wd, wc)
            Repo.saveRead(wd.ref, wc[3], 20, 30, 0)
        }
        pump(14000)
        shot("01_home")

        desc("Library").performClick(); pump(4000)
        shot("02_library")

        desc("Browse").performClick(); pump(1500)
        shot("03_sources")

        text("Atsumaru").performClick(); pump(9000)
        shot("04_browse_atsu")

        // autocomplete under the search box
        val box = rule.onAllNodes(hasSetTextAction())[0]
        box.performTextInput("rom"); pump(2500)
        shot("04b_autocomplete")
        box.performTextClearance(); pump(500)

        desc("Filters").performClick(); pump(4000)
        shot("05_filters")
        back()

        desc("Library").performClick(); pump(1500)
        text("Solo Leveling").performClick(); pump(9000)
        shot("06_details")

        text("Continue").performClick(); pump(12000)
        shot("07_reader")
        rule.onRoot().performTouchInput { click() }; pump(1500)
        shot("08_reader_overlay")
        desc("All pages").performClick(); pump(6000)
        shot("08b_page_grid")
        text("3").performTouchInput { longClick() }; pump(800)
        text("5").performClick(); pump(800)
        shot("08c_page_grid_pick")
        back(); pump(300); back()
        back(); back()

        desc("Updates").performClick(); pump(2500)
        shot("09_updates")

        desc("More").performClick(); pump(1500)
        shot("10_more")
    }
}
