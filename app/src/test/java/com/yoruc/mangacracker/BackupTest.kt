package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Backup
import com.yoruc.mangacracker.core.BackupException
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.HistoryEntry
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.ReadState
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Settings
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Backup: export -> wipe -> import gives the same data; merge keeps the newer; keys never leave; bad files are refused. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupTest {
    private fun ref(i: Int, src: String = "atsu") = MangaRef(src, "id$i", "Title $i", cover = "https://example.org/$i.jpg", tags = listOf("action"))
    private fun ch(i: Int) = Chapter("c$i", "Chapter $i", i.toFloat())

    private fun wipe() {
        Repo.restore(emptyMap(), emptyMap(), emptyMap(), Settings())
        VideoRepo.restore(emptyMap(), emptyMap())
    }

    private fun fill() {
        wipe()
        Repo.addToLibrary(ref(1), null, listOf(ch(3), ch(2), ch(1)))
        Repo.addToLibrary(ref(2), null, listOf(ch(1)))
        Repo.saveRead(ref(1), ch(1), 9, 10, 120, 3)
        Repo.saveRead(ref(3, "nhentai"), ch(1), 4, 20, 0)
        Repo.setRead(ref(1), listOf(ch(2)), true)
        Repo.updateSettings {
            it.copy(
                hiddenTags = listOf("gore"), hideAi = false, disabledSources = setOf("comix"), gridColumns = 4,
                danbooruUser = "someone", danbooruKey = "SECRET-KEY-123", gelbooruUser = "g-user", gelbooruKey = "SECRET-G",
                readerDim = 55, readerTwoPage = true, autoScrollSpeed = 1.5f, hideFinished = true,
            )
        }
        VideoRepo.save(VideoRef("hentaigasm", "v1", "Video 1", series = "Series"), 60_000, 1_200_000)
        VideoRepo.toggleFav(VideoRef("oppaistream", "v2", "Video 2"))
    }

    @After fun clean() { Backup.folderOverride = null }

    @Test fun roundTrip() {
        fill()
        val lib = Repo.library.value; val hist = Repo.history.value; val prog = Repo.progress.value
        val watch = VideoRepo.watch.value; val favs = VideoRepo.favs.value; val set = Repo.settings.value
        val text = Backup.export(appVersion = "test")
        println("[backup] ${text.length} bytes, ${lib.size} library, ${hist.size} history, ${prog.size} progress, ${watch.size} watched, ${favs.size} favs")
        listOf("SECRET", "someone", "g-user").forEach { assertFalse("no login / key '$it' in the file", text.contains(it)) }

        wipe()
        Repo.updateSettings { it.copy(danbooruKey = "DEVICE-KEY") }
        assertTrue(Repo.library.value.isEmpty() && Repo.history.value.isEmpty() && VideoRepo.favs.value.isEmpty())
        val c = Backup.read(text)
        assertEquals(Backup.FORMAT, c.format); assertEquals("test", c.appVersion); assertEquals(0, c.skipped); assertFalse(c.newer)
        assertEquals(2, c.chaptersRead)
        Backup.apply(c, Backup.Mode.REPLACE)

        assertEquals(lib, Repo.library.value)
        assertEquals(hist, Repo.history.value)
        assertEquals(prog, Repo.progress.value)
        assertEquals(watch, VideoRepo.watch.value)
        assertEquals(favs, VideoRepo.favs.value)
        val s = Repo.settings.value
        assertEquals("keys stay this phone's", "DEVICE-KEY", s.danbooruKey)
        assertEquals("", s.gelbooruKey)
        assertEquals(set.copy(danbooruUser = "", danbooruKey = "DEVICE-KEY", gelbooruUser = "", gelbooruKey = ""), s)
        assertEquals(3, Repo.history.value[ref(1).key]!!.chapterCount)
    }

    @Test fun mergeNewerWins() {
        fill()
        val now = System.currentTimeMillis()
        val k1 = ref(1).key
        val mine = Repo.history.value[k1]!!
        val older = HistoryEntry(ref(1), ch(3), ReadState(2, 10, 0, false, now - 86_400_000), now - 86_400_000)
        val other = HistoryEntry(ref(9), ch(1), ReadState(0, 5, 0, false, now - 1000), now - 1000)
        val prog = mapOf("$k1#c1" to ReadState(0, 10, 0, false, now - 86_400_000), "$k1#c3" to ReadState(9, 10, 0, true, now + 5000))
        val c = Backup.Contents(
            format = 1, created = now, appVersion = "x",
            history = mapOf(k1 to older, ref(9).key to other), progress = prog,
            settings = Settings(hiddenTags = listOf("horror"), gridColumns = 2, danbooruKey = "FROM-FILE"),
        )
        Backup.apply(c, Backup.Mode.MERGE)
        assertEquals("this phone's newer entry stays", mine, Repo.history.value[k1])
        assertEquals("new title added", other, Repo.history.value[ref(9).key])
        assertTrue("newer read mark wins", Repo.progress.value["$k1#c3"]!!.read)
        assertTrue("older one doesn't undo reading", Repo.progress.value["$k1#c1"]!!.read)
        assertEquals(2, Repo.library.value.size) // nothing in the backup's library: kept
        assertEquals(listOf("gore", "horror"), Repo.settings.value.hiddenTags)
        assertEquals(2, Repo.settings.value.gridColumns)
        assertEquals("a key in a file is never taken", "SECRET-KEY-123", Repo.settings.value.danbooruKey)
        assertEquals(1, VideoRepo.favs.value.size)
    }

    @Test fun badAndOddFiles() {
        fun refused(t: String) = try { Backup.read(t); fail("should refuse: ${t.take(40)}") } catch (e: BackupException) { println("[backup] refused: ${e.message}") }
        refused("not json at all")
        refused("{}")
        refused("[1,2,3]")
        refused("""{"app":"MangaCracker backup","format":0}""")
        refused("""{"app":"Other app","format":1}""")
        fill()
        val good = Backup.export()
        refused(good.dropLast(25)) // cut short

        // byte-order mark from some editors
        assertEquals(2, Backup.read("﻿" + good).library.size)

        // the smallest file there can be: nothing in it, settings untouched by a merge
        val min = Backup.read("""{"app":"MangaCracker backup","format":1}""")
        assertTrue(min.isEmpty)
        val before = Repo.settings.value
        Backup.apply(min, Backup.Mode.MERGE)
        assertEquals(before, Repo.settings.value)

        // a newer app's file: unknown sections / fields are ignored, the rest read
        val newer = """{"app":"MangaCracker backup","format":7,"created":5,"ratings":{"a":1},
            "library":{"atsu|n1":{"ref":{"source":"atsu","id":"n1","title":"New"},"added":3,"futureField":[1,2]}},
            "settings":{"gridColumns":5,"someNewSwitch":true}}"""
        val n = Backup.read(newer)
        assertTrue(n.newer); assertEquals(1, n.library.size); assertEquals(5, n.settings!!.gridColumns); assertEquals(0, n.skipped)

        // one broken entry is skipped, the rest kept
        val broken = """{"app":"MangaCracker backup","format":1,
            "library":{"bad":{"ref":5},"atsu|ok":{"ref":{"source":"atsu","id":"ok","title":"Ok"},"added":1}},
            "history":{"x":"nope"},"settings":"not an object"}"""
        val b = Backup.read(broken)
        assertEquals(1, b.library.size); assertEquals(2, b.skipped); assertEquals(null, b.settings)
    }

    @Test fun weeklyKeepsFour() = runBlocking {
        fill()
        val dir = File("../_dev_tools/out/backup_test").apply { deleteRecursively(); mkdirs() }
        Backup.folderOverride = dir
        (1..6).forEach { File(dir, "MangaCracker-auto-2026-10-0$it.json").writeText("{}") }
        File(dir, "MangaCracker-backup-2026-10-01.json").writeText("{}")
        Repo.updateSettings { it.copy(autoBackup = true, lastAutoBackup = 0) }
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<App>()
        val where = Backup.autoNow(ctx)
        val autos = dir.listFiles()!!.map { it.name }.filter { it.startsWith("MangaCracker-auto-") }.sorted()
        println("[backup] weekly -> $where; kept $autos")
        assertEquals(4, autos.size)
        assertTrue(File(dir, Backup.fileName(auto = true)).exists())
        assertTrue("hand-made backups are never pruned", File(dir, "MangaCracker-backup-2026-10-01.json").exists())
        assertTrue(Repo.settings.value.lastAutoBackup > 0)
        assertEquals(2, Backup.read(File(dir, Backup.fileName(auto = true)).readText()).library.size)
    }
}
