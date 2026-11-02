package com.yoruc.mangacracker.cutout

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.max
import kotlin.math.roundToInt

/** The two networks behind the cutout. Kept apart from [Cutter] so the rules can be tested without them. */
interface Nets : Closeable {
    /** anime-seg: character alpha (0..1) for an RGB crop (0..255, row-major w x h x 3), at w x h. */
    fun characterAlpha(rgb: FloatArray, w: Int, h: Int): FloatArray

    /** MobileSAM image embedding of the crop (run once per crop). */
    fun embed(rgb: FloatArray, w: Int, h: Int): Any

    /** MobileSAM masks for points (crop px; label 1 = keep, 0 = drop): (scores, 4 logit maps at w x h). */
    fun masks(embedding: Any, points: List<Triple<Float, Float, Int>>, w: Int, h: Int): Pair<FloatArray, List<FloatArray>>
}

/**
 * ONNX Runtime versions of [Nets]. Models (see [CutoutModels]):
 *  - anime-seg isnetis (SkyTNT, Apache-2.0): 1x3x1024x1024 letterboxed RGB/255 -> 1x1x1024x1024 alpha.
 *  - MobileSAM (Apache-2.0) encoder: H x W x 3 RGB 0..255, long side 1024 -> 1x256x64x64; decoder: points in the
 *    1024-long-side frame plus a (0,0,-1) pad point -> 4 low-res 256x256 logit maps of the padded square.
 */
class OrtNets(dir: File, threads: Int = 4) : Nets {
    private val env = OrtEnvironment.getEnvironment()
    private val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
    private val seg = env.createSession(File(dir, CutoutModels.SEG).path, opts)
    private val enc = env.createSession(File(dir, CutoutModels.SAM_ENC).path, opts)
    private val dec = env.createSession(File(dir, CutoutModels.SAM_DEC).path, opts)

    override fun characterAlpha(rgb: FloatArray, w: Int, h: Int): FloatArray {
        val s = 1024
        val (nh, nw) = if (h > w) s to (s * w / h) else (s * h / w) to s
        val ph = (s - nh) / 2; val pw = (s - nw) / 2
        val small = resizeRgb(rgb, w, h, nw, nh)
        val x = FloatArray(3 * s * s)
        for (y in 0 until nh) for (xx in 0 until nw) {
            val i = (y * nw + xx) * 3
            val o = (y + ph) * s + xx + pw
            x[o] = small[i] / 255f; x[s * s + o] = small[i + 1] / 255f; x[2 * s * s + o] = small[i + 2] / 255f
        }
        val out = OnnxTensor.createTensor(env, FloatBuffer.wrap(x), longArrayOf(1, 3, s.toLong(), s.toLong())).use { t ->
            seg.run(mapOf("img" to t)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } }
        }
        val inner = FloatArray(nw * nh)
        for (y in 0 until nh) System.arraycopy(out, (y + ph) * s + pw, inner, y * nw, nw)
        return Mask.resize(inner, nw, nh, w, h)
    }

    private class Embedding(val t: FloatArray, val scale: Float)

    override fun embed(rgb: FloatArray, w: Int, h: Int): Any {
        val sc = 1024f / max(w, h)
        val nw = (w * sc).roundToInt(); val nh = (h * sc).roundToInt()
        val small = resizeRgb(rgb, w, h, nw, nh)
        val e = OnnxTensor.createTensor(env, FloatBuffer.wrap(small), longArrayOf(nh.toLong(), nw.toLong(), 3)).use { t ->
            enc.run(mapOf("input_image" to t)).use { r -> (r[0] as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } } }
        }
        return Embedding(e, sc)
    }

    override fun masks(embedding: Any, points: List<Triple<Float, Float, Int>>, w: Int, h: Int): Pair<FloatArray, List<FloatArray>> {
        val e = embedding as Embedding
        val n = points.size + 1
        val coords = FloatArray(n * 2); val labels = FloatArray(n)
        points.forEachIndexed { i, (x, y, l) -> coords[2 * i] = x * e.scale; coords[2 * i + 1] = y * e.scale; labels[i] = l.toFloat() }
        labels[n - 1] = -1f
        val inputs = mapOf(
            "image_embeddings" to OnnxTensor.createTensor(env, FloatBuffer.wrap(e.t), longArrayOf(1, 256, 64, 64)),
            "point_coords" to OnnxTensor.createTensor(env, FloatBuffer.wrap(coords), longArrayOf(1, n.toLong(), 2)),
            "point_labels" to OnnxTensor.createTensor(env, FloatBuffer.wrap(labels), longArrayOf(1, n.toLong())),
            "mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(FloatArray(256 * 256)), longArrayOf(1, 1, 256, 256)),
            "has_mask_input" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(0f)), longArrayOf(1)),
            "orig_im_size" to OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(256f, 256f)), longArrayOf(2)),
        )
        try {
            dec.run(inputs, setOf("iou_predictions", "low_res_masks")).use { r ->
                val ious = (r.get("iou_predictions").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                val low = (r.get("low_res_masks").get() as OnnxTensor).floatBuffer.let { b -> FloatArray(b.remaining()).also { b.get(it) } }
                // the 256 grid covers the padded 1024 square; the crop is its top-left (w, h) * scale / 4 part
                val cw = ((w * e.scale) / 4f).roundToInt().coerceIn(1, 256); val ch = ((h * e.scale) / 4f).roundToInt().coerceIn(1, 256)
                val maps = (0 until ious.size).map { k ->
                    val part = FloatArray(cw * ch)
                    for (y in 0 until ch) System.arraycopy(low, k * 65536 + y * 256, part, y * cw, cw)
                    Mask.resize(part, cw, ch, w, h)
                }
                return ious to maps
            }
        } finally { inputs.values.forEach { it.close() } }
    }

    override fun close() { seg.close(); enc.close(); dec.close(); opts.close() }

    companion object {
        /** Bilinear resize of interleaved RGB. */
        fun resizeRgb(rgb: FloatArray, w: Int, h: Int, nw: Int, nh: Int): FloatArray {
            val out = FloatArray(nw * nh * 3)
            for (c in 0 until 3) {
                val ch = FloatArray(w * h) { rgb[it * 3 + c] }
                val r = Mask.resize(ch, w, h, nw, nh)
                for (i in r.indices) out[i * 3 + c] = r[i]
            }
            return out
        }
    }
}
