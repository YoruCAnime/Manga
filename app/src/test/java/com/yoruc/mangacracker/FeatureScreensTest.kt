package com.yoruc.mangacracker

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.Backup
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.CheckStatus
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.ReadMarks
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Settings
import com.yoruc.mangacracker.core.SourceCheck
import com.yoruc.mangacracker.sources.AtsuSource
import com.yoruc.mangacracker.sources.NhentaiSource
import com.yoruc.mangacracker.ui.BackupTestHooks
import com.yoruc.mangacracker.ui.SourceTestVM
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The four features in the real app: read badges + hide finished, backup / restore panel, reader auto-scroll and
 * night filter, and "Test sources" run live against every site (comix can't run here: no WebView).
 * Titles of 18+ sources are never printed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class FeatureScreensTest {
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
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump(1200) }
    /** Touch the text that is exactly [t] (buttons / rows whose label has no click of its own). */
    private fun tap(t: String) { rule.onAllNodesWithText(t, substring = false, useUnmergedTree = true)[0].performClick() }

    /** Swipe up until [t] is on screen, then let the fling stop (a tap on a moving list only stops it). */
    private fun scrollTo(t: String) {
        repeat(40) {
            if (shown(t)) { pump(2500); return }
            rule.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f, durationMillis = 300) }
            pump(600)
        }
    }

    /** Network work off the main thread while the looper keeps going. */
    private fun <T> off(block: suspend () -> T): T {
        var r: Result<T>? = null
        val th = Thread { r = runCatching { runBlocking { block() } } }
        th.start()
        while (th.isAlive) { Thread.sleep(50); ShadowLooper.idleMainLooper() }
        return r!!.getOrThrow()
    }

    private fun openSettings() {
        desc("More").performClick(); pump(1500)
        text("Reader, updates, sources")[0].performClick(); pump(1500)
    }

    @After fun clean() { BackupTestHooks.openText = null; Backup.folderOverride = null; SourceCheck.unavailable = emptyMap() }

    @Test fun badgesAndHideFinished() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        // galleries: one read to the end, one part read; manga: 12 of 40 chapters, and a finished one
        val gals = off { Safety.filter(NhentaiSource.popular(1).items) }.take(2)
        val g = Chapter("1", "Gallery", 1f)
        Repo.saveRead(gals[0], g, 19, 20, 0)
        Repo.saveRead(gals[1], g, 6, 20, 0)
        val mangas = off { Safety.filter(AtsuSource.popular(1).items) }.take(2)
        val chs = (40 downTo 1).map { Chapter("t$it", "Chapter $it", it.toFloat()) }
        Repo.addToLibrary(mangas[0], null, chs)
        Repo.setRead(mangas[0], chs.takeLast(12), true)
        Repo.saveRead(mangas[0], chs[chs.size - 13], 4, 18, 0, chs.size)
        val five = chs.takeLast(5)
        Repo.addToLibrary(mangas[1], null, five)
        Repo.setRead(mangas[1], five, true)
        Repo.saveRead(mangas[1], five.first(), 17, 18, 0, five.size)
        pump(1500)
        val marks = ReadMarks.state.value
        assertTrue("gallery read = finished", marks[gals[0].key]?.finished == true)
        assertTrue("12 of 40", marks[mangas[0].key]?.let { it.read == 12 && it.total == 40 } == true)
        assertTrue("5 of 5 = finished", marks[mangas[1].key]?.finished == true)

        desc("Browse").performClick(); pump(1500)
        scrollTo("nhentai"); tap("nhentai"); pump(14000)
        shot("60_badges_browse_nhentai")
        assertTrue("finished gallery shows while the switch is off", text(gals[0].title).fetchSemanticsNodes().isNotEmpty())
        back(); back()
        desc("Library").performClick(); pump(4000)
        shot("61_badges_library")

        openSettings()
        scrollTo("Hide titles I've finished")
        shot("62_settings_hide_finished")
        text("Hide titles I've finished")[0].performClick(); pump(800)
        assertTrue(Repo.settings.value.hideFinished)
        back()

        desc("Browse").performClick(); pump(1500)
        scrollTo("nhentai"); tap("nhentai"); pump(14000)
        shot("63_browse_hide_finished")
        assertTrue("finished gallery hidden", text(gals[0].title).fetchSemanticsNodes().isEmpty())
        assertTrue("part-read gallery still there", text(gals[1].title).fetchSemanticsNodes().isNotEmpty())
        back(); back()
        desc("Library").performClick(); pump(2000)
        assertTrue("Library keeps finished titles", text(mangas[1].title).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun backupPanel() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        val dir = File("../_dev_tools/out/backup_screens").apply { deleteRecursively(); mkdirs() }
        Backup.folderOverride = dir
        val r = (1..3).map { MangaRef("atsu", "demo$it", "Demo $it") }
        r.forEach { Repo.addToLibrary(it, null, listOf(Chapter("c1", "Chapter 1", 1f))) }
        Repo.saveRead(r[0], Chapter("c1", "Chapter 1", 1f), 9, 10, 0, 1)
        VideoRepo.toggleFav(VideoRef("hentaigasm", "demo", "Demo video"))
        openSettings()
        scrollTo("Back up now")
        tap("Back up now"); pump(1500)
        scrollTo("Saved to")
        shot("64_backup_saved")
        val file = dir.listFiles()!!.single()
        println("[backup] wrote ${file.name} (${file.length()} bytes)")
        assertTrue(file.name.startsWith("MangaCracker-backup-"))

        // a backup from "another phone": more titles, a watched video, other settings
        Repo.addToLibrary(MangaRef("atsu", "demo9", "Demo 9"), null, emptyList())
        VideoRepo.save(VideoRef("hentaigasm", "w1", "Watched"), 50_000, 600_000)
        val other = Backup.export()
        Repo.restore(Repo.library.value - "atsu|demo9", Repo.history.value, Repo.progress.value, Repo.settings.value)
        VideoRepo.restore(emptyMap(), VideoRepo.favs.value)
        BackupTestHooks.openText = { other }
        tap("Restore…"); pump(1500)
        scrollTo("Restore this backup?")
        scrollTo("Merge adds everything")
        shot("65_restore_preview")
        tap("Replace"); pump(500)
        assertTrue("replace asks for a second tap, in the panel", shown("Tap again to replace"))
        tap("Merge"); pump(1500)
        scrollTo("Restored")
        shot("66_restore_done")
        assertTrue(Repo.library.value.containsKey("atsu|demo9"))
        assertTrue(VideoRepo.watch.value.containsKey("hentaigasm|w1"))
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
    }

    @Test fun readerExtras() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        val gal = off { Safety.filter(NhentaiSource.popular(1).items) }.first()
        val ch = off { NhentaiSource.chapters(gal) }.first()
        Repo.saveRead(gal, ch, 0, 30, 0)
        desc("Home").performClick(); pump(1500)
        text(gal.title)[0].performClick(); pump(12000)
        val start = Repo.history.value[gal.key]!!.state
        rule.onRoot().performTouchInput { click(center) }; pump(1500)
        shot("67_reader_overlay_autoscroll")
        desc("Auto-scroll").performClick(); pump(1500)
        desc("Faster").performClick(); pump(300)
        desc("Faster").performClick(); pump(300)
        desc("Faster").performClick(); pump(6000)
        shot("68_reader_autoscroll_running")
        val now = Repo.history.value[gal.key]!!.state
        println("[reader] auto-scroll: page ${start.page}+${start.offset} -> ${now.page}+${now.offset} at ${Repo.settings.value.autoScrollSpeed}x")
        assertTrue("auto-scroll moved the strip", now.page > start.page || now.offset > start.offset)
        Repo.updateSettings { it.copy(readerNight = true, readerDim = 35, readerWarm = 60) }
        pump(1000)
        shot("69_reader_night_filter")
        desc("Stop auto-scroll").performClick(); pump(800)
        rule.onRoot().performTouchInput { click(center) }; pump(1500)
        desc("Reader settings").performClick(); pump(2000)
        shot("70_reader_settings_sheet")
        Repo.updateSettings { Settings() }
    }

    @Test fun sourceTestLive() {
        rule.mainClock.autoAdvance = false
        SourceCheck.unavailable = mapOf("comix" to "Can't test here: comix needs a real WebView (works on the phone)")
        pump(3000)
        desc("More").performClick(); pump(1500)
        text("Test sources")[0].performClick(); pump(10_000)
        shot("73_source_test_running")
        val until = System.currentTimeMillis() + 8 * 60_000
        while (SourceTestVM.lastResults.isEmpty() && System.currentTimeMillis() < until) pump(1000)
        pump(1500)
        shot("74_source_test_results")
        scrollTo("Rule34Video")
        shot("75_source_test_videos")
        val rows = SourceTestVM.lastResults
        println("[check] ${"source".padEnd(14)} ${"kind".padEnd(6)} ${"result".padEnd(9)} ${"time".padStart(7)}  note")
        rows.forEach { r -> println("[check] ${r.id.padEnd(14)} ${(if (r.video) "video" else "manga").padEnd(6)} ${r.status.name.padEnd(9)} ${"%.1f s".format(r.ms / 1000f).padStart(7)}  ${r.note}") }
        assertTrue("finished", rows.isNotEmpty())
        // UK blocks / age checks are "needs VPN", never "broken"
        rows.filter { it.id == "ehentai" || it.id == "rule34video" }.forEach { assertTrue("${it.id} ${it.status}", it.status != CheckStatus.BROKEN) }
        assertTrue(rows.first { it.id == "comix" }.status == CheckStatus.SKIPPED)
    }
}
