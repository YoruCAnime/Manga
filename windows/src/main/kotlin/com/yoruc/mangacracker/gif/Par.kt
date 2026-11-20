package com.yoruc.mangacracker.gif

import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/** Splits picture work into bands of rows over a few low-priority threads (phones have 6-8 cores). */
object Par {
    /** How many bands run at once (tests on the PC set this low). */
    @Volatile var threads = maxOf(1, minOf(4, Runtime.getRuntime().availableProcessors() - 1))

    private val made = AtomicInteger()
    private val pool by lazy {
        Executors.newFixedThreadPool(8) { r -> Thread(r, "gif-${made.incrementAndGet()}").apply { isDaemon = true; priority = Thread.MIN_PRIORITY } }
    }

    /** Runs [body] on row ranges [from, to) that together cover 0 until [h]; returns when all are done. */
    fun rows(h: Int, body: (Int, Int) -> Unit) {
        val n = minOf(threads, maxOf(1, h / 24))
        if (n <= 1) { body(0, h); return }
        val step = (h + n - 1) / n
        val jobs = (1 until n).map { k -> pool.submit { body(k * step, minOf(h, (k + 1) * step)) } }
        body(0, minOf(h, step))
        jobs.forEach { it.get() }
    }
}
