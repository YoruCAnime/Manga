package com.yoruc.mangacracker

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerKeyboardModifiers
import androidx.compose.ui.unit.Density
import com.yoruc.mangacracker.core.Chapter
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Tabs
import com.yoruc.mangacracker.ui.tabClick
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TabsTest {
    private fun ctx(): Context {
        val dir = File("build/tabs").apply { deleteRecursively(); mkdirs() }
        return Context(dir).also { Http.init(it); Repo.init(it); VideoRepo.init(it); Tabs.init(it) }
    }
    private val m1 = MangaRef("atsu", "one", "First", "c1")
    private val m2 = MangaRef("atsu", "two", "Second", "c2")
    private val v1 = VideoRef("anikoto", "v1", "Show", "u")

    @Test fun list() {
        val c = ctx()
        val a = Tabs.addManga(m1)!!
        assertEquals("the same page twice is one tab", a.id, Tabs.addManga(m1)!!.id)
        val b = Tabs.addChapter(m2, Chapter("ch", "Chapter 1"))!!
        val v = Tabs.addVideo(v1)!!
        assertEquals(3, Tabs.list.value.size)
        Tabs.activate(a.id)
        assertEquals(b.id, Tabs.step(1)!!.id)
        Tabs.activate(v.id)
        assertEquals("wraps round", a.id, Tabs.step(1)!!.id)
        // the active tab follows its page; a main screen means no tab showing
        Tabs.activate(a.id)
        Tabs.noteScreen("reader/{key}/{chapter}?grid={grid}", m1.key, "c9", false)
        assertTrue(Tabs.list.value.first { it.id == a.id }.route.startsWith("reader/"))
        Tabs.noteScreen("home", "", "", false)
        assertEquals(0L, Tabs.active.value)
        assertNull(Tabs.here.value)
        // opened the normal way, a page that is already a tab lights it up; another page doesn't make a tab
        Tabs.noteScreen("player/{key}", v1.key, "", false)
        assertEquals(v.id, Tabs.active.value)
        Tabs.noteScreen("home", "", "", false)
        Tabs.noteScreen("details/{key}", m2.key, "", false)
        assertEquals(3, Tabs.list.value.size)
        assertEquals(0L, Tabs.active.value)
        val pinned = Tabs.pinHere()!!
        assertEquals(pinned.id, Tabs.active.value)
        assertEquals(4, Tabs.list.value.size)
        // closing another one gives nothing to show; closing the one showing gives its neighbour
        assertNull(Tabs.close(b.id))
        assertEquals(v.id, Tabs.list.value[1].id)
        assertEquals("the last one's neighbour is the one before", v.id, Tabs.close(pinned.id)!!.id)
        // a next episode moves the tab
        Tabs.activate(v.id); Tabs.noteScreen("player/{key}", v1.key, "", false)
        val v2 = v1.copy(id = "v2", title = "Show 2")
        Tabs.noteVideo(v2)
        assertTrue(Tabs.list.value.first { it.id == v.id }.route.endsWith("v2"))
        assertEquals("Show 2", Tabs.list.value.first { it.id == v.id }.title)
        // kept for the next start, none showing
        Tabs.init(c)
        assertEquals(2, Tabs.list.value.size)
        assertEquals(0L, Tabs.active.value)
        repeat(Tabs.MAX + 3) { Tabs.addManga(m1.copy(id = "n$it")) }
        assertEquals(Tabs.MAX, Tabs.list.value.size)
        assertNull(Tabs.addManga(m1.copy(id = "over")))
        Tabs.clear()
        Tabs.init(c)
        assertTrue(Tabs.list.value.isEmpty())
        println("[tabs] list ok")
    }

    @Test fun clicks() {
        var tabs = 0; var plain = 0
        val scene = ImageComposeScene(200, 200, Density(1f)) {
            Box(Modifier.fillMaxSize().tabClick { tabs++ }.clickable { plain++ })
            com.yoruc.mangacracker.ui.TabMenuHost()
        }
        var t = 0L
        fun frame() { t += 20_000_000L; scene.render(t) }
        fun click(b: PointerButton, btns: PointerButtons, ctrl: Boolean) {
            val keys = PointerKeyboardModifiers(isCtrlPressed = ctrl)
            val at = Offset(100f, 100f)
            scene.sendPointerEvent(PointerEventType.Move, at, keyboardModifiers = keys); frame()
            scene.sendPointerEvent(PointerEventType.Press, at, buttons = btns, keyboardModifiers = keys, button = b); frame()
            scene.sendPointerEvent(PointerEventType.Release, at, buttons = PointerButtons(), keyboardModifiers = keys, button = b); frame(); frame()
            Thread.sleep(400); frame()
        }
        frame()
        click(PointerButton.Primary, PointerButtons(isPrimaryPressed = true), false)
        assertEquals("a plain click stays a plain click", 1 to 0, plain to tabs)
        click(PointerButton.Primary, PointerButtons(isPrimaryPressed = true), true)
        assertEquals("Ctrl+click makes a tab and nothing else", 1 to 1, plain to tabs)
        click(PointerButton.Tertiary, PointerButtons(isTertiaryPressed = true), false)
        assertEquals("middle click makes a tab", 1 to 2, plain to tabs)
        // a right click offers it; pressing the line makes the tab
        click(PointerButton.Secondary, PointerButtons(isSecondaryPressed = true), false)
        assertEquals("a right click only opens the menu", 1 to 2, plain to tabs)
        assertTrue(com.yoruc.mangacracker.ui.TabMenu.at != null)
        assertEquals(100f, com.yoruc.mangacracker.ui.TabMenu.at!!.x, 0.5f)
        frame(); frame()
        File("build/shots").mkdirs()
        File("build/shots/tab_menu.png").writeBytes(scene.render(t + 100_000_000L).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        com.yoruc.mangacracker.ui.TabMenu.act(); com.yoruc.mangacracker.ui.TabMenu.at = null; frame()
        assertEquals(1 to 3, plain to tabs)
        scene.close()
        println("[tabs] clicks ok")
    }

    /** Opened from a source's list, the list becomes a tab of its own and the titles line up after it. */
    @Test fun place() {
        ctx()
        val browse = "browse/{source}?search={search}"
        Tabs.noteScreen("home", "", "", false)
        val home = Tabs.addManga(MangaRef("atsu", "zero", "From home", "c0"))!!
        Tabs.noteScreen(browse, "", "", false, "atsu", false, "Atsu")
        assertEquals("no tab for a list nothing was opened from", 0L, Tabs.active.value)
        val a = Tabs.addManga(m1)!!
        val list = Tabs.list.value
        assertEquals(3, list.size)
        val place = list[1]
        assertTrue(place.isPlace)
        assertEquals("Atsu", place.title)
        assertEquals(Tabs.browseRoute("atsu"), place.route)
        assertEquals("the list stays on screen, lit up", place.id, Tabs.active.value)
        assertEquals(a.id, list[2].id)
        val b = Tabs.addManga(m2)!!
        assertEquals(listOf(home.id, place.id, a.id, b.id), Tabs.list.value.map { it.id })
        // a title opened the normal way from the list: the list's tab stays what it is
        Tabs.noteScreen("details/{key}", "atsu|other", "", false)
        assertEquals(0L, Tabs.active.value)
        assertEquals(Tabs.browseRoute("atsu"), Tabs.list.value.first { it.id == place.id }.route)
        Tabs.noteScreen("details/{key}", m2.key, "", false)
        assertEquals(b.id, Tabs.active.value)
        // back on the list: its tab lights up; another source's list has no tab
        Tabs.noteScreen(browse, "", "", false, "atsu", false, "Atsu")
        assertEquals(place.id, Tabs.active.value)
        Tabs.noteScreen(browse, "", "", false, "comix", false, "Comix")
        assertEquals(0L, Tabs.active.value)
        // a second list goes to the end with its own titles
        val c = Tabs.addVideo(v1)!!
        val l2 = Tabs.list.value
        assertEquals(6, l2.size)
        assertTrue(l2[4].isPlace)
        assertEquals(c.id, l2[5].id)
        // the first list's next title still lands next to it
        Tabs.noteScreen(browse, "", "", false, "atsu", false, "Atsu")
        val d = Tabs.addManga(MangaRef("atsu", "three", "Third", "c3"))!!
        assertEquals(listOf(home.id, place.id, a.id, b.id, d.id), Tabs.list.value.take(5).map { it.id })
    }
}
