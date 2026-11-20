package com.yoruc.mangacracker

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.yoruc.mangacracker.core.DayLog
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Stats
import com.yoruc.mangacracker.platform.HoverWindow
import com.yoruc.mangacracker.platform.SeekPreview
import com.yoruc.mangacracker.ui.SeekBar
import com.yoruc.mangacracker.ui.StatsScreen
import com.yoruc.mangacracker.video.VideoRepo
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate

/** A Java key event as the window hands it to Compose (Compose's own converter, internal: by reflection). */
private val toComposeKey = Class.forName("androidx.compose.ui.input.key.KeyEvent_desktopKt").getMethod("toComposeEvent", java.awt.event.KeyEvent::class.java)

/** Stats (streak, the past filled in from the history, the screen) and the seek bar's hover picture. */
class StatsPreviewTest {
    private fun day(k: Long) = LocalDate.now().minusDays(k).toString()

    @Test fun streaks() {
        val a = DayLog(chapters = listOf("x#1"))
        // today empty, yesterday + the 2 days before active: 3 (today doesn't break it yet)
        assertEquals(3, Stats.streak(mapOf(day(1) to a, day(2) to a, day(3) to a, day(5) to a)))
        assertEquals(4, Stats.streak(mapOf(day(0) to a, day(1) to a, day(2) to a, day(3) to a)))
        assertEquals(0, Stats.streak(mapOf(day(2) to a)))
        // a short visit doesn't count, a minute does
        assertEquals(0, Stats.streak(mapOf(day(0) to DayLog(readMs = 30_000))))
        assertEquals(1, Stats.streak(mapOf(day(0) to DayLog(readMs = 60_000))))
        assertEquals(3, Stats.longestStreak(mapOf(day(9) to a, day(8) to a, day(7) to a, day(3) to a, day(1) to a, day(0) to a)))
    }

    /** The first start fills in the past from the history; bulk "mark read" isn't reading; then page moves add time. */
    @Test fun backfillAndCounting() {
        val root = File("build/statstest").apply { deleteRecursively(); mkdirs() }
        val ctx = android.content.Context(root)
        val data = File(ctx.filesDir, "data").apply { mkdirs() }
        val now = System.currentTimeMillis()
        val d2 = now - 2 * 86_400_000L
        val bulk = now - 5 * 86_400_000L
        val prog = buildString {
            append("{")
            append("\"atsu|a#1\":{\"page\":9,\"pages\":10,\"read\":true,\"time\":$d2},")
            append("\"atsu|a#2\":{\"page\":9,\"pages\":10,\"read\":true,\"time\":${d2 + 60_000}},")
            append("\"atsu|a#3\":{\"page\":3,\"pages\":10,\"read\":false,\"time\":${d2 + 120_000}},")
            (1..6).forEach { append("\"atsu|b#$it\":{\"page\":0,\"pages\":10,\"read\":true,\"time\":$bulk},") }
            append("\"atsu|c#1\":{\"page\":9,\"pages\":10,\"read\":true,\"time\":$now}")
            append("}")
        }
        File(data, "progress.json").writeText(prog)
        File(data, "videos.json").writeText("{\"v|1\":{\"ref\":{\"source\":\"v\",\"id\":\"1\",\"title\":\"Ep 1\"},\"positionMs\":600000,\"durationMs\":1400000,\"time\":$d2}," +
            "\"v|2\":{\"ref\":{\"source\":\"v\",\"id\":\"2\",\"title\":\"Peek\"},\"positionMs\":10000,\"durationMs\":1400000,\"time\":$d2}}")
        Repo.init(ctx); VideoRepo.init(ctx); Stats.init(ctx)
        val days = Stats.data.value.days
        println("[stats] filled in: $days")
        assertEquals(listOf("atsu|a#1", "atsu|a#2"), days[day(2)]?.chapters)
        assertEquals(listOf("v|1"), days[day(2)]?.videos)                       // the 10 s peek isn't a watch
        assertEquals(600_000L, days[day(2)]?.watchMs)
        assertNull("bulk-marked chapters aren't reading", days[day(5)])
        assertEquals(listOf("atsu|c#1"), days[day(0)]?.chapters)
        assertEquals(LocalDate.now().toString(), Stats.data.value.since)
        // page moves 1 s apart add reading time; a 3-minute pause doesn't
        Stats.reading("atsu|d#1", false); Thread.sleep(1100); Stats.reading("atsu|d#1", true)
        val t = Stats.data.value.days[day(0)]!!
        assertTrue("reading time ${t.readMs}", t.readMs in 1000..3000)
        assertTrue(t.chapters.contains("atsu|d#1"))
        // video saves: only time that really played (a seek forward doesn't count)
        Stats.watching("v|9", 0); Thread.sleep(1100); Stats.watching("v|9", 1000); Thread.sleep(1100); Stats.watching("v|9", 900_000)
        val w = Stats.data.value.days[day(0)]!!.watchMs
        assertTrue("watching $w", w in 1900..2500)
        Thread.sleep(11_000)   // written within 10 s
        assertTrue(File(data, "stats.json").readText().contains("atsu|d#1"))
    }

    @Test fun statsScreenShot() {
        val root = File("build/statstest2").apply { deleteRecursively(); mkdirs() }
        val ctx = android.content.Context(root)
        Repo.init(ctx); VideoRepo.init(ctx); Stats.init(ctx)
        var t = 0L
        fun shot(name: String, w: Int, h: Int) {
            val scene = ImageComposeScene(w, h, Density(1f)) { StatsScreen(onBack = {}, onOpen = {}) }
            repeat(20) { scene.render(t); t += 50_000_000L; Thread.sleep(20) }
            File("build/shots").mkdirs()
            File("build/shots/$name.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
        }
        shot("stats_empty", 1100, 1400)
        // made-up weeks of reading and watching (a gap 9 days ago, so the streak is 9)
        val rnd = java.util.Random(7)
        val days = (0 until 120).filter { it != 9 && rnd.nextInt(10) < 7 || it < 9 }.associate { k ->
            day(k.toLong()) to DayLog(readMs = rnd.nextInt(90) * 60_000L, watchMs = rnd.nextInt(60) * 60_000L,
                chapters = List(rnd.nextInt(9) + 1) { "atsu|t${it % 3}#$k-$it" }, videos = List(rnd.nextInt(3)) { "v|$k-$it" })
        }
        File(ctx.filesDir, "data/stats.json").writeText(com.yoruc.mangacracker.core.Http.json.encodeToString(
            com.yoruc.mangacracker.core.StatsFile.serializer(), com.yoruc.mangacracker.core.StatsFile(day(30), days)))
        Stats.init(ctx)
        assertEquals(9, Stats.streak(Stats.data.value.days))
        shot("stats_pc", 1100, 1500)
        shot("stats_phone", 412, 2200)
    }

    /** The gallery's viewer: ← → step through the items (keys and the arrows on screen), the keys still work after a click. */
    @Test fun galleryViewerKeys() {
        val dir = File("build/gallery_test")   // a few GIFs and an MP4 put there by hand
        assumeTrue(dir.isDirectory)
        val list = dir.listFiles()!!.filter { it.extension in setOf("gif", "mp4") }.sortedBy { it.name }.map { f ->
            com.yoruc.mangacracker.ui.Gallery.Item(f, if (f.extension == "gif") com.yoruc.mangacracker.ui.Gallery.Kind.GIF else com.yoruc.mangacracker.ui.Gallery.Kind.CLIP, f.lastModified(), f.length())
        }
        assumeTrue(list.size >= 3)
        var index by androidx.compose.runtime.mutableIntStateOf(0)
        var t = 0L
        val scene = ImageComposeScene(1280, 720, Density(1f)) {
            com.yoruc.mangacracker.ui.GalleryViewer(list, index, onIndex = { index = it }, onClose = {}, onDelete = {})
        }
        // an off-screen scene counts as an unfocused window and drops key presses: mark it focused, as a real window is
        val info = ImageComposeScene::class.java.getDeclaredField("_windowInfo").apply { isAccessible = true }.get(scene)
        info.javaClass.getMethod("setWindowFocused", Boolean::class.javaPrimitiveType).invoke(info, true)
        fun frames(n: Int = 6) = repeat(n) { scene.render(t); t += 50_000_000L; Thread.sleep(30) }
        val comp = java.awt.Canvas()
        fun key(code: Int) {
            for (type in listOf(java.awt.event.KeyEvent.KEY_PRESSED, java.awt.event.KeyEvent.KEY_RELEASED))
                scene.sendKeyEvent(androidx.compose.ui.input.key.KeyEvent(toComposeKey.invoke(null, java.awt.event.KeyEvent(comp, type, 0, 0, code, java.awt.event.KeyEvent.CHAR_UNDEFINED))!!))
            frames(4)
        }
        fun click(at: Offset) { scene.sendPointerEvent(PointerEventType.Press, at); frames(1); scene.sendPointerEvent(PointerEventType.Release, at); frames(4) }
        frames(20)
        key(java.awt.event.KeyEvent.VK_RIGHT); assertEquals("→", 1, index)
        key(java.awt.event.KeyEvent.VK_RIGHT); assertEquals("→ again", 2, index)
        key(java.awt.event.KeyEvent.VK_LEFT); assertEquals("←", 1, index)
        key(java.awt.event.KeyEvent.VK_LEFT); key(java.awt.event.KeyEvent.VK_LEFT); assertEquals("stops at the first", 0, index)
        File("build/shots").mkdirs()
        File("build/shots/gallery_viewer.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        click(Offset(1256f, 357f)); assertEquals("the › on screen", 1, index)
        key(java.awt.event.KeyEvent.VK_RIGHT); assertEquals("keys after a click", 2, index)
        click(Offset(24f, 357f)); assertEquals("the ‹ on screen", 1, index)
        key(java.awt.event.KeyEvent.VK_LEFT); assertEquals("keys after the other click", 0, index)
        println("[gallery] viewer keys: → → ← ← ← and the arrows on screen step through ${list.size} items")
        scene.close()
    }

    /** Windows shortcuts in the save folders lead to more folders: a folder (also with an accent), never a file. */
    @Test fun shortcutTargetsAndMove() {
        val dir = File("build/gallery_test")   // shortcuts made there by hand (PowerShell's WScript.Shell)
        assumeTrue(File(dir, "old - Shortcut.lnk").isFile)
        val g = com.yoruc.mangacracker.ui.Gallery
        assertEquals(File(dir, "linked old").canonicalFile, g.lnkTarget(File(dir, "old - Shortcut.lnk"))?.canonicalFile)
        assertEquals(File(dir, "café gifs").canonicalFile, g.lnkTarget(File(dir, "cafe - Shortcut.lnk"))?.canonicalFile)
        assertNull("a shortcut to a file", g.lnkTarget(File(dir, "file - Shortcut.lnk")))
        assertNull("not a shortcut", g.lnkTarget(File(dir, "Test GIF 0.gif")))
        // Move to: the file goes there, a name already taken gets " (2)", nothing is overwritten
        val to = File(dir, "linked old")
        val a = File(dir, "move me.gif").apply { writeBytes(File(dir, "Test GIF 0.gif").readBytes()) }
        val m1 = g.moveTo(a, to)!!
        assertTrue(m1.isFile && !a.exists() && m1.parentFile.canonicalFile == to.canonicalFile)
        val b = File(dir, "move me.gif").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val m2 = g.moveTo(b, to)!!
        assertEquals("move me (2).gif", m2.name)
        assertEquals(1440557L, m1.length())
        println("[gallery] shortcuts read; moved to ${m1.name}, then ${m2.name}")
        m1.delete(); m2.delete()
    }

    /** The bar says where the mouse is (0..1 between its insets), and null when it leaves. */
    @Test fun seekBarHover() {
        var got: Float? = -1f
        var t = 0L
        val scene = ImageComposeScene(600, 100, Density(1f)) {
            Box(Modifier.fillMaxWidth().padding(top = 26.dp)) { SeekBar(30_000, 120_000, 40_000, Modifier.fillMaxWidth(), onHover = { f, _ -> got = f }, onSeek = {}) }
        }
        fun frames() = repeat(3) { scene.render(t); t += 50_000_000L }
        frames()
        scene.sendPointerEvent(PointerEventType.Enter, Offset(300f, 50f)); scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 50f)); frames()
        assertEquals(0.5f, got!!, 0.02f)
        scene.sendPointerEvent(PointerEventType.Move, Offset(11f, 50f)); frames()
        assertEquals(0f, got!!, 0.01f)
        scene.sendPointerEvent(PointerEventType.Move, Offset(300f, 95f)); frames()   // below the bar
        assertNull(got)
        scene.sendPointerEvent(PointerEventType.Move, Offset(450f, 50f)); frames()
        assertEquals(0.75f, got!!, 0.02f)
        scene.sendPointerEvent(PointerEventType.Exit, Offset(700f, 50f)); frames()
        assertNull(got)
        scene.close()
    }

    private fun grabTimes(name: String, input: List<String>, dur: Double, at: List<Double>) {
        val sp = SeekPreview(input, dur, HoverWindow.GRAB_WIDTH)
        val times = at.map { s ->
            val t0 = System.nanoTime()
            val img = sp.grab(s)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertNotNull("$name: a picture at $s s", img)
            assertEquals(HoverWindow.GRAB_WIDTH, img!!.width)
            val diffFromBlack = (0 until 200).count { k -> img.getRGB(img.width * k / 200, img.height / 2) and 0xFFFFFF > 0x101010 }
            println("[preview] $name at ${"%.0f".format(s)} s: ${img.width}x${img.height} in $ms ms, $diffFromBlack/200 non-black")
            if (s == at.last()) {
                File("build/shots").mkdirs()
                javax.imageio.ImageIO.write(img, "png", File("build/shots/preview_$name.png"))
            }
            ms
        }
        println("[preview] $name: average ${times.average().toLong()} ms, slowest ${times.max()} ms")
        sp.close()
    }

    @Test fun grabLocal() {
        val clip = File("build/rife_test/clip.mp4")
        assumeTrue(clip.isFile)
        grabTimes("clip", listOf("-i", clip.absolutePath), 30.0, listOf(1.0, 9.0, 17.0, 25.0))
        // the hover window as it looks
        val sp = SeekPreview(listOf("-i", clip.absolutePath), 30.0, HoverWindow.GRAB_WIDTH)
        val img = sp.grab(12.0)!!
        System.setProperty("java.awt.headless", "false")
        if (!java.awt.GraphicsEnvironment.isHeadless()) {
            val f = javax.swing.JFrame().apply { setLocation(-6000, -6000); setSize(10, 10) }
            val w = HoverWindow(f)
            w.show(-6000, -5000, -6400, -5600, img, true, "0:12")
            javax.imageio.ImageIO.write(w.snapshot(), "png", File("build/shots/preview_window.png"))
            w.show(-6000, -5000, -6400, -5600, img, false, "0:14")
            javax.imageio.ImageIO.write(w.snapshot(), "png", File("build/shots/preview_window_near.png"))
            w.dispose(); f.dispose()
        }
        sp.close()
    }

    /** Which ffmpeg input is quickest for hover pictures: as played, the smallest variant, smaller probing. */
    @Test fun previewSpeed() = kotlinx.coroutines.runBlocking {
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }
        assumeTrue(pick.isNotEmpty() && System.getProperty("cutoutData") == "speed")
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        for (id in pick) {
            val src = com.yoruc.mangacracker.video.VideoSources.byId(id)!!
            val d = src.details(src.latest(1).items.first())
            src.beforePlay()
            val text = src.playerClient.newCall(com.yoruc.mangacracker.core.Http.request(d.stream.url)).execute().use { it.body?.string().orEmpty() }
            val vs = com.yoruc.mangacracker.video.VideoDownloads.variants(text, d.stream.url)
            println("[speed] $id: ${d.stream.kind}, variants ${vs.map { "${it.height}p/${it.bandwidth / 1000}k" }}")
            val asPlayed = com.yoruc.mangacracker.platform.StreamProxy.ffmpegInput(src.id, d.stream.url, d.stream.kind)
            val low = vs.minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE }?.let { com.yoruc.mangacracker.platform.StreamProxy.ffmpegInput(src.id, it.url, "hls") } ?: asPlayed
            val probe = listOf("-probesize", "1000000", "-analyzeduration", "1000000")
            for ((name, input) in listOf("as played" to asPlayed, "smallest" to low, "smallest + small probe" to probe + low, "as played + small probe" to probe + asPlayed)) {
                val sp = SeekPreview(input, 600.0, HoverWindow.GRAB_WIDTH)
                val ms = listOf(40.0, 210.0, 340.0, 480.0).map { s -> val t0 = System.nanoTime(); val ok = sp.grab(s) != null; ((System.nanoTime() - t0) / 1_000_000).let { if (ok) it else -it } }
                println("[speed] $id $name: $ms ms")
                sp.close()
            }
        }
    }

    /** Live: -Psources=muchohentai,anikoto (the first video of each). */
    @Test fun grabLive() = kotlinx.coroutines.runBlocking {
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }
        assumeTrue(pick.isNotEmpty())
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        for (id in pick) {
            val src = com.yoruc.mangacracker.video.VideoSources.byId(id)!!
            val d = src.details(src.latest(1).items.first())
            src.beforePlay()
            val input = SeekPreview.smallest(src.id, d.stream.url, d.stream.kind)
            grabTimes(id, input, 600.0, listOf(30.0, 200.0, 330.0, 470.0))
        }
    }
}
