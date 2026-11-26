package com.yoruc.mangacracker.cutout

import kotlin.math.min
import kotlin.math.sqrt

/** Small float-image tools for the cutout (row-major w x h arrays), no Android needed. */
object Mask {
    /** Bilinear resize of a w x h float map to nw x nh (pixel centres aligned, like cv2.resize). */
    fun resize(src: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
        val out = FloatArray(nw * nh)
        val sx = w.toFloat() / nw; val sy = h.toFloat() / nh
        for (y in 0 until nh) {
            val fy = ((y + 0.5f) * sy - 0.5f).coerceIn(0f, (h - 1).toFloat())
            val y0 = fy.toInt(); val y1 = min(y0 + 1, h - 1); val ty = fy - y0
            for (x in 0 until nw) {
                val fx = ((x + 0.5f) * sx - 0.5f).coerceIn(0f, (w - 1).toFloat())
                val x0 = fx.toInt(); val x1 = min(x0 + 1, w - 1); val tx = fx - x0
                val a = src[y0 * w + x0] * (1 - tx) + src[y0 * w + x1] * tx
                val b = src[y1 * w + x0] * (1 - tx) + src[y1 * w + x1] * tx
                out[y * nw + x] = a * (1 - ty) + b * ty
            }
        }
        return out
    }

    /** 8-connected parts of (m[i] > thr): label per pixel (0 = none) and the pixel count per label. */
    fun components(m: FloatArray, w: Int, h: Int, thr: Float = 0.5f): Pair<IntArray, IntArray> {
        val lab = IntArray(w * h)
        val sizes = ArrayList<Int>().apply { add(0) }
        val stack = IntArray(w * h)
        var next = 1
        for (i in 0 until w * h) {
            if (m[i] <= thr || lab[i] != 0) continue
            var sp = 0; stack[sp++] = i; lab[i] = next
            var n = 0
            while (sp > 0) {
                val p = stack[--sp]; n++
                val px = p % w; val py = p / w
                for (dy in -1..1) for (dx in -1..1) {
                    val qx = px + dx; val qy = py + dy
                    if (qx < 0 || qy < 0 || qx >= w || qy >= h) continue
                    val q = qy * w + qx
                    if (lab[q] == 0 && m[q] > thr) { lab[q] = next; stack[sp++] = q }
                }
            }
            sizes.add(n); next++
        }
        return lab to sizes.toIntArray()
    }

    /** Distance (px) from every pixel to the nearest `inside` pixel; two-pass chamfer (3-4), good to ~4 %. */
    fun distanceTo(inside: BooleanArray, w: Int, h: Int): FloatArray {
        val big = 1e9f
        val d = FloatArray(w * h) { if (inside[it]) 0f else big }
        val a = 1f; val b = 1.4142f
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            var v = d[i]
            if (x > 0) v = min(v, d[i - 1] + a)
            if (y > 0) {
                v = min(v, d[i - w] + a)
                if (x > 0) v = min(v, d[i - w - 1] + b)
                if (x < w - 1) v = min(v, d[i - w + 1] + b)
            }
            d[i] = v
        }
        for (y in h - 1 downTo 0) for (x in w - 1 downTo 0) {
            val i = y * w + x
            var v = d[i]
            if (x < w - 1) v = min(v, d[i + 1] + a)
            if (y < h - 1) {
                v = min(v, d[i + w] + a)
                if (x < w - 1) v = min(v, d[i + w + 1] + b)
                if (x > 0) v = min(v, d[i + w - 1] + b)
            }
            d[i] = v
        }
        return d
    }

    /** The pixel deepest inside a mask. */
    fun innerPoint(inside: BooleanArray, w: Int, h: Int): Pair<Int, Int> {
        val d = distanceTo(BooleanArray(w * h) { !inside[it] }, w, h)
        var best = 0
        for (i in d.indices) if (d[i] > d[best]) best = i
        return (best % w) to (best / w)
    }

    fun iou(a: BooleanArray, b: BooleanArray): Float {
        var i = 0; var u = 0
        for (k in a.indices) { if (a[k] && b[k]) i++; if (a[k] || b[k]) u++ }
        return if (u == 0) 0f else i.toFloat() / u
    }

    fun diag(w: Int, h: Int) = sqrt((w.toFloat() * w + h.toFloat() * h))

    /**
     * A brush stroke through [pts] (x, y pairs in w x h pixels) with radius [r]: inside, alpha becomes [value]
     * (0 = erase, 1 = bring back); a 1.5 px soft rim blends into what was there.
     */
    fun paint(a: FloatArray, w: Int, h: Int, pts: FloatArray, r: Float, value: Float) {
        val n = pts.size / 2
        if (n == 0) return
        val feather = 1.5f
        val reach = r + feather
        // closest distance from each pixel near the stroke to any of its segments
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until n) { minX = min(minX, pts[2 * k]); maxX = kotlin.math.max(maxX, pts[2 * k]); minY = min(minY, pts[2 * k + 1]); maxY = kotlin.math.max(maxY, pts[2 * k + 1]) }
        val x0 = (minX - reach).toInt().coerceIn(0, w - 1); val x1 = (maxX + reach).toInt().coerceIn(0, w - 1)
        val y0 = (minY - reach).toInt().coerceIn(0, h - 1); val y1 = (maxY + reach).toInt().coerceIn(0, h - 1)
        for (y in y0..y1) for (x in x0..x1) {
            var best = Float.MAX_VALUE
            for (k in 0 until max1(n - 1)) {
                val ax = pts[2 * k]; val ay = pts[2 * k + 1]
                val bx = if (n > 1) pts[2 * k + 2] else ax; val by = if (n > 1) pts[2 * k + 3] else ay
                val dx = bx - ax; val dy = by - ay
                val len = dx * dx + dy * dy
                val t = if (len == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len).coerceIn(0f, 1f)
                val ex = x - (ax + t * dx); val ey = y - (ay + t * dy)
                best = min(best, ex * ex + ey * ey)
            }
            val d = sqrt(best)
            if (d >= reach) continue
            val i = y * w + x
            val k = if (d <= r) 1f else 1f - (d - r) / feather
            a[i] = a[i] * (1 - k) + value * k
        }
    }

    private fun max1(v: Int) = if (v < 1) 1 else v
}
