package com.yoruc.mangacracker.gif

import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile

/**
 * Frames kept in a cache file (3 bytes a pixel) instead of memory: ten seconds at 640 x 360 is ~170 MB as pixels, too
 * much for a phone's heap. The last few frames read stay in memory.
 */
class FileFrames(private val file: File, override val width: Int, override val height: Int, override val fps: Double) : FrameSource, Closeable {
    private val raf = RandomAccessFile(file, "rw").apply { setLength(0) }
    private val frameBytes = width * height * 3
    private val buf = ByteArray(frameBytes)
    private val recent = LinkedHashMap<Int, IntArray>(16, 0.75f, true)
    override var count = 0; private set

    fun add(px: IntArray) {
        var o = 0
        for (c in px) { buf[o++] = (c shr 16).toByte(); buf[o++] = (c shr 8).toByte(); buf[o++] = c.toByte() }
        raf.seek(count.toLong() * frameBytes); raf.write(buf)
        count++
    }

    @Synchronized override fun frame(i: Int): IntArray = recent.getOrPut(i) {
        raf.seek(i.toLong() * frameBytes); raf.readFully(buf)
        IntArray(width * height) { k -> ((buf[3 * k].toInt() and 0xFF) shl 16) or ((buf[3 * k + 1].toInt() and 0xFF) shl 8) or (buf[3 * k + 2].toInt() and 0xFF) }
    }.also { while (recent.size > 8) recent.remove(recent.keys.first()) }

    override fun close() { runCatching { raf.close() }; file.delete() }
}
