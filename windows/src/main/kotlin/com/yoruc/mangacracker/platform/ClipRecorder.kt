package com.yoruc.mangacracker.platform

import com.sun.jna.platform.win32.Kernel32
import com.sun.jna.platform.win32.WinNT
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
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

    private fun t(s: Double) = "%.3f".format(Locale.US, s)

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
            val args = mutableListOf(ff.path, "-hide_banner", "-loglevel", "error", "-y", "-nostdin", "-ss", t(start))
            args += input
            if (audio != null) args += listOf("-ss", t(start), "-i", audio.path)
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
     * video's own (from the player).
     * [onFrame] gets the share read so far (0..1). The caller closes the frames.
     */
    suspend fun frames(
        input: List<String>, start: Double, end: Double, srcW: Int, srcH: Int, srcFps: Double, long: Int, name: String,
        keepFps: Double = 30.0, onFrame: (Float) -> Unit = {},
    ): com.yoruc.mangacracker.gif.FileFrames = withContext(Dispatchers.IO) {
        val ff = Avif.ffmpeg ?: throw Failed("GIFs need ffmpeg (not found)")
        if (srcW <= 0 || srcH <= 0) throw Failed("The video isn't playing yet")
        val len = (end - start).coerceIn(0.2, MAX_GIF_SECONDS + 8)
        val (w, h) = if (srcW >= srcH) long to ((long.toDouble() * srcH / srcW / 2).toInt() * 2).coerceAtLeast(2)
            else ((long.toDouble() * srcW / srcH / 2).toInt() * 2).coerceAtLeast(2) to long
        val fps = if (srcFps > keepFps + 1) srcFps / 2 else if (srcFps > 1) srcFps else 24.0
        val dir = File(Desktop.ctx.cacheDir, "gif").apply { mkdirs() }
        // a file of its own for every reading (and every running copy): a new reading never cuts an old one short
        // while its frames are still being drawn; files left by a crash are cleared after a day
        dir.listFiles()?.filter { it.name.endsWith(".bin") && System.currentTimeMillis() - it.lastModified() > 86_400_000L }?.forEach { it.delete() }
        val frames = com.yoruc.mangacracker.gif.FileFrames(File(dir, "$name-${ProcessHandle.current().pid()}-${System.nanoTime()}.bin"), w, h, fps)
        val args = mutableListOf(ff.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-ss", t(start))
        args += input
        args += listOf("-t", t(len), "-an", "-sn", "-dn", "-vf", "fps=%.4f,scale=$w:$h:flags=lanczos".format(Locale.US, fps),
            "-f", "rawvideo", "-pix_fmt", "rgb24", "pipe:1")
        val p = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
        lower(p)
        val frameBytes = w * h * 3
        val buf = ByteArray(frameBytes)
        val total = (len * fps).toInt().coerceAtLeast(1)
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
        if (frames.count == 0) { frames.close(); throw Failed("ffmpeg couldn't read the video here") }
        frames
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
