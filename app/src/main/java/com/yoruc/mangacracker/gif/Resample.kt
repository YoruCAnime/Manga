package com.yoruc.mangacracker.gif

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.sin

/** Lanczos-3 resize of 0xRRGGBB frames (sharp, no aliasing when shrinking). */
object Resample {
    private class Taps(val start: IntArray, val n: Int, val w: FloatArray)

    private fun sinc(x: Double) = if (x == 0.0) 1.0 else sin(PI * x) / (PI * x)

    private fun taps(src: Int, dst: Int): Taps {
        val scale = src.toDouble() / dst
        val fs = maxOf(1.0, scale)            // widen the kernel when shrinking
        val support = 3.0 * fs
        val n = ceil(support * 2).toInt() + 1
        val start = IntArray(dst); val w = FloatArray(dst * n)
        for (x in 0 until dst) {
            val center = (x + 0.5) * scale - 0.5
            val s = floor(center - support).toInt() + 1
            start[x] = s
            var sum = 0.0
            for (t in 0 until n) {
                val d = (s + t - center) / fs
                val v = if (kotlin.math.abs(d) >= 3.0) 0.0 else sinc(d) * sinc(d / 3.0)
                w[x * n + t] = v.toFloat(); sum += v
            }
            for (t in 0 until n) w[x * n + t] = (w[x * n + t] / sum).toFloat()
        }
        return Taps(start, n, w)
    }

    fun resize(src: IntArray, sw: Int, sh: Int, dw: Int, dh: Int): IntArray {
        if (sw == dw && sh == dh) return src.copyOf()
        val tx = taps(sw, dw); val ty = taps(sh, dh)
        // horizontal pass -> planar floats (sw -> dw), rows sh
        val hr = FloatArray(dw * sh); val hg = FloatArray(dw * sh); val hb = FloatArray(dw * sh)
        Par.rows(sh) { r0, r1 -> for (y in r0 until r1) {
            val row = y * sw
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                val s = tx.start[x]; val o = x * tx.n
                for (t in 0 until tx.n) {
                    val wt = tx.w[o + t]; if (wt == 0f) continue
                    val c = src[row + (s + t).coerceIn(0, sw - 1)]
                    r += wt * (c shr 16 and 0xFF); g += wt * (c shr 8 and 0xFF); b += wt * (c and 0xFF)
                }
                val i = y * dw + x
                hr[i] = r; hg[i] = g; hb[i] = b
            }
        } }
        val out = IntArray(dw * dh)
        Par.rows(dh) { r0, r1 -> for (y in r0 until r1) {
            val s = ty.start[y]; val o = y * ty.n
            for (x in 0 until dw) {
                var r = 0f; var g = 0f; var b = 0f
                for (t in 0 until ty.n) {
                    val wt = ty.w[o + t]; if (wt == 0f) continue
                    val i = (s + t).coerceIn(0, sh - 1) * dw + x
                    r += wt * hr[i]; g += wt * hg[i]; b += wt * hb[i]
                }
                out[y * dw + x] = (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)
            }
        } }
        return out
    }

    private fun clamp(v: Float): Int = (v + 0.5f).toInt().coerceIn(0, 255)
}
