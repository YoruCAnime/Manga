package com.yoruc.mangacracker.video

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.Clock
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.Presentation
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultAssetLoaderFactory
import androidx.media3.transformer.DefaultDecoderFactory
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.VideoEncoderSettings
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import com.yoruc.mangacracker.core.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Snapshots (Pictures/MangaCracker/Snapshots), recorded clips (Movies/MangaCracker) and GIFs (Pictures/MangaCracker/GIFs). */
object ClipSaver {
    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "video" }

    suspend fun saveSnapshot(ctx: Context, bmp: Bitmap, name: String): String = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${clean(name)}.png")
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MangaCracker/Snapshots")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw SourceException("Could not create the file")
        cr.openOutputStream(uri)!!.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        "Pictures/MangaCracker/Snapshots"
    }

    /** A finished GIF -> Pictures/MangaCracker/GIFs. */
    suspend fun saveGif(ctx: Context, file: File, name: String): String = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${clean(name)}.gif")
            put(MediaStore.Images.Media.MIME_TYPE, "image/gif")
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MangaCracker/GIFs")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw SourceException("Could not create the file")
        cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        "Pictures/MangaCracker/GIFs"
    }

    /** Hands a finished GIF to another app (through the FileProvider's share/ folder). */
    suspend fun shareGif(ctx: Context, file: File, name: String) {
        val copy = withContext(Dispatchers.IO) {
            val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            File(dir, "${clean(name)}.gif").also { file.copyTo(it, overwrite = true) }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", copy)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("image/gif")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(android.content.Intent.createChooser(send, "Share GIF").addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private suspend fun publish(ctx: Context, file: File, name: String): String = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, "${clean(name)}.mp4")
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/MangaCracker")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values) ?: throw SourceException("Could not create the file")
        cr.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
        values.clear(); values.put(MediaStore.Video.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        file.delete()
        "Movies/MangaCracker"
    }

    /**
     * Cuts [startMs]..[endMs] out of the stream into an MP4 (H.264 + AAC, re-encoded where needed, e.g. from WebM)
     * and saves it to Movies/MangaCracker. [onProgress] gets 0..100. Must be called on the main thread.
     */
    fun record(
        ctx: Context, source: VideoSource, stream: VideoStream, startMs: Long, endMs: Long, name: String,
        onProgress: (Int) -> Unit, onDone: suspend (Result<String>) -> Unit,
    ): () -> Unit {
        val app = ctx.applicationContext
        val out = File(app.cacheDir, "clips").apply { mkdirs() }.let { File(it, "clip_${System.currentTimeMillis()}.mp4") }
        return cut(app, source, stream, startMs, endMs, out, forGif = false, maxHeight = 0, onProgress = onProgress) { err ->
            if (err == null) onDone(runCatching { publish(app, out, name) }) else onDone(Result.failure(err))
        }
    }

    /**
     * The cutting behind [record] and the GIF maker. [forGif]: video only, at most [maxHeight] tall, a high bit rate and
     * a key frame every half second (so nothing blurs and the frames read back fast). [onDone] gets null on success;
     * [out] is deleted on failure. Returns a cancel function. Main thread.
     */
    @OptIn(UnstableApi::class)
    fun cut(
        ctx: Context, source: VideoSource, stream: VideoStream, startMs: Long, endMs: Long, out: File, forGif: Boolean, maxHeight: Int,
        onProgress: (Int) -> Unit, onDone: suspend (Throwable?) -> Unit,
    ): () -> Unit {
        val app = ctx.applicationContext
        val item = VideoDownloads.mediaItem(stream) {
            setClippingConfiguration(MediaItem.ClippingConfiguration.Builder().setStartPositionMs(startMs).setEndPositionMs(endMs).build())
        }
        // the site through the player client, or a download's file / HLS cache
        val sources = DefaultMediaSourceFactory(app).setDataSourceFactory(VideoDownloads.dataSourceFactory(app, source, stream))
        val main = Handler(Looper.getMainLooper())
        val scope = kotlinx.coroutines.MainScope()
        var finished = false
        val transformer = Transformer.Builder(app)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setAssetLoaderFactory(DefaultAssetLoaderFactory(app, DefaultDecoderFactory.Builder(app).build(), Clock.DEFAULT, sources, DataSourceBitmapLoader(app)))
            .apply {
                if (forGif) setEncoderFactory(DefaultEncoderFactory.Builder(app)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(16_000_000).setiFrameIntervalSeconds(0.5f).build())
                    .build())
            }
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    finished = true
                    scope.launch { onDone(null) }
                }
                override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                    finished = true
                    out.delete()
                    scope.launch { onDone(exportException) }
                }
            })
            .build()
        val edited = EditedMediaItem.Builder(item).apply {
            if (forGif) {
                setRemoveAudio(true)
                if (maxHeight > 0) setEffects(Effects(emptyList(), listOf(Presentation.createForHeight(maxHeight))))
            }
        }.build()
        transformer.start(edited, out.path)
        val holder = ProgressHolder()
        val tick = object : Runnable {
            override fun run() {
                if (finished) return
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress)
                main.postDelayed(this, 400)
            }
        }
        main.post(tick)
        return { if (!finished) { finished = true; transformer.cancel(); out.delete() } }
    }
}
