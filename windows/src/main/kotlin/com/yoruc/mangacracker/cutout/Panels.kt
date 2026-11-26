package com.yoruc.mangacracker.cutout

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The panel under a tap: cuts the page along gutters (rows / columns of one flat tone, white or black) and keeps the
 * piece that holds the tap, again and again. A manhwa strip splits into its panels; a page whose art runs into the
 * margins stays whole. Same rules as the study script (panels.py).
 */
object Panels {
    data class Box(val x0: Int, val y0: Int, val x1: Int, val y1: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
    }

    /** gray: luminance 0..255, row-major w x h. */
    fun find(gray: IntArray, w: Int, h: Int, x: Int, y: Int, maxAspect: Float = 2f): Box {
        var x0 = 0; var y0 = 0; var x1 = w; var y1 = h
        val gap = max(3, (min(w, h) * 0.004f).roundToInt())
        for (round in 0 until 6) {
            val prev = Box(x0, y0, x1, y1)
            val rows = BooleanArray(y1 - y0) { flat(gray, w, x0, x1, y0 + it, horizontal = true) }
            val ry = cut(rows, y - y0, gap) ?: break
            y1 = y0 + ry.second; y0 += ry.first
            val cols = BooleanArray(x1 - x0) { flat(gray, w, y0, y1, x0 + it, horizontal = false) }
            val rx = cut(cols, x - x0, gap) ?: break
            x1 = x0 + rx.second; x0 += rx.first
            if (Box(x0, y0, x1, y1) == prev) break
        }
        // a tall strip with no gutters: a window around the tap
        if (y1 - y0 > maxAspect * (x1 - x0)) {
            val ch = (maxAspect * (x1 - x0)).toInt()
            y0 = (y - ch / 2).coerceIn(y0, y1 - ch); y1 = y0 + ch
        }
        if ((x1 - x0).toLong() * (y1 - y0) < 0.02 * w * min(h, 2 * w) || x1 - x0 < 64 || y1 - y0 < 64)
            return Box(0, max(0, y - w), w, min(h, y + w))
        return Box(max(0, x0 - gap), max(0, y0 - gap), min(w, x1 + gap), min(h, y1 + gap))
    }

    /** one row (or column) is a gutter: 98.5 % of it within 12 of its median, and that tone near white or black */
    private fun flat(g: IntArray, w: Int, a: Int, b: Int, at: Int, horizontal: Boolean): Boolean {
        val n = b - a
        if (n <= 0) return false
        val hist = IntArray(256)
        for (i in a until b) hist[if (horizontal) g[at * w + i] else g[i * w + at]]++
        var acc = 0; var med = 0
        while (med < 255) { acc += hist[med]; if (acc * 2 >= n) break; med++ }
        if (med in 31..224) return false
        var near = 0
        for (v in max(0, med - 12)..min(255, med + 12)) near += hist[v]
        return near >= 0.985 * n
    }

    /** the stretch between gutter runs (>= minLen) that holds pos; null when pos sits on a gutter */
    private fun cut(flat: BooleanArray, pos: Int, minLen: Int): Pair<Int, Int>? {
        var a = 0; var b = flat.size
        var s = -1
        for (i in 0..flat.size) {
            val v = i < flat.size && flat[i]
            if (v && s < 0) s = i
            else if (!v && s >= 0) {
                if (i - s >= minLen) {
                    if (i <= pos) a = max(a, i)
                    else if (s > pos) b = min(b, s)
                    else return null
                }
                s = -1
            }
        }
        return a to b
    }
}
