package com.yoruc.mangacracker

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.runBlocking
import org.jsoup.Jsoup
import org.junit.Test
import java.io.File

/** Why a title says "This title is hidden": its tags and which rule each one hits. -Psources=hiddenwhy, HIDDEN_Q = words. */
class HiddenWhyTest {
    @Test fun why() = runBlocking {
        if (System.getProperty("sources") != "hiddenwhy") return@runBlocking
        val ctx = Context(File("build/hiddenwhy").apply { mkdirs() })
        Http.init(ctx); Repo.init(ctx); Repo.updateSettings { it.copy(ageConfirmed = it.ageConfirmed + "imhentai") }
        val src = Sources.byId(System.getenv("HIDDEN_SRC") ?: "imhentai")!!
        val q = System.getenv("HIDDEN_Q") ?: "Tips Please"
        val hits = src.search(SearchQuery(text = q), 1).items
        println("[why] search '$q': ${hits.size} titles: ${hits.take(8).map { it.id + " " + it.title.take(60) }}")
        val r = hits.firstOrNull { it.title.contains("Tips Please", true) } ?: return@runBlocking
        val doc = Jsoup.parse(Http.get(src.webUrl(r)))
        doc.select("div.right_details ul.galleries_info li").forEach { li ->
            val kind = li.selectFirst("span.tags_text")?.text().orEmpty()
            val tags = li.select("a.tag").map { it.ownText().trim() to it.attr("href") }
            if (tags.isEmpty()) return@forEach
            println("[why] $kind " + tags.joinToString { (n, h) ->
                val why = listOfNotNull(
                    "MINOR".takeIf { Safety.isMinorTag(n) },
                    "SWITCH".takeIf { Safety.optTag(n) },
                    "BLOCKED".takeIf { Safety.blocked(listOf(n), src.id) },
                ).joinToString("+")
                "$n ($h)" + if (why.isNotEmpty()) " <-- $why" else ""
            })
        }
        println("[why] settings: hideAi ${Safety.hideAi}, hideFuta ${Safety.hideFuta}, hideFurry ${Safety.hideFurry}")
    }
}
