package com.yoruc.mangacracker

import com.yoruc.mangacracker.music.Music
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Music tab's sources, live: AniList suggestions + seasons, MyAnimeList's openings / endings, the full song from
 * YouTube for each; archive.org albums; LISTEN.moe now playing.
 */
class MusicSourcesTest {
    private fun init() {
        val ctx = android.content.Context(File("build/smoke").apply { mkdirs() })
        com.yoruc.mangacracker.core.Http.init(ctx)
    }

    private fun on() = System.getProperty("sources").orEmpty().contains("music")

    @Test fun allSeasonsFullSongs() = runBlocking {
        assumeTrue(on())
        init()
        for ((typed, resolveAll) in listOf("zero no tsu" to true, "frieren" to false, "kimetsu" to false)) {
            val sug = Music.searchAnime(typed)
            println("[music] typed '$typed': " + sug.take(5).joinToString(" | ") { "${it.title} (${it.format} ${it.year})" })
            assertTrue(sug.isNotEmpty())
            val a = sug.first()
            val seasons = Music.seasons(a)
            println("[music]   seasons of ${a.title}: " + seasons.joinToString(" > ") { "${it.title} (${it.year})" })
            runCatching { Music.songs(seasons.first()) }.onFailure { println("[music]   songs of ${seasons.first().title} (MAL ${seasons.first().mal}) failed: $it") }
                .onSuccess { println("[music]   songs of ${seasons.first().title} (MAL ${seasons.first().mal}): ${it.size}") }
            val songs = Music.allSongs(a)
            println("[music]   ${songs.size} openings / endings over ${songs.map { it.season }.distinct().size} seasons")
            songs.groupBy { it.season }.forEach { (s, l) -> println("[music]     $s: " + l.joinToString(" | ") { "${it.kind}${it.order} ${it.title} - ${it.artist}" + if (it.ytUrl.isNotEmpty()) " [MAL link]" else "" }) }
            assertTrue("songs found", songs.isNotEmpty())
            val check = if (resolveAll) songs else songs.take(3)
            var full = 0
            for (t in check) {
                val t0 = System.currentTimeMillis()
                val r = runCatching { Music.resolve(t) }
                r.onSuccess { s -> if (s.durationS >= 150) full++; println("[music]     ${t.kind}${t.order} ${t.title}: ${s.durationS / 60}:${"%02d".format(s.durationS % 60)} ${s.quality} (${System.currentTimeMillis() - t0} ms)") }
                    .onFailure { println("[music]     ${t.kind}${t.order} ${t.title}: NOT FOUND (${it.message})") }
            }
            println("[music]   full-length: $full of ${check.size}")
            assertTrue("most songs found in full", full * 10 >= check.size * 7)
        }
    }

    /** A song listed on several seasons with the singer's name the other way round (Yui Horie / Horie Yui) is one song. */
    @Test fun sameSongOnce() = runBlocking {
        assumeTrue(on())
        init()
        val all = Music.allSongs(Music.searchAnime("school rumble").first())
        println("[music] School Rumble, all seasons: ${all.size}: " + all.joinToString(" | ") { "${it.kind}${it.order} ${it.title} - ${it.artist}" })
        assertTrue("Scramble once", all.count { it.title.startsWith("Scramble") } == 1)
        assertTrue("Onna no Ko Otoko no Ko once", all.count { it.title.startsWith("Onna no Ko Otoko no Ko") } == 1)
    }

    @Test fun albums() = runBlocking {
        assumeTrue(on())
        init()
        for (typed in listOf("zero no tsukaima", "frieren", "shingeki no kyojin", "kimetsu")) {
            val a = Music.searchAnime(typed).first()
            val albums = Music.albums(a.english, a.name, a.native)
            println("[music] albums of ${a.title} (${a.native}): ${albums.count { it.source == "youtube" }} YouTube Music + ${albums.count { it.source == "archive" }} archive.org")
            albums.take(8).forEach { println("[music]     ${if (it.source == "youtube") "YT" else "IA"} ${it.title}" + if (it.artist.isNotEmpty()) " - ${it.artist}" else "") }
            assertTrue(albums.isNotEmpty())
            albums.firstOrNull { it.source == "youtube" }?.let { al ->
                val tr = Music.albumTracks(al, a.title, a.cover)
                val r = Music.resolve(tr.first())
                println("[music]   ${al.title}: ${tr.size} tracks; '${tr.first().title}' plays as ${r.quality}, ${r.durationS}s")
            }
        }
    }

    @Test fun radioNowPlaying() {
        assumeTrue(on())
        init()
        Music.watchRadio()
        var waited = 0
        while (Music.radioNow.value == null && waited++ < 100) Thread.sleep(100)
        println("[music] radio now: ${Music.radioNow.value?.title} - ${Music.radioNow.value?.artist}")
        Music.unwatchRadio()
        assertTrue(Music.radioNow.value != null)
    }
}
