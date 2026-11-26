package com.yoruc.mangacracker

import android.content.Context
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.ui.GalleryScreen
import com.yoruc.mangacracker.ui.Nav
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/** My GIFs & clips: closing a GIF's preview comes back to where the list was scrolled (Exos 2026-11-14: it went to the top). */
class GalleryScrollTest {
    @Test fun closeKeepsTheScroll() {
        val root = File("build/galscroll").apply { deleteRecursively(); mkdirs() }
        val home = File(root, "home")
        // 60 numbered GIFs in a pretend Videos\MangaCracker (the real folders are never read)
        val dir = File(home, "Videos/MangaCracker").apply { mkdirs() }
        repeat(60) { i ->
            val img = BufferedImage(320, 180, BufferedImage.TYPE_INT_RGB)
            img.createGraphics().apply {
                color = Color.getHSBColor(i / 60f, 0.7f, 0.9f); fillRect(0, 0, 320, 180)
                color = Color.BLACK; font = Font(Font.SANS_SERIF, Font.BOLD, 90); drawString("$i", 90, 130); dispose()
            }
            ImageIO.write(img, "gif", File(dir, "gif %02d.gif".format(i)).also { it.setLastModified(1_700_000_000_000L + i * 60_000L) })
        }
        val saved = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            Repo.init(Context(File(root, "data")))
            var shown = 0
            com.yoruc.mangacracker.ui.galleryProbe = { list, _ -> shown = list.size }
            val scene = ImageComposeScene(1200, 800, Density(1f)) { GalleryScreen(Nav(rememberNavController())) }
            var t = 0L
            fun frames(n: Int = 6) = repeat(n) { t += 50_000_000L; scene.render(t); Thread.sleep(40) }
            fun pixels(): IntArray { t += 50_000_000L; val bmp = org.jetbrains.skia.Bitmap.makeFromImage(scene.render(t)); return IntArray(1200 * 800) { k -> bmp.getColor(k % 1200, k / 1200) } }
            var waited = 0
            while (shown < 60 && waited < 200) { frames(1); waited++ }
            assertEquals(60, shown)
            frames(30)
            val top = pixels()
            repeat(8) { scene.sendPointerEvent(PointerEventType.Scroll, Offset(600f, 500f), scrollDelta = Offset(0f, 3f)); frames(2) }
            frames(20)
            val scrolled = pixels()
            assertNotEquals("the list moved", top.toList(), scrolled.toList())
            com.yoruc.mangacracker.ui.galleryOpen!!(12); frames(10)
            com.yoruc.mangacracker.ui.galleryOpen!!(null); frames(30)
            val after = pixels()
            val same = scrolled.indices.count { scrolled[it] == after[it] } * 100.0 / scrolled.size
            val sameAsTop = top.indices.count { top[it] == after[it] } * 100.0 / top.size
            println("[galscroll] after closing: %.1f%% the same as before opening, %.1f%% the same as the top".format(same, sameAsTop))
            assertTrue("back where it was", same > 98.0)
            scene.close()
        } finally {
            System.setProperty("user.home", saved)
            com.yoruc.mangacracker.ui.galleryProbe = null
        }
    }
}
