package com.yoruc.mangacracker.gif

import java.io.ByteArrayOutputStream
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Size choice: the file is made to fit [maxBytes]; [maxWidth] / [fps] are where the fitting starts. */
enum class GifSize(val label: String, val maxBytes: Long, val maxWidth: Int, val fps: Int) {
    TINY("Tiny", 2_000_000, 360, 20),
    SMALL("Small", 5_000_000, 480, 25),
    BIG("Big", 10_000_000, 640, 30),
}

data class GifOptions(val speed: Float = 1f, val pingPong: Boolean = false, val size: GifSize = GifSize.SMALL)

/** The real frames of the chosen range, all [width] x [height] 0xRRGGBB, [fps] per second. */
interface FrameSource {
    val count: Int
    val width: Int
    val height: Int
    val fps: Double
    fun frame(i: Int): IntArray
}

class ListFrames(private val frames: List<IntArray>, override val width: Int, override val height: Int, override val fps: Double) : FrameSource {
    override val count get() = frames.size
    override fun frame(i: Int) = frames[i]
}

/** One output frame: real frame [a], or the in-between of real frames [a] and [b] at [t] (0..1). */
data class Pick(val a: Int, val b: Int = a, val t: Float = 0f)

/**
 * Settings of one encode: [long] = longest side in px, [threshold] = colour change (0..255 per channel) that still
 * counts as "unchanged", [dither] = largest colour step (per channel) smoothed by the fixed-pattern dither (0 = none).
 */
data class GifParams(
    val long: Int,
    val fpsCap: Int,
    val colours: Int = 256,
    val threshold: Int = 6,
    val dither: Float = 24f,
    val interpolate: Boolean = true,
)

class GifResult(
    val bytes: ByteArray, val width: Int, val height: Int, val frames: Int, val durationMs: Long, val fps: Double,
    val params: GifParams, val tries: Int, val inBetweens: Int, val fallbacks: Int,
)

class GifCancelled : RuntimeException("cancelled")

/**
 * Turns real frames into a small, sharp GIF: speed (fast = picks frames, slow = motion-compensated in-betweens),
 * ping-pong, one palette for the whole GIF with a position-fixed dither only in soft gradients (static areas never shimmer),
 * and frames cropped to what changed with unchanged pixels transparent. [make] lowers colours, width and fps until the
 * file fits the chosen size.
 */
class GifMaker(private val src: FrameSource, private val opts: GifOptions) {
    var progress: (String, Float) -> Unit = { _, _ -> }
    var cancelled: () -> Boolean = { false }

    private val n = src.count
    // ---- analysis shared by every try: held frames, motion between key frames ----
    private val hold = BooleanArray(maxOf(0, n - 1))
    private var analysed = false
    private val planes = LinkedHashMap<Int, Plane>(16, 0.75f, true)
    private val flows = HashMap<Long, Triple<Flow, Flow, Boolean>>()

    private fun plane(i: Int): Plane = planes.getOrPut(i) { Motion.plane(src.frame(i), src.width, src.height) }.also {
        while (planes.size > 8) planes.remove(planes.keys.first())
    }

    /** Marks frames that repeat the one before (anime drawn "on twos / threes", pauses). */
    fun analyse() {
        if (analysed) return
        for (i in 0 until n - 1) {
            check()
            hold[i] = same(plane(i), plane(i + 1))
            if (i % 8 == 0) progress("Looking at the frames", i.toFloat() / n)
        }
        analysed = true
    }

    private fun same(a: Plane, b: Plane): Boolean {
        if (Motion.diff(a, b) > 1.2f) return false
        // no 8 x 8 block may change visibly (a small moving mouth is still motion)
        val bw = a.w / 8; val bh = a.h / 8
        for (by in 0 until bh) for (bx in 0 until bw) {
            var s = 0
            for (y in 0 until 8) for (x in 0 until 8) { val i = (by * 8 + y) * a.w + bx * 8 + x; s += kotlin.math.abs(a.p[i] - b.p[i]) }
            if (s > 8 * 64) return false
        }
        return true
    }

    /** Key frames for slow motion: the first frame of each drawing, and the last one of a long hold. */
    fun keys(): IntArray {
        analyse()
        val maxHold = maxOf(2, (src.fps / 8).roundToInt())
        val out = ArrayList<Int>()
        var runStart = 0
        for (i in 0 until n) {
            val dupPrev = i > 0 && hold[i - 1]
            if (!dupPrev) { out += i; runStart = i; continue }
            val endOfRun = i == n - 1 || !hold[i]
            if (endOfRun && i - runStart >= maxHold) out += i
            else if (i == n - 1) out += i
        }
        return out.distinct().toIntArray()
    }

    /** Motion a -> b, b -> a and whether a cut lies between them (cached for every try). */
    private fun flow(a: Int, b: Int): Triple<Flow, Flow, Boolean> = flows.getOrPut(a.toLong() * 1_000_000 + b) {
        val pa = plane(a); val pb = plane(b)
        val f = Motion.estimate(pa, pb)
        val cut = Motion.isCut(pa, pb, f)
        Triple(f, if (cut) f else Motion.estimate(pb, pa), cut)
    }

    /** Output frames and their rate. */
    fun timeline(fpsCap: Int, interpolate: Boolean): Pair<List<Pick>, Double> {
        val speed = opts.speed.toDouble()
        val rate = src.fps * speed                      // source frames per output second
        val slow = speed < 0.999 && interpolate
        val fps = if (slow) fpsCap.toDouble() else {
            // fast / normal: take every m-th frame when that keeps the rate near the cap (even steps, no judder)
            val m = ceil(rate / fpsCap - 1e-6).toInt().coerceAtLeast(1)
            val even = rate / m
            if (even >= 0.8 * fpsCap || m == 1) minOf(even, fpsCap.toDouble()) else fpsCap.toDouble()
        }
        val duration = n / rate
        val count = maxOf(1, (duration * fps).roundToInt())
        val keys = if (slow) keys() else IntArray(0)
        val picks = ArrayList<Pick>(count)
        var j = 0
        for (k in 0 until count) {
            val sp = k * rate / fps
            if (!slow) { picks += Pick(floor(sp + 1e-6).toInt().coerceIn(0, n - 1)); continue }
            while (j + 1 < keys.size && keys[j + 1] <= sp + 1e-6) j++
            if (j + 1 >= keys.size) { picks += Pick(keys.last()); continue }
            val a = keys[j]; val b = keys[j + 1]
            val t = ((sp - a) / (b - a)).toFloat()
            picks += when {
                t < 0.08f -> Pick(a)
                t > 0.92f -> Pick(b)
                else -> Pick(a, b, t)
            }
        }
        return (if (opts.pingPong) pingPong(picks) else picks) to fps
    }

    /**
     * Make the GIF, trying smaller settings until it fits the chosen size: first a little more "unchanged" tolerance,
     * then a smaller picture (down to 3/4), then fewer frames per second (down to 15), then smaller again, then 10 fps.
     */
    fun make(): GifResult {
        val size = opts.size
        val full = maxOf(src.width, src.height)
        var long = minOf(size.maxWidth, full)
        var fpsCap = size.fps
        var colours = 256
        var threshold = 6
        val minLong = minOf(long, (size.maxWidth * 0.75).toInt())
        var last: GifResult? = null
        for (tryNo in 1..9) {
            val r = encode(GifParams(long, fpsCap, colours, threshold), tryNo)
            last = r
            if (r.bytes.size <= size.maxBytes) return r
            val ratio = size.maxBytes.toDouble() / r.bytes.size
            when {
                ratio > 0.8 && threshold < 10 -> threshold = 10
                ratio > 0.9 && colours > 160 -> colours = 160
                long > minLong -> long = maxOf(minLong, (long * sqrt(ratio) * 0.97).toInt())
                fpsCap > 15 -> fpsCap = maxOf(15, (fpsCap * ratio * 0.97).toInt())
                long > 200 -> long = maxOf(200, (long * sqrt(ratio) * 0.95).toInt())
                fpsCap > 10 -> fpsCap = 10
                colours > 64 -> colours = 64
                else -> return r
            }
        }
        return last!!
    }

    /** GIF size for a long side of [long] px (never larger than the source). */
    fun dims(long: Int): Pair<Int, Int> {
        val scale = minOf(1.0, long.toDouble() / maxOf(src.width, src.height))
        if (scale >= 1.0) return src.width to src.height
        val dw = maxOf(2, (src.width * scale).roundToInt()); val dh = maxOf(2, (src.height * scale).roundToInt())
        return dw to dh
    }

    /** Frames of the last try, kept so a try that only changes the "unchanged" tolerance doesn't redo them. */
    private class Made(val key: List<Any>, val pal: Palette, val frames: List<ByteArray>, val inBetweens: Int, val fallbacks: Int)
    private var lastMade: Made? = null

    /** One encode with fixed settings. */
    fun encode(p: GifParams, tryNo: Int = 1): GifResult {
        val (dw, dh) = dims(p.long)
        val (picks, fps) = timeline(p.fpsCap, p.interpolate)
        val delays = delays(picks.size, fps)
        val label = if (tryNo == 1) "Making the GIF" else "Making it smaller (try $tryNo)"
        val key = listOf(dw, dh, p.fpsCap, p.colours, p.dither, p.interpolate)
        lastMade?.takeIf { it.key == key }?.let { m ->
            progress(label, 0.9f)
            val out = FrameWriter(dw, dh, m.pal, p.threshold)
            for ((k, f) in m.frames.withIndex()) out.add(f, delays[k])
            return GifResult(out.finish(), dw, dh, out.written, delays.sum() * 10L, fps, p, tryNo, m.inBetweens, m.fallbacks)
        }
        lastMade = null

        val scaled = LinkedHashMap<Int, IntArray>(16, 0.75f, true)
        fun frame(i: Int): IntArray = scaled.getOrPut(i) { if (dw == src.width && dh == src.height) src.frame(i) else Resample.resize(src.frame(i), src.width, src.height, dw, dh) }
            .also { while (scaled.size > 6) scaled.remove(scaled.keys.first()) }
        // palette from up to 16 real frames spread over the range
        val sampleIdx = (0 until minOf(16, n)).map { (it * (n - 1).toDouble() / maxOf(1, minOf(16, n) - 1)).roundToInt() }.distinct()
        val step = maxOf(1, dw * dh * sampleIdx.size / 400_000)
        val pal = Palette.build(sampleIdx.map { frame(it) }, p.colours, step)

        // forward frames; ping-pong's way back reuses them. All of them are kept for the next try when they fit in ~100 MB.
        val forward = if (opts.pingPong) picks.size / 2 + 1 else picks.size
        val keepAll = forward.toLong() * dw * dh <= 100_000_000L
        val made = HashMap<Pick, ByteArray>()
        val all = if (keepAll) ArrayList<ByteArray>(picks.size) else null
        val out = FrameWriter(dw, dh, pal, p.threshold)
        var inBetweens = 0; var fallbacks = 0
        for ((k, pk) in picks.withIndex()) {
            check()
            val have = made[pk]
            if (have != null) { out.add(have, delays[k]); all?.add(have); continue }
            val rgb: IntArray = if (pk.t == 0f || pk.a == pk.b) frame(pk.a) else {
                val (f, b, cut) = flow(pk.a, pk.b)
                val mid = if (cut) null else Motion.inBetween(frame(pk.a), frame(pk.b), dw, dh, f, b, pk.t)
                if (mid == null) { fallbacks++; frame(if (pk.t < 0.5f) pk.a else pk.b) } else { inBetweens++; mid }
            }
            val idx = quantize(rgb, dw, dh, pal, p.dither)
            if (opts.pingPong && k < forward) made[pk] = idx
            out.add(idx, delays[k]); all?.add(idx)
            progress(label, k.toFloat() / picks.size)
        }
        if (all != null) lastMade = Made(key, pal, all, inBetweens, fallbacks)
        return GifResult(out.finish(), dw, dh, out.written, delays.sum() * 10L, fps, p, tryNo, inBetweens, fallbacks)
    }

    private fun check() { if (cancelled()) throw GifCancelled() }

    companion object {
        /** Forward then back, without showing either end twice in a row. */
        fun pingPong(p: List<Pick>): List<Pick> = if (p.size < 3) p else p + p.subList(1, p.size - 1).reversed()

        /** Delays in 1/100 s that keep the total time right (e.g. 30 fps = 3, 4, 3, 3, 4, 3 ...). */
        fun delays(count: Int, fps: Double): IntArray {
            val step = 100.0 / fps
            return IntArray(count) { k -> maxOf(2, ((k + 1) * step).roundToInt() - (k * step).roundToInt()) }
        }

        // interleaved gradient noise: a fixed per-pixel threshold in 0..1, so a still pixel always gets the same colour
        private val NOISE = FloatArray(64 * 64) { i ->
            val x = i % 64; val y = i / 64
            val v = 52.9829189 * ((0.06711056 * x + 0.00583715 * y) % 1.0)
            (v % 1.0).toFloat()
        }

        /**
         * Palette indices for a frame. With [dither] > 0 a pixel that lies between two close palette colours (a soft
         * gradient: blush, glow, sky) takes the second one on a fixed pattern in proportion to how far it lies towards
         * it; a pixel that matches its colour (flat areas) or sits on an edge (colours further apart than [dither])
         * is never touched. So gradients don't band, flat areas don't get noisy, still areas never shimmer.
         */
        fun quantize(rgb: IntArray, w: Int, h: Int, pal: Palette, dither: Float): ByteArray {
            val out = ByteArray(w * h)
            val maxD = dither * dither * 3
            // bands of rows in parallel (the lookup table fills the same value from any thread)
            Par.rows(h) { y0, y1 -> for (y in y0 until y1) {
                val row = (y and 63) * 64
                for (x in 0 until w) {
                    val c = rgb[y * w + x]
                    val r = c shr 16 and 0xFF; val g = c shr 8 and 0xFF; val b = c and 0xFF
                    val c1 = pal.map(r, g, b)
                    var pick = c1
                    if (dither > 0f) {
                        val p1 = pal.rgb[c1]
                        val er = r - (p1 shr 16 and 0xFF); val eg = g - (p1 shr 8 and 0xFF); val eb = b - (p1 and 0xFF)
                        if (er != 0 || eg != 0 || eb != 0) {
                            val c2 = pal.map((r + er).coerceIn(0, 255), (g + eg).coerceIn(0, 255), (b + eb).coerceIn(0, 255))
                            if (c2 != c1) {
                                val p2 = pal.rgb[c2]
                                val dr = (p2 shr 16 and 0xFF) - (p1 shr 16 and 0xFF); val dg = (p2 shr 8 and 0xFF) - (p1 shr 8 and 0xFF); val db = (p2 and 0xFF) - (p1 and 0xFF)
                                val dd = dr * dr + dg * dg + db * db
                                if (dd <= maxD) {
                                    val t = (er * dr + eg * dg + eb * db).toFloat() / dd
                                    if (t > NOISE[row + (x and 63)]) pick = c2
                                }
                            }
                        }
                    }
                    out[y * w + x] = pick.toByte()
                }
            } }
            return out
        }
    }
}

/**
 * Writes frames as they come: each one cropped to the pixels that changed (a pixel whose new colour is within
 * [threshold] of what is already shown counts as unchanged and stays transparent); a frame that changes nothing just
 * lengthens the one before.
 */
class FrameWriter(private val w: Int, private val h: Int, private val pal: Palette, threshold: Int) {
    private val bos = ByteArrayOutputStream()
    private val gw = GifWriter(bos, w, h).apply { start(pal.rgb) }
    private val tr = pal.transparent
    private val nc = pal.rgb.size
    private val close = BooleanArray(nc * nc).also {
        for (i in 0 until nc) for (j in 0 until nc) {
            val a = pal.rgb[i]; val b = pal.rgb[j]
            it[i * nc + j] = kotlin.math.abs((a shr 16 and 0xFF) - (b shr 16 and 0xFF)) <= threshold &&
                kotlin.math.abs((a shr 8 and 0xFF) - (b shr 8 and 0xFF)) <= threshold && kotlin.math.abs((a and 0xFF) - (b and 0xFF)) <= threshold
        }
    }
    private val canvas = IntArray(w * h) { -1 }
    private val changed = BooleanArray(w * h)
    private var pend: IntArray? = null       // x, y, w, h of the frame waiting for its delay
    private var pendPx: ByteArray? = null
    private var pendDelay = 0
    var written = 0; private set

    fun add(f: ByteArray, delay: Int) {
        var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                val i = row + x
                val c = canvas[i]; val v = f[i].toInt() and 0xFF
                val ch = c < 0 || (c != v && !close[c * nc + v])
                changed[i] = ch
                if (ch) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; y1 = y }
            }
        }
        if (x1 < 0) { pendDelay += delay; return }
        val rw = x1 - x0 + 1; val rh = y1 - y0 + 1
        val px = ByteArray(rw * rh)
        for (y in 0 until rh) for (x in 0 until rw) {
            val i = (y0 + y) * w + x0 + x
            if (changed[i]) { px[y * rw + x] = f[i]; canvas[i] = f[i].toInt() and 0xFF } else px[y * rw + x] = tr.toByte()
        }
        flush()
        pend = intArrayOf(x0, y0, rw, rh); pendPx = px; pendDelay = delay
    }

    private fun flush() { pend?.let { gw.frame(it[0], it[1], it[2], it[3], pendPx!!, pendDelay, tr); written++ } }

    fun finish(): ByteArray { flush(); pend = null; gw.finish(); return bos.toByteArray() }
}
