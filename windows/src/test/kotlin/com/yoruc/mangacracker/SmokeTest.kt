package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** Every manga and video source's first list from the Windows build (counts only, no titles). */
class SmokeTest {
    @Test fun lists() = runBlocking {
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        val pick = System.getProperty("sources").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
        Sources.all.filter { pick.isEmpty() || it.id in pick }.forEach { s ->
            val r = runCatching { s.popular(1) }
            println("[smoke] manga ${s.id}: " + r.fold({ "${it.items.size} items" }, { "FAIL ${it.message?.take(90)}" }))
        }
        VideoSources.all.filter { pick.isEmpty() || it.id in pick }.forEach { s ->
            val r = runCatching { s.latest(1) }
            println("[smoke] video ${s.id}: " + r.fold({ "${it.items.size} items" }, { "FAIL ${it.message?.take(90)}" }))
        }
    }
}
