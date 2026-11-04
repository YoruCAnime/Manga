package com.yoruc.mangacracker.platform

import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DecodeResult
import coil3.decode.Decoder
import coil3.fetch.SourceFetchResult
import coil3.request.Options
import coil3.size.Dimension
import okio.ByteString.Companion.encodeUtf8
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Image
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AVIF pictures (atsu stores many chapters only as AVIF). Skia on Windows can't read them, so ffmpeg (found on the
 * PATH or in the usual places) turns them into PNG, scaled down to the width they are shown at.
 */
object Avif {
    val ffmpeg: File? by lazy {
        val onPath = System.getenv("PATH").orEmpty().split(File.pathSeparator).map { File(it, "ffmpeg.exe") }
        val known = listOf(System.getenv("LOCALAPPDATA") + "/Microsoft/WinGet/Links/ffmpeg.exe", "C:/ffmpeg/bin/ffmpeg.exe",
            System.getenv("ProgramFiles") + "/ffmpeg/bin/ffmpeg.exe").map { File(it) }
        (listOf(File(Desktop.root, "ffmpeg.exe")) + onPath + known).firstOrNull { it.isFile }
    }

    fun isAvif(b: ByteArray) = b.size > 12 && String(b, 4, 8, Charsets.ISO_8859_1).let { it == "ftypavif" || it == "ftypavis" }

    /** PNG bytes of an AVIF picture, shrunk to fit [maxWidth] x [maxHeight] (0 = no limit), never enlarged. */
    fun toPng(avif: ByteArray, maxWidth: Int = 0, maxHeight: Int = 0): ByteArray {
        val ff = ffmpeg ?: throw IllegalStateException("AVIF pictures need ffmpeg (not found)")
        // AVIF is read by ffmpeg's MP4 reader, which needs to seek: a temp file, not a pipe
        val tmp = File.createTempFile("avif", ".avif", Desktop.ctx.cacheDir)
        try {
            tmp.writeBytes(avif)
            val args = mutableListOf(ff.path, "-hide_banner", "-loglevel", "error", "-i", tmp.path)
            if (maxWidth > 0 || maxHeight > 0) {
                val w = if (maxWidth > 0) maxWidth else 100_000
                val h = if (maxHeight > 0) maxHeight else 100_000
                // the smaller of the two shrink factors, at most 1, even width
                args += listOf("-vf", "scale=trunc(iw*min(1\\,min($w/iw\\,$h/ih))/2)*2:-2")
            }
            args += listOf("-frames:v", "1", "-f", "image2pipe", "-vcodec", "png", "pipe:1")
            val p = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            p.outputStream.close()
            val reading = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.use { it.readBytes() } }
            if (!p.waitFor(30, TimeUnit.SECONDS)) { p.destroyForcibly(); throw IllegalStateException("ffmpeg took too long") }
            val out = reading.get(10, TimeUnit.SECONDS)
            if (out.isEmpty()) throw IllegalStateException("This AVIF picture can't be read")
            return out
        } finally { tmp.delete() }
    }

    /** Coil decoder for AVIF bytes. */
    class CoilDecoder(private val result: SourceFetchResult, private val options: Options) : Decoder {
        override suspend fun decode(): DecodeResult {
            val bytes = result.source.source().use { it.readByteArray() }
            val w = (options.size.width as? Dimension.Pixels)?.px ?: 0
            val h = (options.size.height as? Dimension.Pixels)?.px ?: 0
            val img = Image.makeFromEncoded(toPng(bytes, w, h))
            val bmp = Bitmap.makeFromImage(img)
            img.close()
            bmp.setImmutable()
            return DecodeResult(bmp.asImage(), isSampled = w > 0 || h > 0)
        }

        class Factory : Decoder.Factory {
            override fun create(result: SourceFetchResult, options: Options, imageLoader: ImageLoader): Decoder? {
                val s = result.source.source()
                if (!s.request(12) || !(s.rangeEquals(4, "ftypavif".encodeUtf8()) || s.rangeEquals(4, "ftypavis".encodeUtf8()))) return null
                return CoilDecoder(result, options)
            }
        }
    }
}
