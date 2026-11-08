package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.music.Music
import com.yoruc.mangacracker.music.PhoneMusic
import com.yoruc.mangacracker.music.REPEAT_ALL
import com.yoruc.mangacracker.music.REPEAT_OFF
import com.yoruc.mangacracker.music.REPEAT_ONE
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

/** The Music tab, live (AniList + MyAnimeList): suggestions while typing, all seasons, Play, the player bar and its loop button. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class MusicScreensTest {
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
    private fun shown(t: String) = runCatching { text(t)[0].assertIsDisplayed() }.isSuccess
    private fun waitFor(t: String, ms: Long = 30_000): Boolean { var w = 0L; while (w < ms) { if (shown(t)) return true; pump(500); w += 500 }; return false }

    @After fun clean() { rule.runOnUiThread { PhoneMusic.stop() } }

    @Test fun searchPlayLoop() {
        rule.mainClock.autoAdvance = false
        pump(2500)
        desc("Music").performClick(); pump(1500)
        assertTrue("radio card", waitFor("LISTEN.moe radio", 5_000))
        shot("music_tab")
        rule.onAllNodes(hasSetTextAction())[0].performTextInput("zero no tsu"); pump(500)
        assertTrue("suggestions while typing", waitFor("The Familiar of Zero"))
        pump(3000)   // covers
        shot("music_search")
        text("The Familiar of Zero")[0].performClick()
        assertTrue("its openings / endings", waitFor("opening"))
        text("All seasons")[0].performClick()
        assertTrue("every season", waitFor("4 seasons", 60_000))
        pump(2000)
        shot("music_all_seasons")
        text("Play")[0].performClick(); pump(1500)
        val q = PhoneMusic.queue.value
        println("[music] phone queue: " + q.joinToString(" | ") { it.kind + it.order + " " + it.title + " (" + it.season + ")" })
        assertEquals("all 8 openings / endings of the 4 seasons", 8, q.size)
        assertEquals(4, q.map { it.season }.distinct().size)
        assertEquals(q.first().title, PhoneMusic.now.value?.title)
        assertTrue("the bar shows", shown(q.first().title.substringBefore(" (")))
        shot("music_playing")
        // the loop button: the list (default) -> this song -> no loop -> the list
        assertEquals(REPEAT_ALL, PhoneMusic.repeat.value)
        desc("Loop all seasons").performClick(); pump(400); assertEquals(REPEAT_ONE, PhoneMusic.repeat.value)
        shot("music_loop_one")
        desc("Loop 1 song").performClick(); pump(400); assertEquals(REPEAT_OFF, PhoneMusic.repeat.value)
        desc("No loop").performClick(); pump(400); assertEquals(REPEAT_ALL, PhoneMusic.repeat.value)
        assertEquals(androidx.media3.common.Player.REPEAT_MODE_ALL, PhoneMusic.player().repeatMode)
        // Next / Previous move through the queue
        desc("Next").performClick(); pump(600)
        println("[music] after Next: ${PhoneMusic.now.value?.title} (index ${PhoneMusic.index.value})")
        assertEquals(1, PhoneMusic.index.value)
        // another tab and back: the anime's page is still open (not the search)
        desc("Home").performClick(); pump(1500)
        desc("Music").performClick(); pump(1500)
        assertTrue("still on the anime's page", shown("All seasons"))
        shot("music_back_from_home")
        // the volume opens under the bar on a phone
        desc("Music volume").performClick(); pump(500)
        assertTrue(shown("%"))
        shot("music_volume")
        println("[music] phone: search, songs, play, loop modes, next, tab memory, volume all work")
    }
}
