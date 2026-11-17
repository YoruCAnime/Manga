package com.yoruc.mangacracker.core

import android.content.Context
import com.yoruc.mangacracker.platform.Desktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Saves pictures to <saved pages folder>\<title>\ ([Desktop.pages]); "Share" puts the picture on the clipboard. */
object ImageSaver {
    private fun ext(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        bytes.size > 11 && String(bytes, 8, 4) == "WEBP" -> "webp"
        bytes.size > 11 && String(bytes, 4, 8).let { it == "ftypavif" || it == "ftypavis" } -> "avif"
        else -> "jpg"
    }

    private fun folder(name: String) = File(Desktop.pages, Desktop.clean(name)).apply { mkdirs() }

    /** The folder a title's pages go to (for the messages after saving). */
    fun folderOf(title: String): File = File(Desktop.pages, Desktop.clean(title))

    /** Returns the saved file. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun save(ctx: Context, url: String, folder: String, name: String): File = withContext(Dispatchers.IO) {
        val bytes = Http.bytes(url)
        Desktop.freeFile(folder(folder), Desktop.clean(name), ext(bytes)).apply { writeBytes(bytes) }
    }

    /** One picture waiting to be saved: where it goes, and how to find its address ([best] = the full-size original, if the site has one). */
    class Job(val folder: String, val name: String, val best: String, val url: suspend () -> String)

    private val queue = kotlinx.coroutines.channels.Channel<List<Job>>(kotlinx.coroutines.channels.Channel.UNLIMITED)
    private val worker = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)
    /** Pictures still to save (for a "saving n" hint). */
    val waiting = kotlinx.coroutines.flow.MutableStateFlow(0)
    @Volatile private var started = false

    /**
     * Saves in the background, one after another, so nothing waits on a big download and leaving the reader doesn't
     * stop it. Each picture is the best the site has: the original where the reader shows a reduced copy, the page's
     * own file otherwise (never a re-compressed one). A message says when a batch is done.
     */
    fun enqueue(jobs: List<Job>) {
        if (jobs.isEmpty()) return
        waiting.value += jobs.size
        if (jobs.size > 1) Desktop.toast("Saving ${jobs.size} pictures in the background")
        queue.trySend(jobs)
        if (started) return
        synchronized(this) { if (started) return; started = true }
        worker.launch {
            for (batch in queue) {
                var ok = 0; var full = 0; var last: File? = null
                for (j in batch) {
                    val file = runCatching {
                        // the original first; if the site won't give it, the page as it was read
                        val bytes = j.best.takeIf { it.isNotEmpty() }?.let { runCatching { Http.bytes(it) }.getOrNull() }?.also { full++ } ?: Http.bytes(j.url())
                        Desktop.freeFile(folder(j.folder), Desktop.clean(j.name), ext(bytes)).apply { writeBytes(bytes) }
                    }.getOrNull()
                    if (file != null) { ok++; last = file }
                    waiting.value = (waiting.value - 1).coerceAtLeast(0)
                }
                val size = if (full > 0) " (full size)" else ""
                Desktop.toast(when {
                    ok == 0 -> "Couldn't save: the site didn't send the picture"
                    batch.size == 1 -> "Saved$size to $last"
                    ok < batch.size -> "Saved $ok of ${batch.size} pictures$size to ${last?.parentFile}"
                    else -> "Saved $ok pictures$size to ${last?.parentFile}"
                })
            }
        }
    }

    /** A picture made in the app (ARGB pixels, e.g. a character cutout) as PNG next to the title's saved pages. */
    suspend fun savePng(px: IntArray, w: Int, h: Int, folder: String, name: String): File = withContext(Dispatchers.IO) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, px, 0, w) }
        Desktop.freeFile(folder(folder), Desktop.clean(name), "png").apply { ImageIO.write(img, "png", this) }
    }

    /** Copies a picture made in the app to the clipboard (paste it into Discord, Paint, ...). */
    fun copyPng(px: IntArray, w: Int, h: Int) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, px, 0, w) }
        clip(img)
    }

    /** Copies a page to the clipboard. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun share(ctx: Context, url: String) {
        val bytes = Http.bytes(url)
        val img = withContext(Dispatchers.IO) { ImageIO.read(ByteArrayInputStream(bytes)) }
            ?: org.jetbrains.skia.Image.makeFromEncoded(bytes).let { si ->
                val bmp = org.jetbrains.skia.Bitmap.makeFromImage(si)
                val w = bmp.width; val h = bmp.height
                val px = IntArray(w * h)
                val raw = bmp.readPixels(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.UNPREMUL), w * 4, 0, 0)
                    ?: throw SourceException("Couldn't read the picture")
                for (i in px.indices) {
                    val b = raw[4 * i].toInt() and 255; val g = raw[4 * i + 1].toInt() and 255; val r = raw[4 * i + 2].toInt() and 255; val a = raw[4 * i + 3].toInt() and 255
                    px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
                BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, w, h, px, 0, w) }
            }
        clip(img)
    }

    private fun clip(img: java.awt.Image) {
        val t = object : Transferable {
            override fun getTransferDataFlavors() = arrayOf(DataFlavor.imageFlavor)
            override fun isDataFlavorSupported(f: DataFlavor) = f == DataFlavor.imageFlavor
            override fun getTransferData(f: DataFlavor): Any = img
        }
        Toolkit.getDefaultToolkit().systemClipboard.setContents(t, null)
    }
}
