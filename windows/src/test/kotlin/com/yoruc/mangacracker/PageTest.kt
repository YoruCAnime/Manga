package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** One page of each source's first popular title: fetched with the image client and decoded by Skia. */
class PageTest {
    @Test fun pages() = runBlocking {
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        com.yoruc.mangacracker.platform.ComixEdge.install()
        Runtime.getRuntime().addShutdownHook(Thread { com.yoruc.mangacracker.platform.Edge.close() })
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        Sources.all.filter { pick.isEmpty() || it.id in pick }.forEach { s ->
            val r = runCatching {
                val ref = s.popular(1).items.first()
                val ch = s.chapters(ref).last()
                val p = s.pages(ref, ch).first()
                val url = p.url.ifEmpty { s.imageUrl(p) }
                val b = Http.bytes(url)
                val head = b.take(12).map { it.toInt().toChar() }.joinToString("").filter { it.isLetterOrDigit() }
                val img = runCatching { org.jetbrains.skia.Image.makeFromEncoded(if (com.yoruc.mangacracker.platform.Avif.isAvif(b)) com.yoruc.mangacracker.platform.Avif.toPng(b, 800) else b).use { "${it.width}x${it.height}" } }.getOrElse { "NO DECODE (${it.message})" }
                "${b.size} bytes [$head] $img  ${url.take(70)}"
            }
            println("[page] ${s.id}: " + r.getOrElse { "FAIL ${it.message?.take(400)}" })
        }
    }
}
