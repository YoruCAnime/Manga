package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.Ambient
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.sources.NhentaiSource
import com.yoruc.mangacracker.ui.Seasons
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.time.LocalDateTime

/** 1.8.0: Home season effects (each kind), Settings › Home screen, and the reader's sounds sheet. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class CozyShotTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(50, java.util.concurrent.TimeUnit.MILLISECONDS); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)
    private fun desc(t: String) = rule.onAllNodesWithContentDescription(t, useUnmergedTree = true)

    private fun <T> off(block: suspend () -> T): T {
        var r: Result<T>? = null
        val th = Thread { r = runCatching { runBlocking { block() } } }
        th.start()
        while (th.isAlive) { Thread.sleep(50); ShadowLooper.idleMainLooper() }
        return r!!.getOrThrow()
    }

    @Test fun seasonsRules() {
        val t = { m: Int, h: Int -> LocalDateTime.of(2026, m, 10, h, 0) }
        assertEquals(Seasons.Scene(Seasons.SAKURA, false), Seasons.scene(Seasons.AUTO, true, t(4, 14)))
        assertEquals(Seasons.Scene(Seasons.MOTES, false), Seasons.scene(Seasons.AUTO, true, t(7, 14)))
        assertEquals(Seasons.Scene(Seasons.FIREFLIES, true), Seasons.scene(Seasons.AUTO, true, t(7, 22)))
        assertEquals(Seasons.Scene(Seasons.LEAVES, true), Seasons.scene(Seasons.AUTO, true, t(11, 3)))
        assertEquals(Seasons.Scene(Seasons.LEAVES, false), Seasons.scene(Seasons.AUTO, false, t(11, 3)))
        assertEquals(Seasons.Scene(Seasons.SNOW, false), Seasons.scene(Seasons.AUTO, true, t(1, 12)))
        assertEquals(Seasons.Scene(null, true), Seasons.scene(Seasons.STARS, false, t(6, 12)))
        assertEquals(Seasons.Scene(null, false), Seasons.scene(Seasons.OFF, true, t(1, 23)))
        assertEquals(Seasons.Scene(Seasons.SNOW, true), Seasons.scene(Seasons.SNOW, true, t(8, 21)))
    }

    @Test fun cozy() {
        rule.mainClock.autoAdvance = false
        pump(6000)
        for ((effect, name) in listOf(Seasons.LEAVES to "100_home_leaves", Seasons.SAKURA to "101_home_sakura", Seasons.SNOW to "102_home_snow",
            Seasons.SUMMER to "103_home_summer", Seasons.STARS to "104_home_stars")) {
            Repo.updateSettings { it.copy(homeEffect = effect, homeNightSky = true) }
            pump(5000)
            shot(name)
        }
        Repo.updateSettings { it.copy(homeEffect = Seasons.AUTO) }

        // Settings › Home screen
        desc("More")[0].performClick(); pump(1500)
        text("Reader, updates, sources")[0].performClick(); pump(1500)
        repeat(30) {
            if (runCatching { text("Stars at night")[0].assertIsDisplayed() }.isSuccess) return@repeat
            rule.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f, durationMillis = 300) }
            pump(600)
        }
        pump(2500)
        shot("107_settings_home_screen")
        text("Snow")[0].performClick(); pump(600)
        assertEquals(Seasons.SNOW, Repo.settings.value.homeEffect)
        text("By season")[0].performClick(); pump(600)
        repeat(12) {
            if (runCatching { text("Sounds while reading")[0].assertIsDisplayed(); text("Night crickets")[0].assertIsDisplayed() }.isSuccess) return@repeat
            rule.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f, durationMillis = 300) }
            pump(600)
        }
        pump(2500)
        shot("108_settings_sounds")
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump(800)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump(800)

        // reader: 🎧 opens the sounds sheet; a pick is saved and resolves to a sound
        val gal = off { Safety.filter(NhentaiSource.popular(1).items) }.first()
        val ch = off { NhentaiSource.chapters(gal) }.first()
        Repo.saveRead(gal, ch, 0, 30, 0)
        desc("Home")[0].performClick(); pump(1500)
        text(gal.title)[0].performClick(); pump(12000)
        if (desc("Sounds while reading").fetchSemanticsNodes().isEmpty()) { rule.onRoot().performTouchInput { click(center) }; pump(1500) }
        shot("105_reader_bar")
        desc("Sounds while reading")[0].performClick(); pump(2500)
        text("Rain")[0].performClick(); pump(1500)
        assertEquals("rain", Repo.settings.value.readerSound)
        assertNotNull(Ambient.uriOf(rule.activity))
        shot("106_reader_sounds")
        text("Off")[0].performClick(); pump(800)
        assertTrue(Repo.settings.value.readerSound.isEmpty())
    }
}
