package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.WebCookieJar
import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Many threads saving cookies at once, the way parallel downloads do (the 2026-11-08 / -10 crashes: "Index 22 out of
 * bounds" inside WebCookieJar.store). No error, and each cookie name kept once.
 */
class CookieRaceTest {
    @Test fun parallelSaves() {
        val dir = File("build/cookierace").apply { deleteRecursively(); mkdirs() }
        WebCookieJar.load(File(dir, "cookies.json"))
        val url = "https://race.example.test/".toHttpUrl()
        val pool = Executors.newFixedThreadPool(16)
        val failed = AtomicReference<Throwable?>(null)
        val start = CountDownLatch(1)
        repeat(16) { t ->
            pool.execute {
                start.await()
                runCatching {
                    repeat(1500) { i ->
                        val cookies = (0 until 6).map { k ->
                            Cookie.Builder().domain("race.example.test").path("/").name("c${(i + k + t) % 25}").value("$t-$i")
                                .expiresAt(System.currentTimeMillis() + 3_600_000).build()
                        }
                        WebCookieJar.saveFromResponse(url, cookies)
                        if (i % 50 == 0) WebCookieJar.clear(listOf("race.example.test"), keep = setOf("c1", "c2"))
                        WebCookieJar.loadForRequest(url)
                    }
                }.onFailure { failed.compareAndSet(null, it) }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue("finished", pool.awaitTermination(120, TimeUnit.SECONDS))
        failed.get()?.let { throw AssertionError("a save failed: $it", it) }
        val names = WebCookieJar.loadForRequest(url).map { it.name }
        assertEquals("each cookie once", names.toSet().size, names.size)
        WebCookieJar.clear(listOf("race.example.test"))
        println("[cookies] 16 threads x 1500 saves ok, ${names.size} cookies kept, no duplicates")
    }
}
