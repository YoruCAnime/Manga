package com.yoruc.mangacracker

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.yoruc.mangacracker.ui.GifEdit
import com.yoruc.mangacracker.ui.GifPanel
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The GIF panel's -1 / +1 frame buttons: each end moves exactly one frame and the player is asked for that exact frame. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w800dp-h400dp-xhdpi")
class GifStepsTest {
    @get:Rule val rule = createComposeRule()

    @Test fun steps() {
        val g = GifEdit(10_000L, 16_000L).apply { frameMs = 40L }
        val seen = mutableListOf<Triple<Long, Boolean, Int>>()
        rule.setContent { GifPanel(g, onPreview = { ms, exact, which -> seen += Triple(ms, exact, which) }, onMake = {}, onCancelMake = {}, onSave = {}, onShare = {}, onClose = {}) }
        val a0 = g.a; val b0 = g.b
        rule.onNodeWithContentDescription("Start one frame on").performClick()
        rule.onNodeWithContentDescription("Start one frame on").performClick()
        rule.onNodeWithContentDescription("Start one frame back").performClick()
        assertEquals(a0 + 40, g.a)
        rule.onNodeWithContentDescription("End one frame back").performClick()
        assertEquals(b0 - 40, g.b)
        rule.onNodeWithContentDescription("End one frame on").performClick()
        rule.onNodeWithContentDescription("End one frame on").performClick()
        // the end can't pass the captured span
        assertEquals(16_000L, g.b)
        assertEquals("every tap asks for the exact frame of that end", listOf(0, 0, 0, 1, 1, 1), seen.map { it.third })
        assertEquals(true, seen.all { it.second })
        assertEquals(a0 + 40, seen[2].first)
        rule.waitForIdle()
        rule.onNodeWithText("Start", substring = true).assertExists()
        rule.onRoot().captureRoboImage(File("../_dev_tools/out/screens/gif_steps.png").path)
        println("[gif] steps ok: start ${g.a}, end ${g.b}")
    }
}
