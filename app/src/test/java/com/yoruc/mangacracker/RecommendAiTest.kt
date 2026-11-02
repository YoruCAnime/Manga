package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Recommender
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.sources.NhentaiSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Recommended for you" with AI hidden, for someone whose favourites are all AI galleries (nhentai, live): the AI
 * tag must not become the taste, and nothing recommended may be AI by title, list tags or its own page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RecommendAiTest {
    private fun log(s: String) = println("[rec] $s")

    @Test fun noAiRecommended() {
        var err: Throwable? = null
        val th = Thread {
            runCatching {
                runBlocking {
                    Safety.hideAi = false
                    val aiTag = NhentaiSource.suggestTags("ai generated").first { Safety.isAiTag(it.label) || Safety.isAiTag(it.id) }
                    val ai = NhentaiSource.search(SearchQuery(include = listOf(aiTag)), 1).items.take(4)
                    for (r in ai) {
                        val d = NhentaiSource.details(r)
                        Repo.addToLibrary(r, d, null)
                    }
                    log("favourites: ${ai.size} AI galleries")
                    Safety.hideAi = true
                    val recs = Recommender.load(force = true)
                    log("recommended: ${recs.size}")
                    assertTrue("something is recommended", recs.isNotEmpty())
                    val bad = recs.filter { Safety.isAiTitle(it.title) || it.tags.any(Safety::isAiTag) }
                    log("AI by title / list tags: ${bad.size}")
                    var opened = 0; var aiPages = 0
                    for (r in recs.take(8)) {
                        Safety.hideAi = false
                        val d = runCatching { NhentaiSource.details(r) }.getOrNull()
                        Safety.hideAi = true
                        if (d == null) continue
                        opened++
                        if (Safety.isAi(d)) aiPages++
                    }
                    log("opened $opened recommendations: $aiPages AI by their page")
                    assertTrue(bad.isEmpty() && aiPages == 0)
                }
            }.onFailure { err = it }
        }
        th.start()
        val until = System.currentTimeMillis() + 10 * 60_000
        while (th.isAlive && System.currentTimeMillis() < until) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        err?.let { throw it }
    }
}
