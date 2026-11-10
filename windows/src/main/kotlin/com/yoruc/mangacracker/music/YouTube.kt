package com.yoruc.mangacracker.music

import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.util.concurrent.TimeUnit

/**
 * The Music tab's full songs from YouTube (the same file in both apps), through NewPipe Extractor (GPL-3): YouTube
 * Music's song search (official audio first), then the plain search; the audio stream is worked out when the song
 * plays (its address only lasts a few hours).
 */
object YouTube {
    /** A YouTube result: its watch URL, title, channel and length. */
    data class Hit(val url: String, val title: String, val channel: String, val seconds: Long)

    private val client = OkHttpClient.Builder().connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    private object Net : Downloader() {
        override fun execute(request: Request): Response {
            val b = okhttp3.Request.Builder().url(request.url())
            request.headers().forEach { (k, vs) -> b.removeHeader(k); vs.forEach { v -> b.addHeader(k, v) } }
            if (request.headers().keys.none { it.equals("User-Agent", true) }) b.header("User-Agent", UA)
            b.method(request.httpMethod(), request.dataToSend()?.toRequestBody())
            client.newCall(b.build()).execute().use { r ->
                if (r.code == 429) throw ReCaptchaException("YouTube asks to slow down", request.url())
                return Response(r.code, r.message, r.headers.toMultimap(), r.body?.string(), r.request.url.toString())
            }
        }
    }

    @Volatile private var ready = false
    @Synchronized private fun init() {
        if (!ready) { NewPipe.init(Net, Localization("en", "GB"), ContentCountry("JP")); ready = true }
    }

    /** Songs on YouTube Music for [query] (official audio with the song's real length). */
    fun musicSongs(query: String): List<Hit> = search(query, YoutubeSearchQueryHandlerFactory.MUSIC_SONGS)

    /** Plain YouTube videos for [query]. */
    fun videos(query: String): List<Hit> = search(query, YoutubeSearchQueryHandlerFactory.VIDEOS)

    private fun search(query: String, filter: String): List<Hit> {
        init()
        val yt = ServiceList.YouTube
        val ex = yt.getSearchExtractor(yt.searchQHFactory.fromQuery(query, listOf(filter), ""))
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<StreamInfoItem>().map { Hit(it.url, it.name, it.uploaderName.orEmpty(), it.duration) }
    }

    /** An album on YouTube Music (a soundtrack, a character-song CD...): its playlist URL, title, artist and track count. */
    data class AlbumHit(val url: String, val title: String, val artist: String, val tracks: Long)

    /** Albums on YouTube Music for [query]. */
    fun musicAlbums(query: String): List<AlbumHit> {
        init()
        val yt = ServiceList.YouTube
        val ex = yt.getSearchExtractor(yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.MUSIC_ALBUMS), ""))
        ex.fetchPage()
        return ex.initialPage.items.filterIsInstance<org.schabi.newpipe.extractor.playlist.PlaylistInfoItem>()
            .map { AlbumHit(it.url, it.name, it.uploaderName.orEmpty(), it.streamCount) }
    }

    /** The tracks of an album / playlist (all its pages). */
    fun albumTracks(url: String): List<Hit> {
        init()
        val yt = ServiceList.YouTube
        val info = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getInfo(yt, url)
        val out = ArrayList<Hit>()
        info.relatedItems.forEach { out += Hit(it.url, it.name, it.uploaderName.orEmpty(), it.duration) }
        var page = info.nextPage
        var pages = 0
        while (page != null && pages++ < 10) {
            val more = org.schabi.newpipe.extractor.playlist.PlaylistInfo.getMoreItems(yt, url, page)
            more.items.forEach { out += Hit(it.url, it.name, it.uploaderName.orEmpty(), it.duration) }
            page = more.nextPage
        }
        return out
    }

    /** The best audio of [url]: its stream address and a quality label ("Opus 160 kbps"). */
    fun audio(url: String): Pair<String, String> {
        init()
        val info = StreamInfo.getInfo(ServiceList.YouTube, url)
        val a = info.audioStreams.filter { it.content.isNotEmpty() && it.isUrl }.maxByOrNull { it.averageBitrate }
            ?: throw IllegalStateException("No audio for this song on YouTube")
        val kind = a.format?.name?.let { if (it.contains("OPUS", true) || it.contains("WEBMA", true)) "Opus" else if (it.contains("M4A", true)) "AAC" else it } ?: "audio"
        return a.content to "YouTube · $kind ${if (a.averageBitrate > 0) "${a.averageBitrate} kbps" else ""}".trim()
    }
}
