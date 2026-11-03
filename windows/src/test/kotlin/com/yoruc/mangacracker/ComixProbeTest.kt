package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.platform.Edge
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/** comix in the hidden Edge page: the chapter links on a title page (the reader address format). */
class ComixProbeTest {
    @Test fun probe() = runBlocking {
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        com.yoruc.mangacracker.platform.ComixEdge.install()
        val e = Edge.Instance("edge-probe", headless = true)
        try {
            val src = com.yoruc.mangacracker.sources.Sources.byId("comix")!!
            val ref = src.popular(1).items.first()
            val ch = src.chapters(ref).last()
            println("[probe] ch ${ch.id} ${ch.number} ${ch.name}")
            e.goto("https://comix.to/title/${ref.id}")
            delay(6000)
            e.eval("window.scrollTo(0, document.body.scrollHeight)")
            delay(3000)
            val js = "JSON.stringify([...document.querySelectorAll('a[href]')].map(a=>a.getAttribute('href')).filter(h=>h.indexOf('/title/')>=0).slice(0,40))"
            println("[probe] links " + e.eval(js))
            println("[probe] text " + e.eval("document.body.innerText.slice(0,600)").replace('\n', ' '))
        } finally { e.close(); Edge.close() }
    }
}
