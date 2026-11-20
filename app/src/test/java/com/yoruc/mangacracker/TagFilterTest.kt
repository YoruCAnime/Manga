package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.Safety
import com.yoruc.mangacracker.core.SearchQuery
import com.yoruc.mangacracker.core.Tag
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * "Hide futanari" / "Hide furry" (manga, pictures and videos): the tag / title rules on their own, then every source live:
 * the site's own tag (or the words) searched with the switches on, how many cards still show, and the first ones opened
 * to see whether their page tags say futanari / furry after all. Whole titles are never printed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagFilterTest {
    private val pick = System.getProperty("sources").orEmpty().split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
    private fun log(s: String) = println("[tags] $s")
    private fun on(v: Boolean) { Safety.hideFuta = v; Safety.hideFurry = v }

    @Test fun rules() {
        on(true)
        listOf("futanari", "female:futanari", "futa on male", "Futa_with_female", "dickgirl on male", "male:dickgirl on male", "futanari protagonist",
            "tag-futanari").forEach { assertTrue("futa tag $it", Safety.isFutaTag(it)) }
        listOf("futari", "futaba", "futago", "two girls", "sumata", "futon").forEach { assertTrue("kept tag $it", !Safety.isFutaTag(it)) }
        listOf("furry", "female:furry", "furry female", "human on furry", "anthro", "Furry_with_non-furry", "yiff").forEach {
            assertTrue("furry tag $it", Safety.isFurryTag(it))
        }
        listOf("kemonomimi", "cat ears", "animal ears", "anthropomorphic", "kemono friends", "fur trim", "fur coat", "monster girl").forEach {
            assertTrue("kept tag $it", !Safety.isFurryTag(it))
        }
        listOf("[Futanari] Something", "Futa Sisters", "ふたなり 本", "My Furry Neighbour").forEach { assertTrue("title $it", Safety.optTitle(it)) }
        listOf("Futaba's Day", "Futari Ecchi", "Futago no Himitsu", "Kemonomimi Cafe", "Ai's Secret").forEach { assertTrue("kept title $it", !Safety.optTitle(it)) }
        val r = MangaRef("nhentai", "1", "Ok", tags = listOf("futanari"))
        assertTrue(Safety.blocked(r))
        assertTrue(Safety.blocked(r.copy(tags = listOf("human on furry"))))
        // videos: futanari / furry hidden, AI not (the AI switch is for reading only)
        assertTrue(VideoSafety.blocked(VideoRef("x", "1", "Clip", tags = listOf("futanari"))))
        assertTrue(VideoSafety.blocked(VideoRef("x", "1", "Futa clip")))
        assertTrue(VideoSafety.blocked(VideoRef("x", "1", "Clip", tags = listOf("furry"))))
        assertTrue(!VideoSafety.blocked(VideoRef("x", "1", "Clip [AI Generated]")))
        on(false)
        assertTrue(!Safety.blocked(r))
        assertTrue(!VideoSafety.blocked(VideoRef("x", "1", "Clip", tags = listOf("futanari"))))
        on(true)
        assertEquals(1, Safety.cleanTags(listOf(Tag("futanari", "Futanari"), Tag("furry", "Furry"), Tag("x", "Big"))).size)
    }

    @Test fun live() {
        var err: Throwable? = null
        val th = Thread {
            runCatching {
                runBlocking {
                    for (s in Sources.all.filter { (pick.isEmpty() || it.id in pick) && it.id !in setOf("comix", "ehentai") }) {
                        for (word in listOf("futanari", "furry")) {
                            on(false)
                            val tag = runCatching { s.suggestTags(word) }.getOrDefault(emptyList())
                                .firstOrNull { if (word == "futanari") Safety.isFutaTag(it.label) else Safety.isFurryTag(it.label) }
                            val got = runCatching { s.search(if (tag != null) SearchQuery(include = listOf(tag)) else SearchQuery(text = word), 1).items }
                            on(true)
                            got.exceptionOrNull()?.let { log("${s.id} $word: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
                            val all = got.getOrNull() ?: continue
                            val shown = Safety.filter(all)
                            // the same search as the user does it with the switches on (sites that drop them themselves)
                            val asUser = runCatching { Safety.filter(s.search(if (tag != null) SearchQuery(include = listOf(tag)) else SearchQuery(text = word), 1).items) }
                            var leak = 0; var opened = 0
                            for (x in shown.take(4)) {
                                on(false)
                                val d = runCatching { s.details(x) }.getOrNull()
                                on(true)
                                if (d == null) continue
                                opened++
                                if (Safety.switchReason(d) != null) leak++
                            }
                            log("${s.id} $word (${if (tag != null) "tag" else "words"}): ${all.size} cards, ${shown.size} still shown " +
                                "(as the user: ${asUser.getOrNull()?.size ?: asUser.exceptionOrNull()?.javaClass?.simpleName}); opened $opened shown, $leak are $word by their page")
                        }
                    }
                    for (v in VideoSources.all.filter { pick.isEmpty() || it.id in pick }) {
                        for (word in listOf("futanari", "furry")) {
                            on(false)
                            val got = runCatching { v.search(SearchQuery(text = word), 1).items }
                            on(true)
                            got.exceptionOrNull()?.let { log("video ${v.id} $word: ${it.javaClass.simpleName} ${it.message?.take(80)}") }
                            val all = got.getOrNull() ?: continue
                            val shown = all.filterNot { VideoSafety.blocked(it) }
                            var leak = 0; var opened = 0
                            for (x in shown.take(3)) {
                                on(false)
                                val d = runCatching { v.details(x) }.getOrNull()
                                on(true)
                                if (d == null) continue
                                opened++
                                if (runCatching { VideoSafety.check(d) }.exceptionOrNull()?.message?.contains("Settings") == true) leak++
                            }
                            log("video ${v.id} $word: ${all.size} cards, ${shown.size} still shown; opened $opened shown, $leak are $word by their page (hidden there)")
                        }
                    }
                }
            }.onFailure { err = it }
        }
        th.start()
        val until = System.currentTimeMillis() + 30 * 60_000
        while (th.isAlive && System.currentTimeMillis() < until) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        err?.let { throw it }
    }
}
