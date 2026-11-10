package com.yoruc.mangacracker.cutout

import kotlin.math.max
import kotlin.math.min

/**
 * Speech bubbles and stray bits out of a cutout (study: bubbles.py).
 * A bubble = a paper-white blob (grey >= 225, no colour) that is round-ish (fills >= 80 % of its convex hull) with
 * at least 8 letter-sized dark marks inside. Pale faces have colour or few marks and stay. A Remove tap on any light,
 * round-ish blob takes the whole blob (short lines like "YUP." that the automatic rule leaves).
 */
object Bubbles {
    /** grey and colour spread per pixel of an RGB crop (0..255, interleaved) */
    class Tones(rgb: FloatArray, n: Int) {
        val g = IntArray(n); val chroma = IntArray(n)
        init {
            for (i in 0 until n) {
                val r = rgb[3 * i]; val gg = rgb[3 * i + 1]; val b = rgb[3 * i + 2]
                g[i] = ((r * 299 + gg * 587 + b * 114) / 1000).toInt()
                chroma[i] = (max(r, max(gg, b)) - min(r, min(gg, b))).toInt()
            }
        }
    }

    /** Removes bubbles that touch the cutout and (with [strays]) pieces far smaller than the main figure; in place. */
    fun clean(t: Tones, w: Int, h: Int, a: FloatArray, strays: Boolean = true) {
        val white = BooleanArray(w * h) { t.g[it] >= 225 && t.chroma[it] < 14 }
        val lab = label4(white, w, h)
        val touched = HashSet<Int>()
        for (i in a.indices) if (a[i] > 0.5f && lab.first[i] != 0) touched += lab.first[i]
        for (l in touched) {
            val blob = Blob.of(lab, l, w) ?: continue
            if (blob.area < 0.0015f * w * h || blob.area > 0.35f * w * h) continue
            if (blob.solidity() < 0.8f) continue
            if (blob.glyphs(t.g) >= 8) blob.erase(a, w, h)
        }
        if (strays) dropStrays(a, w, h)
    }

    /** A Remove tap on a light, round-ish blob: the whole blob goes. False when (x, y) is not on one. */
    fun removeAt(t: Tones, w: Int, h: Int, a: FloatArray, x: Int, y: Int): Boolean {
        val i0 = y * w + x
        if (t.g[i0] < 200) return false
        val light = BooleanArray(w * h) { t.g[it] >= 200 }
        val lab = label4(light, w, h)
        val blob = Blob.of(lab, lab.first[i0], w) ?: return false
        if (blob.area > 0.35f * w * h || blob.solidity() < 0.7f) return false
        blob.erase(a, w, h)
        return true
    }

    /** Pieces of the cutout under 4 % of the biggest piece. */
    fun dropStrays(a: FloatArray, w: Int, h: Int) {
        val (lab, sizes) = Mask.components(a, w, h)
        if (sizes.size <= 2) return
        val big = sizes.max()
        val drop = BooleanArray(w * h) { lab[it] != 0 && sizes[lab[it]] < 0.04f * big }
        val near = Mask.distanceTo(drop, w, h)
        for (i in a.indices) if (near[i] <= 2.5f) a[i] = 0f
    }

    /** 4-connected labels of [m] with each label's bounding box (x0, y0, x1, y1) and pixel count. */
    private fun label4(m: BooleanArray, w: Int, h: Int): Pair<IntArray, List<IntArray>> {
        val lab = IntArray(w * h)
        val info = ArrayList<IntArray>().apply { add(IntArray(5)) }
        val stack = IntArray(w * h)
        for (s in 0 until w * h) {
            if (!m[s] || lab[s] != 0) continue
            val l = info.size
            val b = intArrayOf(w, h, -1, -1, 0)
            var sp = 0; stack[sp++] = s; lab[s] = l
            while (sp > 0) {
                val p = stack[--sp]; val px = p % w; val py = p / w
                b[0] = min(b[0], px); b[1] = min(b[1], py); b[2] = max(b[2], px); b[3] = max(b[3], py); b[4]++
                if (px > 0 && m[p - 1] && lab[p - 1] == 0) { lab[p - 1] = l; stack[sp++] = p - 1 }
                if (px < w - 1 && m[p + 1] && lab[p + 1] == 0) { lab[p + 1] = l; stack[sp++] = p + 1 }
                if (py > 0 && m[p - w] && lab[p - w] == 0) { lab[p - w] = l; stack[sp++] = p - w }
                if (py < h - 1 && m[p + w] && lab[p + w] == 0) { lab[p + w] = l; stack[sp++] = p + w }
            }
            info += b
        }
        return lab to info
    }

    /** One blob in its bounding box: its pixels, the blob with holes filled, and its holes. */
    private class Blob(val x0: Int, val y0: Int, val bw: Int, val bh: Int, val sub: BooleanArray, val filled: BooleanArray, val area: Int) {
        val filledArea = filled.count { it }

        /** filled area / convex hull area */
        fun solidity(): Float {
            val pts = ArrayList<LongArray>()
            for (y in 0 until bh) {
                var l = -1; var r = -1
                for (x in 0 until bw) if (filled[y * bw + x]) { if (l < 0) l = x; r = x }
                if (l >= 0) { pts += longArrayOf(l.toLong(), y.toLong()); pts += longArrayOf(r + 1L, y.toLong()); pts += longArrayOf(l.toLong(), y + 1L); pts += longArrayOf(r + 1L, y + 1L) }
            }
            val hull = hullArea(pts)
            return if (hull <= 0) 0f else filledArea / hull
        }

        /** letter-sized dark marks inside the blob's holes */
        fun glyphs(g: IntArray): Int {
            val dark = FloatArray(bw * bh)
            for (y in 0 until bh) for (x in 0 until bw) {
                val k = y * bw + x
                if (filled[k] && !sub[k] && g[(y + y0) * fullW + x + x0] < 150) dark[k] = 1f
            }
            val (lab, sizes) = Mask.components(dark, bw, bh)
            val hgt = IntArray(sizes.size); val top = IntArray(sizes.size) { Int.MAX_VALUE }; val bot = IntArray(sizes.size) { -1 }
            for (k in lab.indices) { val l = lab[k]; if (l != 0) { val y = k / bw; top[l] = min(top[l], y); bot[l] = max(bot[l], y) } }
            var n = 0
            for (l in 1 until sizes.size) { hgt[l] = bot[l] - top[l] + 1; if (sizes[l] >= 3 && hgt[l] <= 0.12f * bh + 6) n++ }
            return n
        }

        /** clears the filled blob and a 3 px ring (its outline) from the cutout */
        fun erase(a: FloatArray, w: Int, h: Int) {
            for (y in max(0, y0 - 3) until min(h, y0 + bh + 3)) for (x in max(0, x0 - 3) until min(w, x0 + bw + 3)) {
                var hit = false
                for (dy in -3..3) { for (dx in -3..3) {
                    val sx = x - x0 + dx; val sy = y - y0 + dy
                    if (sx in 0 until bw && sy in 0 until bh && filled[sy * bw + sx]) { hit = true; break }
                }; if (hit) break }
                if (hit) a[y * w + x] = 0f
            }
        }

        var fullW = 0

        companion object {
            fun of(lab: Pair<IntArray, List<IntArray>>, l: Int, w: Int): Blob? {
                if (l <= 0) return null
                val b = lab.second[l]
                val bw = b[2] - b[0] + 1; val bh = b[3] - b[1] + 1
                val sub = BooleanArray(bw * bh) { lab.first[(it / bw + b[1]) * w + it % bw + b[0]] == l }
                // holes = what the outside can't reach
                val outside = BooleanArray(bw * bh)
                val stack = IntArray(bw * bh); var sp = 0
                for (x in 0 until bw) for (y in intArrayOf(0, bh - 1)) { val k = y * bw + x; if (!sub[k] && !outside[k]) { outside[k] = true; stack[sp++] = k } }
                for (y in 0 until bh) for (x in intArrayOf(0, bw - 1)) { val k = y * bw + x; if (!sub[k] && !outside[k]) { outside[k] = true; stack[sp++] = k } }
                while (sp > 0) {
                    val k = stack[--sp]; val x = k % bw; val y = k / bw
                    if (x > 0 && !sub[k - 1] && !outside[k - 1]) { outside[k - 1] = true; stack[sp++] = k - 1 }
                    if (x < bw - 1 && !sub[k + 1] && !outside[k + 1]) { outside[k + 1] = true; stack[sp++] = k + 1 }
                    if (y > 0 && !sub[k - bw] && !outside[k - bw]) { outside[k - bw] = true; stack[sp++] = k - bw }
                    if (y < bh - 1 && !sub[k + bw] && !outside[k + bw]) { outside[k + bw] = true; stack[sp++] = k + bw }
                }
                return Blob(b[0], b[1], bw, bh, sub, BooleanArray(bw * bh) { !outside[it] }, b[4]).also { it.fullW = w }
            }

            private fun hullArea(p: List<LongArray>): Float {
                if (p.size < 3) return 0f
                val s = p.distinctBy { it[0] * 1_000_003 + it[1] }.sortedWith(compareBy({ it[0] }, { it[1] }))
                fun cross(o: LongArray, a: LongArray, b: LongArray) = (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
                val lower = ArrayList<LongArray>(); val upper = ArrayList<LongArray>()
                for (q in s) { while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), q) <= 0) lower.removeAt(lower.lastIndex); lower += q }
                for (q in s.asReversed()) { while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), q) <= 0) upper.removeAt(upper.lastIndex); upper += q }
                val hull = lower.dropLast(1) + upper.dropLast(1)
                var area = 0L
                for (i in hull.indices) { val a = hull[i]; val b = hull[(i + 1) % hull.size]; area += a[0] * b[1] - b[0] * a[1] }
                return kotlin.math.abs(area) / 2f
            }
        }
    }
}
