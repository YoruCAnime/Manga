package com.yoruc.mangacracker.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.Window
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.swing.JPanel
import javax.swing.JWindow
import javax.swing.SwingUtilities

/**
 * The picture under the mouse on the player's seek bar. Frames are read by ffmpeg (below normal priority, one at a
 * time, the newest wish only) at the key frame nearest the spot: a few seconds' steps for long videos, kept in memory
 * for this video. [inputOf] = ffmpeg's input options (the site's stream with its cookies / keys, or `-i <file>`),
 * worked out once on the reading thread (see [smallest]).
 */
class SeekPreview(inputOf: () -> List<String>, private val durS: Double, private val width: Int) {
    constructor(input: List<String>, durS: Double, width: Int) : this({ input }, durS, width)
    private val input by lazy(inputOf)

    companion object {
        /**
         * ffmpeg's input for hover pictures: a stream's smallest size (its pieces download ~4x quicker: MuchoHentai
         * 0.65 s a picture instead of 2.6 s, Anikoto 0.26 s instead of 0.7 s), the file itself for a download.
         */
        fun smallest(sourceId: String, url: String, kind: String): List<String> {
            if (url.startsWith("file:")) return listOf("-i", java.io.File(java.net.URI(url)).path)
            if (kind == "hls" || url.substringBefore('?').endsWith(".m3u8")) runCatching {
                val src = com.yoruc.mangacracker.video.VideoSources.byId(sourceId) ?: return@runCatching
                val text = src.playerClient.newCall(com.yoruc.mangacracker.core.Http.request(url)).execute().use { it.body?.string().orEmpty() }
                val low = com.yoruc.mangacracker.video.VideoDownloads.variants(text, url).minByOrNull { if (it.height > 0) it.height else Int.MAX_VALUE }
                if (low != null) return StreamProxy.ffmpegInput(sourceId, low.url, "hls")
            }
            return StreamProxy.ffmpegInput(sourceId, url, kind)
        }
    }
    /** The step between pictures: 2 s, longer for long videos (at most ~600 different pictures per video). */
    val step: Double = maxOf(2.0, durS / 600.0)
    private val cache = object : LinkedHashMap<Int, BufferedImage>(96, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, BufferedImage>?) = size > 90
    }
    private val wanted = Channel<Int>(Channel.CONFLATED)
    private var job: Job? = null
    /** Called on the Swing thread with a new picture (its step number). */
    @Volatile var onFrame: (Int, BufferedImage) -> Unit = { _, _ -> }
    @Volatile var closed = false

    fun bucket(sec: Double) = (sec / step).toInt().coerceAtLeast(0)

    /** The picture for [b], or the nearest one already read (within 15 steps), or null. */
    fun cached(b: Int): Pair<BufferedImage, Boolean>? = synchronized(cache) {
        cache[b]?.let { return it to true }
        (1..15).firstNotNullOfOrNull { d -> cache[b - d] ?: cache[b + d] }?.let { it to false }
    }

    fun want(b: Int) {
        if (closed) return
        if (job == null) job = Desktop.scope.launch(Dispatchers.IO) {
            for (n in wanted) {
                if (closed) break
                if (synchronized(cache) { cache.containsKey(n) }) continue
                val img = grab((n + 0.5) * step) ?: continue
                synchronized(cache) { cache[n] = img }
                SwingUtilities.invokeLater { if (!closed) onFrame(n, img) }
            }
        }
        wanted.trySend(b)
    }

    /** One frame at [sec], [width] px wide; null when ffmpeg can't read it in 10 s. */
    fun grab(sec: Double): BufferedImage? {
        val ff = Avif.ffmpeg ?: return null
        val t = "%.3f".format(Locale.US, sec.coerceIn(0.0, (durS - 0.5).coerceAtLeast(0.0)))
        val args = mutableListOf(ff.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-noaccurate_seek", "-ss", t)
        args += input
        args += listOf("-frames:v", "1", "-an", "-sn", "-dn", "-vf", "scale=$width:-2", "-f", "image2pipe", "-c:v", "bmp", "pipe:1")
        return runCatching {
            val p = ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            ClipRecorder.lower(p)
            val bytes = java.util.concurrent.CompletableFuture.supplyAsync { p.inputStream.readAllBytes() }
            if (!p.waitFor(10, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
            val b = bytes.get(2, TimeUnit.SECONDS)
            if (b.isEmpty()) null else javax.imageio.ImageIO.read(b.inputStream())?.let { src ->
                BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB).apply { createGraphics().apply { drawImage(src, 0, 0, null); dispose() } }
            }
        }.getOrNull()
    }

    fun close() { closed = true; wanted.close(); job = null }
}

/** The small window that floats above the seek bar: the picture and the time under it. Never takes the focus. */
class HoverWindow(owner: Window) {
    private var img: BufferedImage? = null
    private var exact = false
    private var label = ""
    private val w = 260; private val imgH = 146; private val h = imgH + 30
    private val panel = object : JPanel() {
        init { isOpaque = false }
        override fun paintComponent(g0: Graphics) {
            val g = g0.create() as Graphics2D
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g.composite = AlphaComposite.Src
            g.color = Color(0, 0, 0, 0); g.fillRect(0, 0, width, height)
            g.composite = AlphaComposite.SrcOver
            g.color = Color(0x0B, 0x16, 0x22, 240)
            g.fill(RoundRectangle2D.Float(0f, 0f, w.toFloat(), h.toFloat(), 14f, 14f))
            val clip = RoundRectangle2D.Float(3f, 3f, (w - 6).toFloat(), (imgH - 3).toFloat(), 10f, 10f)
            g.color = Color(0x1C, 0x28, 0x38); g.fill(clip)
            img?.let { im ->
                val s = g.clip; g.clip(clip)
                // fit the frame in the box (letterboxed if it isn't 16:9)
                val bw = w - 6; val bh = imgH - 3
                val k = minOf(bw.toDouble() / im.width, bh.toDouble() / im.height)
                val dw = (im.width * k).toInt(); val dh = (im.height * k).toInt()
                g.drawImage(im, 3 + (bw - dw) / 2, 3 + (bh - dh) / 2, dw, dh, null)
                if (!exact) { g.color = Color(0, 0, 0, 90); g.fill(clip) }   // a nearby picture while the right one loads
                g.clip = s
            }
            g.font = Font("Segoe UI", Font.BOLD, 14)
            g.color = Color.WHITE
            val fm = g.fontMetrics
            g.drawString(label, (w - fm.stringWidth(label)) / 2, imgH + 6 + fm.ascent)
            g.dispose()
        }
    }
    private val win = JWindow(owner).apply {
        type = Window.Type.POPUP
        focusableWindowState = false
        isAlwaysOnTop = false
        background = Color(0, 0, 0, 0)
        contentPane = panel
        setSize(w, h)
    }

    init {
        owner.addWindowListener(object : java.awt.event.WindowAdapter() {
            override fun windowDeactivated(e: java.awt.event.WindowEvent) = hide()
            override fun windowIconified(e: java.awt.event.WindowEvent) = hide()
        })
    }

    /** Above the point ([x], [top]) in screen units (centred on x, its bottom 10 px over top), kept within [left]..[right]. */
    fun show(x: Int, top: Int, left: Int, right: Int, picture: BufferedImage?, isExact: Boolean, time: String) {
        img = picture; exact = isExact; label = time
        val px = (x - w / 2).coerceIn(left, (right - w).coerceAtLeast(left))
        win.setLocation(px, top - h - 10)
        if (!win.isVisible) win.isVisible = true
        panel.repaint()
    }

    fun update(picture: BufferedImage, isExact: Boolean) { if (win.isVisible) { img = picture; exact = isExact; panel.repaint() } }

    fun hide() { if (win.isVisible) win.isVisible = false }
    fun bounds(): java.awt.Rectangle = win.bounds
    fun dispose() { win.isVisible = false; win.dispose() }

    /** Test runs: what the window shows, drawn into a picture. */
    fun snapshot(): BufferedImage = BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB).also { val g = it.createGraphics(); panel.setSize(w, h); panel.paint(g); g.dispose() }

    companion object { const val GRAB_WIDTH = 320 }
}
