package com.yoruc.mangacracker.platform

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** ARGB int pixels <-> Skia pictures (Windows' stand-in for Android's Bitmap in the cutout and GIF code). */
object Pixels {
    /** ARGB ints (0xAARRGGBB, not premultiplied) as a picture Compose can draw. */
    fun image(px: IntArray, w: Int, h: Int): ImageBitmap {
        val buf = ByteBuffer.allocate(px.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        buf.asIntBuffer().put(px)   // little-endian 0xAARRGGBB = B, G, R, A bytes = BGRA_8888
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
        bmp.installPixels(buf.array())
        bmp.setImmutable()
        return bmp.asComposeImageBitmap()
    }

    /** Decodes a picture file's bytes (JPEG, PNG, WebP, GIF...) to ARGB ints, rows [y0, y1) only (all rows when null). */
    fun decode(bytes: ByteArray, y0: Int? = null, y1: Int? = null): Triple<IntArray, Int, Int> {
        Image.makeFromEncoded(if (Avif.isAvif(bytes)) Avif.toPng(bytes) else bytes).use { img ->
            val w = img.width
            val top = (y0 ?: 0).coerceIn(0, img.height)
            val bottom = (y1 ?: img.height).coerceIn(top, img.height)
            val h = bottom - top
            val bmp = Bitmap()
            bmp.allocPixels(ImageInfo(w, h, ColorType.BGRA_8888, ColorAlphaType.UNPREMUL))
            if (!img.readPixels(bmp, 0, top)) throw IllegalStateException("This picture can't be read")
            val raw = bmp.readPixels() ?: throw IllegalStateException("This picture can't be read")
            bmp.close()
            val px = IntArray(w * h)
            ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(px)
            return Triple(px, w, h)
        }
    }

    /** Width and height without decoding the pixels. */
    fun size(bytes: ByteArray): Pair<Int, Int> =
        Image.makeFromEncoded(if (Avif.isAvif(bytes)) Avif.toPng(bytes) else bytes).use { it.width to it.height }
}
