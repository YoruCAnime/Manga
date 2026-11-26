package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.NhentaiXxxSource
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.AgeConfirm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The one-time age question: "go back" records nothing, "I am 18 or older" switches the source on for good. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class AgeConfirmTest {
    @get:Rule val rule = createComposeRule()

    @Test fun askOnce() {
        val s = NhentaiXxxSource
        rule.runOnUiThread { Repo.updateSettings { it.copy(ageConfirmed = emptySet()) } }
        assertFalse(Sources.enabled().contains(s))
        var back = 0
        rule.setContent { AgeConfirm(s.id, s.ageNotice!!) { back++ } }
        rule.onNodeWithText("Adults only").assertIsDisplayed()
        rule.onNodeWithText("I'm not 18, go back").performClick()
        rule.waitForIdle()
        assertEquals(1, back)
        assertFalse(s.id in Repo.settings.value.ageConfirmed)
        rule.onNodeWithText("I am 18 or older, continue").performClick()
        rule.waitForIdle()
        assertTrue(s.id in Repo.settings.value.ageConfirmed)
        assertTrue(Sources.enabled().contains(s))
        rule.runOnUiThread { Repo.updateSettings { it.copy(ageConfirmed = emptySet()) } }
    }
}
