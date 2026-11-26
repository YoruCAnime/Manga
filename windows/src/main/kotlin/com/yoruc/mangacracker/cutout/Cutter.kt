package com.yoruc.mangacracker.cutout

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Cuts the character under a tap out of a page (rules tuned on a study of doujin, colour and manhwa pages; CLAUDE.md).
 *
 * 1. Only the panel under the tap ([Panels]).
 * 2. anime-seg gives every figure in the panel with clean outlines; the part under the tap (or the nearest) is "the
 *    figure".
 * 3. MobileSAM, asked about the tap (or the figure's middle when the tap missed), tells which figure: when two figures
 *    touch, anime-seg sees one blob and SAM's mask splits them. Outlines always come from anime-seg.
 * 4. Taps to add / remove parts go to SAM as more points; an added part anime-seg never saw (a prop) comes from SAM.
 */
class Cutter(private val nets: Nets) {
    class Point(val x: Float, val y: Float, val keep: Boolean)

    /** One page, one panel: everything that does not change while the user adds / removes parts. */
    inner class Job internal constructor(
        val px: IntArray, val pw: Int, val ph: Int,
        val box: Panels.Box,
        private val rgb: FloatArray, val w: Int, val h: Int,
        private val alpha: FloatArray,
        private val labels: IntArray, private val sizes: IntArray,
        private val figure: Int,
        private val start: Pair<Float, Float>,
        /** the long-press was beside the figure, not on it */
        private val missed: Boolean,
    ) {
        private val embedding by lazy { nets.embed(rgb, w, h) }
        private val diag = Mask.diag(w, h)

        private val tones by lazy { Bubbles.Tones(rgb, w * h) }

        /**
         * The cutout as alpha 0..1 over the panel crop (w x h), for the start tap plus [extra] taps (crop px), without
         * speech bubbles. A Remove tap on a bubble takes the whole bubble; other taps go to SAM.
         */
        fun alpha(extra: List<Point> = emptyList()): FloatArray {
            val scratch = FloatArray(w * h)
            val onBubble = extra.filter { !it.keep && Bubbles.removeAt(tones, w, h, scratch, it.x.toInt().coerceIn(0, w - 1), it.y.toInt().coerceIn(0, h - 1)) }
            val out = figure(extra - onBubble.toSet())
            Bubbles.clean(tones, w, h, out, strays = extra.none { it.keep })
            onBubble.forEach { Bubbles.removeAt(tones, w, h, out, it.x.toInt().coerceIn(0, w - 1), it.y.toInt().coerceIn(0, h - 1)) }
            return out
        }

        private fun figure(extra: List<Point>): FloatArray {
            val pts = listOf(Triple(start.first, start.second, 1)) + extra.map { Triple(it.x, it.y, if (it.keep) 1 else 0) }
            val (scores, maps) = nets.masks(embedding, pts, w, h)
            val masks = maps.map { m -> BooleanArray(m.size) { m[it] > 0f } }
            val n = w * h
            if (figure == 0 && extra.none { it.keep }) {
                // nothing figure-like here (an object, a silhouette): SAM alone, soft edges from its logits
                val k = scores.indices.maxBy { scores[it] }
                return FloatArray(n) { ((maps[k][it] + 2f) / 4f).coerceIn(0f, 1f) }
            }
            // the figure(s): the tapped part plus any part an added tap lands on
            val parts = HashSet<Int>()
            if (figure != 0) parts += figure
            extra.filter { it.keep }.forEach { p -> labelAt(p.x, p.y).takeIf { it != 0 }?.let { parts += it } }
            val c = BooleanArray(n) { labels[it] in parts }
            val k = if (extra.isEmpty()) chooseSingle(scores, masks, c) else 0
            val s = k?.let { masks[it] }
            val agree = if (s != null) Mask.iou(s, c) else 0f
            val out = FloatArray(n)
            if (s == null || (extra.isEmpty() && (agree >= 0.85f || missed))) {
                for (i in 0 until n) if (c[i]) out[i] = alpha[i]
                return out
            }
            val r = max(3f, 0.015f * diag)
            val near = Mask.distanceTo(s, w, h)
            for (i in 0 until n) if (c[i] && near[i] <= r) out[i] = alpha[i]
            // added parts anime-seg doesn't see as a figure (held props, hair it missed): SAM's own pixels there
            if (extra.any { it.keep }) {
                val (sl, _) = Mask.components(FloatArray(n) { if (s[it] && alpha[it] < 0.5f) 1f else 0f }, w, h)
                val add = extra.filter { it.keep }.mapNotNull { p -> sl.getOrNull(idx(p.x, p.y))?.takeIf { it != 0 } }.toSet()
                if (add.isNotEmpty()) for (i in 0 until n) if (sl[i] in add) out[i] = max(out[i], ((maps[k ?: 0][i] + 2f) / 4f).coerceIn(0f, 1f))
            }
            return out
        }

        /**
         * SAM's "whole object" mask (the last one) when at least 70 % of it is on the figure; else, of its confident
         * masks, the biggest that stays on the figure (one person of two hugging, not a sleeve).
         */
        private fun chooseSingle(scores: FloatArray, masks: List<BooleanArray>, c: BooleanArray): Int? {
            fun onFigure(k: Int): Boolean {
                var on = 0; var all = 0
                for (i in c.indices) if (masks[k][i]) { all++; if (c[i]) on++ }
                return all > 0 && on >= 0.7f * all
            }
            if (onFigure(masks.lastIndex)) return masks.lastIndex
            val best = scores.max()
            return masks.indices.filter { k -> scores[k] >= best - 0.05f && onFigure(k) }.maxByOrNull { k -> masks[k].count { it } }
        }

        private fun idx(x: Float, y: Float) = y.toInt().coerceIn(0, h - 1) * w + x.toInt().coerceIn(0, w - 1)
        private fun labelAt(x: Float, y: Float) = labels[idx(x, y)].takeIf { it != 0 && sizes[it] >= minPart }  ?: 0
        private val minPart = (0.002f * w * h).toInt()

        /** The crop's pixels with [a] as alpha, trimmed to the cutout plus a small margin; null when it is empty. */
        fun render(a: FloatArray): Triple<IntArray, Int, Int>? {
            var x0 = w; var y0 = h; var x1 = -1; var y1 = -1
            for (y in 0 until h) for (x in 0 until w) if (a[y * w + x] > 0.03f) {
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
            }
            if (x1 < 0) return null
            val m = 6
            x0 = max(0, x0 - m); y0 = max(0, y0 - m); x1 = min(w - 1, x1 + m); y1 = min(h - 1, y1 + m)
            val ow = x1 - x0 + 1; val oh = y1 - y0 + 1
            val out = IntArray(ow * oh)
            for (y in 0 until oh) for (x in 0 until ow) {
                val i = (y + y0) * w + x + x0
                val al = (a[i].coerceIn(0f, 1f) * 255f).roundToInt()
                val p = px[(y + y0 + box.y0) * pw + x + x0 + box.x0]
                out[y * ow + x] = (al shl 24) or (p and 0xFFFFFF)
            }
            return Triple(out, ow, oh)
        }
    }

    /**
     * Starts a cutout. [px] = page ARGB pixels (pw x ph), (x, y) = the tap in page pixels. Runs anime-seg; SAM's
     * embedding waits for the first [Job.alpha].
     */
    fun start(px: IntArray, pw: Int, ph: Int, x: Int, y: Int): Job {
        val gray = IntArray(pw * ph) { val p = px[it]; (((p shr 16) and 255) * 299 + ((p shr 8) and 255) * 587 + (p and 255) * 114) / 1000 }
        val box = Panels.find(gray, pw, ph, x.coerceIn(0, pw - 1), y.coerceIn(0, ph - 1))
        val w = box.w; val h = box.h
        val rgb = FloatArray(w * h * 3)
        for (yy in 0 until h) for (xx in 0 until w) {
            val p = px[(yy + box.y0) * pw + xx + box.x0]; val o = (yy * w + xx) * 3
            rgb[o] = ((p shr 16) and 255).toFloat(); rgb[o + 1] = ((p shr 8) and 255).toFloat(); rgb[o + 2] = (p and 255).toFloat()
        }
        val alpha = nets.characterAlpha(rgb, w, h)
        val (labels, sizes) = Mask.components(alpha, w, h)
        val cx = (x - box.x0).toFloat().coerceIn(0f, w - 1f); val cy = (y - box.y0).toFloat().coerceIn(0f, h - 1f)
        val minPart = (0.002f * w * h).toInt()
        var figure = labels[cy.toInt() * w + cx.toInt()].takeIf { it != 0 && sizes[it] >= minPart } ?: 0
        if (figure == 0) figure = nearest(labels, sizes, w, h, cx, cy, 0.08f * Mask.diag(w, h), minPart)
        var start = cx to cy
        val missed = figure != 0 && labels[cy.toInt() * w + cx.toInt()] != figure
        if (missed) {
            // the tap missed the figure: the figure as anime-seg sees it; later taps ask SAM about its middle
            val (ix, iy) = Mask.innerPoint(BooleanArray(w * h) { labels[it] == figure }, w, h)
            start = ix.toFloat() to iy.toFloat()
        }
        return Job(px, pw, ph, box, rgb, w, h, alpha, labels, sizes, figure, start, missed)
    }

    private fun nearest(labels: IntArray, sizes: IntArray, w: Int, h: Int, x: Float, y: Float, reach: Float, minPart: Int): Int {
        var best = 0; var bd = reach * reach
        for (yy in 0 until h step 2) for (xx in 0 until w step 2) {
            val l = labels[yy * w + xx]
            if (l == 0 || sizes[l] < minPart) continue
            val d = (xx - x) * (xx - x) + (yy - y) * (yy - y)
            if (d < bd) { bd = d; best = l }
        }
        return best
    }
}
