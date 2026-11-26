package com.yoruc.mangacracker.gif

/**
 * A GIF palette: a variance split ("median cut" that cuts where the two halves are tightest) of a 5-bit colour
 * histogram, then a few rounds of k-means. [map] finds the nearest entry through a lazily filled 7-bit lookup table.
 * Index [transparent] (the last one) is kept free for "unchanged" pixels. Plain RGB distance: measured against OKLab on
 * real anime / 3D frames it kept 1-5 dB more detail (OKLab with a lookup table lost the darks).
 */
class Palette private constructor(val rgb: IntArray, val transparent: Int) {
    private val pl = FloatArray(rgb.size)   // the entries as float r, g, b
    private val pa = FloatArray(rgb.size)
    private val pb = FloatArray(rgb.size)
    private val lut = ShortArray(1 shl 21) { -1 }
    private val tmp = FloatArray(3)

    init {
        for (i in rgb.indices) {
            if (i == transparent) { pl[i] = 1e6f; continue } // never picked as a colour
            rgbOf(rgb[i] shr 16 and 0xFF, rgb[i] shr 8 and 0xFF, rgb[i] and 0xFF, tmp)
            pl[i] = tmp[0]; pa[i] = tmp[1]; pb[i] = tmp[2]
        }
    }

    /** Nearest entry for an RGB colour (inputs 0..255). */
    fun map(r: Int, g: Int, b: Int): Int {
        val k = (r shr 1 shl 14) or (g shr 1 shl 7) or (b shr 1)
        val hit = lut[k]
        if (hit >= 0) return hit.toInt()
        val best = nearest((r or 1) - 0.5f, (g or 1) - 0.5f, (b or 1) - 0.5f, pl, pa, pb, rgb.size)
        lut[k] = best.toShort()
        return best
    }

    companion object {
        fun rgbOf(r: Int, g: Int, b: Int, out: FloatArray) { out[0] = r.toFloat(); out[1] = g.toFloat(); out[2] = b.toFloat() }

        private fun nearest(l: Float, a: Float, b: Float, pl: FloatArray, pa: FloatArray, pb: FloatArray, n: Int): Int {
            var best = 0; var bd = Float.MAX_VALUE
            for (i in 0 until n) {
                val dl = l - pl[i]; val da = a - pa[i]; val db = b - pb[i]
                val d = dl * dl + da * da + db * db
                if (d < bd) { bd = d; best = i }
            }
            return best
        }

        /**
         * Builds a palette of [colours] entries (2..256, the last one transparent) from sample frames (0xRRGGBB pixels;
         * [step] = use every n-th pixel).
         */
        fun build(samples: List<IntArray>, colours: Int, step: Int = 1): Palette {
            val n = colours.coerceIn(2, 256) - 1
            val cnt = IntArray(1 shl 15)
            val sr = LongArray(1 shl 15); val sg = LongArray(1 shl 15); val sb = LongArray(1 shl 15)
            for (f in samples) {
                var i = 0
                while (i < f.size) {
                    val c = f[i]
                    val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                    val k = (r shr 3 shl 10) or (g shr 3 shl 5) or (b shr 3)
                    cnt[k]++; sr[k] += r.toLong(); sg[k] += g.toLong(); sb[k] += b.toLong()
                    i += step
                }
            }
            // the occupied bins: mean colour (exact), weight
            var m = 0
            for (k in cnt.indices) if (cnt[k] > 0) m++
            val w = FloatArray(m); val bl = FloatArray(m); val ba = FloatArray(m); val bb = FloatArray(m)
            val br = IntArray(m); val bg = IntArray(m); val bbl = IntArray(m)
            val tmp = FloatArray(3)
            var j = 0
            for (k in cnt.indices) if (cnt[k] > 0) {
                val c = cnt[k]
                br[j] = (sr[k] / c).toInt(); bg[j] = (sg[k] / c).toInt(); bbl[j] = (sb[k] / c).toInt()
                rgbOf(br[j], bg[j], bbl[j], tmp)
                bl[j] = tmp[0]; ba[j] = tmp[1]; bb[j] = tmp[2]; w[j] = c.toFloat()
                j++
            }
            val k = minOf(n, m)
            val (cl, ca, cb) = split(bl, ba, bb, w, k)
            kmeans(bl, ba, bb, w, cl, ca, cb, k, 5)
            // the weighted mean of each cluster's bins (exact colours)
            val assign = IntArray(m) { nearest(bl[it], ba[it], bb[it], cl, ca, cb, k) }
            val ar = DoubleArray(k); val ag = DoubleArray(k); val ab = DoubleArray(k); val aw = DoubleArray(k)
            for (i in 0 until m) { val c = assign[i]; ar[c] += br[i] * w[i].toDouble(); ag[c] += bg[i] * w[i].toDouble(); ab[c] += bbl[i] * w[i].toDouble(); aw[c] += w[i].toDouble() }
            val out = IntArray(k + 1)
            for (c in 0 until k) {
                if (aw[c] <= 0) continue
                val r = (ar[c] / aw[c] + 0.5).toInt(); val g = (ag[c] / aw[c] + 0.5).toInt(); val b = (ab[c] / aw[c] + 0.5).toInt()
                out[c] = (r shl 16) or (g shl 8) or b
            }
            return Palette(out, k)
        }

        /** Splits the bins into [k] boxes, always the box with the largest error, at the cut that leaves the least error. */
        private fun split(l: FloatArray, a: FloatArray, b: FloatArray, w: FloatArray, k: Int): Triple<FloatArray, FloatArray, FloatArray> {
            val m = w.size
            val idx = IntArray(m) { it }
            // boxes as [start, end) ranges of idx
            val starts = IntArray(k); val ends = IntArray(k); val errs = DoubleArray(k)
            starts[0] = 0; ends[0] = m; errs[0] = sse(idx, 0, m, l, a, b, w)
            var boxes = 1
            val axes = arrayOf(l, a, b)
            while (boxes < k) {
                var pick = -1; var pe = 0.0
                for (i in 0 until boxes) if (ends[i] - starts[i] > 1 && errs[i] > pe) { pe = errs[i]; pick = i }
                if (pick < 0) break
                val s = starts[pick]; val e = ends[pick]
                // the axis with the largest weighted variance
                var axis = 0; var bestVar = -1.0
                for (ax in 0 until 3) {
                    val v = axes[ax]; var sw = 0.0; var sx = 0.0; var sxx = 0.0
                    for (t in s until e) { val q = idx[t]; sw += w[q]; sx += w[q] * v[q]; sxx += w[q].toDouble() * v[q] * v[q] }
                    val variance = sxx - sx * sx / sw
                    if (variance > bestVar) { bestVar = variance; axis = ax }
                }
                val v = axes[axis]
                val sub = idx.copyOfRange(s, e).sortedBy { v[it] }
                for (t in sub.indices) idx[s + t] = sub[t]
                // best cut along that axis: the least total squared error of the two halves (prefix sums)
                val len = e - s
                val pw = DoubleArray(len + 1); val px = DoubleArray(len + 1); val pxx = DoubleArray(len + 1)
                for (t in 0 until len) { val q = idx[s + t]; pw[t + 1] = pw[t] + w[q]; px[t + 1] = px[t] + w[q] * v[q]; pxx[t + 1] = pxx[t] + w[q].toDouble() * v[q] * v[q] }
                var cut = len / 2; var cutErr = Double.MAX_VALUE
                for (t in 1 until len) {
                    val e1 = pxx[t] - px[t] * px[t] / pw[t]
                    val w2 = pw[len] - pw[t]; val x2 = px[len] - px[t]
                    val e2 = (pxx[len] - pxx[t]) - x2 * x2 / w2
                    if (e1 + e2 < cutErr) { cutErr = e1 + e2; cut = t }
                }
                starts[boxes] = s + cut; ends[boxes] = e; ends[pick] = s + cut
                errs[pick] = sse(idx, s, s + cut, l, a, b, w); errs[boxes] = sse(idx, s + cut, e, l, a, b, w)
                boxes++
            }
            val cl = FloatArray(k); val ca = FloatArray(k); val cb = FloatArray(k)
            for (i in 0 until boxes) {
                var sw = 0.0; var x = 0.0; var y = 0.0; var z = 0.0
                for (t in starts[i] until ends[i]) { val q = idx[t]; sw += w[q]; x += w[q] * l[q]; y += w[q] * a[q]; z += w[q] * b[q] }
                cl[i] = (x / sw).toFloat(); ca[i] = (y / sw).toFloat(); cb[i] = (z / sw).toFloat()
            }
            for (i in boxes until k) { cl[i] = 1e6f } // fewer bins than colours: unused
            return Triple(cl, ca, cb)
        }

        private fun sse(idx: IntArray, s: Int, e: Int, l: FloatArray, a: FloatArray, b: FloatArray, w: FloatArray): Double {
            var sw = 0.0; var sl = 0.0; var sa = 0.0; var sb = 0.0; var sq = 0.0
            for (t in s until e) {
                val q = idx[t]; val ww = w[q].toDouble()
                sw += ww; sl += ww * l[q]; sa += ww * a[q]; sb += ww * b[q]
                sq += ww * (l[q] * l[q] + a[q] * a[q] + b[q] * b[q])
            }
            return if (sw <= 0) 0.0 else sq - (sl * sl + sa * sa + sb * sb) / sw
        }

        private fun kmeans(l: FloatArray, a: FloatArray, b: FloatArray, w: FloatArray, cl: FloatArray, ca: FloatArray, cb: FloatArray, k: Int, rounds: Int) {
            val m = w.size
            repeat(rounds) {
                val sl = DoubleArray(k); val sa = DoubleArray(k); val sb = DoubleArray(k); val sw = DoubleArray(k)
                for (i in 0 until m) {
                    val c = nearest(l[i], a[i], b[i], cl, ca, cb, k)
                    sl[c] += w[i] * l[i].toDouble(); sa[c] += w[i] * a[i].toDouble(); sb[c] += w[i] * b[i].toDouble(); sw[c] += w[i].toDouble()
                }
                for (c in 0 until k) if (sw[c] > 0) { cl[c] = (sl[c] / sw[c]).toFloat(); ca[c] = (sa[c] / sw[c]).toFloat(); cb[c] = (sb[c] / sw[c]).toFloat() }
            }
        }
    }
}
