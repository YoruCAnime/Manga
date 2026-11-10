package com.yoruc.mangacracker

import com.yoruc.mangacracker.music.YouTube
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** NewPipe Extractor on the PC: YouTube Music song search, full length, an audio stream that answers. */
class YouTubeTest {
    @Test fun fullSongAudio() {
        assumeTrue(System.getProperty("sources").orEmpty().contains("youtube"))
        for (q in listOf("I SAY YES ICHIKO", "Hikaru Nara Goose house", "Gurenge LiSA")) {
            val t0 = System.currentTimeMillis()
            val hits = YouTube.musicSongs(q)
            println("[yt] '$q' in ${System.currentTimeMillis() - t0} ms: " + hits.take(4).joinToString(" | ") { "${it.title} - ${it.channel} (${it.seconds}s)" })
            assertTrue("$q: songs found", hits.isNotEmpty())
            val h = hits.first()
            val t1 = System.currentTimeMillis()
            val (url, q2) = YouTube.audio(h.url)
            val r = okhttp3.OkHttpClient().newCall(okhttp3.Request.Builder().url(url).header("Range", "bytes=0-65535").build()).execute()
            println("[yt]   audio in ${System.currentTimeMillis() - t1} ms: $q2, HTTP ${r.code} ${r.header("Content-Type")} ${r.body?.bytes()?.size} bytes")
            assertTrue(r.code in 200..206); r.close()
        }
    }

    @Test fun soundtrackAlbums() {
        assumeTrue(System.getProperty("sources").orEmpty().contains("youtube"))
        for (q in listOf("Zero no Tsukaima soundtrack", "Sousou no Frieren original soundtrack", "Shingeki no Kyojin OST")) {
            val albums = YouTube.musicAlbums(q)
            println("[yt] albums '$q': " + albums.take(6).joinToString(" | ") { "${it.title} - ${it.artist} (${it.tracks})" })
            assertTrue(albums.isNotEmpty())
            val tr = YouTube.albumTracks(albums.first().url)
            println("[yt]   ${albums.first().title}: ${tr.size} tracks, e.g. " + tr.take(4).joinToString(" | ") { "${it.title} (${it.seconds}s)" })
            assertTrue(tr.isNotEmpty())
        }
    }
}
