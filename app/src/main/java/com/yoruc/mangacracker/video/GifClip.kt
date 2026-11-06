package com.yoruc.mangacracker.video

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.gif.FileFrames
import com.yoruc.mangacracker.gif.GifCancelled
import com.yoruc.mangacracker.gif.GifMaker
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.Resample
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToInt

/** What the GIF panel asks for: a range of the video (ms) and how to make it. */
data class GifRequest(val startMs: Long, val endMs: Long, val opts: GifOptions)

/** A finished GIF in the app's cache. */
data class GifDone(val file: File, val bytes: Long, val width: Int, val height: Int, val durationMs: Long, val fps: Double)

/**
 * The GIF button's work on the phone: the chosen range is cut out of the stream with the same player client (cookies,
 * keys) into a temporary MP4 (video only, at most 720p, high bit rate), its frames are read back with
 * MediaMetadataRetriever into a cache file at the biggest GIF size, and [GifMaker] does the rest. Temp files go.
 */
object GifClip {
    const val MAX_CAPTURE_MS = 30_000L
    const val MAX_RANGE_MS = 30_000L
    private const val WORK_LONG = 640   // frames are kept at the biggest GIF size; every try shrinks from there
    private const val FRAMES_BUDGET = 450_000_000L   // bytes of frames in the cache file (long clips are kept smaller)

    private fun dir(ctx: Context) = File(ctx.cacheDir, "gif").apply { mkdirs() }

    /** Removes everything the GIF maker left in the cache (closing the panel). */
    fun cleanup(ctx: Context) { dir(ctx).listFiles()?.forEach { it.delete() } }

    suspend fun make(
        ctx: Context, src: VideoSource, stream: VideoStream, videoHeight: Int, req: GifRequest, progress: (String, Float) -> Unit,
    ): GifDone {
        val dir = dir(ctx)
        val cut = File(dir, "cut.mp4")
        try {
            // a download plays from the phone: no key unlock needed (and maybe no network)
            if (stream.local.isEmpty()) withContext(Dispatchers.IO) { src.beforePlay() }
            progress("Getting the clip", 0f)
            cutClip(ctx, src, stream, req.startMs, req.endMs, if (videoHeight > 720) 720 else 0, cut) { progress("Getting the clip", it / 100f) }
            val job = currentCoroutineContext()[Job]
            // slow motion and 50 fps use every frame of a 60 fps video; otherwise about 30 a second are enough
            val keepFps = if (req.opts.speed < 0.999f || req.opts.fps > 30) 60.0 else 30.0
            val frames = withContext(Dispatchers.Default) { read(cut, File(dir, "frames.bin"), keepFps, progress) { job?.isActive == false } }
            try {
                val r = withContext(Dispatchers.Default) {
                    GifMaker(frames, req.opts).apply { this.progress = progress; cancelled = { job?.isActive == false } }.make()
                }
                dir.listFiles { f -> f.name.startsWith("gif_") }?.forEach { it.delete() }
                val out = File(dir, "gif_${System.currentTimeMillis()}.gif").apply { writeBytes(r.bytes) }
                return GifDone(out, r.bytes.size.toLong(), r.width, r.height, r.durationMs, r.fps)
            } finally { frames.close() }
        } catch (e: GifCancelled) {
            throw CancellationException("cancelled")
        } finally { cut.delete() }
    }

    private suspend fun cutClip(ctx: Context, src: VideoSource, stream: VideoStream, a: Long, b: Long, maxHeight: Int, out: File, onProgress: (Int) -> Unit) =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                val cancel = ClipSaver.cut(ctx, src, stream, a, b, out, forGif = true, maxHeight = maxHeight, onProgress = onProgress) { err ->
                    if (cont.isActive) { if (err == null) cont.resume(Unit) else cont.resumeWithException(err) }
                }
                cont.invokeOnCancellation { android.os.Handler(android.os.Looper.getMainLooper()).post { cancel() } }
            }
        }

    /**
     * The clip's frames (at most [keepFps] a second: 60 fps is halved for 30), shrunk to [WORK_LONG] on the long side,
     * or less when a long clip would pass [FRAMES_BUDGET], into a cache file.
     */
    private suspend fun read(file: File, store: File, keepFps: Double, progress: (String, Float) -> Unit, cancelled: () -> Boolean): FileFrames {
        val mmr = MediaMetadataRetriever()
        var frames: FileFrames? = null
        try {
            mmr.setDataSource(file.path)
            val count = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull() ?: 0
            val durMs = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            if (count <= 0 || durMs <= 0) throw SourceException("Couldn't read the clip")
            val srcFps = count * 1000.0 / durMs
            val step = maxOf(1, (srcFps / keepFps).roundToInt())
            val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
            var i = 0
            while (i < count) {
                currentCoroutineContext().ensureActive()
                if (cancelled()) throw GifCancelled()
                val n = minOf(8, count - i)
                // a few at a time: one decode pass per chunk, and only 8 full-size pictures in memory
                val bmps = runCatching { mmr.getFramesAtIndex(i, n, params) }.getOrElse {
                    (i until i + n).mapNotNull { k -> mmr.getFrameAtTime(k * 1_000_000L * durMs / 1000 / count, MediaMetadataRetriever.OPTION_CLOSEST) }
                }
                for ((j, bmp) in bmps.withIndex()) {
                    if ((i + j) % step == 0) {
                        val w = bmp.width; val h = bmp.height
                        val fs = frames ?: run {
                            val kept = (count + step - 1) / step
                            val budget = Math.sqrt(FRAMES_BUDGET / 3.0 / kept / (w.toDouble() * h))
                            val scale = minOf(1.0, WORK_LONG.toDouble() / maxOf(w, h), budget)
                            FileFrames(store, maxOf(2, (w * scale).roundToInt()), maxOf(2, (h * scale).roundToInt()), srcFps / step)
                        }.also { frames = it }
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        for (q in px.indices) px[q] = px[q] and 0xFFFFFF
                        fs.add(if (w == fs.width && h == fs.height) px else Resample.resize(px, w, h, fs.width, fs.height))
                    }
                    bmp.recycle()
                }
                i += n
                progress("Reading the frames", i.toFloat() / count)
            }
            return frames ?: throw SourceException("No frames in the clip")
        } catch (e: Throwable) {
            frames?.close(); throw e
        } finally { mmr.release() }
    }
}
