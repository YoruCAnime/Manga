package com.yoruc.mangacracker.gif

import java.io.OutputStream

/**
 * GIF89a writer: one global palette, infinite loop, frame rectangles with a transparent index (pixels that did not
 * change), delays in 1/100 s. Disposal is always "leave in place", so a frame only draws what changed.
 */
class GifWriter(private val out: OutputStream, private val width: Int, private val height: Int) {
    private var bits = 8

    /** [palette] = 0xRRGGBB colours (at most 256; padded to a power of two). */
    fun start(palette: IntArray) {
        out.write("GIF89a".toByteArray(Charsets.US_ASCII))
        short(width); short(height)
        bits = tableBits(palette.size)
        out.write(0x80 or (7 shl 4) or (bits - 1)) // global table, 8-bit colour resolution, table size
        out.write(0); out.write(0)                 // background index, aspect
        for (i in 0 until (1 shl bits)) {
            val c = if (i < palette.size) palette[i] else 0
            out.write(c shr 16 and 0xFF); out.write(c shr 8 and 0xFF); out.write(c and 0xFF)
        }
        // NETSCAPE2.0 application block: loop forever
        out.write(byteArrayOf(0x21, 0xFF.toByte(), 0x0B)); out.write("NETSCAPE2.0".toByteArray(Charsets.US_ASCII))
        out.write(byteArrayOf(3, 1, 0, 0, 0))
    }

    /** One frame: [px] = w x h palette indices placed at (x, y); [transparent] = index that keeps the old pixel, or -1. */
    fun frame(x: Int, y: Int, w: Int, h: Int, px: ByteArray, delayCs: Int, transparent: Int) {
        out.write(byteArrayOf(0x21, 0xF9.toByte(), 4))
        out.write((1 shl 2) or (if (transparent >= 0) 1 else 0)) // disposal 1 = leave in place
        short(delayCs.coerceIn(0, 65535))
        out.write(if (transparent >= 0) transparent else 0)
        out.write(0)
        out.write(0x2C); short(x); short(y); short(w); short(h); out.write(0)
        lzw(px, w * h, maxOf(2, bits))
    }

    fun finish() { out.write(0x3B); out.flush() }

    private fun short(v: Int) { out.write(v and 0xFF); out.write(v shr 8 and 0xFF) }

    // ---- LZW (variable code size, clear code when the table is full), packed into 255-byte sub-blocks ----
    private val dict = IntArray(4096 * 256)
    private val used = IntArray(4096)
    private val block = ByteArray(255)
    private var blockLen = 0
    private var acc = 0
    private var accBits = 0

    private fun lzw(px: ByteArray, n: Int, minBits: Int) {
        out.write(minBits)
        val clear = 1 shl minBits
        val eoi = clear + 1
        var size = minBits + 1
        var next = clear + 2
        var nUsed = 0
        acc = 0; accBits = 0; blockLen = 0
        emit(clear, size)
        if (n > 0) {
            var prefix = px[0].toInt() and 0xFF
            for (i in 1 until n) {
                val c = px[i].toInt() and 0xFF
                val key = (prefix shl 8) or c
                val code = dict[key]
                if (code != 0) { prefix = code; continue }
                emit(prefix, size)
                if (next > (1 shl size) - 1 && size < 12) size++
                if (next < 4096) {
                    dict[key] = next++; used[nUsed++] = key
                } else {
                    emit(clear, size)
                    for (k in 0 until nUsed) dict[used[k]] = 0
                    nUsed = 0; size = minBits + 1; next = clear + 2
                }
                prefix = c
            }
            emit(prefix, size)
            if (next > (1 shl size) - 1 && size < 12) size++
        }
        emit(eoi, size)
        for (k in 0 until nUsed) dict[used[k]] = 0
        if (accBits > 0) byte(acc and 0xFF)
        flushBlock()
        out.write(0)
    }

    private fun emit(code: Int, size: Int) {
        acc = acc or (code shl accBits); accBits += size
        while (accBits >= 8) { byte(acc and 0xFF); acc = acc ushr 8; accBits -= 8 }
    }

    private fun byte(b: Int) { block[blockLen++] = b.toByte(); if (blockLen == 255) flushBlock() }

    private fun flushBlock() { if (blockLen > 0) { out.write(blockLen); out.write(block, 0, blockLen); blockLen = 0 } }

    companion object {
        fun tableBits(n: Int): Int { var b = 1; while ((1 shl b) < n) b++; return b }
    }
}
