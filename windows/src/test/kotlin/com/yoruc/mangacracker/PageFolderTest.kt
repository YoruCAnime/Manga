package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.ImageSaver
import com.yoruc.mangacracker.core.Repo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Saved reader pages: Pictures\picif\<title>\ when picif is there, the Settings folder when set, else Pictures\MangaCracker. */
class PageFolderTest {
    @Test fun folders() = runBlocking {
        val root = File("build/pagefolder").apply { deleteRecursively(); mkdirs() }
        val home = File(root, "home")
        val png = File(root, "page.png")
        javax.imageio.ImageIO.write(java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", png)
        val url = "file:" + png.absolutePath
        val saved = System.getProperty("user.home")
        System.setProperty("user.home", home.absolutePath)
        try {
            Repo.init(Context(File(root, "data")))
            Repo.updateSettings { it.copy(pageFolder = "") }
            val ctx = Context(File(root, "data"))
            // no picif yet: Pictures\MangaCracker\<title>
            val a = ImageSaver.save(ctx, url, "Solo Leveling", "Chapter 1 - 001")
            assertEquals(File(home, "Pictures/MangaCracker/Solo Leveling").canonicalPath, a.parentFile.canonicalPath)
            // picif there: every save of a title in its own folder inside it, chapters by name
            File(home, "Pictures/picif").mkdirs()
            val b = ImageSaver.save(ctx, url, "Solo Leveling", "Chapter 1 - 001")
            val c = ImageSaver.save(ctx, url, "Solo Leveling", "Chapter 2 - 001")
            val d = ImageSaver.save(ctx, url, "Another: Title?", "Chapter 1 - 005")
            println("[pages] $b | $c | $d")
            val title = File(home, "Pictures/picif/Solo Leveling").canonicalPath
            assertEquals(title, b.parentFile.canonicalPath)
            assertEquals(title, c.parentFile.canonicalPath)
            assertEquals("Chapter 2 - 001.png", c.name)
            assertEquals(File(home, "Pictures/picif/Another Title").canonicalPath, d.parentFile.canonicalPath)
            // the same name again: a second file, never overwritten
            val e = ImageSaver.save(ctx, url, "Solo Leveling", "Chapter 1 - 001")
            assertEquals("Chapter 1 - 001 (2).png", e.name)
            assertEquals(ImageSaver.folderOf("Solo Leveling").canonicalPath, title)
            // a folder chosen in Settings wins
            val chosen = File(root, "chosen")
            Repo.updateSettings { it.copy(pageFolder = chosen.absolutePath) }
            val f = ImageSaver.save(ctx, url, "Solo Leveling", "Chapter 3 - 001")
            assertEquals(File(chosen, "Solo Leveling").canonicalPath, f.parentFile.canonicalPath)
            assertTrue(f.isFile)
            Repo.updateSettings { it.copy(pageFolder = "") }
            println("[pages] ok")
        } finally { System.setProperty("user.home", saved) }
    }
}
