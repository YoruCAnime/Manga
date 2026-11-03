package com.yoruc.mangacracker.video

import android.content.Context
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
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

@Serializable
data class SavedSub(val file: String, val lang: String, val label: String)

/** video.json (written last: no file = not finished). [streamKeys] is kept for backups made on the phone. */
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
 * Videos for offline (Windows). MP4 / WebM: the very file the player streams, fetched with the source's player client
 * (Range resume). HLS (also MuchoHentai's AES-128 streams): one variant (the "Video downloads" height, else the
 * tallest under it) fetched segment by segment through the same client, decrypted here and joined into one video.ts
 * (plays anywhere, no keys left behind); a separate audio rendition goes to audio.ts. Each folder:
 * downloads/videos/<site>/<video>/video.json + video.mp4 | video.ts (+ audio.ts) + subs.
 */
object VideoDownloads {
    private val _videos = MutableStateFlow<Map<String, OfflineVideo>>(emptyMap())
    val videos: StateFlow<Map<String, OfflineVideo>> = _videos

    /** Tests only: stop a download after this many bytes and keep what came (null = whole video). */
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

    fun offline(key: String): OfflineVideo? = _videos.value[key]?.takeIf { o -> File(o.dir, o.saved.file).exists() }

    /** The stream the player uses for a download (a local file). */
    fun stream(o: OfflineVideo): VideoStream {
        val s = o.saved
        val subs = s.subs.map { VideoSub(File(o.dir, it.file).toURI().toString(), it.lang, it.label) }
        return VideoStream(File(o.dir, s.file).toURI().toString(), if (s.kind == "hls") "ts" else s.kind, subs, local = s.ref.key)
    }

    /** A separate audio track that came with an HLS download, if any. */
    fun audioFile(o: OfflineVideo): File? = File(o.dir, "audio.ts").takeIf { it.exists() }

    private val one = kotlinx.coroutines.sync.Mutex()

    /** One video at a time (two HLS downloads at once only slow each other down). */
    @Suppress("UNUSED_PARAMETER")
    suspend fun download(ctx: Context?, t: DlTask, progress: (Int, Int, Long) -> Unit) = one.withLock { downloadNow(t, progress) }

    private suspend fun downloadNow(t: DlTask, progress: (Int, Int, Long) -> Unit) {
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
                                    if (Downloads.freeBytes() < Downloads.MIN_FREE_RUN) throw PermanentDownloadError("The disk is almost full")
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

    // ---------- HLS ----------

    class Variant(val url: String, val height: Int, val bandwidth: Long, val audioGroup: String?)

    fun attrs(line: String): Map<String, String> {
        val out = HashMap<String, String>()
        Regex("([A-Z0-9-]+)=(\"[^\"]*\"|[^,]*)").findAll(line.substringAfter(':')).forEach { m ->
            out[m.groupValues[1]] = m.groupValues[2].trim('"')
        }
        return out
    }

    fun resolve(base: String, ref: String) = base.toHttpUrl().resolve(ref)?.toString() ?: ref

    /** The variant to keep: the tallest at or under [maxHeight] (0 = tallest), else the smallest. */
    fun pickVariant(vs: List<Variant>, maxHeight: Int): Variant? {
        if (vs.isEmpty()) return null
        val under = vs.filter { maxHeight <= 0 || it.height in 1..maxHeight }
        return under.ifEmpty { listOf(vs.minBy { if (it.height > 0) it.height else Int.MAX_VALUE }) }
            .maxWith(compareBy<Variant> { it.height }.thenBy { it.bandwidth })
    }

    /** The variants listed in a master playlist (empty for a media playlist). */
    fun variants(master: String, base: String): List<Variant> {
        val lines = master.lines()
        val vs = ArrayList<Variant>()
        lines.forEachIndexed { i, l ->
            if (l.startsWith("#EXT-X-STREAM-INF")) {
                val a = attrs(l)
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") } ?: return@forEachIndexed
                val h = a["RESOLUTION"]?.substringAfter('x')?.toIntOrNull() ?: 0
                vs += Variant(resolve(base, uri.trim()), h, a["BANDWIDTH"]?.toLongOrNull() ?: 0, a["AUDIO"])
            }
        }
        return vs
    }

    private fun text(src: VideoSource, url: String): String =
        src.playerClient.newCall(Http.request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("The stream answered HTTP ${r.code}")
            r.body?.string() ?: ""
        }

    private fun bytes(src: VideoSource, url: String): ByteArray =
        src.playerClient.newCall(Http.request(url)).execute().use { r ->
            if (!r.isSuccessful) throw IOException("A video piece answered HTTP ${r.code}")
            r.body!!.bytes()
        }

    private suspend fun hls(src: VideoSource, ref: VideoRef, stream: VideoStream, dir: File, subs: List<SavedSub>, progress: (Int, Int, Long) -> Unit): SavedVideo =
        withContext(Dispatchers.IO) {
            val master = text(src, stream.url)
            var media = stream.url
            var height = 0
            var audioUrl: String? = null
            val vs = variants(master, stream.url)
            if (vs.isNotEmpty()) {
                val v = pickVariant(vs, Repo.settings.value.videoDownloadHeight) ?: throw IOException("No video in this stream")
                media = v.url; height = v.height
                if (v.audioGroup != null) {
                    master.lines().firstOrNull { it.startsWith("#EXT-X-MEDIA") && "TYPE=AUDIO" in it && "GROUP-ID=\"${v.audioGroup}\"" in it && "URI=" in it }
                        ?.let { attrs(it)["URI"] }?.let { audioUrl = resolve(stream.url, it) }
                }
            }
            segments(src, media, File(dir, "video.ts"), 0, if (audioUrl != null) 800 else 1000, progress)
            audioUrl?.let { segments(src, it, File(dir, "audio.ts"), 800, 200, progress) }
            val size = dirSize(dir)
            progress(1000, 1000, size)
            SavedVideo(ref, "hls", stream.url, "video.ts", emptyList(), subs, size, height, System.currentTimeMillis())
        }

    private class Seg(val url: String, val key: String?, val iv: ByteArray?, val seq: Long)

    /**
     * Fetches every segment of a media playlist into [out], decrypting AES-128 pieces. Resumes: "<out>.done" counts
     * the segments already joined. Progress goes from [from] to [from] + [span] (of 1000).
     */
    private suspend fun segments(src: VideoSource, url: String, out: File, from: Int, span: Int, progress: (Int, Int, Long) -> Unit): Long {
        val pl = text(src, url)
        val keys = HashMap<String, ByteArray>()
        val segs = ArrayList<Seg>()
        var key: String? = null
        var iv: ByteArray? = null
        var seq = 0L
        var init: String? = null
        for (raw in pl.lines()) {
            val l = raw.trim()
            when {
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq = l.substringAfter(':').toLongOrNull() ?: 0
                l.startsWith("#EXT-X-MAP:") -> init = attrs(l)["URI"]?.let { resolve(url, it) }
                l.startsWith("#EXT-X-KEY:") -> {
                    val a = attrs(l)
                    when (a["METHOD"]) {
                        "NONE" -> { key = null; iv = null }
                        "AES-128" -> {
                            key = a["URI"]?.let { resolve(url, it) }
                            iv = a["IV"]?.removePrefix("0x")?.removePrefix("0X")?.padStart(32, '0')
                                ?.let { h -> ByteArray(16) { i -> h.substring(i * 2, i * 2 + 2).toInt(16).toByte() } }
                        }
                        else -> throw PermanentDownloadError("This stream is locked in a way the app can't save")
                    }
                }
                l.isNotEmpty() && !l.startsWith("#") -> { segs += Seg(resolve(url, l), key, iv, seq); seq++ }
            }
        }
        if (segs.isEmpty()) throw IOException("The stream has no pieces")
        val done = File(out.path + ".done")
        val start = done.takeIf { it.exists() }?.readText()?.trim()?.toIntOrNull() ?: 0
        if (start == 0) out.delete()
        val cap = testByteCap
        FileOutputStream(out, start > 0).use { os ->
            init?.let { if (start == 0) os.write(bytes(src, it)) }
            for (i in start until segs.size) {
                currentCoroutineContext().ensureActive()
                if (Downloads.freeBytes() < Downloads.MIN_FREE_RUN) throw PermanentDownloadError("The disk is almost full")
                val s = segs[i]
                var b = bytes(src, s.url)
                if (s.key != null) {
                    val k = keys.getOrPut(s.key) { bytes(src, s.key) }
                    val ivb = s.iv ?: ByteArray(16).also { a -> var v = s.seq; for (j in 15 downTo 8) { a[j] = (v and 0xFF).toByte(); v = v shr 8 } }
                    b = Cipher.getInstance("AES/CBC/PKCS5Padding").run { init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(ivb)); doFinal(b) }
                }
                os.write(b)
                os.flush()
                done.writeText((i + 1).toString())
                progress(from + span * (i + 1) / segs.size, 1000, out.length())
                if (cap != null && out.length() >= cap) break
            }
        }
        done.delete()
        return out.length()
    }

    fun dirSize(f: File): Long = f.walkTopDown().filter { it.isFile }.sumOf { it.length() }

    /** What an unfinished download left behind. */
    fun removePartial(ref: VideoRef) {
        if (_videos.value.containsKey(ref.key)) return
        dir(ref).deleteRecursively()
    }

    fun delete(key: String) {
        val o = _videos.value[key] ?: return
        o.dir.deleteRecursively()
        _videos.update { it - key }
    }
}
