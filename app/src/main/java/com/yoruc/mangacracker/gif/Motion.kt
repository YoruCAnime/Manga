package com.yoruc.mangacracker.gif

import kotlin.math.abs

/** A grey (luma) picture used for motion search. */
class Plane(val w: Int, val h: Int, val p: IntArray) {
    fun half(): Plane {
        val nw = maxOf(1, w / 2); val nh = maxOf(1, h / 2)
        val o = IntArray(nw * nh)
        for (y in 0 until nh) for (x in 0 until nw) {
            val i = 2 * y * w + 2 * x
            o[y * nw + x] = (p[i] + p[i + 1] + p[i + w] + p[i + w + 1] + 2) shr 2
        }
        return Plane(nw, nh, o)
    }

    companion object {
        /** Luma of a 0xRRGGBB frame, shrunk by [f] x [f] boxes. */
        fun of(frame: IntArray, w: Int, h: Int, f: Int): Plane {
            val nw = w / f; val nh = h / f
            val o = IntArray(nw * nh)
            val n = f * f
            for (y in 0 until nh) for (x in 0 until nw) {
                var s = 0
                for (j in 0 until f) for (i in 0 until f) {
                    val c = frame[(y * f + j) * w + x * f + i]
                    s += (c shr 16 and 0xFF) * 77 + (c shr 8 and 0xFF) * 150 + (c and 0xFF) * 29
                }
                o[y * nw + x] = (s / n) shr 8
            }
            return Plane(nw, nh, o)
        }
    }
}

/**
 * Motion of the picture from one frame to the next on a grid (one vector per [CELL] x [CELL] cell of the [w] x [h] flow
 * plane, in flow-plane pixels). [err] = what is left after moving each block (mean absolute luma difference per pixel).
 */
class Flow(val w: Int, val h: Int, val gw: Int, val gh: Int, val vx: FloatArray, val vy: FloatArray, val err: FloatArray) {
    val meanErr: Float = if (err.isEmpty()) 0f else err.average().toFloat()

    /** Vector at flow-plane position (u, v), bilinear between cell centres. */
    fun at(u: Float, v: Float, out: FloatArray) {
        val gx = ((u - CELL / 2f) / CELL).coerceIn(0f, (gw - 1).toFloat())
        val gy = ((v - CELL / 2f) / CELL).coerceIn(0f, (gh - 1).toFloat())
        val x0 = gx.toInt(); val y0 = gy.toInt()
        val x1 = minOf(x0 + 1, gw - 1); val y1 = minOf(y0 + 1, gh - 1)
        val fx = gx - x0; val fy = gy - y0
        val i00 = y0 * gw + x0; val i01 = y0 * gw + x1; val i10 = y1 * gw + x0; val i11 = y1 * gw + x1
        out[0] = (vx[i00] * (1 - fx) + vx[i01] * fx) * (1 - fy) + (vx[i10] * (1 - fx) + vx[i11] * fx) * fy
        out[1] = (vy[i00] * (1 - fx) + vy[i01] * fx) * (1 - fy) + (vy[i10] * (1 - fx) + vy[i11] * fx) * fy
    }

    companion object { const val CELL = 4 }
}

/**
 * Motion-compensated in-between frames ("a bit of optical flow"): hierarchical block matching on a small pyramid with
 * a smoothness bias and median-filtered vectors, both directions; each in-between pixel takes the best of the two
 * motion guesses and "not moving" (keeps subtitles and borders crisp), and is only blended where both frames agree
 * after moving; where they don't, it takes the nearer frame's moved pixel (never a cross-fade, so no ghosts). Frames
 * where too much of what moves doesn't line up, or scene cuts, return null so the caller shows the nearest real frame.
 */
object Motion {
    private const val CELL = Flow.CELL
    private const val WIN = 8          // block size
    private const val TOP_RANGE = 6    // full search at the coarsest level (pixels there)
    private const val LAMBDA = 6       // cost of a vector that differs from its prediction, per pixel of difference

    /** Box-shrink factor that brings a [w]-wide frame to a flow plane of about 240 px. */
    fun factor(w: Int) = maxOf(1, (w + 120) / 240)

    fun plane(frame: IntArray, w: Int, h: Int) = Plane.of(frame, w, h, factor(w))

    /** Motion from [a] to [b] (where each block of [a] went in [b]). */
    fun estimate(a: Plane, b: Plane): Flow {
        val pa = mutableListOf(a); val pb = mutableListOf(b)
        while (pa.size < 3 && pa.last().w >= 48 && pa.last().h >= 32) { pa += pa.last().half(); pb += pb.last().half() }
        var prev: Flow? = null
        for (lvl in pa.indices.reversed()) {
            val la = pa[lvl]; val lb = pb[lvl]
            val gw = (la.w + CELL - 1) / CELL; val gh = (la.h + CELL - 1) / CELL
            val vx = FloatArray(gw * gh); val vy = FloatArray(gw * gh); val err = FloatArray(gw * gh)
            val tmp = FloatArray(2)
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val cx = (gx * CELL + CELL / 2).coerceAtMost(la.w - 1); val cy = (gy * CELL + CELL / 2).coerceAtMost(la.h - 1)
                var px = 0; var py = 0
                val cand = ArrayList<Long>(8)
                if (prev == null) {
                    var best = Int.MAX_VALUE
                    for (dy in -TOP_RANGE..TOP_RANGE) for (dx in -TOP_RANGE..TOP_RANGE) {
                        val c = sad(la, lb, cx, cy, dx, dy, best) + LAMBDA * (abs(dx) + abs(dy))
                        if (c < best) { best = c; px = dx; py = dy }
                    }
                } else {
                    prev.at(cx / 2f, cy / 2f, tmp)
                    val predX = Math.round(tmp[0] * 2); val predY = Math.round(tmp[1] * 2)
                    cand += pack(predX, predY); cand += pack(0, 0)
                    for ((ox, oy) in NEIGH) { prev.at((cx + ox * CELL) / 2f, (cy + oy * CELL) / 2f, tmp); cand += pack(Math.round(tmp[0] * 2), Math.round(tmp[1] * 2)) }
                    if (gx > 0) cand += pack(vx[gy * gw + gx - 1].toInt(), vy[gy * gw + gx - 1].toInt())
                    if (gy > 0) cand += pack(vx[(gy - 1) * gw + gx].toInt(), vy[(gy - 1) * gw + gx].toInt())
                    var best = Int.MAX_VALUE
                    for (c in cand) {
                        val dx = unX(c); val dy = unY(c)
                        val cost = sad(la, lb, cx, cy, dx, dy, best) + LAMBDA * (abs(dx - predX) + abs(dy - predY))
                        if (cost < best) { best = cost; px = dx; py = dy }
                    }
                    // small steps downhill
                    var moved = true; var steps = 0
                    while (moved && steps++ < 6) {
                        moved = false
                        var bx = px; var by = py
                        for ((ox, oy) in STEP) {
                            val dx = px + ox; val dy = py + oy
                            val cost = sad(la, lb, cx, cy, dx, dy, best) + LAMBDA * (abs(dx - predX) + abs(dy - predY))
                            if (cost < best) { best = cost; bx = dx; by = dy; moved = true }
                        }
                        px = bx; py = by
                    }
                }
                vx[gy * gw + gx] = px.toFloat(); vy[gy * gw + gx] = py.toFloat()
            }
            median(vx, gw, gh); median(vy, gw, gh)
            // finest level: sub-pixel by a parabola through the costs either side, and the leftover error
            for (gy in 0 until gh) for (gx in 0 until gw) {
                val i = gy * gw + gx
                val cx = (gx * CELL + CELL / 2).coerceAtMost(la.w - 1); val cy = (gy * CELL + CELL / 2).coerceAtMost(la.h - 1)
                val dx = vx[i].toInt(); val dy = vy[i].toInt()
                val c0 = sad(la, lb, cx, cy, dx, dy, Int.MAX_VALUE)
                err[i] = c0 / (WIN * WIN).toFloat()
                if (lvl == 0) {
                    vx[i] = dx + subPixel(sad(la, lb, cx, cy, dx - 1, dy, Int.MAX_VALUE), c0, sad(la, lb, cx, cy, dx + 1, dy, Int.MAX_VALUE))
                    vy[i] = dy + subPixel(sad(la, lb, cx, cy, dx, dy - 1, Int.MAX_VALUE), c0, sad(la, lb, cx, cy, dx, dy + 1, Int.MAX_VALUE))
                }
            }
            prev = Flow(la.w, la.h, gw, gh, vx, vy, err)
        }
        return prev!!
    }

    private val NEIGH = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1)
    private val STEP = listOf(-1 to 0, 1 to 0, 0 to -1, 0 to 1, -1 to -1, 1 to -1, -1 to 1, 1 to 1)
    private fun pack(x: Int, y: Int) = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
    private fun unX(c: Long) = (c shr 32).toInt()
    private fun unY(c: Long) = c.toInt()

    private fun subPixel(cm: Int, c0: Int, cp: Int): Float {
        val d = cm - 2 * c0 + cp
        return if (d <= 0) 0f else ((cm - cp) / (2f * d)).coerceIn(-0.5f, 0.5f)
    }

    /** Sum of absolute differences of the block around (cx, cy) in [a] and the same block moved by (dx, dy) in [b]. */
    private fun sad(a: Plane, b: Plane, cx: Int, cy: Int, dx: Int, dy: Int, stop: Int): Int {
        val x0 = cx - WIN / 2; val y0 = cy - WIN / 2
        var s = 0
        val inside = x0 >= 0 && y0 >= 0 && x0 + WIN <= a.w && y0 + WIN <= a.h &&
            x0 + dx >= 0 && y0 + dy >= 0 && x0 + dx + WIN <= b.w && y0 + dy + WIN <= b.h
        if (inside) {
            for (j in 0 until WIN) {
                var ia = (y0 + j) * a.w + x0; var ib = (y0 + j + dy) * b.w + x0 + dx
                for (i in 0 until WIN) { s += abs(a.p[ia++] - b.p[ib++]) }
                if (s >= stop) return s
            }
        } else {
            for (j in 0 until WIN) {
                val ya = (y0 + j).coerceIn(0, a.h - 1); val yb = (y0 + j + dy).coerceIn(0, b.h - 1)
                for (i in 0 until WIN) {
                    val xa = (x0 + i).coerceIn(0, a.w - 1); val xb = (x0 + i + dx).coerceIn(0, b.w - 1)
                    s += abs(a.p[ya * a.w + xa] - b.p[yb * b.w + xb])
                }
                if (s >= stop) return s
            }
        }
        return s
    }

    private fun median(v: FloatArray, gw: Int, gh: Int) {
        val src = v.copyOf()
        val win = FloatArray(9)
        for (y in 0 until gh) for (x in 0 until gw) {
            var n = 0
            for (j in -1..1) for (i in -1..1) {
                val xx = x + i; val yy = y + j
                if (xx in 0 until gw && yy in 0 until gh) win[n++] = src[yy * gw + xx]
            }
            java.util.Arrays.sort(win, 0, n)
            v[y * gw + x] = win[n / 2]
        }
    }

    /** Mean absolute luma difference of two planes (no motion). */
    fun diff(a: Plane, b: Plane): Float {
        var s = 0L
        for (i in a.p.indices) s += abs(a.p[i] - b.p[i])
        return s.toFloat() / a.p.size
    }

    /**
     * A scene cut between two frames: moving the blocks doesn't explain the change, or the colour mix (luma histogram)
     * changed a lot.
     */
    fun isCut(a: Plane, b: Plane, fwd: Flow): Boolean {
        val d0 = diff(a, b)
        val ha = IntArray(32); val hb = IntArray(32)
        for (v in a.p) ha[v shr 3]++
        for (v in b.p) hb[v shr 3]++
        var inter = 0
        for (i in 0 until 32) inter += minOf(ha[i], hb[i])
        val hist = 1f - inter.toFloat() / a.p.size
        return (fwd.meanErr > 12f && fwd.meanErr > 0.45f * d0) || hist > 0.45f
    }

    /**
     * The frame at time [t] (0..1) between [a] and [b] (0xRRGGBB, [w] x [h]), from the flows [fwd] (a -> b) and [bwd]
     * (b -> a). Null when too much of the picture doesn't line up: show the nearest real frame instead.
     */
    fun inBetween(a: IntArray, b: IntArray, w: Int, h: Int, fwd: Flow, bwd: Flow, t: Float): IntArray? {
        val s = w.toFloat() / fwd.w        // flow plane -> frame pixels
        val n = w * h
        val e1 = FloatArray(n); val e2 = FloatArray(n)
        val v1x = FloatArray(n); val v1y = FloatArray(n); val v2x = FloatArray(n); val v2y = FloatArray(n)
        val t1 = 1f - t
        Par.rows(h) { y0, y1 -> val tmp = FloatArray(2); for (y in y0 until y1) for (x in 0 until w) {
            val i = y * w + x
            val u = (x + 0.5f) / s; val v = (y + 0.5f) / s
            fwd.at(u, v, tmp); val ax = tmp[0] * s; val ay = tmp[1] * s
            bwd.at(u, v, tmp); val bx = -tmp[0] * s; val by = -tmp[1] * s
            v1x[i] = ax; v1y[i] = ay; v2x[i] = bx; v2y[i] = by
            if (abs(ax) < 0.05f && abs(ay) < 0.05f && abs(bx) < 0.05f && abs(by) < 0.05f) {
                // still: compare the pixel with itself, no sampling
                val e = diff3(a[i], b[i]); e1[i] = e; e2[i] = e
                v1x[i] = 0f; v1y[i] = 0f; v2x[i] = 0f; v2y[i] = 0f
                continue
            }
            e1[i] = diff3(bilinear(a, w, h, x - t * ax, y - t * ay), bilinear(b, w, h, x + t1 * ax, y + t1 * ay))
            e2[i] = if (abs(ax - bx) + abs(ay - by) < 0.25f) e1[i]
                else diff3(bilinear(a, w, h, x - t * bx, y - t * by), bilinear(b, w, h, x + t1 * bx, y + t1 * by))
        } }
        val s1 = blur(e1, w, h); val s2 = blur(e2, w, h)
        // a third guess per pixel: not moving at all (subtitles, borders, a still background behind a moving body)
        val s0 = blur(FloatArray(n) { diff3(a[it], b[it]) }, w, h)
        val out = IntArray(n)
        val bads = java.util.concurrent.atomic.AtomicInteger()
        val movers = java.util.concurrent.atomic.AtomicInteger()
        Par.rows(h) { y0, y1 -> val ca = FloatArray(3); val cb = FloatArray(3); var bad = 0; var mov = 0; for (y in y0 until y1) for (x in 0 until w) {
            val i = y * w + x
            val first = s1[i] <= s2[i]
            var e = if (first) s1[i] else s2[i]
            var mx = if (first) v1x[i] else v2x[i]; var my = if (first) v1y[i] else v2y[i]
            if (s0[i] < STILL_MAX && s0[i] + STILL_BIAS < e) { e = s0[i]; mx = 0f; my = 0f }
            if (e > E_HI) bad++
            if (abs(mx) + abs(my) > 0.75f) mov++
            val wb = ((E_HI - e) / (E_HI - E_LO)).coerceIn(0f, 1f)
            if (mx == 0f && my == 0f) {
                // still pixel: no resampling at all
                val p = a[i]; val q = b[i]
                out[i] = if (wb <= 0f || p == q) (if (t < 0.5f) p else q) else mix(p, q, t, wb)
                continue
            }
            cubic(a, w, h, x - t * mx, y - t * my, ca)
            cubic(b, w, h, x + t1 * mx, y + t1 * my, cb)
            var r: Float; var g: Float; var bl: Float
            if (t < 0.5f) { r = ca[0]; g = ca[1]; bl = ca[2] } else { r = cb[0]; g = cb[1]; bl = cb[2] }
            if (wb > 0f) {
                r += wb * (t1 * ca[0] + t * cb[0] - r); g += wb * (t1 * ca[1] + t * cb[1] - g); bl += wb * (t1 * ca[2] + t * cb[2] - bl)
            }
            out[i] = (cl(r) shl 16) or (cl(g) shl 8) or cl(bl)
        }; bads.addAndGet(bad); movers.addAndGet(mov) }
        // judged against the part that moves: a missed scene change or a big turn mangles most of what moves even
        // when it is a small part of the picture
        lastBad = bads.get().toFloat() / maxOf(movers.get(), n / 10)
        return if (lastBad > BAD_SHARE) null else out
    }

    /** Share of the moving part that didn't line up in the last [inBetween] (for tests). */
    @Volatile var lastBad = 0f; private set

    // mismatch (sum of |dR|+|dG|+|dB| after moving both frames) below E_LO: blend; above E_HI: the nearer frame alone
    private const val E_LO = 30f
    private const val E_HI = 75f
    private const val BAD_SHARE = 0.14f
    // "not moving" wins when its mismatch is this small and clearly below the moved guesses'
    private const val STILL_MAX = 8f
    private const val STILL_BIAS = 20f

    private fun mix(p: Int, q: Int, t: Float, wb: Float): Int {
        val near = if (t < 0.5f) p else q
        val t1 = 1 - t
        val r = (near shr 16 and 0xFF) + wb * (t1 * (p shr 16 and 0xFF) + t * (q shr 16 and 0xFF) - (near shr 16 and 0xFF))
        val g = (near shr 8 and 0xFF) + wb * (t1 * (p shr 8 and 0xFF) + t * (q shr 8 and 0xFF) - (near shr 8 and 0xFF))
        val b = (near and 0xFF) + wb * (t1 * (p and 0xFF) + t * (q and 0xFF) - (near and 0xFF))
        return (cl(r) shl 16) or (cl(g) shl 8) or cl(b)
    }

    private fun diff3(p: Int, q: Int): Float =
        (abs((p shr 16 and 0xFF) - (q shr 16 and 0xFF)) + abs((p shr 8 and 0xFF) - (q shr 8 and 0xFF)) + abs((p and 0xFF) - (q and 0xFF))).toFloat()

    /** Bilinear sample as 0xRRGGBB (8-bit), good enough for the mismatch test. */
    private fun bilinear(f: IntArray, w: Int, h: Int, x: Float, y: Float): Int {
        val xf = x.coerceIn(0f, (w - 1).toFloat()); val yf = y.coerceIn(0f, (h - 1).toFloat())
        val x0 = xf.toInt(); val y0 = yf.toInt()
        val x1 = minOf(x0 + 1, w - 1); val y1 = minOf(y0 + 1, h - 1)
        val fx = xf - x0; val fy = yf - y0
        val c00 = f[y0 * w + x0]; val c01 = f[y0 * w + x1]; val c10 = f[y1 * w + x0]; val c11 = f[y1 * w + x1]
        val w00 = (1 - fx) * (1 - fy); val w01 = fx * (1 - fy); val w10 = (1 - fx) * fy; val w11 = fx * fy
        val r = (c00 shr 16 and 0xFF) * w00 + (c01 shr 16 and 0xFF) * w01 + (c10 shr 16 and 0xFF) * w10 + (c11 shr 16 and 0xFF) * w11
        val g = (c00 shr 8 and 0xFF) * w00 + (c01 shr 8 and 0xFF) * w01 + (c10 shr 8 and 0xFF) * w10 + (c11 shr 8 and 0xFF) * w11
        val b = (c00 and 0xFF) * w00 + (c01 and 0xFF) * w01 + (c10 and 0xFF) * w10 + (c11 and 0xFF) * w11
        return ((r + 0.5f).toInt() shl 16) or ((g + 0.5f).toInt() shl 8) or (b + 0.5f).toInt()
    }

    /** Catmull-Rom (sharp) sample into [out] = r, g, b. */
    private fun cubic(f: IntArray, w: Int, h: Int, x: Float, y: Float, out: FloatArray) {
        val xf = x.coerceIn(0f, (w - 1).toFloat()); val yf = y.coerceIn(0f, (h - 1).toFloat())
        val xi = xf.toInt(); val yi = yf.toInt()
        val fx = xf - xi; val fy = yf - yi
        if (fx == 0f && fy == 0f) {
            val c = f[yi * w + xi]; out[0] = (c shr 16 and 0xFF).toFloat(); out[1] = (c shr 8 and 0xFF).toFloat(); out[2] = (c and 0xFF).toFloat(); return
        }
        val x2 = fx * fx; val x3 = x2 * fx; val y2 = fy * fy; val y3 = y2 * fy
        val wx0 = (-x3 + 2 * x2 - fx) / 2f; val wx1 = (3 * x3 - 5 * x2 + 2) / 2f; val wx2 = (-3 * x3 + 4 * x2 + fx) / 2f; val wx3 = (x3 - x2) / 2f
        val wy0 = (-y3 + 2 * y2 - fy) / 2f; val wy1 = (3 * y3 - 5 * y2 + 2) / 2f; val wy2 = (-3 * y3 + 4 * y2 + fy) / 2f; val wy3 = (y3 - y2) / 2f
        val xa = (xi - 1).coerceIn(0, w - 1); val xb = xi; val xc = minOf(xi + 1, w - 1); val xd = minOf(xi + 2, w - 1)
        var r = 0f; var g = 0f; var b = 0f
        for (j in 0 until 4) {
            val yy = (yi - 1 + j).coerceIn(0, h - 1) * w
            val wy = when (j) { 0 -> wy0; 1 -> wy1; 2 -> wy2; else -> wy3 }
            val p0 = f[yy + xa]; val p1 = f[yy + xb]; val p2 = f[yy + xc]; val p3 = f[yy + xd]
            r += wy * (wx0 * (p0 shr 16 and 0xFF) + wx1 * (p1 shr 16 and 0xFF) + wx2 * (p2 shr 16 and 0xFF) + wx3 * (p3 shr 16 and 0xFF))
            g += wy * (wx0 * (p0 shr 8 and 0xFF) + wx1 * (p1 shr 8 and 0xFF) + wx2 * (p2 shr 8 and 0xFF) + wx3 * (p3 shr 8 and 0xFF))
            b += wy * (wx0 * (p0 and 0xFF) + wx1 * (p1 and 0xFF) + wx2 * (p2 and 0xFF) + wx3 * (p3 and 0xFF))
        }
        out[0] = r; out[1] = g; out[2] = b
    }

    private fun blur(e: FloatArray, w: Int, h: Int): FloatArray {
        val t = FloatArray(e.size); val o = FloatArray(e.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (i in -1..1) { val xx = x + i; if (xx in 0 until w) { s += e[y * w + xx]; c++ } }
            t[y * w + x] = s / c
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0f; var c = 0
            for (j in -1..1) { val yy = y + j; if (yy in 0 until h) { s += t[yy * w + x]; c++ } }
            o[y * w + x] = s / c
        }
        return o
    }

    private fun cl(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)
}
