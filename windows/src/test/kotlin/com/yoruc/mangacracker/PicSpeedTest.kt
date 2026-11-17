package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.platform.Avif
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Live: how long a screen of covers and the first pages of a chapter take to arrive, all asked for at once the way a
 * grid does (-Psources=atsu,toonily). Times and counts only.
 */
class PicSpeedTest {
    private suspend fun together(urls: List<String>): Pair<Long, List<ByteArray?>> {
        val t0 = System.currentTimeMillis()
        val got = kotlinx.coroutines.coroutineScope { urls.map { u -> async(Dispatchers.IO) { runCatching { Http.bytes(u) }.getOrNull() } }.awaitAll() }
        return (System.currentTimeMillis() - t0) to got
    }

    @Test fun pictures() = runBlocking {
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }
        if (pick.isEmpty()) return@runBlocking
        val ctx = Context(File("build/picspeed").apply { deleteRecursively(); mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        Repo.updateSettings { it.copy(ageConfirmed = it.ageConfirmed + pick) }
        for (id in pick) {
            val s = Sources.byId(id) ?: continue
            val list = runCatching { s.popular(1).items }.getOrElse { println("[pics] $id list FAIL ${it.message?.take(100)}"); emptyList() }
            val covers = list.map { it.cover }.filter { it.isNotBlank() }.take(24)
            val (ms, got) = together(covers)
            println("[pics] $id covers: ${got.count { it != null }} of ${covers.size} in $ms ms (${got.sumOf { it?.size ?: 0 } / 1024} KB), host ${covers.firstOrNull()?.substringAfter("//")?.substringBefore('/')}")
            val ref = list.firstOrNull { r -> runCatching { s.chapters(r) }.getOrDefault(emptyList()).isNotEmpty() } ?: continue
            val pages = runCatching { s.pages(ref, s.chapters(ref).last()) }.getOrElse { println("[pics] $id pages FAIL ${it.message?.take(100)}"); emptyList() }
            val urls = pages.take(8).mapNotNull { p -> runCatching { p.url.ifEmpty { s.imageUrl(p) } }.getOrNull() }
            val (pm, pg) = together(urls)
            val avif = pg.filterNotNull().filter { Avif.isAvif(it) }
            println("[pics] $id pages: ${pg.count { it != null }} of ${urls.size} in $pm ms (${pg.sumOf { it?.size ?: 0 } / 1024} KB), AVIF ${avif.size}, host ${urls.firstOrNull()?.substringAfter("//")?.substringBefore('/')}")
            avif.take(3).forEach { b ->
                val t = System.currentTimeMillis()
                val png = runCatching { Avif.toPng(b, 900, 0) }.getOrNull()
                println("[pics] $id AVIF page ${b.size / 1024} KB -> ${png?.size?.div(1024)} KB PNG in ${System.currentTimeMillis() - t} ms")
            }
        }
    }
}
