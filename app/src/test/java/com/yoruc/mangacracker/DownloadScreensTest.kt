package com.yoruc.mangacracker

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.DlState
import com.yoruc.mangacracker.core.DownloadRunner
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.Settings
import com.yoruc.mangacracker.core.SourceCheck
import com.yoruc.mangacracker.sources.Manhwa18CcSource
import com.yoruc.mangacracker.ui.Notes
import com.yoruc.mangacracker.video.SfmCompileSource
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSafety
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File

/**
 * The new screens in the real app (shots 80-89): title page buttons, Other sites + move + Undo, a chapter downloaded
 * from its row menu with progress, the Downloads screen, the reader with the network blocked, the in-window "clear
 * watched" confirm, a switched-off / removed site's title page. Live (a manhwa18cc title, one chapter, one short
 * SFM Compile video). Titles of 18+ sources are never printed.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class DownloadScreensTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun pump(ms: Long) {
        var t = 0L
        while (t < ms) { Thread.sleep(50); t += 50; ShadowLooper.idleMainLooper(); rule.mainClock.advanceTimeByFrame() }
    }

    @OptIn(ExperimentalRoborazziApi::class)
    private fun shot(name: String) { captureScreenRoboImage(File(out, "$name.png").path); println("[shot] $name") }

    private fun text(t: String, sub: Boolean = true) = rule.onAllNodesWithText(t, substring = sub, useUnmergedTree = true)
    private fun desc(t: String) = rule.onAllNodesWithContentDescription(t, useUnmergedTree = true)[0]
    private fun shown(t: String) = runCatching { text(t)[0].assertIsDisplayed() }.isSuccess
    private fun has(t: String) = text(t).fetchSemanticsNodes().isNotEmpty()
    private fun back() { rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }; pump(1200) }
    /** Merged-node click on the text that is exactly [t]. */
    private fun tap(t: String) { rule.onAllNodesWithText(t, substring = false)[0].performSemanticsAction(SemanticsActions.OnClick) }
    private fun waitFor(ms: Long, ok: () -> Boolean) { val until = System.currentTimeMillis() + ms; while (!ok() && System.currentTimeMillis() < until) pump(500) }

    private fun scrollTo(t: String) {
        repeat(30) {
            if (shown(t)) { pump(2500); return }
            rule.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 4f, durationMillis = 300) }
            pump(600)
        }
    }

    private fun <T> off(block: suspend () -> T): T {
        var r: Result<T>? = null
        val th = Thread { r = runCatching { runBlocking { block() } } }
        th.start()
        while (th.isAlive) { Thread.sleep(50); ShadowLooper.idleMainLooper() }
        return r!!.getOrThrow()
    }

    /** Runs the download queue in the background while the screen keeps drawing; returns the thread. */
    private fun runQueue(): Thread {
        val app = rule.activity.applicationContext
        return Thread { runBlocking { DownloadRunner.run(app) } }.apply { start() }
    }

    @Before fun setup() {
        Downloads.autoStart = false
        SourceCheck.unavailable = mapOf("comix" to "Can't test here: comix needs a real WebView (works on the phone)")
    }

    @After fun clean() {
        Http.blockNetwork = false
        SourceCheck.unavailable = emptyMap()
        Downloads.queue.cancelAll()
        Downloads.titles.value.keys.toList().forEach { Downloads.deleteTitle(it) }
        VideoDownloads.videos.value.keys.toList().forEach { VideoDownloads.delete(it) }
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
    }

    /** Plainly adult stories only for the shots: no school / student settings. */
    private val schoolish = Regex("school|student|class|teen|academy|uniform|campus|tutor|\\b1[0-7]\\b|minor|young|little|loli|elf|maid|slave", RegexOption.IGNORE_CASE)
    /** Stories about grown women first (wives, neighbours, office). */
    private val grownUp = Regex("wife|married|office|company|boss|neighbo|landlady|divorc|mother|aunt|noona|woman|women|lady|colleague|widow", RegexOption.IGNORE_CASE)

    /** A popular manhwa18cc title (these are often on Hentai20 / Manga18.club too), plainly adult, with its chapters. */
    private fun manhwa(): Pair<MangaRef, List<Chapter>> {
        val items = off { Safety.filter(Manhwa18CcSource.popular(1).items) }.take(10)
        val ok = items.mapNotNull { r ->
            val d = off { Manhwa18CcSource.details(r) }
            val text = d.genres.joinToString(" ") + " " + d.description + " " + d.ref.title
            if (Safety.blocked(d) || schoolish.containsMatchIn(text)) null else Triple(r, d, grownUp.containsMatchIn(text))
        }.sortedByDescending { it.third }
        for ((r, d, _) in ok) {
            Repo.detailsCache[r.key] = d
            val chs = off { Manhwa18CcSource.chapters(r) }
            if (chs.size < 3) continue
            Repo.chaptersCache[r.key] = chs
            return d.ref to chs
        }
        error("no plainly adult title in the first popular page")
    }

    private fun shownExact(t: String) = runCatching { rule.onAllNodesWithText(t, substring = false, useUnmergedTree = true)[0].assertIsDisplayed() }.isSuccess

    private fun scrollToExact(t: String) {
        repeat(30) {
            if (shownExact(t)) { pump(2500); return }
            rule.onRoot().performTouchInput { swipeUp(startY = centerY + height / 4f, endY = centerY - height / 6f, durationMillis = 400) }
            pump(700)
        }
    }

    private fun openFromLibrary(title: String) {
        desc("Library").performClick(); pump(1500)
        text(title)[0].performClick(); pump(9000)
    }

    @Test fun otherSitesMoveAndUndo() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        val (ref, chs) = manhwa()
        Repo.addToLibrary(ref, Repo.detailsCache[ref.key], chs)
        val oldest = chs.asReversed()
        Repo.setRead(ref, oldest.take(3), true)
        Repo.saveRead(ref, oldest[3], 7, 20, 0, chs.size)
        val addedBefore = Repo.library.value[ref.key]!!.added
        openFromLibrary(ref.title)
        shot("80_details_other_sites_download")
        assertTrue(has("Other sites") && has("Download"))

        tap("Other sites"); pump(2000)
        shot("81a_other_sites_searching")
        waitFor(120_000) { !has("Searching…") }
        waitFor(40_000) { !has("Chapters…") }
        pump(2500)
        shot("81_other_sites_results")
        assertTrue("a match to move to", has("Move here"))

        tap("Move here")
        waitFor(40_000) { Notes.current.value?.text?.startsWith("Moved to") == true }
        pump(2500)
        shot("82_moved_with_undo")
        val note = Notes.current.value?.text.orEmpty()
        println("[move] note: $note")
        assertTrue(note.startsWith("Moved to"))
        assertTrue("the old entry left the library", ref.key !in Repo.library.value)
        val moved = Repo.library.value.values.single()
        assertEquals("added date kept", addedBefore, moved.added)
        val readNew = Repo.progress.value.filterKeys { it.startsWith(moved.ref.key + "#") }.count { it.value.read }
        println("[move] ${moved.ref.source}: ${moved.chapterCount} chapters, $readNew read, continue = ${Repo.history.value[moved.ref.key]?.chapter?.name}")
        assertTrue("read chapters carried over", readNew >= 3)

        tap("Undo"); pump(2000)
        shot("83_move_undone")
        assertTrue("undo brings the old entry back", ref.key in Repo.library.value)
        assertEquals(1, Repo.library.value.size)
        assertEquals(oldest[3].id, Repo.history.value[ref.key]?.chapter?.id)
    }

    @Test fun downloadAChapterThenReadOffline() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        val (ref, chs) = manhwa()
        Repo.addToLibrary(ref, Repo.detailsCache[ref.key], chs)
        // a short video for the Downloads list (real download, a few MB)
        val vid = off {
            SfmCompileSource.latest(1).items.filterNot { VideoSafety.blocked(it) }.take(6)
                .firstOrNull { r -> runCatching { SfmCompileSource.details(r).also { VideoSafety.check(it); VideoRepo.details[r.key] = it } }.isSuccess }
        }
        openFromLibrary(ref.title)
        // oldest first, then the first chapter's row menu (long-press) -> Download
        desc("Order").performClick(); pump(1500)
        val first = chs.last()
        scrollToExact(first.name)
        text(first.name, sub = false)[0].performTouchInput { longClick() }; pump(1500)
        shot("84b_chapter_row_menu")
        // the menu's "Download" (the chip above has the same word)
        rule.onAllNodesWithText("Download", substring = false).let { it[it.fetchSemanticsNodes().size - 1] }.performSemanticsAction(SemanticsActions.OnClick); pump(800)
        assertTrue(Downloads.queue.tasks.value.any { it.chapter?.id == first.id })
        // a second one waits paused (for the queue rows)
        Downloads.enqueueChapters(null, ref, listOf(chs[chs.size - 2]))
        Downloads.pause(Downloads.queue.tasks.value.last().id)
        if (vid != null) Downloads.enqueueVideo(null, vid)
        val worker = runQueue()
        // catch it part way (the page counter on the row)
        val until = System.currentTimeMillis() + 60_000
        while ((Downloads.queue.tasks.value.firstOrNull { it.chapter?.id == first.id }?.done ?: 0) < 4 && !Downloads.isDownloaded(ref.key, first.id) && System.currentTimeMillis() < until) pump(100)
        pump(150)
        shot("85_chapter_downloading")
        waitFor(5 * 60_000) { Downloads.isDownloaded(ref.key, first.id) && (vid == null || vid.key in VideoDownloads.videos.value) }
        worker.join(60_000)
        pump(2500)
        shot("86_chapter_downloaded_marker")
        assertTrue(Downloads.isDownloaded(ref.key, first.id))

        // the title page's Download menu (next 5 / all unread / all)
        tap("Download · 1 saved"); pump(1500)
        shot("84_download_menu")
        back()
        if (has("Other sites")) back()
        // More > Downloads
        desc("More").performClick(); pump(1500)
        text("for offline")[0].performClick(); pump(2500)
        text(ref.title, sub = false)[0].performClick(); pump(1500)   // open the title's chapters
        shot("87_downloads_screen")
        assertTrue(has("Paused"))
        assertTrue(has("used by downloads"))

        // the reader with no network at all
        Http.blockNetwork = true
        text(first.name, sub = false)[0].performClick(); pump(6000)
        // the harness freezes Coil's cross-fade part way (black pages): open it again so the pages come from memory, no fade
        back()
        text(first.name, sub = false)[0].performClick(); pump(5000)
        val pages = Downloads.titles.value[ref.key]!!.saved[first.id]!!.files.size
        shot("88_offline_reader")
        assertTrue("page counter of the downloaded chapter", has("/ $pages"))
        back()
        Http.blockNetwork = false
        println("[dl] chapter: $pages pages, ${Downloads.size(Downloads.titles.value[ref.key]!!.bytes)}; video: ${vid?.let { VideoDownloads.videos.value[it.key]?.let { o -> Downloads.size(o.bytes) } }}")
    }

    @Test fun clearWatchedIsInWindow() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        VideoRepo.save(VideoRef("hentaigasm", "w1", "Demo episode 1", series = "Demo series", episode = "1"), 120_000, 1_200_000)
        VideoRepo.save(VideoRef("hentaigasm", "w2", "Demo episode 2", series = "Demo series", episode = "2"), 1_190_000, 1_200_000)
        desc("Library").performClick(); pump(1500)
        tap("Watched"); pump(1500)
        desc("Clear watched").performClick(); pump(1500)
        shot("89_clear_watched_inline")
        assertTrue(has("Clear watched videos?"))
        tap("Keep"); pump(800)
        assertEquals(2, VideoRepo.watch.value.size)
        desc("Clear watched").performClick(); pump(800)
        tap("Clear"); pump(1500)
        shot("89b_clear_watched_done")
        assertTrue(VideoRepo.watch.value.isEmpty())
    }

    @Test fun siteOffAndSiteGone() {
        rule.mainClock.autoAdvance = false
        pump(3000)
        val (ref, chs) = manhwa()
        Repo.addToLibrary(ref, Repo.detailsCache[ref.key], chs)
        Repo.updateSettings { it.copy(disabledSources = setOf("manhwa18cc")) }
        openFromLibrary(ref.title)
        shot("8a_site_switched_off_banner")
        assertTrue(has("switched off in Settings") && has("Find on other sites"))
        back()
        Repo.updateSettings { it.copy(disabledSources = emptySet()) }
        // a title from a site the app no longer has
        val gone = MangaRef("manhwaread", "x1", ref.title + " ", cover = ref.cover)
        Repo.removeFromLibrary(ref.key)
        Repo.addToLibrary(gone, null, emptyList())
        pump(1000)
        text(ref.title)[0].performClick(); pump(3000)
        shot("8b_site_gone_page")
        assertTrue(has("no longer in MangaCracker"))
        tap("Find on other sites"); pump(3000)
        assertTrue(has("Searching") || has("found") || has("Not found"))
        Downloads.queue.tasks.value.forEach { assertTrue(it.state != DlState.RUNNING) }
    }
}
