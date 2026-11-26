package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

class AtsuDimTest {
    @Test fun dims() = runBlocking {
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        val s = Sources.byId("atsu")!!
        val ref = s.popular(1).items.first()
        val ch = s.chapters(ref).last()
        val pages = s.pages(ref, ch)
        println("[dim] ${pages.size} pages: " + pages.joinToString(" ") { "${it.width}x${it.height}" })
    }
}
