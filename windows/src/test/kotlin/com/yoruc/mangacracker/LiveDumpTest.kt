package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.video.VideoRepo
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Saves pages (HTML text only) through the app's own client for reading their layout. DUMP=name=url;name=url -Psources=livedump */
class LiveDumpTest {
    @Test fun dump() = runBlocking {
        if (System.getProperty("sources") != "livedump") return@runBlocking
        val ctx = Context(File("build/livevid").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val out = File(System.getenv("DUMP_DIR") ?: "build/livedump").apply { mkdirs() }
        for (pair in System.getenv("DUMP").orEmpty().split(';').filter { '=' in it }) {
            val (name, url) = pair.split('=', limit = 2)
            val r = runCatching { Http.getRaw(url, mapOf("Referer" to url.substringBefore("/", "") )) }
            r.onSuccess { (code, body) -> File(out, "$name.html").writeText(body); println("[dump] $name $code ${body.length / 1024} KB") }
                .onFailure { println("[dump] $name FAIL ${it.message}") }
            delay(1200)
        }
    }
}
