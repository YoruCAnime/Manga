package com.yoruc.mangacracker

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
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
import com.yoruc.mangacracker.core.Settings
import com.yoruc.mangacracker.sources.NhentaiSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/** Reader two-page view in landscape (860 x 400 dp): cover alone, then pairs; right-to-left and left-to-right. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w860dp-h400dp-land-xhdpi")
class TwoPageShotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        // the looper clock moves too: Coil's cross-fade runs on SystemClock (else pictures stay half faded in)
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun <T> off(block: suspend () -> T): T {
        var r: Result<T>? = null
        val th = Thread { r = runCatching { runBlocking { block() } } }
        th.start()
        while (th.isAlive) { Thread.sleep(50); ShadowLooper.idleMainLooper() }
        return r!!.getOrThrow()
    }

    @Test fun twoPages() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        Repo.updateSettings { it.copy(readerTwoPage = false, readerRtl = true, showReaderPageNumber = true) }
        val gal = off { Safety.filter(NhentaiSource.popular(1).items) }.first()
        val ch = off { NhentaiSource.chapters(gal) }.first()
        Repo.saveRead(gal, ch, 0, 30, 0)
        rule.onAllNodesWithContentDescription("Home", useUnmergedTree = true)[0].performClick(); pump(1500)
        rule.onAllNodesWithText(gal.title, substring = true, useUnmergedTree = true)[0].performClick(); pump(14000)
        // turned on while reading: the same page stays in view
        Repo.updateSettings { it.copy(readerTwoPage = true) }
        pump(15000)
        shot("71_reader_two_page_cover")
        // past the header and the cover: the first pair (slow drags, so no fling overshoots)
        repeat(2) {
            rule.onRoot().performTouchInput { swipeUp(startY = height * 0.9f, endY = height * 0.25f, durationMillis = 1500) }
            pump(2500)
        }
        pump(30000)
        // the harness freezes Coil's cross-fade half way: redraw the loaded pages from memory (no fade) for the picture
        Repo.updateSettings { it.copy(readerTwoPage = false) }; pump(1500)
        Repo.updateSettings { it.copy(readerTwoPage = true) }; pump(4000)
        shot("72_reader_two_page_rtl")
        val pairs = rule.onAllNodesWithContentDescription("Page ", substring = true, useUnmergedTree = true).fetchSemanticsNodes()
        val sameRow = pairs.groupBy { (it.boundsInRoot.center.y / 20).toInt() }.values.any { it.size >= 2 }
        assertTrue("two pages side by side", sameRow)
        // right to left: page 2 to the right of page 3
        fun page(n: Int) = pairs.firstOrNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() == "Page $n" }
        val p2 = page(2); val p3 = page(3)
        println("[two-page] page 2 at x=${p2?.boundsInRoot?.left}, page 3 at x=${p3?.boundsInRoot?.left}")
        if (p2 != null && p3 != null) assertTrue("RTL: page 2 on the right", p2.boundsInRoot.left > p3.boundsInRoot.left)
        Repo.updateSettings { it.copy(readerRtl = false) }
        pump(6000)
        shot("72b_reader_two_page_ltr")
        Repo.updateSettings { Settings() }
    }
}
