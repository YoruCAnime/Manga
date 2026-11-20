package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.video.LiveHosts
import com.yoruc.mangacracker.video.MissAvSource
import com.yoruc.mangacracker.video.SupJavSource
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.Test
import java.io.File

/** Live: where supjav's players lead, what each host gives, and whether the streams answer the player's client. -Psources=liveplayers */
class LivePlayersTest {
    private fun head(url: String): String = runCatching {
        Http.imageClient.newCall(Request.Builder().url(url).header("Range", "bytes=0-200").build()).execute().use { r ->
            "${r.code} ${r.header("Content-Type")} '${r.body?.string()?.take(20)?.replace("\n", " ")}'"
        }
    }.getOrElse { "FAIL ${it.message}" }

    @Test fun players() = runBlocking {
        if (System.getProperty("sources") != "liveplayers") return@runBlocking
        val ctx = Context(File("build/livevid").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx); VideoRepo.init(ctx)
        val page = Http.getDoc(System.getenv("SJ_URL") ?: "https://supjav.com/474016.html")
        for (a in page.select("div.btnst > a[data-link]")) {
            val code = a.attr("data-link").reversed()
            val host = runCatching { LiveHosts.location("https://lk1.supremejav.com/supjav.php?c=$code", "https://lk1.supremejav.com/") }
            println("[players] ${a.text()} -> ${host.getOrElse { "FAIL ${it.message}" }}")
            host.getOrNull()?.let { h ->
                val s = runCatching { LiveHosts.stream(h, "https://supjav.com/") }
                println("[players]    stream: ${s.getOrNull()?.let { "${it.kind} ${it.url.take(70)} -> ${head(it.url)}" } ?: "none (${s.exceptionOrNull()?.message})"}")
            }
        }
        val d = runCatching { SupJavSource.details(VideoRef("supjav", "474016", "", "https://supjav.com/474016.html")) }
        println("[players] supjav details: " + d.fold({ "${it.stream.kind} ${it.stream.url.take(70)}" }, { "FAIL ${it.message}" }))
        val m = MissAvSource.latest(1).items.first()
        val md = runCatching { MissAvSource.details(m) }
        println("[players] missav: " + md.fold({ "${it.stream.url.take(80)} -> ${head(it.stream.url)} | related ${it.episodes.size - 1}" }, { "FAIL ${it.message}" }))
    }
}
