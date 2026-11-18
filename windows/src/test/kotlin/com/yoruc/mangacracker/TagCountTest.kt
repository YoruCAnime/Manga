package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.File

/**
 * Live (-Psources=tagcount -Ptags=hitomi=female:nurse;tag:full color,nhentai=...): how many titles each page of a
 * tag's list gives, and how many are left after the app's own filter. Counts only.
 */
class TagCountTest {
    @Test fun counts() = runBlocking {
        if (System.getProperty("sources") != "tagcount") return@runBlocking
        val ctx = Context(File("build/tagcount").apply { deleteRecursively(); mkdirs() })
        Http.init(ctx); Repo.init(ctx)
        for (part in System.getProperty("tags").orEmpty().split(',').filter { it.isNotBlank() }) {
            val id = part.substringBefore('=')
            val s = Sources.byId(id) ?: continue
            Repo.updateSettings { it.copy(ageConfirmed = it.ageConfirmed + id) }
            if (part.substringAfter('=') == "@builtin") {
                // every tag of the source's own quick list: how many titles its first page gives (0 = a wrong name)
                for (t in s.tags()) {
                    val r = runCatching { s.search(com.yoruc.mangacracker.core.SearchQuery(include = listOf(t)), 1) }
                    println("[tags] $id builtin '${t.id}': " + (r.getOrNull()?.let { "${it.items.size} titles, more=${it.hasNext}" } ?: "FAIL ${r.exceptionOrNull()?.message?.take(80)}"))
                }
                continue
            }
            for (tag in part.substringAfter('=').split(';')) {
                val q = if (tag.startsWith("text:")) com.yoruc.mangacracker.core.SearchQuery(text = tag.removePrefix("text:")) else s.tagQuery(tag)
                val seen = HashSet<String>()
                var total = 0
                for (page in 1..4) {
                    val t0 = System.currentTimeMillis()
                    val r = runCatching { s.search(q, page) }.getOrElse { println("[tags] $id '$tag' page $page FAIL ${it.message?.take(120)}"); null } ?: break
                    val kept = Safety.filter(r.items)
                    val fresh = kept.count { seen.add(it.key) }
                    total += fresh
                    println("[tags] $id '$tag' page $page: ${r.items.size} from the site, ${kept.size} after the filter, $fresh new, more=${r.hasNext}, ${System.currentTimeMillis() - t0} ms")
                    if (!r.hasNext) break
                }
                println("[tags] $id '$tag' total $total")
            }
        }
    }
}
