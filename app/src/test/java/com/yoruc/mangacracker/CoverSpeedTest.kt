package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/** How fast does a whole grid of video covers download (all at once, like the app)? Prints codes / sizes / times. */
class CoverSpeedTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() { Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http")) }
    }

    @Test fun grids() = runBlocking {
        for (src in VideoSources.all) {
            val items = src.latest(1).items
            // the fallback addresses must be real pictures too
            val alts = items.take(4).flatMap { it.altCovers }
            val altRes = alts.map { u -> runCatching { Http.imageClient.newCall(Request.Builder().url(u).build()).execute().use { "${it.code} ${it.header("Content-Type")}" } }.getOrElse { "ERR" } }
            println("[covers] ${src.id} fallbacks: ${altRes.count { it.startsWith("200 image") }}/${alts.size} are pictures ${altRes.groupingBy { it }.eachCount()}")
            val covers = items.map { it.cover }
            val t0 = System.currentTimeMillis()
            val res = covers.map { u ->
                async(Dispatchers.IO) {
                    val s = System.currentTimeMillis()
                    runCatching {
                        Http.imageClient.newCall(Request.Builder().url(u).build()).execute().use { r -> "${r.code} ${(r.body?.bytes()?.size ?: 0) / 1024}KB" }
                    }.getOrElse { "ERR ${it.javaClass.simpleName}" } + " ${System.currentTimeMillis() - s}ms"
                }
            }.awaitAll()
            println("[covers] ${src.id}: ${covers.size} in ${System.currentTimeMillis() - t0} ms; ok=${res.count { it.startsWith("200") }}; " +
                "slowest=${res.maxOf { it.substringAfterLast(' ').removeSuffix("ms").toLong() }}ms; sizes=${res.take(6).joinToString { it.substringBefore(" ", "").let { _ -> it.split(' ')[1] } }}")
            res.filter { !it.startsWith("200") }.take(3).forEach { println("[covers] ${src.id} bad: $it") }
        }
    }
}
