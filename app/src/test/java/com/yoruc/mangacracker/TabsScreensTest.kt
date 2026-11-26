package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Tabs
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.MangaCard
import com.yoruc.mangacracker.ui.VideoCard
import com.yoruc.mangacracker.video.VideoRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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

/** The tabs button and list in the real app; live for the title page (Atsu), with who made it on it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class TabsScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }
    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }
    private fun text(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true)
    private fun desc(t: String) = rule.onAllNodesWithContentDescription(t, useUnmergedTree = true)
    private fun shown(t: String) = runCatching { text(t)[0].assertIsDisplayed() }.isSuccess
    private fun waitFor(t: String, ms: Long = 30_000): Boolean { var w = 0L; while (w < ms) { if (shown(t)) return true; pump(250); w += 250 }; return false }

    @Test fun listAndSwitch() {
        rule.mainClock.autoAdvance = false
        val atsu = Sources.byId("atsu")!!
        val refs = runBlocking(Dispatchers.IO) { atsu.popular(1).items.take(4) }
        // the first title with a named author
        val named = runBlocking(Dispatchers.IO) { refs.first { atsu.details(it).let { d -> d.authors.isNotEmpty() || d.artists.isNotEmpty() } } }
        val other = refs.first { it.key != named.key }
        rule.runOnUiThread {
            Tabs.clear()
            Tabs.addManga(named); Tabs.addManga(other)
            Tabs.addVideo(VideoRef("anikoto", "test-video", "A test video", "https://example.invalid/v"))
        }
        pump(2500)
        desc("Open tabs")[0].performClick()
        assertTrue(waitFor("Open tabs (3)"))
        pump(800); shot("tabs_sheet")
        text(named.title)[0].performClick()
        assertTrue("the title page opens", waitFor("Atsu", 30_000))
        var waited = 0L; while (waited < 30_000 && !(shown("Author") || shown("Artist") || shown("Story & art"))) { pump(250); waited += 250 }
        assertTrue("who made it is labelled", shown("Author") || shown("Artist") || shown("Story & art"))
        pump(600); shot("tabs_details")
        assertTrue(Tabs.active.value != 0L)
        assertTrue(Tabs.here.value!!.startsWith("details/"))
        // close it from the list: the next one comes up
        desc("Open tabs")[0].performClick()
        assertTrue(waitFor("Open tabs (3)"))
        pump(600)
        desc("Close tab")[0].performClick()
        pump(1500)
        assertEquals(2, Tabs.list.value.size)
        assertTrue(Tabs.active.value != 0L)
        rule.runOnUiThread { Tabs.sheetOpen.value = false; Tabs.clear() }
        pump(800)
        println("[tabs] phone list ok")
    }
}

/** Holding down on a cover or a video offers the new tab, next to what holding down did before. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class TabCardsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun holdDown() {
        rule.runOnUiThread { Tabs.clear() }
        val manga = MangaRef("atsu", "hold", "Held title")
        val video = VideoRef("anikoto", "hold-v", "Held video", "https://example.invalid/v")
        var continued = 0
        rule.setContent {
            androidx.compose.foundation.layout.Column {
                MangaCard(manga, menu = listOf("Continue reading" to { continued++ })) {}
                VideoCard(video, null) {}
            }
        }
        rule.onNodeWithText("Held title", useUnmergedTree = true).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Continue reading").assertIsDisplayed()
        rule.onNodeWithText("Open in new tab").performClick()
        rule.waitForIdle()
        assertEquals(1, Tabs.list.value.size)
        assertEquals("Held title", Tabs.list.value[0].title)
        rule.onNodeWithText("Held video", useUnmergedTree = true).performTouchInput { longClick() }
        rule.waitForIdle()
        rule.onNodeWithText("Add to favourites").assertIsDisplayed()
        rule.onNodeWithText("Open in new tab").performClick()
        rule.waitForIdle()
        assertEquals(2, Tabs.list.value.size)
        assertTrue(Tabs.list.value[1].route.startsWith("player/"))
        rule.runOnUiThread { Tabs.clear() }
        assertEquals(0, continued)
        println("[tabs] hold-down menus ok")
    }

}
