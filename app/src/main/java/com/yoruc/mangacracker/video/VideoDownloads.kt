package com.yoruc.mangacracker.video

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.NoOpCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.hls.offline.HlsDownloader
import androidx.media3.exoplayer.hls.playlist.HlsMultivariantPlaylist
import androidx.media3.exoplayer.hls.playlist.HlsPlaylistParser
import com.yoruc.mangacracker.core.DlTask
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.PermanentDownloadError
import com.yoruc.mangacracker.core.Repo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

@Serializable
data class SavedSub(val file: String, val lang: String, val label: String)

/** video.json (written last: no file = not finished). [streamKeys] = (group, index) of the HLS variant / audio kept. */
@Serializable
data class SavedVideo(
    val ref: VideoRef,
    val kind: String,
    val streamUrl: String,
    val file: String = "",
    val streamKeys: List<List<Int>> = emptyList(),
    val subs: List<SavedSub> = emptyList(),
    val bytes: Long = 0L,
    val height: Int = 0,
    val time: Long = 0L,
)

data class OfflineVideo(val saved: SavedVideo, val dir: File) {
    val bytes: Long get() = saved.bytes
}

/**
 * Videos for offline. MP4 / WebM: the very file the player streams, fetched with the source's player client (Range
 * resume). HLS (also MuchoHentai's AES-128 streams): Media3's HlsDownloader through the same player client into a
 * cache folder of the video's own; one variant (the "Video downloads" height) and its audio; the key files are
 * downloaded with the segments, so playback from the cache decrypts with no network. Each folder:
 * downloads/videos/<site>/<video>/video.json + video.mp4 | hls/ + subs.
 */
@OptIn(UnstableApi::class)
object VideoDownloads {
    private val _videos = MutableStateFlow<Map<String, OfflineVideo>>(emptyMap())
    val videos: StateFlow<Map<String, OfflineVideo>> = _videos

    /** Open HLS caches (Media3 allows one SimpleCache per folder per process). */
    private val caches = HashMap<String, SimpleCache>()

    /** Tests only: stop an HLS / MP4 download after this many bytes and keep what came (null = whole video). */
    @Volatile var testByteCap: Long? = null

    fun dir(ref: VideoRef) = File(Downloads.root, "videos/${Downloads.safe(ref.source)}/${Downloads.safe(ref.id)}")

    fun rescan() {
        val out = HashMap<String, OfflineVideo>()
        File(Downloads.root, "videos").listFiles()?.forEach { site ->
            site.listFiles()?.forEach { d ->
                runCatching { Http.json.decodeFromString(SavedVideo.serializer(), File(d, "video.json").readText()) }.getOrNull()
                    ?.let { out[it.ref.key] = OfflineVideo(it, d) }
            }
        }
        _videos.value = out
    }

    fun offline(key: String): OfflineVideo? = _videos.value[key]?.takeIf { o -> o.saved.kind == "hls" || File(o.dir, o.saved.file).exists() }

    /** The stream the player / clip maker uses for a download. */
    fun stream(o: OfflineVideo): VideoStream {
        val s = o.saved
        val subs = s.subs.map { VideoSub(Uri.fromFile(File(o.dir, it.file)).toString(), it.lang, it.label) }
        return if (s.kind == "hls") VideoStream(s.streamUrl, "hls", subs, local = s.ref.key, streamKeys = s.streamKeys.map { it[0] to it[1] })
        else VideoStream(Uri.fromFile(File(o.dir, s.file)).toString(), s.kind, subs, local = s.ref.key)
    }

    @Synchronized
    private fun cache(dir: File): SimpleCache {
        val d = File(dir, "hls")
        return caches.getOrPut(d.absolutePath) {
            d.mkdirs()
            @Suppress("DEPRECATION")
            SimpleCache(d, NoOpCacheEvictor())
        }
    }

    @Synchronized
    private fun release(dir: File) {
        caches.remove(File(dir, "hls").absolutePath)?.let { runCatching { it.release() } }
    }

    /**
     * Where the player / Transformer read [stream] from: the file on disk, the video's HLS cache (read only; anything
     * missing still comes from the network), or the site with its player client.
     */
    fun dataSourceFactory(ctx: Context, src: VideoSource, stream: VideoStream): DataSource.Factory {
        val online = OkHttpDataSource.Factory(src.playerClient)
        if (stream.local.isEmpty()) return online
        val o = _videos.value[stream.local] ?: return DefaultDataSource.Factory(ctx, online)
        if (o.saved.kind != "hls") return DefaultDataSource.Factory(ctx, online)
        return CacheDataSource.Factory().setCache(cache(o.dir))
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(ctx, online))
            .setCacheWriteDataSinkFactory(null)
    }

    /** A MediaItem for [stream] (HLS: only the downloaded variant when it is a download). */
    fun mediaItem(stream: VideoStream, build: MediaItem.Builder.() -> Unit = {}): MediaItem =
        MediaItem.Builder().setUri(stream.url)
            .apply { if (stream.kind == "hls") setMimeType(MimeTypes.APPLICATION_M3U8) }
            .apply { if (stream.streamKeys.isNotEmpty()) setStreamKeys(stream.streamKeys.map { StreamKey(it.first, it.second) }) }
            .apply(build)
            .build()

    // ---------- downloading ----------

    /** The worker's video job: [progress] gets per mille done, 1000, bytes. */
    suspend fun download(ctx: Context?, t: DlTask, progress: (Int, Int, Long) -> Unit) {
        val ref = t.video ?: throw PermanentDownloadError("Nothing to download")
        val src = VideoSources.byId(ref.source) ?: throw PermanentDownloadError("This site is no longer in the app")
        if (VideoSafety.blocked(ref)) throw PermanentDownloadError("This video is hidden")
        val d = try {
            VideoRepo.details[ref.key] ?: withContext(Dispatchers.IO) { src.details(ref) }
        } catch (e: Throwable) {
            if (e.message == "This video is hidden") throw PermanentDownloadError("This video is hidden") else throw e
        }
        try { VideoSafety.check(d) } catch (e: Throwable) { throw PermanentDownloadError("This video is hidden") }
        withContext(Dispatchers.IO) { src.beforePlay() }
        val dir = dir(ref).apply { mkdirs() }
        val stream = d.stream
        val subs = stream.subs.mapIndexedNotNull { i, s ->
            runCatching {
                val b = Http.bytes(s.url)
                val name = "sub$i.vtt"
                File(dir, name).writeBytes(b)
                SavedSub(name, s.lang, s.label)
            }.getOrNull()
        }
        val saved = if (stream.kind == "hls") hls(src, d.ref, stream, dir, subs, progress)
        else file(src, d.ref, stream, dir, subs, progress)
        Downloads.writeJson(File(dir, "video.json"), Http.json.encodeToString(SavedVideo.serializer(), saved))
        _videos.update { it + (ref.key to OfflineVideo(saved, dir)) }
    }

    private fun mp4Like(b: ByteArray, n: Int): Boolean =
        (n >= 8 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp") ||
            (n >= 4 && b[0] == 0x1A.toByte() && b[1] == 0x45.toByte() && b[2] == 0xDF.toByte() && b[3] == 0xA3.toByte())

    /** MP4 / WebM: the file itself, resumed with Range after a break. */
    private suspend fun file(src: VideoSource, ref: VideoRef, stream: VideoStream, dir: File, subs: List<SavedSub>, progress: (Int, Int, Long) -> Unit): SavedVideo =
        withContext(Dispatchers.IO) {
            val ext = if (stream.kind == "webm") "webm" else "mp4"
            val name = "video.$ext"
            val part = File(dir, "$name.part")
            var have = part.length()
            val cap = testByteCap
            val req = Http.request(stream.url, if (have > 0) mapOf("Range" to "bytes=$have-") else emptyMap())
            val call = src.playerClient.newCall(req)
            val h = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) call.cancel() }
            try {
                call.execute().use { r ->
                    if (r.code == 416 && have > 0) return@use
                    if (!r.isSuccessful) throw IOException("The video answered HTTP ${r.code}")
                    if (r.code != 206) have = 0
                    val body = r.body ?: throw IOException("Empty answer")
                    val total = if (body.contentLength() > 0) have + body.contentLength() else -1L
                    val buf = ByteArray(64 * 1024)
                    var first = have == 0L
                    FileOutputStream(part, have > 0).use { out ->
                        body.byteStream().use { inp ->
                            var last = 0L
                            while (true) {
                                val n = inp.read(buf)
                                if (n < 0) break
                                if (first) {
                                    if (!mp4Like(buf, n)) throw IOException("The site sent something that isn't a video")
                                    first = false
                                }
                                out.write(buf, 0, n)
                                have += n
                                if (have - last > 256 * 1024) {
                                    last = have
                                    currentCoroutineContext().ensureActive()
                                    if (Downloads.freeBytes() < Downloads.MIN_FREE_RUN) throw PermanentDownloadError("The phone is almost full")
                                    progress(if (total > 0) (have * 1000 / total).toInt() else 0, 1000, have)
                                }
                                if (cap != null && have >= cap) break
                            }
                        }
                    }
                }
            } finally { h?.dispose() }
            if (!part.renameTo(File(dir, name))) throw IOException("Couldn't write the video")
            progress(1000, 1000, have)
            SavedVideo(ref, stream.kind, stream.url, name, emptyList(), subs, File(dir, name).length() + subs.sumOf { File(dir, it.file).length() }, 0, System.currentTimeMillis())
        }

    /** The variant to keep: the tallest at or under the chosen height (else the smallest), plus its audio. */
    fun pickVariant(p: HlsMultivariantPlaylist, maxHeight: Int): Pair<List<StreamKey>, Int> {
        if (p.variants.isEmpty()) return emptyList<StreamKey>() to 0
        val idx = p.variants.indices.toList()
        val h = { i: Int -> p.variants[i].format.height }
        val bw = { i: Int -> p.variants[i].format.bitrate }
        val pick = if (idx.all { h(it) <= 0 }) idx.maxBy { bw(it) }
        else {
            val fit = idx.filter { h(it) in 1..(if (maxHeight <= 0) Int.MAX_VALUE else maxHeight) }
            if (fit.isNotEmpty()) fit.maxWith(compareBy({ h(it) }, { bw(it) })) else idx.filter { h(it) > 0 }.minWith(compareBy({ h(it) }, { bw(it) }))
        }
        val keys = arrayListOf(StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_VARIANT, pick))
        val group = p.variants[pick].audioGroupId
        if (group != null) {
            val a = p.audios.indices.filter { p.audios[it].groupId == group && p.audios[it].url != null }
            val best = a.firstOrNull { (p.audios[it].format.selectionFlags and androidx.media3.common.C.SELECTION_FLAG_DEFAULT) != 0 } ?: a.firstOrNull()
            if (best != null) keys += StreamKey(HlsMultivariantPlaylist.GROUP_INDEX_AUDIO, best)
        }
        return keys to h(pick)
    }

    /** HLS through Media3's downloader into this video's own cache (segments, playlists and AES keys). */
    private suspend fun hls(src: VideoSource, ref: VideoRef, stream: VideoStream, dir: File, subs: List<SavedSub>, progress: (Int, Int, Long) -> Unit): SavedVideo =
        withContext(Dispatchers.IO) {
            // which variant: read the playlist once ourselves
            val text = src.playerClient.newCall(Http.request(stream.url)).execute().use { r ->
                if (!r.isSuccessful) throw IOException("The video playlist answered HTTP ${r.code}")
                r.body!!.bytes()
            }
            if (!String(text, 0, minOf(text.size, 16), Charsets.ISO_8859_1).contains("#EXTM3U")) throw IOException("The site sent something that isn't a video playlist")
            val parsed = HlsPlaylistParser().parse(Uri.parse(stream.url), text.inputStream())
            val (keys, height) = if (parsed is HlsMultivariantPlaylist) pickVariant(parsed, Repo.settings.value.videoDownloadHeight) else emptyList<StreamKey>() to 0
            val item = MediaItem.Builder().setUri(stream.url).setMimeType(MimeTypes.APPLICATION_M3U8).setStreamKeys(keys).build()
            val factory = CacheDataSource.Factory().setCache(cache(dir)).setUpstreamDataSourceFactory(OkHttpDataSource.Factory(src.playerClient))
            val dl = HlsDownloader(item, factory)
            val cap = testByteCap
            val job = currentCoroutineContext()[Job]
            val h = job?.invokeOnCompletion { if (it != null) dl.cancel() }
            var capped = false
            try {
                dl.download { length, bytes, percent ->
                    progress((percent * 10).toInt().coerceIn(0, 1000), 1000, bytes)
                    if (cap != null && bytes >= cap && !capped) { capped = true; dl.cancel() }
                }
            } catch (e: Exception) {
                currentCoroutineContext().ensureActive()
                if (!capped) throw (if (e is InterruptedException) IOException("The download stopped") else e)
            } finally { h?.dispose() }
            progress(1000, 1000, dirSize(File(dir, "hls")))
            SavedVideo(ref, "hls", stream.url, "", keys.map { listOf(it.groupIndex, it.streamIndex) }, subs,
                dirSize(dir), height, System.currentTimeMillis())
        }

    fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** What an unfinished download left behind. */
    fun removePartial(ref: VideoRef) {
        if (_videos.value.containsKey(ref.key)) return
        val d = dir(ref)
        release(d)
        d.deleteRecursively()
    }

    fun delete(key: String) {
        val o = _videos.value[key] ?: return
        release(o.dir)
        o.dir.deleteRecursively()
        _videos.update { it - key }
    }

    /** Tests: a read-only view of a downloaded HLS video's cache with NO network behind it. */
    fun offlineReader(key: String): DataSource? {
        val o = _videos.value[key] ?: return null
        if (o.saved.kind != "hls") return FileDataSource()
        return CacheDataSource.Factory().setCache(cache(o.dir)).setUpstreamDataSourceFactory {
            object : DataSource by FileDataSource() {
                override fun open(dataSpec: androidx.media3.datasource.DataSpec): Long = throw IOException("offline: ${dataSpec.uri}")
            }
        }.setCacheWriteDataSinkFactory(null).createDataSource()
    }
}
