package com.yoruc.mangacracker

import com.yoruc.mangacracker.gif.GifMaker
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.gif.ListFrames
import com.yoruc.mangacracker.gif.PickedFrames
import com.yoruc.mangacracker.gif.fillBetween
import com.yoruc.mangacracker.gif.framesByNumber
import com.yoruc.mangacracker.gif.keptFrames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The frame strip's maths: numbers, "select between", deleted frames matched by time, gaps in the GIF engine. */
class FrameCutsTest {
    @Test fun numbersAndBetween() {
        assertEquals(setOf(2, 3, 4), framesByNumber(10, emptySet(), 3, 5))
        assertEquals(setOf(2, 4), framesByNumber(10, setOf(3), 5, 3))
        assertEquals(emptySet<Int>(), framesByNumber(10, emptySet(), 20, 30))
        assertEquals(setOf(9), framesByNumber(10, emptySet(), 10, 40))
        assertEquals(setOf(3, 4, 6, 7), fillBetween(setOf(3, 7), setOf(5)))
        assertEquals(setOf(3), fillBetween(setOf(3), emptySet()))
    }

    @Test fun byTime() {
        // same reading: exactly the deleted ones go
        assertArrayEquals(intArrayOf(0, 1, 4), keptFrames(5, 30.0, 1000, 1000, 30.0, setOf(2, 3)))
        // a 60 fps reading of the same range: two frames for every deleted strip frame
        assertArrayEquals(intArrayOf(0, 1, 2, 3, 8, 9), keptFrames(10, 60.0, 1000, 1000, 30.0, setOf(2, 3)))
        // the range moved half a second later: strip frames 15.. are this reading's 0..
        assertArrayEquals(intArrayOf(1, 2, 3), keptFrames(4, 30.0, 1500, 1000, 30.0, setOf(15)))
    }

    @Test fun gapIsACut() {
        val w = 64; val h = 36
        val frames = (0 until 30).map { k -> IntArray(w * h) { i -> if (i % w in k..k + 6) 0xFFFFFF else 0x203040 } }
        val all = ListFrames(frames, w, h, 30.0)
        val picked = PickedFrames(all, (0 until 10).toList().plus(20 until 30).toIntArray())
        assertEquals(20, picked.count)
        assertTrue("a gap after frame 9", picked.gapBefore(10) && !picked.gapBefore(9))
        // slow motion makes in-betweens, but never across the gap
        val r = GifMaker(picked, GifOptions(0.5f, false, GifSize.SMALL)).make()
        assertTrue(r.bytes.isNotEmpty())
        println("[cuts] gif of 20 picked frames at 0.5x: ${r.frames} frames, ${r.inBetweens} in-betweens, ${r.bytes.size} bytes")
    }
}
