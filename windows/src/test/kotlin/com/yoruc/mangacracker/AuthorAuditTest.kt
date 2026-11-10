package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Live, for each manga site: do its titles name an author / artist, and does tapping one list works?
 * Only counts are printed. -Psources=audit runs it.
 */
class AuthorAuditTest {
    @Test fun audit() = runBlocking {
        if (System.getProperty("sources") != "audit") return@runBlocking
        val ctx = Context(File("build/smoke").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        for (src in Sources.all) {
            val line = StringBuilder("[people] ${src.id.padEnd(12)}")
            runCatching {
                val items = src.popular(1).items.take(5)
                var withPeople = 0; var aut = 0; var art = 0; var first: Pair<String, String>? = null; var firstRef = ""; var circle: String? = null; var circleRef = ""
                for (r in items) {
                    val d = src.details(r)
                    if (d.authors.isNotEmpty() || d.artists.isNotEmpty()) withPeople++
                    aut += d.authors.size; art += d.artists.size
                    if (first == null) (d.artists.firstOrNull()?.let { "artist" to it } ?: d.authors.firstOrNull()?.let { "author" to it })?.let { first = it; firstRef = r.key }
                    if (circle == null) d.authors.firstOrNull()?.let { circle = it; circleRef = r.key }
                    delay(400)
                }
                line.append(" titles ${items.size}, with a name $withPeople, authors $aut, artists $art")
                circle?.takeIf { src.authorsAreGroups }?.let { c ->
                    val res = src.search(src.groupQuery(c), 1)
                    line.append(" | tap circle: ${res.items.size} works, same title: ${res.items.any { it.key == circleRef }}")
                }
                first?.let { (kind, name) ->
                    val res = src.search(src.artistQuery(name), 1)
                    line.append(" | tap $kind: ${res.items.size} works, same title in list: ${res.items.any { it.key == firstRef }}, more: ${res.hasNext}")
                }
            }.onFailure { line.append(" FAILED ${it::class.simpleName}: ${it.message?.take(90)}") }
            println(line)
        }
    }
}
