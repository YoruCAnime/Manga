package com.yoruc.mangacracker

import com.yoruc.mangacracker.gif.ListFrames
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.Deflater

/**
 * Picture I/O for the GIF tests (the unit-test classpath has no javax.imageio): raw RGB frame files made by ffmpeg,
 * a small PNG writer, a small GIF reader, and ffmpeg as an independent GIF decoder.
 */
object GifTestIo {
    /** <dir>/<clip>.rgb (rgb24 frames) + <clip>.txt ("w h fps"). */
    fun load(dir: File, clip: String, max: Int = 10_000): ListFrames? {
        val raw = File(dir, "$clip.rgb"); val info = File(dir, "$clip.txt")
        if (!raw.exists() || !info.exists()) return null
        val (w, h, fps) = info.readText().trim().split(" ").map { it.toDouble() }
        val fw = w.toInt(); val fh = h.toInt()
        val size = fw * fh * 3
        val frames = ArrayList<IntArray>()
        raw.inputStream().buffered(1 shl 20).use { inp ->
            val buf = ByteArray(size)
            while (frames.size < max) {
                var got = 0
                while (got < size) { val r = inp.read(buf, got, size - got); if (r < 0) break; got += r }
                if (got < size) break
                frames += IntArray(fw * fh) { i -> ((buf[3 * i].toInt() and 0xFF) shl 16) or ((buf[3 * i + 1].toInt() and 0xFF) shl 8) or (buf[3 * i + 2].toInt() and 0xFF) }
            }
        }
        return ListFrames(frames, fw, fh, fps)
    }

    fun png(px: IntArray, w: Int, h: Int, file: File) {
        val raw = ByteArray((w * 3 + 1) * h)
        var o = 0
        for (y in 0 until h) {
            raw[o++] = 0
            for (x in 0 until w) { val c = px[y * w + x]; raw[o++] = (c shr 16).toByte(); raw[o++] = (c shr 8).toByte(); raw[o++] = c.toByte() }
        }
        val def = Deflater(6); def.setInput(raw); def.finish()
        val z = ByteArrayOutputStream(); val tmp = ByteArray(1 shl 16)
        while (!def.finished()) z.write(tmp, 0, def.deflate(tmp))
        file.parentFile?.mkdirs()
        DataOutputStream(file.outputStream().buffered()).use { d ->
            d.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
            fun chunk(type: String, data: ByteArray) {
                d.writeInt(data.size); val t = type.toByteArray(Charsets.US_ASCII); d.write(t); d.write(data)
                val crc = CRC32(); crc.update(t); crc.update(data); d.writeInt(crc.value.toInt())
            }
            val ihdr = ByteArrayOutputStream().also { b -> DataOutputStream(b).apply { writeInt(w); writeInt(h); write(8); write(2); write(0); write(0); write(0) } }.toByteArray()
            chunk("IHDR", ihdr); chunk("IDAT", z.toByteArray()); chunk("IEND", ByteArray(0))
        }
    }

    class Decoded(val w: Int, val h: Int, val frames: List<Pair<Int, IntArray>>, val loops: Boolean)

    /** Our own reader: (start time in 1/100 s, full picture after the frame) per frame. Handles disposal 0/1 only. */
    fun decodeGif(b: ByteArray): Decoded {
        var p = 6
        fun u8() = b[p++].toInt() and 0xFF
        fun u16() = u8() or (u8() shl 8)
        val w = u16(); val h = u16(); val flags = u8(); p += 2
        var gct = IntArray(0)
        if (flags and 0x80 != 0) { val n = 1 shl ((flags and 7) + 1); gct = IntArray(n) { (u8() shl 16) or (u8() shl 8) or u8() } }
        val canvas = IntArray(w * h)
        val out = ArrayList<Pair<Int, IntArray>>()
        var t = 0; var delay = 0; var trans = -1; var loops = false
        while (p < b.size) {
            when (u8()) {
                0x21 -> {
                    val label = u8()
                    if (label == 0xF9) { u8(); val f = u8(); delay = u16(); val ti = u8(); trans = if (f and 1 != 0) ti else -1; u8() }
                    else {
                        if (label == 0xFF && String(b, p + 1, 11, Charsets.US_ASCII) == "NETSCAPE2.0") loops = true
                        while (true) { val n = u8(); if (n == 0) break; p += n }
                    }
                }
                0x2C -> {
                    val x0 = u16(); val y0 = u16(); val fw = u16(); val fh = u16(); val f = u8()
                    var ct = gct
                    if (f and 0x80 != 0) { val n = 1 shl ((f and 7) + 1); ct = IntArray(n) { (u8() shl 16) or (u8() shl 8) or u8() } }
                    val minBits = u8()
                    val data = ByteArrayOutputStream()
                    while (true) { val n = u8(); if (n == 0) break; data.write(b, p, n); p += n }
                    val idx = lzwDecode(data.toByteArray(), minBits, fw * fh)
                    for (i in 0 until fw * fh) { val v = idx[i]; if (v != trans) canvas[(y0 + i / fw) * w + x0 + i % fw] = ct[v] }
                    out += t to canvas.copyOf()
                    t += delay
                }
                0x3B -> break
                else -> error("bad block at $p")
            }
        }
        return Decoded(w, h, out, loops)
    }

    private fun lzwDecode(d: ByteArray, minBits: Int, n: Int): IntArray {
        val out = IntArray(n); var o = 0
        val clear = 1 shl minBits; val eoi = clear + 1
        val prefix = IntArray(4096); val suffix = IntArray(4096); val first = IntArray(4096)
        for (i in 0 until clear) { suffix[i] = i; first[i] = i }
        var size = minBits + 1; var next = clear + 2; var old = -1
        var acc = 0; var bits = 0; var p = 0
        val stack = IntArray(4097)
        fun emit(code: Int) {
            var sp = 0; var x = code
            while (x >= clear) { stack[sp++] = suffix[x]; x = prefix[x] }
            stack[sp++] = x
            while (sp > 0 && o < n) out[o++] = stack[--sp]
        }
        while (o < n) {
            while (bits < size) { if (p >= d.size) return out; acc = acc or ((d[p++].toInt() and 0xFF) shl bits); bits += 8 }
            val code = acc and ((1 shl size) - 1); acc = acc ushr size; bits -= size
            if (code == clear) { size = minBits + 1; next = clear + 2; old = -1; continue }
            if (code == eoi) break
            if (old < 0) { emit(code); old = code; continue }
            val fc = if (code < next) first[code] else first[old]
            if (code < next) emit(code) else { emit(old); if (o < n) out[o++] = fc }
            if (next < 4096) { prefix[next] = old; suffix[next] = fc; first[next] = first[old]; next++ }
            old = code
            if (next == (1 shl size) && size < 12) size++
        }
        return out
    }

    /** ffmpeg's decode of a GIF, one picture per GIF frame (null when ffmpeg is missing). */
    fun ffmpegDecode(gif: File, w: Int, h: Int): List<IntArray>? = runCatching {
        val p = ProcessBuilder("ffmpeg", "-v", "error", "-i", gif.path, "-fps_mode", "passthrough", "-f", "rawvideo", "-pix_fmt", "rgb24", "-")
            .start()
        val bytes = p.inputStream.use { it.readBytes() }; p.waitFor()
        val size = w * h * 3
        (0 until bytes.size / size).map { f -> IntArray(w * h) { i -> val o = f * size + 3 * i; ((bytes[o].toInt() and 0xFF) shl 16) or ((bytes[o + 1].toInt() and 0xFF) shl 8) or (bytes[o + 2].toInt() and 0xFF) } }
    }.getOrNull()
}
