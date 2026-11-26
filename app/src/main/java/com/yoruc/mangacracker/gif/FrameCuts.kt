package com.yoruc.mangacracker.gif

import kotlin.math.floor

/**
 * Frames numbered [from]..[to] (every frame keeps its own number, #1 = the strip's first frame, so deleting doesn't
 * renumber the rest), minus the ones in [gone]. Either order; a range running past an end stops there, one wholly
 * outside picks nothing.
 */
fun framesByNumber(count: Int, gone: Set<Int>, from: Int, to: Int): Set<Int> {
    if (count <= 0) return emptySet()
    if (maxOf(from, to) < 1 || minOf(from, to) > count) return emptySet()
    val lo = minOf(from, to).coerceIn(1, count); val hi = maxOf(from, to).coerceIn(1, count)
    return (lo - 1..hi - 1).filter { it !in gone }.toSet()
}

/** "Select between": every frame (not deleted) from the first picked one to the last. */
fun fillBetween(sel: Set<Int>, gone: Set<Int>): Set<Int> =
    if (sel.size < 2) sel else (sel.min()..sel.max()).filter { it !in gone }.toSet()

/**
 * Which frames of a reading to keep: frame j (at [fps], starting at [fromMs]) is left out when it falls in a frame of
 * the strip (at [stripFps], starting at [stripMs]) listed in [cut]. The two readings can differ (another range, 60 fps
 * for slow motion), so this goes by time.
 */
fun keptFrames(count: Int, fps: Double, fromMs: Long, stripMs: Long, stripFps: Double, cut: Set<Int>): IntArray =
    (0 until count).filter { j -> floor(((fromMs - stripMs) / 1000.0 + j / fps) * stripFps + 1e-6).toInt() !in cut }.toIntArray()
