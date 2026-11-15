package com.yoruc.mangacracker.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

/**
 * Record and GIF in the player, both done by ffmpeg (below normal priority). [input] = ffmpeg's input options from
 * [StreamProxy.ffmpegInput] (the site's cookies / keys) or `-i <file>` for a download.
 */
object ClipRecorder {
    class Failed(msg: String) : Exception(msg)

    /** The longest GIF (as on the phone). */
    const val MAX_GIF_SECONDS = 30.0

    /** A part of the picture for the GIF, as shares (0..1) of the video's width and height. */
    data class Crop(val x: Float, val y: Float, val w: Float, val h: Float) {
        val full: Boolean get() = x <= 0.002f && y <= 0.002f && w >= 0.996f && h >= 0.996f
    }

    private fun t(s: Double) = "%.3f".format(Locale.US, s)

    /** A streamed video's playlist (the variant ffmpeg plays), cut into pieces, with what each piece needs before it. */
    private class Hls(val header: List<String>, val seq0: Long, val pieces: List<Piece>) {
        class Piece(val start: Double, val lines: List<String>, val key: String?, val map: String?)
        /** each piece's start in the player's time, read from its own timestamps (filled as needed) */
        val real = java.util.concurrent.ConcurrentHashMap<Int, Double>()
        @Volatile var zero: Double? = null
    }
    private val playlists = java.util.concurrent.ConcurrentHashMap<String, Hls>()
    private const val PROTOCOLS = "file,http,https,tcp,tls,crypto,data"

    private fun hls(url: String): Hls? = playlists[url] ?: runCatching {
        fun get(u: String) = java.net.URI(u).toURL().openConnection().apply { connectTimeout = 15_000; readTimeout = 20_000 }
            .getInputStream().use { it.readBytes().toString(Charsets.UTF_8) }
        var u = url
        var text = get(u)
        if ("#EXT-X-STREAM-INF" in text) {
            // ffmpeg plays the best variant: the highest bandwidth
            val lines = text.lines()
            val best = lines.indices.filter { lines[it].startsWith("#EXT-X-STREAM-INF") }
                .maxBy { Regex("BANDWIDTH=(\\d+)").find(lines[it])?.groupValues?.get(1)?.toLongOrNull() ?: 0L }
            val v = lines.drop(best + 1).first { it.isNotBlank() && !it.startsWith("#") }.trim()
            u = java.net.URI(u).resolve(v).toString()
            text = get(u)
        }
        val header = mutableListOf<String>(); var seq0 = 0L
        val pieces = mutableListOf<Hls.Piece>()
        var key: String? = null; var map: String? = null; var at = 0.0
        val block = mutableListOf<String>()
        for (raw in text.lines()) {
            val l = raw.trim()
            when {
                l.isEmpty() || l.startsWith("#EXT-X-ENDLIST") -> {}
                l.startsWith("#EXTM3U") || l.startsWith("#EXT-X-VERSION") || l.startsWith("#EXT-X-TARGETDURATION") ||
                    l.startsWith("#EXT-X-PLAYLIST-TYPE") || l.startsWith("#EXT-X-INDEPENDENT-SEGMENTS") || l.startsWith("#EXT-X-ALLOW-CACHE") -> header += l
                l.startsWith("#EXT-X-MEDIA-SEQUENCE:") -> seq0 = l.substringAfter(':').trim().toLongOrNull() ?: 0L
                l.startsWith("#EXT-X-KEY") -> key = l
                l.startsWith("#EXT-X-MAP") -> map = l
                l.startsWith("#") -> block += l
                else -> {
                    val len = block.firstOrNull { it.startsWith("#EXTINF:") }?.substringAfter(':')?.substringBefore(',')?.trim()?.toDoubleOrNull() ?: 0.0
                    pieces += Hls.Piece(at, block + l, key, map)
                    at += len; block.clear()
                }
            }
        }
        Hls(header.ifEmpty { listOf("#EXTM3U") }, seq0, pieces).takeIf { pieces.size > 1 }
    }.getOrNull()?.also { playlists[url] = it }

    /** A playlist file of pieces [from]..[to] (to the end when null), for ffmpeg. */
    private fun writeList(h: Hls, from: Int, to: Int? = null): File {
        val dir = File(Desktop.ctx.cacheDir, "gif").apply { mkdirs() }
        val f = File(dir, "hls-${ProcessHandle.current().pid()}-${System.nanoTime()}.m3u8")
        val p = h.pieces[from]
        val lines = h.header + "#EXT-X-MEDIA-SEQUENCE:${h.seq0 + from}" + listOfNotNull(p.key, p.map) +
            h.pieces.subList(from, (to ?: (h.pieces.size - 1)) + 1).withIndex().flatMap { (k, q) ->
                // a key or init part that changes later in the list is written where it changes
                val prev = if (k == 0) p else h.pieces[from + k - 1]
                listOfNotNull(q.key?.takeIf { k > 0 && it != prev.key }, q.map?.takeIf { k > 0 && it != prev.map }) + q.lines
            } + "#EXT-X-ENDLIST"
        f.writeText(lines.joinToString("\n") + "\n")
        return f
    }

    /** Where piece [i] starts by its own timestamps (ffmpeg's "start:" of a playlist holding only it). */
    private fun ownStart(h: Hls, i: Int): Double? {
        val ff = Avif.ffmpeg ?: return null
        val f = writeList(h, i, i)
        return try {
            val p = ProcessBuilder(ff.path, "-hide_banner", "-nostdin", "-protocol_whitelist", PROTOCOLS, "-extension_picky", "0",
                "-allowed_extensions", "ALL", "-i", f.path, "-t", "0.05", "-an", "-sn", "-dn", "-f", "null", "-").redirectErrorStream(true).start()
            lower(p)
            val out = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(40, java.util.concurrent.TimeUnit.SECONDS)) p.destroyForcibly()
            Regex("start: (-?[0-9.]+)").find(out)?.groupValues?.get(1)?.toDoubleOrNull()
        } finally { f.delete() }
    }

    /** Piece [i]'s start in the player's time (the player counts from the stream's own start). */
    private fun realStart(h: Hls, i: Int): Double? {
        h.real[i]?.let { return it }
        val zero = h.zero ?: ownStart(h, 0)?.also { h.zero = it } ?: return null
        val s = (if (i == 0) zero else ownStart(h, i) ?: return null) - zero
        h.real[i] = s
        return s
    }

    /**
     * How ffmpeg starts reading at [start]: options before the input, the input, options after it, and the options for
     * a separate audio input. A streamed video (HLS through the relay) is NOT jumped into: on Anikoto ffmpeg's jump
     * landed at the start of the piece while calling it [start] (the GIF editor's row came out 10 s early), and after a
     * mislabelled 11 s piece in Prison School 1 its jumps landed on other scenes altogether (the GIF came out "old").
     * The player goes by the pieces' own timestamps, so ffmpeg gets a playlist that starts at the right piece (found by
     * those timestamps) and decodes on to [start].
     */
    private class Seek(val pre: List<String>, val input: List<String>, val post: List<String>, val audioPre: List<String>, val from: Double,
                       val skip: Double = 0.0, val streamAt: Double? = null)

    private fun seekAt(input: List<String>, start: Double): Seek {
        val plain = Seek(listOf("-ss", t(start)), input, emptyList(), listOf("-ss", t(start)), start)
        if (start <= 0.05 || "-allowed_extensions" !in input || input.getOrNull(input.size - 2) != "-i") return plain
        val h = hls(input.last()) ?: return plain
        var i = h.pieces.indexOfLast { it.start <= start }.coerceAtLeast(0)
        var real: Double? = null
        for (step in 0 until 8) {
            val r = realStart(h, i) ?: return plain
            if (r <= start + 0.02 || i == 0) { real = r; break }
            // the stream's times run ahead of the playlist here: step back by the gap
            val j = h.pieces.indexOfLast { it.start <= start - (r - h.pieces[i].start) }.coerceAtLeast(0)
            i = if (j < i) j else i - 1
        }
        val from = real ?: return plain
        val list = writeList(h, i)
        val inp = input.dropLast(2) + listOf("-protocol_whitelist", PROTOCOLS, "-i", list.path)
        val skip = start - from
        return Seek(emptyList(), inp, if (skip > 0.005) listOf("-ss", t(skip)) else emptyList(), listOf("-ss", t(from)), from,
            skip.coerceAtLeast(0.0), (h.zero ?: 0.0) + start)
    }

    /** Where reading from [start] really begins (see [seekAt]): for the tests. */
    internal fun jumpFor(input: List<String>, start: Double): Double = seekAt(input, start).from

    /**
     * Record: start..end into an H.264 / AAC MP4. The graphics card's encoder (NVENC) first, the processor's (x264)
     * if that fails. [onProgress] 0..1. Returns [out].
     */
    suspend fun cut(input: List<String>, start: Double, end: Double, out: File, audio: File? = null, onProgress: (Float) -> Unit): File = withContext(Dispatchers.IO) {
        val ff = Avif.ffmpeg ?: throw Failed("Recording needs ffmpeg (not found)")
        val len = (end - start).coerceAtLeast(0.5)
        val encoders = listOf(
            listOf("-c:v", "h264_nvenc", "-preset", "p5", "-cq", "21", "-pix_fmt", "yuv420p"),
            listOf("-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p"),
        )
        var lastError = ""
        for (enc in encoders) {
            val tmp = File(out.path + ".part.mp4")
            val sk = seekAt(input, start)
            val args = mutableListOf(ff.path, "-hide_banner", "-loglevel", "error", "-y", "-nostdin")
            args += sk.pre
            args += sk.input
            if (audio != null) args += sk.audioPre + listOf("-i", audio.path)
            args += sk.post
            args += listOf("-t", t(len), "-map", "0:v:0", "-map", if (audio != null) "1:a:0?" else "0:a:0?", "-sn", "-dn")
            args += enc
            args += listOf("-c:a", "aac", "-b:a", "160k", "-movflags", "+faststart", "-progress", "pipe:1", "-nostats", tmp.path)
            val p = ProcessBuilder(args).start()
            lower(p)
            val err = StringBuilder()
            val errThread = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (err.length < 4000) err.appendLine(it) } } }.apply { start() }
            val stop = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) p.destroyForcibly() }
            try {
                p.inputStream.bufferedReader().useLines { lines ->
                    for (l in lines) {
                        currentCoroutineContext().ensureActive()
                        if (l.startsWith("out_time_us=")) l.substringAfter('=').toLongOrNull()?.let { onProgress(((it / 1e6) / len).toFloat().coerceIn(0f, 1f)) }
                    }
                }
                p.waitFor()
                currentCoroutineContext().ensureActive()
            } catch (e: Throwable) { p.destroyForcibly(); tmp.delete(); throw e } finally { stop?.dispose() }
            errThread.join(2000)
            if (p.exitValue() == 0 && tmp.length() > 0) {
                out.delete()
                if (!tmp.renameTo(out)) throw Failed("Couldn't save the clip")
                onProgress(1f)
                return@withContext out
            }
            tmp.delete()
            lastError = err.toString().lines().lastOrNull { it.isNotBlank() }.orEmpty()
        }
        throw Failed("ffmpeg couldn't make the clip" + if (lastError.isNotBlank()) ": ${lastError.take(160)}" else "")
    }

    /**
     * The frames of start..end (at most [MAX_GIF_SECONDS]) read by ffmpeg into a cache file, [long] px on the long
     * side, the video's own speed up to [keepFps] a second (a faster video is halved). [srcW] x [srcH] / [srcFps] = the
     * video's own (from the player). [crop]: only that part of the picture, cut at the video's full size before it is
     * shrunk (so a small part keeps its detail).
     * [onFrame] gets the share read so far (0..1). The caller closes the frames.
     */
    suspend fun frames(
        input: List<String>, start: Double, end: Double, srcW: Int, srcH: Int, srcFps: Double, long: Int, name: String,
        keepFps: Double = 30.0, crop: Crop? = null, onFrame: (Float) -> Unit = {},
    ): com.yoruc.mangacracker.gif.FileFrames = withContext(Dispatchers.IO) {
        val ff = Avif.ffmpeg ?: throw Failed("GIFs need ffmpeg (not found)")
        if (srcW <= 0 || srcH <= 0) throw Failed("The video isn't playing yet")
        val len = (end - start).coerceIn(0.2, MAX_GIF_SECONDS + 8)
        val c = crop?.takeUnless { it.full }
        val cw = if (c != null) srcW * c.w.toDouble() else srcW.toDouble()
        val ch = if (c != null) srcH * c.h.toDouble() else srcH.toDouble()
        val (w, h) = if (cw >= ch) long to ((long * ch / cw / 2).toInt() * 2).coerceAtLeast(2)
            else ((long * cw / ch / 2).toInt() * 2).coerceAtLeast(2) to long
        // the stream's own size can differ from the player's numbers, so the cut is in shares of it (iw / ih)
        val cut = if (c == null) "" else "crop=w=trunc(iw*%.5f/2)*2:h=trunc(ih*%.5f/2)*2:x=trunc(iw*%.5f):y=trunc(ih*%.5f),"
            .format(Locale.US, c.w, c.h, c.x, c.y)
        val fps = if (srcFps > keepFps + 1) srcFps / 2 else if (srcFps > 1) srcFps else 24.0
        val dir = File(Desktop.ctx.cacheDir, "gif").apply { mkdirs() }
        // a file of its own for every reading (and every running copy): a new reading never cuts an old one short
        // while its frames are still being drawn; files left by a crash are cleared after a day
        dir.listFiles()?.filter { (it.name.endsWith(".bin") || it.name.endsWith(".m3u8")) && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val frames = com.yoruc.mangacracker.gif.FileFrames(File(dir, "$name-${ProcessHandle.current().pid()}-${System.nanoTime()}.bin"), w, h, fps)
        val frameBytes = w * h * 3
        val buf = ByteArray(frameBytes)
        val total = (len * fps).toInt().coerceAtLeast(1)
        // a site can give nothing for a moment (Hentaigasm did: every read for a few seconds, then fine), so try again
        // before giving up; ffmpeg's first message goes in cache\gif\last_gif.log
        val tries = 4
        var why = ""
        for (attempt in 0 until tries) {
            if (attempt > 0) delay(1500L * attempt)
            val sk = seekAt(input, start)
            val args = mutableListOf(ff.path, "-hide_banner", "-loglevel", "error", "-nostdin")
            args += sk.pre
            // a streamed video read from the start of its piece: cut at the stream's own time (-copyts). Without the
            // audio, ffmpeg's own count starts at the first video frame, 2 frames after the audio on Anikoto, and a cut
            // by that count came out late
            val trim = sk.streamAt?.let { "trim=start=%.4f,setpts=PTS-STARTPTS,".format(Locale.US, it) }.orEmpty()
            if (sk.streamAt != null) args += "-copyts"
            args += sk.input
            args += listOf("-t", t(len), "-an", "-sn", "-dn", "-vf", trim + cut + "fps=%.4f,scale=$w:$h:flags=lanczos".format(Locale.US, fps),
                "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
            val p = ProcessBuilder(args).start()
            lower(p)
            val err = StringBuilder()
            val errThread = Thread { runCatching { p.errorStream.bufferedReader().forEachLine { if (err.length < 4000) err.appendLine(it) } } }.apply { isDaemon = true; start() }
            val stop = currentCoroutineContext()[Job]?.invokeOnCompletion { if (it != null) p.destroyForcibly() }
            try {
                p.inputStream.buffered(1 shl 20).use { inp ->
                    while (true) {
                        var n = 0
                        while (n < frameBytes) { val r = inp.read(buf, n, frameBytes - n); if (r < 0) break; n += r }
                        if (n < frameBytes) break
                        currentCoroutineContext().ensureActive()
                        frames.add(IntArray(w * h) { k -> ((buf[3 * k].toInt() and 0xFF) shl 16) or ((buf[3 * k + 1].toInt() and 0xFF) shl 8) or (buf[3 * k + 2].toInt() and 0xFF) })
                        onFrame((frames.count.toFloat() / total).coerceAtMost(1f))
                    }
                }
                p.waitFor()
                currentCoroutineContext().ensureActive()
            } catch (e: Throwable) { p.destroyForcibly(); frames.close(); throw e } finally { stop?.dispose() }
            errThread.join(2000)
            // the first message is the cause; the ones after it only say that ffmpeg gave up
            why = err.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().replace(Regex("^\\[[^]]+]\\s*"), "").take(140)
            if (frames.count > 0) return@withContext frames
            runCatching { File(dir, "last_gif.log").appendText("read ${attempt + 1} of $tries from $start: no frames, ffmpeg ended ${p.exitValue()}: $why\n") }
        }
        frames.close()
        throw Failed("The site didn't send the video (tried $tries times). Close this and try again in a moment." + if (why.isNotBlank()) "\n($why)" else "")
    }

    /**
     * The phone's GIF engine over [frames] (all of them), fitted to [opts]' size, with RIFE drawing the slow-motion /
     * 50 fps in-betweens when it's there. Returns [out].
     */
    suspend fun gif(frames: com.yoruc.mangacracker.gif.FrameSource, opts: com.yoruc.mangacracker.gif.GifOptions, out: File, onProgress: (String, Float) -> Unit): File =
        withContext(Dispatchers.Default) {
            val job = currentCoroutineContext()[kotlinx.coroutines.Job]
            val rife = Rife.open()
            try {
                val r = com.yoruc.mangacracker.gif.GifMaker(frames, opts).apply {
                    progress = onProgress
                    cancelled = { job?.isActive == false }
                    drawer = rife
                }.make()
                out.writeBytes(r.bytes)
            } finally { rife?.close() }
            out
        }

    /** Below normal priority, so a clip never slows the video or the PC. */
    private val live = java.util.concurrent.ConcurrentHashMap.newKeySet<Process>()
    init { Runtime.getRuntime().addShutdownHook(Thread { live.forEach { runCatching { it.destroyForcibly() } } }) }

    internal fun lower(p: Process) {
        live += p
        p.onExit().thenRun { live -= p }
        runCatching {
            val h = Kernel32.INSTANCE.OpenProcess(WinNT.PROCESS_SET_INFORMATION, false, p.pid().toInt()) ?: return
            Kernel32.INSTANCE.SetPriorityClass(h, com.sun.jna.platform.win32.WinDef.DWORD(0x00004000L))
            Kernel32.INSTANCE.CloseHandle(h)
        }
    }
}
