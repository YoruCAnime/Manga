package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.ImageSaver
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.DanbooruSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Live (-Psources=savequeue): pictures saved through the background queue are the site's full-size originals. Sizes only. */
class SaveQueueTest {
    @Test fun originals() = runBlocking {
        if (System.getProperty("sources") != "savequeue") return@runBlocking
        val dir = File("build/savequeue").apply { deleteRecursively(); mkdirs() }
        val ctx = Context(dir)
        Http.init(ctx); Repo.init(ctx)
        val out = File(dir, "out").apply { mkdirs() }
        Repo.updateSettings { it.copy(pageFolder = out.absolutePath) }

        // posts whose reading copy is a reduced sample
        val posts = DanbooruSource.popular(1).items.take(12).mapNotNull { r ->
            runCatching { DanbooruSource.pages(r, DanbooruSource.chapters(r).last()).first() }.getOrNull()?.takeIf { it.original.isNotEmpty() && it.original != it.url }
        }.take(3)
        println("[save] posts with a bigger original: ${posts.size}")
        assertTrue(posts.isNotEmpty())
        ImageSaver.enqueue(posts.mapIndexed { i, p -> ImageSaver.Job("Queue test", "picture ${i + 1}", p.original) { p.url } })
        // a page with no original: its own file is saved as it is
        val plain = posts.first().url
        ImageSaver.enqueue(listOf(ImageSaver.Job("Queue test", "plain", "") { plain }))
        var waited = 0
        while (ImageSaver.waiting.value > 0 && waited < 120) { delay(500); waited++ }
        assertEquals(0, ImageSaver.waiting.value)
        delay(300)
        val saved = File(out, "Queue test").listFiles().orEmpty().sortedBy { it.name }
        println("[save] files: " + saved.joinToString { it.name + " " + it.length() / 1024 + " KB" })
        assertEquals(posts.size + 1, saved.size)
        posts.forEachIndexed { i, p ->
            val f = saved.first { it.name.startsWith("picture ${i + 1}.") }
            val img = org.jetbrains.skia.Image.makeFromEncoded(f.readBytes())
            val sample = org.jetbrains.skia.Image.makeFromEncoded(Http.bytes(p.url))
            println("[save] picture ${i + 1}: saved ${img.width}x${img.height}, the reading copy is ${sample.width}x${sample.height}")
            assertTrue("the saved picture is the bigger one", img.width > sample.width)
        }
        assertEquals(Http.bytes(plain).size.toLong(), saved.first { it.name.startsWith("plain.") }.length())
    }
}
