package com.yoruc.mangacracker

import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.video.FavVideo
import com.yoruc.mangacracker.video.SeriesCovers
import com.yoruc.mangacracker.video.VideoRecommender
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import com.yoruc.mangacracker.video.WatchState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Live: Videos → Recommended for you from a made-up watch history (an anime viewer, then an adult-video viewer):
 * something comes back, nothing already watched or from a watched show, one card per show, more than one site.
 * Run: python _dev_tools\run_tests.py VideoRecommenderTest
 */
class VideoRecommenderTest {
    companion object {
        @BeforeClass @JvmStatic fun setup() {
            Http.initForTests(File(System.getProperty("java.io.tmpdir"), "mc-test-http"))
        }
    }

    private fun log(s: String) = println("[recs] $s")

    /** Watches (finished) the first episode of the first [n] items of each list, favourites the very first. */
    private fun history(picks: List<Pair<String, List<VideoRef>>>, n: Int) {
        val now = System.currentTimeMillis()
        val watched = picks.flatMap { (_, l) -> l.take(n) }
        watched.forEach { log("watched: ${it.source} · ${it.series.ifEmpty { it.title }} · tags ${it.tags.take(6)}") }
        VideoRepo.restore(
            watched.mapIndexed { i, r -> r.key to WatchState(r, 1_400_000, 1_420_000, now - i * 60_000L) }.toMap(),
            watched.take(1).associate { it.key to FavVideo(it, now) },
        )
    }

    private fun check(label: String, recs: List<VideoRef>) {
        log("$label: ${recs.size} recommended, sites ${recs.groupingBy { it.source }.eachCount()}")
        recs.take(12).forEach { log("  ${it.source} · ${it.series.ifEmpty { it.title }} · ${it.lang} · ${it.tags.take(5)}") }
        assertTrue("$label: nothing recommended", recs.isNotEmpty())
        val seenShows = VideoRepo.watch.value.values.map { SeriesCovers.norm(it.ref.series.ifEmpty { it.ref.title }) }.toSet()
        assertTrue("$label: a watched video came back", recs.none { it.key in VideoRepo.watch.value })
        assertTrue("$label: a watched show came back", recs.none { SeriesCovers.norm(it.series.ifEmpty { it.title }) in seenShows })
        val shows = recs.map { SeriesCovers.norm(it.series.ifEmpty { it.title }) }.filter { it.length >= 3 }
        assertTrue("$label: a show twice", shows.size == shows.toSet().size)
    }

    @Test fun anime() = runBlocking {
        val ak = VideoSources.byId("anikoto")!!
        val shows = ak.popular(1).items.take(3)
        history(listOf("anikoto" to shows), 3)
        check("anime", VideoRecommender.load(force = true))
    }

    @Test fun adult() = runBlocking {
        val hg = VideoSources.byId("hentaigasm")!!.latest(1).items
        val os = VideoSources.byId("oppaistream")?.latest(1)?.items.orEmpty()
        history(listOf("hentaigasm" to hg, "oppaistream" to os), 2)
        check("adult", VideoRecommender.load(force = true))
    }
}
