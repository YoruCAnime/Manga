package com.yoruc.mangacracker.platform

import com.yoruc.mangacracker.gif.GifCancelled
import com.yoruc.mangacracker.gif.InBetweens
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.awt.image.BufferedImage
import java.io.Closeable
import java.io.File
import java.util.concurrent.TimeUnit
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

/**
 * RIFE ("Real-Time Intermediate Flow Estimation": rife-ncnn-vulkan with the rife-v4.6 model, on the graphics card) draws
 * the GIF's slow-motion / 50 fps in-betweens, like the ScreenToGif AnimeGif presets. One run per GIF: the key frames go
 * in, and asking for [steps] times as many frames back gives every key pair its in-betweens at 1/steps, 2/steps, ...
 * (frame i comes out at position i x keys / frames). The program takes ~2 s to start but then draws a few hundred
 * frames a second. Shipped in appResources/windows/rife (not in git); MC_NO_RIFE=1 turns it off (tests).
 */
class Rife private constructor(private val exe: File, private val model: File) : InBetweens, Closeable {
    private val dir = File(Desktop.ctx.cacheDir, "gif/rife-${ProcessHandle.current().pid()}-${System.nanoTime()}")
    private val out = File(dir, "out")
    private var w = 0; private var h = 0; private var steps = 1
    private val recent = LinkedHashMap<Int, IntArray>(16, 0.75f, true)

    override fun prepare(
        keys: IntArray, frame: (Int) -> IntArray, w: Int, h: Int, steps: Int, progress: (Float) -> Unit, cancelled: () -> Boolean,
    ): Boolean {
        if (keys.size < 2) return false
        dir.deleteRecursively()
        val inp = File(dir, "in").apply { mkdirs() }
        out.mkdirs()
        this.w = w; this.h = h; this.steps = steps
        // the key frames as uncompressed PNGs (compressing them was most of the wait), a few written at once
        for (start in keys.indices step 8) {
            if (cancelled()) throw GifCancelled()
            val batch = (start until minOf(keys.size, start + 8)).map { k -> k to frame(keys[k]) }
            batch.parallelStream().forEach { (k, px) -> writePng(px, w, h, File(inp, name(k))) }
            progress(0.2f * minOf(keys.size, start + 8) / keys.size)
        }
        val n = keys.size * steps
        val p = ProcessBuilder(exe.path, "-i", inp.path, "-o", out.path, "-n", "$n", "-m", model.path, "-f", "%08d.png", "-j", "1:2:4")
            .directory(exe.parentFile).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        ClipRecorder.lower(p)
        try {
            while (!p.waitFor(250, TimeUnit.MILLISECONDS)) {
                if (cancelled()) throw GifCancelled()
                progress(0.2f + 0.8f * (out.list()?.size ?: 0) / n)
            }
        } catch (e: Throwable) { p.destroyForcibly(); throw e }
        // the last pair's last in-between must be there
        return p.exitValue() == 0 && File(out, name((keys.size - 1) * steps - 1)).isFile
    }

    override fun get(pair: Int, step: Int): IntArray = synchronized(recent) {
        recent.getOrPut(pair * steps + step) {
            // Skia decodes PNGs several times faster than ImageIO
            val img = Image.makeFromEncoded(File(out, name(pair * steps + step)).readBytes())
            val b = try { Bitmap.makeFromImage(img).use { it.readPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.OPAQUE)) } }
                finally { img.close() } ?: error("RIFE frame ${pair * steps + step} unreadable")
            IntArray(w * h) { k -> ((b[4 * k + 2].toInt() and 0xFF) shl 16) or ((b[4 * k + 1].toInt() and 0xFF) shl 8) or (b[4 * k].toInt() and 0xFF) }
        }.also { while (recent.size > 6) recent.remove(recent.keys.first()) }
    }

    /** Deletes the frames. */
    override fun close() { dir.deleteRecursively() }

    private fun name(i: Int) = "%08d.png".format(i + 1)

    /** An uncompressed PNG (deflate level 0: quick to write, quick for RIFE to read). */
    private fun writePng(px: IntArray, w: Int, h: Int, f: File) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB).apply { setRGB(0, 0, w, h, px, 0, w) }
        val writer = ImageIO.getImageWritersByFormatName("png").next()
        val param = writer.defaultWriteParam.apply { compressionMode = ImageWriteParam.MODE_EXPLICIT; compressionQuality = 1f }
        ImageIO.createImageOutputStream(f).use { os -> writer.output = os; writer.write(null, IIOImage(img, null, null), param) }
        writer.dispose()
    }

    companion object {
        /** The folder with rife-ncnn-vulkan.exe and rife-v4.6\ (in the app, or ScreenToGif's AnimeGif copy). */
        private val home: File? by lazy {
            if (System.getenv("MC_NO_RIFE") == "1") return@lazy null
            listOfNotNull(
                System.getProperty("compose.application.resources.dir")?.let { File(it, "rife") },
                File(System.getProperty("user.dir"), "appResources/windows/rife"),
                System.getenv("APPDATA")?.let { File(it, "ScreenToGif/rife") },
            ).firstOrNull { File(it, "rife-ncnn-vulkan.exe").isFile && File(it, "rife-v4.6/flownet.param").isFile }
        }

        val available get() = home != null

        /** A RIFE for one GIF, or null when it isn't there (the GIF then uses the built-in in-betweens). */
        fun open(): Rife? = home?.let { Rife(File(it, "rife-ncnn-vulkan.exe"), File(it, "rife-v4.6")) }
    }
}
