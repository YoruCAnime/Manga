package com.yoruc.mangacracker

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import androidx.compose.ui.geometry.Offset
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.cutout.CutoutModels
import com.yoruc.mangacracker.ui.CutoutRequest
import com.yoruc.mangacracker.ui.CutoutScreen
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The cutout window with the real models (needs -PcutoutData, see CutoutTest): the one-time download card, then a
 * study page cut out, then a Remove tap.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class CutoutScreenTest {
    @get:Rule val rule = createComposeRule()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun has(t: String) = rule.onAllNodesWithText(t, substring = true, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun cutout() {
        val dir = System.getProperty("cutoutData").orEmpty()
        assumeTrue("needs -PcutoutData", dir.isNotEmpty() && File(dir, "parity/cases.json").exists())
        val cases = Http.json.parseToJsonElement(File(dir, "parity/cases.json").readText()).jsonArray.map { it.jsonObject }
        val c = cases.first { it["case"]!!.jsonPrimitive.int == (System.getProperty("cutoutCase")?.toIntOrNull() ?: 2) }
        val name = "case%02d".format(c["case"]!!.jsonPrimitive.int)
        val w = c["w"]!!.jsonPrimitive.int.toFloat(); val h = c["h"]!!.jsonPrimitive.int.toFloat()
        val req = CutoutRequest("file:" + File(dir, "parity/$name.png").path, c["x"]!!.jsonPrimitive.int / w, c["y"]!!.jsonPrimitive.int / h, "Cutout test", name)

        rule.mainClock.autoAdvance = false
        var step by androidx.compose.runtime.mutableStateOf(0)
        CutoutModels.dirOverride = File(dir, "no_models_here")
        rule.setContent { if (step == 0) CutoutScreen(req) {} else androidx.compose.runtime.key(step) { CutoutScreen(req) {} } }
        pump(1500)
        assertTrue("download card", has("One-time download"))
        shot("60_cutout_download")

        CutoutModels.dirOverride = File(dir, "app_models")
        step = 1
        var waited = 0
        while (waited < 120_000 && !has("Tap a part")) { pump(500); waited += 500 }
        while (waited < 120_000 && (has("Cutting out") || has("Finding"))) { pump(500); waited += 500 }
        pump(800)
        assertTrue("cut out within 2 minutes", has("Tap a part"))
        shot("61_cutout_result")
        rule.onAllNodesWithText("Remove", useUnmergedTree = true)[0].performClick(); pump(600)
        shot("62_cutout_remove_mode")
        rule.onAllNodesWithContentDescription("Preview", useUnmergedTree = true)[0].performClick(); pump(3000)
        assertTrue("preview", has("what Save keeps"))
        shot("63_cutout_preview")
        // back to the panel; Erase brush: a stroke across the figure is highlighted while drawn, removed on lift
        rule.onAllNodesWithContentDescription("Show the panel", useUnmergedTree = true)[0].performClick(); pump(2000)
        rule.onAllNodesWithText("Erase", useUnmergedTree = true)[0].performClick(); pump(400)
        assertTrue("erase hint", has("Paint over what should go"))
        val root = rule.onAllNodes(isRoot()).onLast()
        root.performTouchInput { down(Offset(width * 0.12f, height * 0.56f)) }
        for (k in 1..12) { root.performTouchInput { moveTo(Offset(width * (0.12f + 0.012f * k), height * (0.56f + 0.004f * k))) }; pump(60) }
        pump(300)
        shot("64_cutout_brush_drawing")
        root.performTouchInput { up() }
        pump(2500)
        shot("65_cutout_brush_erased")
        rule.onAllNodesWithContentDescription("Undo", useUnmergedTree = true)[0].performClick(); pump(2000)
        shot("66_cutout_brush_undone")
        CutoutModels.dirOverride = null
    }
}
