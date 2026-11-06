package com.yoruc.mangacracker.core

import android.content.Context
import com.yoruc.mangacracker.platform.Desktop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Saves pictures to Pictures\MangaCracker\<title>\ ; "Share" puts the picture on the clipboard. */
object ImageSaver {
    private fun ext(bytes: ByteArray): String = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif"
        bytes.size > 11 && String(bytes, 8, 4) == "WEBP" -> "webp"
        bytes.size > 11 && String(bytes, 4, 8).let { it == "ftypavif" || it == "ftypavis" } -> "avif"
        else -> "jpg"
    }

    private fun folder(name: String) = File(Desktop.pictures, Desktop.clean(name)).apply { mkdirs() }

    /** Returns the saved file. */
    @Suppress("UNUSED_PARAMETER")
    suspend fun save(ctx: Context, url: String, folder: String, name: String): File = withContext(Dispatchers.IO) {
        val bytes = Http.bytes(url)
        Desktop.freeFile(folder(folder), Desktop.clean(name), ext(bytes)).apply { writeBytes(bytes) }
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
