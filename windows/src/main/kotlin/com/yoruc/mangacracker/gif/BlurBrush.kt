package com.yoruc.mangacracker.gif

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One brush stroke of the GIF editor's blur: its points as shares (0..1) of the WHOLE video picture, the brush's radius
 * as a share of the picture's long side, and whether it paints or erases.
 */
class BlurStroke(val xs: FloatArray, val ys: FloatArray, val radius: Float, val erase: Boolean) {
    fun plus(x: Float, y: Float) = BlurStroke(xs + x, ys + y, radius, erase)
}

/** The blur brush: strokes -> a mask for a picture of any size / crop, and that mask's areas blurred. */
object BlurBrush {
    /** How strong the blur is: its radius as a share of the picture's long side. */
    const val LIGHT = 0.012f
    const val STRONG = 0.032f

    /**
     * The strokes as a mask (0..1 a pixel) for a [w] x [h] picture showing [part] of the video (x, y, w, h as shares;
     * null = the whole picture). Null when nothing is painted.
     */
    fun mask(strokes: List<BlurStroke>, w: Int, h: Int, part: FloatArray? = null): FloatArray? {
        if (strokes.none { !it.erase } || w <= 0 || h <= 0) return null
        val px = part?.get(0) ?: 0f; val py = part?.get(1) ?: 0f; val pw = part?.get(2) ?: 1f; val ph = part?.get(3) ?: 1f
        // the whole picture's size in this picture's pixels
        val fw = w / pw; val fh = h / ph
        val m = FloatArray(w * h)
        for (s in strokes) {
            val r = s.radius * max(fw, fh)
            val n = s.xs.size
            if (n == 0) continue
            for (k in 0 until max(1, n - 1)) {
                val a = k; val b = min(k + 1, n - 1)
                capsule(m, w, h, (s.xs[a] - px) * fw, (s.ys[a] - py) * fh, (s.xs[b] - px) * fw, (s.ys[b] - py) * fh, r, s.erase)
            }
        }
        // soft edges: the blur fades in over a few pixels instead of a hard line
        val feather = max(1, ((w + h) / 360f).toInt())
        boxBlur(m, w, h, feather)
        return if (m.any { it > 0.004f }) m else null
    }

    private fun capsule(m: FloatArray, w: Int, h: Int, ax: Float, ay: Float, bx: Float, by: Float, r: Float, erase: Boolean) {
        val x0 = max(0, (min(ax, bx) - r - 1).toInt()); val x1 = min(w - 1, (max(ax, bx) + r + 1).toInt())
        val y0 = max(0, (min(ay, by) - r - 1).toInt()); val y1 = min(h - 1, (max(ay, by) + r + 1).toInt())
        if (x0 > x1 || y0 > y1) return
        val dx = bx - ax; val dy = by - ay
        val len2 = dx * dx + dy * dy
        for (y in y0..y1) for (x in x0..x1) {
            val cx = x + 0.5f; val cy = y + 0.5f
            val t = if (len2 > 0f) (((cx - ax) * dx + (cy - ay) * dy) / len2).coerceIn(0f, 1f) else 0f
            val ex = cx - (ax + t * dx); val ey = cy - (ay + t * dy)
            val cov = (r + 0.5f - sqrt(ex * ex + ey * ey)).coerceIn(0f, 1f)
            if (cov <= 0f) continue
            val i = y * w + x
            m[i] = if (erase) min(m[i], 1f - cov) else max(m[i], cov)
        }
    }

    /**
     * [px] (w x h, 0xRRGGBB) with the [mask]ed areas blurred by [strength] ([LIGHT] / [STRONG]). Only the painted part
     * (and a margin) is worked on, so a small brush mark costs little.
     */
    fun apply(px: IntArray, w: Int, h: Int, mask: FloatArray?, strength: Float): IntArray {
        if (mask == null) return px
        var l = w; var t = h; var r = -1; var b = -1
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x] > 0.004f) { if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > b) b = y }
        if (r < 0) return px
        val rad = max(1, (strength * max(w, h)).toInt())
        // the blur reaches 3 radii: work on the painted box plus that
        val sl = max(0, l - 3 * rad); val st = max(0, t - 3 * rad); val sr = min(w - 1, r + 3 * rad); val sb = min(h - 1, b + 3 * rad)
        val sw = sr - sl + 1; val sh = sb - st + 1
        val ch = Array(3) { FloatArray(sw * sh) }
        for (y in 0 until sh) for (x in 0 until sw) {
            val c = px[(st + y) * w + sl + x]
            val i = y * sw + x
            ch[0][i] = ((c shr 16) and 255).toFloat(); ch[1][i] = ((c shr 8) and 255).toFloat(); ch[2][i] = (c and 255).toFloat()
        }
        // three box blurs = close to a Gaussian
        for (c in ch) repeat(3) { boxBlur(c, sw, sh, rad) }
        val out = px.copyOf()
        for (y in t..b) for (x in l..r) {
            val a = mask[y * w + x]
            if (a <= 0.004f) continue
            val o = px[y * w + x]
            val i = (y - st) * sw + (x - sl)
            fun mix(orig: Int, blur: Float) = (orig + (blur - orig) * a).toInt().coerceIn(0, 255)
            out[y * w + x] = (mix((o shr 16) and 255, ch[0][i]) shl 16) or (mix((o shr 8) and 255, ch[1][i]) shl 8) or mix(o and 255, ch[2][i])
        }
        return out
    }

    /** One box blur of radius [r] over [a] (w x h), across then down; the edges repeat their last pixel. */
    private fun boxBlur(a: FloatArray, w: Int, h: Int, r: Int) {
        if (r < 1) return
        val n = 2 * r + 1
        val line = FloatArray(max(w, h))
        for (y in 0 until h) {
            val o = y * w
            var sum = 0f
            for (k in -r..r) sum += a[o + k.coerceIn(0, w - 1)]
            for (x in 0 until w) {
                line[x] = sum / n
                sum += a[o + (x + r + 1).coerceAtMost(w - 1)] - a[o + (x - r).coerceAtLeast(0)]
            }
            System.arraycopy(line, 0, a, o, w)
        }
        for (x in 0 until w) {
            var sum = 0f
            for (k in -r..r) sum += a[k.coerceIn(0, h - 1) * w + x]
            for (y in 0 until h) {
                line[y] = sum / n
                sum += a[(y + r + 1).coerceAtMost(h - 1) * w + x] - a[(y - r).coerceAtLeast(0) * w + x]
            }
            for (y in 0 until h) a[y * w + x] = line[y]
        }
    }
}

/** [src]'s frames with the painted areas blurred ([part] = the crop the frames show, as x, y, w, h shares). */
class BlurredFrames(private val src: FrameSource, strokes: List<BlurStroke>, part: FloatArray?, private val strength: Float) : FrameSource {
    private val mask = BlurBrush.mask(strokes, src.width, src.height, part)
    private val recent = LinkedHashMap<Int, IntArray>(8, 0.75f, true)
    override val count get() = src.count
    override val width get() = src.width
    override val height get() = src.height
    override val fps get() = src.fps
    override fun gapBefore(i: Int) = src.gapBefore(i)
    @Synchronized override fun frame(i: Int): IntArray = recent.getOrPut(i) { BlurBrush.apply(src.frame(i), width, height, mask, strength) }
        .also { while (recent.size > 4) recent.remove(recent.keys.first()) }
}
