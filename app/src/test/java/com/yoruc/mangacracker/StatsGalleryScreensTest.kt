package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.DayLog
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Stats
import com.yoruc.mangacracker.core.StatsFile
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
import java.time.LocalDate

/** More → Your stats (with made-up weeks of reading) and More → My GIFs & clips in the real app. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class StatsGalleryScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }
    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)
    private fun shown(t: String) = runCatching { text(t)[0].assertIsDisplayed() }.isSuccess
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump(1200) }

    @Test fun statsAndGallery() {
        rule.mainClock.autoAdvance = false
        pump(2500)
        // made-up weeks: every day of the last 6 active, then a gap
        val rnd = java.util.Random(3)
        val days = (0 until 60).filter { it < 6 || (it > 6 && rnd.nextInt(10) < 6) }.associate { k ->
            LocalDate.now().minusDays(k.toLong()).toString() to DayLog(readMs = rnd.nextInt(80) * 60_000L, watchMs = rnd.nextInt(50) * 60_000L,
                chapters = List(rnd.nextInt(7) + 1) { "atsu|t#$k-$it" }, videos = List(rnd.nextInt(3)) { "v|$k-$it" })
        }
        val f = File(rule.activity.filesDir, "data/stats.json")
        f.writeText(Http.json.encodeToString(StatsFile.serializer(), StatsFile(LocalDate.now().minusDays(20).toString(), days)))
        Stats.init(rule.activity)
        assertEquals(6, Stats.streak(Stats.data.value.days))

        rule.onAllNodesWithContentDescription("More", useUnmergedTree = true)[0].performClick(); pump(1500)
        assertTrue("Your stats row", shown("Your stats"))
        assertTrue("gallery row", shown("My GIFs & clips"))
        shot("stats_more")
        text("Your stats")[0].performClick(); pump(2000)
        assertTrue(shown("6-day streak"))
        assertTrue(shown("Last 7 days"))
        shot("stats_screen")
        back()
        text("My GIFs & clips")[0].performClick(); pump(2500)
        // nothing saved by the app here: the empty page, with the filters
        assertTrue(shown("Nothing made yet"))
        assertTrue(shown("GIFs · 0"))
        shot("gallery_empty")
        back()
        assertTrue(shown("Your stats"))
    }
}
