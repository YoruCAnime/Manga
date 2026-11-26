package com.yoruc.mangacracker

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.ui.SeasonLayer
import com.yoruc.mangacracker.ui.Seasons
import com.yoruc.mangacracker.ui.theme.Mc
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Each Home season effect alone on the app's navy, to judge how it looks (shots 109a-109e). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class SeasonPreviewTest {
    @get:Rule val rule = createComposeRule()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun render(e: String, name: String) {
        Repo.updateSettings { it.copy(homeEffect = e, homeNightSky = true) }
        rule.mainClock.autoAdvance = false
        rule.setContent { Box(Modifier.fillMaxSize().background(Mc.Bg)) { SeasonLayer(Modifier.fillMaxSize()) } }
        repeat(150) { rule.mainClock.advanceTimeByFrame() }  // a few seconds of drifting
        captureScreenRoboImage(File(out, "$name.png").path)
    }

    @Test fun leaves() = render(Seasons.LEAVES, "109a_season_leaves")
    @Test fun sakura() = render(Seasons.SAKURA, "109b_season_sakura")
    @Test fun snow() = render(Seasons.SNOW, "109c_season_snow")
    @Test fun summer() = render(Seasons.SUMMER, "109d_season_summer")
    @Test fun stars() = render(Seasons.STARS, "109e_season_stars")
}
