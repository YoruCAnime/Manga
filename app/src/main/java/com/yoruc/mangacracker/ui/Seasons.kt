package com.yoruc.mangacracker.ui

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.provider.Settings as SysSettings
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.yoruc.mangacracker.core.Repo
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Path as AndroidPath
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Home screen ambience: sakura blossoms and petals (spring), light motes by day / fireflies by night (summer), maple
 * leaves (autumn) or snow crystals (winter) drifting over Home with a faint seasonal glow at the top, plus twinkling
 * stars and now and then a shooting star at night. Each piece is drawn once in detail and then only moved; far ones are
 * out of focus. Drawn in the draw phase only (no recomposition per frame, about 30 fps), never takes touches, and off
 * when the phone's animations are switched off.
 */
object Seasons {
    const val AUTO = "auto"
    const val OFF = "off"
    const val SAKURA = "sakura"
    const val SUMMER = "summer"
    const val LEAVES = "leaves"
    const val SNOW = "snow"
    const val STARS = "stars"
    internal const val MOTES = "motes"
    internal const val FIREFLIES = "fireflies"

    val choices = listOf(AUTO to "By season", SAKURA to "Sakura", SUMMER to "Summer", LEAVES to "Autumn leaves",
        SNOW to "Snow", STARS to "Only stars", OFF to "Off")

    fun bySeason(now: LocalDateTime) = when (now.monthValue) { 3, 4, 5 -> SAKURA; 6, 7, 8 -> SUMMER; 9, 10, 11 -> LEAVES; else -> SNOW }
    fun isNight(now: LocalDateTime) = now.hour >= 20 || now.hour < 6

    /** What Home shows now: the drifting kind (null = none) and whether the stars are out. */
    data class Scene(val kind: String?, val stars: Boolean)

    fun scene(effect: String, nightSky: Boolean, now: LocalDateTime = LocalDateTime.now()): Scene {
        val night = isNight(now)
        val kind = when (effect) { OFF, STARS -> null; AUTO -> bySeason(now); else -> effect }
        val k = if (kind == SUMMER) (if (night) FIREFLIES else MOTES) else kind
        return Scene(k, effect == STARS || (effect != OFF && nightSky && night))
    }

    /** "Autumn leaves now · stars after 8 pm" for the Settings line. */
    fun describe(effect: String, nightSky: Boolean): String {
        if (effect == OFF) return "Nothing drifts over Home."
        val now = LocalDateTime.now()
        val k = if (effect == AUTO) bySeason(now) else effect
        val what = choices.firstOrNull { it.first == k }?.second ?: ""
        val stars = when { effect == STARS -> ""; nightSky -> " · stars from 8 pm to 6 am"; else -> "" }
        return if (k == STARS) "Stars all day" else "$what${if (effect == AUTO) " now (it follows the month)" else ""}$stars"
    }
}

@Composable
fun SeasonLayer(modifier: Modifier = Modifier) {
    val s by Repo.settings.collectAsState()
    val ctx = LocalContext.current
    val animationsOn = remember { runCatching { SysSettings.Global.getFloat(ctx.contentResolver, SysSettings.Global.ANIMATOR_DURATION_SCALE, 1f) }.getOrDefault(1f) > 0f }
    if (!animationsOn || s.homeEffect == Seasons.OFF) return
    var scene by remember(s.homeEffect, s.homeNightSky) { mutableStateOf(Seasons.scene(s.homeEffect, s.homeNightSky)) }
    val field = remember(scene) { Field(scene) }
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(field) {
        var last = 0L
        var lookedAt = 0f
        while (true) {
            withFrameNanos { t ->
                if (last == 0L) last = t
                val dt = (t - last) / 1e9f
                if (dt >= 1f / 31f) {
                    field.step(min(dt, 0.1f))
                    last = t
                    frame = t
                }
            }
            if (field.time - lookedAt > 60f) {  // the season / night can change while Home stays open
                lookedAt = field.time
                val now = Seasons.scene(s.homeEffect, s.homeNightSky)
                if (now != scene) scene = now
            }
        }
    }
    Canvas(modifier) {
        if (frame >= 0L) field.draw(this)  // reading `frame` redraws on every step
    }
}

private class Bit {
    var x = 0f; var y = 0f; var vx = 0f; var vy = 0f
    var size = 0f; var rot = 0f; var spin = 0f
    var phase = 0f; var swayW = 0f; var swayAmp = 0f; var flipW = 0f
    var depth = 1f; var look = 0; var big = true
}

private class Star(val x: Float, val y: Float, val r: Float, val tw: Float, val phase: Float, val warm: Boolean)

/**
 * The pictures that drift: drawn once in detail (gradients, veins, glow) into small bitmaps, then only moved, turned
 * and tilted every frame. Far ones use a softened copy (out of focus).
 */
private object Sprites {
    const val PX = 192
    private val cache = HashMap<String, List<ImageBitmap>>()

    fun of(kind: String): List<ImageBitmap> = synchronized(cache) { cache.getOrPut(kind) { make(kind) } }

    /** [sharp..., soft...] for each look of [kind]. */
    private fun make(kind: String): List<ImageBitmap> {
        val sharp: List<Bitmap> = when (kind) {
            Seasons.SNOW -> (0 until 6).map { crystal(it) } + listOf(dot())
            Seasons.LEAVES -> MAPLE_COLOURS.indices.map { maple(it) }
            Seasons.SAKURA -> listOf(blossom(0), blossom(1), petal(0), petal(1))
            else -> emptyList()
        }
        return sharp.map { it.asImageBitmap() } + sharp.map { soft(it).asImageBitmap() }
    }

    private fun canvas(): Pair<Bitmap, AndroidCanvas> = Bitmap.createBitmap(PX, PX, Bitmap.Config.ARGB_8888).let { it to AndroidCanvas(it) }

    /** Out of focus: shrunk and grown back twice. */
    private fun soft(b: Bitmap): Bitmap {
        val s1 = Bitmap.createScaledBitmap(b, PX / 3, PX / 3, true)
        val s2 = Bitmap.createScaledBitmap(s1, PX / 7, PX / 7, true)
        val u1 = Bitmap.createScaledBitmap(s2, PX / 3, PX / 3, true)
        return Bitmap.createScaledBitmap(u1, PX, PX, true)
    }

    // a shader is multiplied by the colour's alpha, so the default is opaque
    private fun paint(color: Int = 0xFF000000.toInt(), style: Paint.Style = Paint.Style.FILL, width: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color; this.style = style; strokeWidth = width; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }

    // ---------------- snow crystals ----------------

    /** A six-armed crystal: each [seed] grows its own side branches, plate and tips; ice-blue glow under white lines. */
    private fun crystal(seed: Int): Bitmap {
        val (bmp, cv) = canvas()
        val c = PX / 2f; val r = PX * 0.44f
        val rnd = java.util.Random(seed * 7919L + 13)
        fun f(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)
        // one arm as line segments (x1, y1, x2, y2, width) pointing up from the centre
        val segs = ArrayList<FloatArray>()
        val w0 = PX * f(0.016f, 0.024f)
        segs += floatArrayOf(0f, 0f, 0f, -r, w0)
        val branches = 2 + rnd.nextInt(3)
        for (i in 0 until branches) {
            val p = f(0.22f, 0.8f) * r
            val len = (r - p) * f(0.35f, 0.75f)
            val ang = Math.toRadians(f(50f, 62f).toDouble())
            val bx = (sin(ang) * len).toFloat(); val by = (-cos(ang) * len).toFloat()
            segs += floatArrayOf(0f, -p, bx, -p + by, w0 * 0.75f); segs += floatArrayOf(0f, -p, -bx, -p + by, w0 * 0.75f)
            if (len > r * 0.25f && rnd.nextBoolean()) {   // a twig on the branch
                val q = 0.55f
                val tx = bx * q; val ty = -p + by * q
                val tl = len * 0.35f
                segs += floatArrayOf(tx, ty, tx, ty - tl, w0 * 0.55f); segs += floatArrayOf(-tx, ty, -tx, ty - tl, w0 * 0.55f)
            }
        }
        val plate = rnd.nextFloat() < 0.6f
        val tip = rnd.nextInt(3)   // 0 none, 1 small hexagon, 2 arrow
        fun drawArms(p: Paint, widen: Float) {
            for (k in 0 until 6) {
                cv.save(); cv.rotate(60f * k, c, c)
                for (s in segs) { p.strokeWidth = s[4] * widen; cv.drawLine(c + s[0], c + s[1], c + s[2], c + s[3], p) }
                if (tip == 1) { p.strokeWidth = w0 * 0.6f * widen; cv.drawPath(hexagon(c, c - r * 0.95f, r * 0.07f), p) }
                if (tip == 2) { p.strokeWidth = w0 * 0.6f * widen; cv.drawLine(c, c - r, c - r * 0.07f, c - r * 0.88f, p); cv.drawLine(c, c - r, c + r * 0.07f, c - r * 0.88f, p) }
                cv.restore()
            }
            if (plate) { p.strokeWidth = w0 * 0.7f * widen; cv.drawPath(hexagon(c, c, r * 0.2f), p) }
        }
        // glow, then the ice lines
        drawArms(paint(0xAAB8E0FF.toInt(), Paint.Style.STROKE).apply { maskFilter = BlurMaskFilter(PX * 0.03f, BlurMaskFilter.Blur.NORMAL) }, 3f)
        drawArms(paint(0xFFFFFFFF.toInt(), Paint.Style.STROKE), 1f)
        if (plate) cv.drawPath(hexagon(c, c, r * 0.2f), paint(0x55DDF0FF))
        // a bright heart
        cv.drawCircle(c, c, r * 0.09f, paint().apply { shader = RadialGradient(c, c, r * 0.09f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP) })
        return bmp
    }

    private fun hexagon(cx: Float, cy: Float, rr: Float) = AndroidPath().apply {
        for (i in 0..6) {
            val a = Math.toRadians(60.0 * i + 30.0)
            val x = cx + (cos(a) * rr).toFloat(); val y = cy + (sin(a) * rr).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    /** A soft round flake (the small, far snow). */
    private fun dot(): Bitmap {
        val (bmp, cv) = canvas()
        val c = PX / 2f
        cv.drawCircle(c, c, PX * 0.45f, paint().apply { shader = RadialGradient(c, c, PX * 0.45f, intArrayOf(0xFFFFFFFF.toInt(), 0xCCF2F8FF.toInt(), 0x00FFFFFF), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP) })
        return bmp
    }

    // ---------------- maple leaves ----------------

    val MAPLE_COLOURS = listOf(
        intArrayOf(0xFFFFB04A.toInt(), 0xFFE5402B.toInt(), 0xFF9C1222.toInt()),   // crimson
        intArrayOf(0xFFFFD866.toInt(), 0xFFF2842A.toInt(), 0xFFC2421C.toInt()),   // orange
        intArrayOf(0xFFFFF3A6.toInt(), 0xFFF4C23E.toInt(), 0xFFD08A1A.toInt()),   // gold
        intArrayOf(0xFFF7C35A.toInt(), 0xFFE9622C.toInt(), 0xFFB0242A.toInt()),   // red-orange
        intArrayOf(0xFFFF8A5C.toInt(), 0xFFD8233A.toInt(), 0xFF7E0E2A.toInt()),   // deep red
    )

    /** Seven lobes (angle in degrees, 90 = up; length) and two builds: broad maple and slim momiji. */
    private val LOBES = listOf(90f to 1f, 48f to 0.92f, 132f to 0.92f, 8f to 0.74f, 172f to 0.74f, -32f to 0.45f, 212f to 0.45f)
    private class Build(val width: Float, val peak: Float, val core: Float, val teeth: Float, val amp: Float)
    private val BUILDS = listOf(Build(0.24f, 0.38f, 0.2f, 7f, 0.025f), Build(0.17f, 0.42f, 0.15f, 7f, 0.02f))

    /** One lance-shaped, saw-toothed lobe from (cx, cy) along [ang], [len] long. */
    private fun lobe(cx: Float, cy: Float, ang: Float, len: Float, b: Build): AndroidPath {
        val n = 60
        val half = FloatArray(n + 1)
        val k = (ln(0.5) / ln(b.peak.toDouble())).toFloat()
        for (i in 0..n) {
            val s = i.toFloat() / n
            var hw = b.width * len * sin(PI.toFloat() * s.pow(k).coerceAtMost(1f)).coerceAtLeast(0f).pow(0.9f)  // sin(pi) is a hair below 0: pow would be NaN
            if (s > 0.25f && s < 0.98f) {
                val tri = abs(((s * b.teeth) % 1f) - 0.5f) * 2f
                hw += b.amp * len * tri * tri * ((s - 0.25f) / 0.2f).coerceAtMost(1f)
            }
            half[i] = hw
        }
        val t = Math.toRadians(ang.toDouble()); val ct = cos(t).toFloat(); val st = sin(t).toFloat()
        fun pt(x: Float, y: Float) = (cx + x * ct - y * st) to (cy - (x * st + y * ct))
        return AndroidPath().apply {
            for (i in 0..n) { val (x, y) = pt(i * len / n, half[i]); if (i == 0) moveTo(x, y) else lineTo(x, y) }
            for (i in n downTo 0) { val (x, y) = pt(i * len / n, -half[i]); lineTo(x, y) }
            close()
        }
    }

    /** The leaf's pieces (centre + lobes); painted one by one, so no path merging is needed. */
    private fun mapleParts(cx: Float, cy: Float, rr: Float, b: Build): List<AndroidPath> =
        listOf(AndroidPath().apply { addCircle(cx, cy, b.core * rr, AndroidPath.Direction.CW) }) + LOBES.map { (a, l) -> lobe(cx, cy, a, l * rr, b) }

    private fun maple(look: Int): Bitmap {
        val (bmp, cv) = canvas()
        val cols = MAPLE_COLOURS[look]
        val cx = PX / 2f; val cy = PX * 0.55f; val rr = PX * 0.44f
        val parts = mapleParts(cx, cy, rr, BUILDS[look % 2])
        // stem first (under the leaf)
        cv.drawLine(cx, cy, cx + rr * 0.05f, cy + rr * 0.5f, paint(0xFF6B2A12.toInt(), Paint.Style.STROKE, PX * 0.016f))
        val body = paint().apply {
            shader = RadialGradient(cx, cy, rr, intArrayOf(cols[0], cols[1], cols[2]), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        }
        for (p in parts) cv.drawPath(p, body)
        // everything after this only paints on the leaf (on top of what is already there)
        val atop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        // light from the top left, a little shade bottom right
        cv.drawRect(0f, 0f, PX.toFloat(), PX.toFloat(), paint().apply {
            shader = LinearGradient(0f, 0f, PX.toFloat(), PX.toFloat(), intArrayOf(0x40FFFFFF, 0x00FFFFFF, 0x30000000), floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
            xfermode = atop
        })
        // veins: one to each lobe tip, small ones off them
        val vein = paint((cols[2] and 0x00FFFFFF) or 0x99000000.toInt(), Paint.Style.STROKE, PX * 0.009f).apply { xfermode = atop }
        val light = paint(0x40FFFFFF, Paint.Style.STROKE, PX * 0.004f).apply { xfermode = atop }
        for ((a, l) in LOBES) {
            val t = Math.toRadians(a.toDouble())
            val ex = cx + (cos(t) * l * rr * 0.85f).toFloat(); val ey = cy - (sin(t) * l * rr * 0.85f).toFloat()
            cv.drawLine(cx, cy, ex, ey, vein); cv.drawLine(cx + 1f, cy - 1f, ex + 1f, ey - 1f, light)
            for (q in listOf(0.35f, 0.55f, 0.75f)) {
                val px = cx + (ex - cx) * q; val py = cy + (ey - cy) * q
                for (side in listOf(-1, 1)) {
                    val ta = t + side * Math.toRadians(42.0)
                    val len = l * rr * 0.13f * (1.1f - q)
                    cv.drawLine(px, py, px + (cos(ta) * len).toFloat(), py - (sin(ta) * len).toFloat(), paint(vein.color and 0x66FFFFFF, Paint.Style.STROKE, PX * 0.005f).apply { xfermode = atop })
                }
            }
        }
        return bmp
    }

    // ---------------- sakura ----------------

    /** A petal pointing up from (cx, cy), [len] long, with the notch at its tip. */
    private fun petalPath(cx: Float, cy: Float, len: Float, wide: Float) = AndroidPath().apply {
        moveTo(cx, cy)
        cubicTo(cx + wide * 0.9f, cy - len * 0.25f, cx + wide, cy - len * 0.8f, cx + wide * 0.35f, cy - len)
        lineTo(cx, cy - len * 0.88f)
        lineTo(cx - wide * 0.35f, cy - len)
        cubicTo(cx - wide, cy - len * 0.8f, cx - wide * 0.9f, cy - len * 0.25f, cx, cy)
        close()
    }

    private fun blossom(look: Int): Bitmap {
        val (bmp, cv) = canvas()
        val c = PX / 2f; val len = PX * 0.44f
        val edge = if (look == 0) 0xFFF59AB8.toInt() else 0xFFF7B3C9.toInt()
        val fill = paint().apply {
            shader = RadialGradient(c, c, len, intArrayOf(0xFFFFF7FA.toInt(), 0xFFFFDCE8.toInt(), edge), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        }
        val line = paint(0x55E0708F, Paint.Style.STROKE, PX * 0.005f)
        for (k in 0 until 5) {
            cv.save(); cv.rotate(72f * k + look * 12f, c, c)
            cv.drawPath(petalPath(c, c, len, len * 0.42f), fill)
            for (s in listOf(-1, 0, 1)) cv.drawLine(c, c - len * 0.12f, c + s * len * 0.12f, c - len * 0.62f, line)
            cv.restore()
        }
        // the heart: a pink eye, stamens with yellow tips
        cv.drawCircle(c, c, len * 0.16f, paint().apply { shader = RadialGradient(c, c, len * 0.16f, 0xFFE0577A.toInt(), 0x00F59AB8, Shader.TileMode.CLAMP) })
        val stem = paint(0xFFD8607F.toInt(), Paint.Style.STROKE, PX * 0.006f)
        val dot = paint(0xFFFFD45A.toInt())
        for (k in 0 until 10) {
            val a = Math.toRadians(36.0 * k + 8)
            val l = len * (0.24f + (k % 2) * 0.06f)
            val x = c + (cos(a) * l).toFloat(); val y = c + (sin(a) * l).toFloat()
            cv.drawLine(c, c, x, y, stem); cv.drawCircle(x, y, PX * 0.012f, dot)
        }
        return bmp
    }

    private fun petal(look: Int): Bitmap {
        val (bmp, cv) = canvas()
        val c = PX / 2f; val len = PX * 0.86f
        val base = c + len / 2
        cv.drawPath(petalPath(c, base, len, len * 0.42f), paint().apply {
            shader = LinearGradient(c, base, c, base - len, intArrayOf(0xFFF27DA3.toInt(), if (look == 0) 0xFFFFD3E2.toInt() else 0xFFFFE6EF.toInt(), 0xFFFFF4F8.toInt()),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
        })
        val line = paint(0x40D85C82, Paint.Style.STROKE, PX * 0.006f)
        for (s in listOf(-1, 0, 1)) cv.drawLine(c, base - len * 0.05f, c + s * len * 0.13f, base - len * 0.7f, line)
        return bmp
    }
}

private class Field(val scene: Seasons.Scene) {
    var time = 0f
    private val rnd = java.util.Random()
    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private val kind = scene.kind
    private val art = kind?.let { Sprites.of(it) } ?: emptyList()
    private val looks = art.size / 2
    private val bits = Array(
        when (kind) { Seasons.SAKURA -> 22; Seasons.LEAVES -> 13; Seasons.SNOW -> 46; Seasons.MOTES -> 14; Seasons.FIREFLIES -> 14; else -> 0 }
    ) { i -> Bit().also { it.big = i % 2 == 0; spawn(it, first = true) } }
    private val stars = if (scene.stars) Array(56) {
        val y = r(0f, 1f).pow(1.6f) * 0.5f  // more near the top
        Star(r(0f, 1f), y, r(0.5f, 1.6f), r(0.6f, 2.2f), r(0f, 6.28f), rnd.nextFloat() < 0.2f)
    } else emptyArray()
    private var shootAt = r(6f, 20f)
    private var shootX = 0f; private var shootY = 0f

    fun spawn(b: Bit, first: Boolean) {
        b.depth = r(0.35f, 1f)
        b.x = r(-0.05f, 1.05f)
        b.phase = r(0f, 6.28f)
        b.rot = r(0f, 360f)
        b.spin = r(-40f, 40f)
        b.flipW = r(0.7f, 2f)
        b.swayW = r(0.35f, 1f)
        val speed = 0.55f + 0.45f * b.depth  // nearer = faster
        val top = if (first) r(-0.05f, 1f) else r(-0.14f, -0.06f)
        when (kind) {
            Seasons.SAKURA -> {
                // every third one a whole blossom, the rest loose petals
                b.look = if (b.big && rnd.nextInt(3) == 0) rnd.nextInt(2) else 2 + rnd.nextInt(2)
                b.size = (if (b.look < 2) r(30f, 44f) else r(18f, 28f)) * (0.5f + 0.5f * b.depth)
                b.vy = r(0.028f, 0.045f) * speed; b.swayAmp = r(12f, 30f); b.y = top
                if (b.look < 2) b.spin = r(-25f, 25f)
            }
            Seasons.LEAVES -> {
                b.look = rnd.nextInt(looks); b.size = r(38f, 58f) * (0.45f + 0.55f * b.depth)
                b.vy = r(0.04f, 0.065f) * speed; b.swayAmp = r(20f, 46f); b.spin = r(-80f, 80f); b.y = top
            }
            Seasons.SNOW -> {
                // half crystals (some near and big), half small round flakes far away
                b.look = if (b.big) rnd.nextInt(looks - 1) else looks - 1
                b.size = if (b.big) r(24f, 46f) * (0.45f + 0.55f * b.depth) else r(5f, 11f) * (0.5f + 0.5f * b.depth)
                b.vy = r(0.018f, 0.034f) * speed; b.swayAmp = r(8f, 22f); b.spin = r(-22f, 22f)
                b.y = if (first) r(-0.05f, 1f) else r(-0.1f, -0.03f)
            }
            Seasons.MOTES -> {
                b.look = rnd.nextInt(3)   // warm gold, peach, sky blue
                b.size = r(14f, 34f) * (0.5f + 0.5f * b.depth); b.vy = -r(0.008f, 0.016f); b.swayAmp = r(8f, 20f); b.y = if (first) r(0f, 1.05f) else r(1.02f, 1.08f)
            }
            Seasons.FIREFLIES -> { b.size = r(12f, 20f) * b.depth; b.y = r(0.1f, 1f); b.vx = r(-0.02f, 0.02f); b.vy = r(-0.015f, 0.015f) }
        }
    }

    fun step(dt: Float) {
        time += dt
        val wind = 0.004f + 0.007f * sin(time * 0.06f)
        for (b in bits) when (kind) {
            Seasons.FIREFLIES -> {
                b.vx = (b.vx * 0.985f + (rnd.nextFloat() - 0.5f) * 0.004f).coerceIn(-0.025f, 0.025f)
                b.vy = (b.vy * 0.985f + (rnd.nextFloat() - 0.5f) * 0.003f).coerceIn(-0.02f, 0.02f)
                b.x += b.vx * dt; b.y += b.vy * dt
                if (b.x < -0.05f) b.x = 1.05f; if (b.x > 1.05f) b.x = -0.05f
                if (b.y < 0.05f) b.vy = abs(b.vy); if (b.y > 1.02f) b.vy = -abs(b.vy)
            }
            Seasons.MOTES -> {
                b.y += b.vy * dt; b.x += wind * 0.3f * dt
                if (b.y < -0.06f) spawn(b, first = false)
            }
            else -> {
                b.y += b.vy * dt; b.x += wind * b.depth * dt; b.rot += b.spin * dt
                if (b.y > 1.08f || b.x > 1.14f) spawn(b, first = false)
            }
        }
        if (stars.isNotEmpty() && time > shootAt + 1.2f) {
            shootAt = time + r(14f, 40f)
            shootX = r(0.35f, 1f); shootY = r(0.02f, 0.25f)
        }
    }

    /** A sprite centred on (cx, cy), [px] across, turned [rot] and squeezed sideways by [flip] (tumbling). */
    private fun DrawScope.sprite(img: ImageBitmap, cx: Float, cy: Float, px: Float, rot: Float, flip: Float, alpha: Float) {
        val s = px.roundToInt().coerceAtLeast(2)
        translate(cx, cy) {
            rotate(rot, Offset.Zero) {
                scale(flip, 1f, Offset.Zero) {
                    drawImage(img, IntOffset.Zero, IntSize(img.width, img.height), IntOffset(-s / 2, -s / 2), IntSize(s, s), alpha = alpha, filterQuality = FilterQuality.Medium)
                }
            }
        }
    }

    fun draw(d: DrawScope) = with(d) {
        val w = size.width; val h = size.height; val dp = density
        glow(w, h)
        if (stars.isNotEmpty()) drawStars(w, h, dp)
        for (b in bits) {
            val cx = b.x * w + sin(time * b.swayW + b.phase) * b.swayAmp * dp
            val cy = b.y * h
            // far ones out of focus and fainter
            val img = { look: Int -> if (b.depth < 0.6f && looks > 0) art[looks + look] else art[look] }
            when (kind) {
                Seasons.SAKURA -> {
                    val flip = cos(time * b.flipW + b.phase)
                    val f = if (b.look < 2) 0.75f + 0.25f * abs(flip) else 0.25f + 0.75f * abs(flip)
                    sprite(img(b.look), cx, cy, b.size * dp, b.rot, f, 0.55f + 0.4f * b.depth)
                }
                Seasons.LEAVES -> {
                    val flip = cos(time * b.flipW + b.phase)
                    sprite(img(b.look), cx, cy, b.size * dp, b.rot, 0.2f + 0.8f * abs(flip), 0.6f + 0.38f * b.depth)
                }
                Seasons.SNOW -> {
                    val glint = if (b.big) 0.85f + 0.15f * sin(time * 3f * b.flipW + b.phase) else 1f
                    val flip = if (b.big) 0.7f + 0.3f * abs(cos(time * 0.5f * b.flipW + b.phase)) else 1f
                    sprite(img(b.look), cx, cy, b.size * dp, b.rot, flip, (0.55f + 0.45f * b.depth) * glint)
                }
                Seasons.MOTES -> {
                    // summer light: lens bokeh discs with a brighter rim, and a glint now and then
                    val r = b.size * dp
                    val pulse = 0.5f + 0.5f * sin(time * b.flipW * 0.7f + b.phase)
                    val a = (0.14f + 0.18f * pulse) * (0.55f + 0.45f * b.depth)
                    val c = MOTE_COLOURS[b.look]
                    drawCircle(Brush.radialGradient(0f to c.copy(alpha = a * 0.55f), 0.8f to c.copy(alpha = a * 0.75f), 0.92f to c.copy(alpha = a), 1f to Color.Transparent,
                        center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
                    if (pulse > 0.9f && b.depth > 0.7f) {
                        val k = r * 0.5f * (pulse - 0.9f) * 10f
                        drawLine(Color.White.copy(alpha = 0.6f), Offset(cx - k, cy), Offset(cx + k, cy), 1f * dp)
                        drawLine(Color.White.copy(alpha = 0.6f), Offset(cx, cy - k), Offset(cx, cy + k), 1f * dp)
                    }
                }
                Seasons.FIREFLIES -> {
                    val blink = (0.5f + 0.5f * sin(time * b.flipW + b.phase)).pow(3)
                    val r = b.size * dp
                    val x = b.x * w; val y = b.y * h
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFD9FF7A).copy(alpha = 0.45f * blink), Color(0xFF9CFF6A).copy(alpha = 0.12f * blink), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
                    drawCircle(Color(0xFFF6FFC4).copy(alpha = 0.95f * blink), 1.8f * dp, Offset(x, y))
                }
            }
        }
    }

    private val MOTE_COLOURS = listOf(Color(0xFFFFE6A0), Color(0xFFFFC2A0), Color(0xFFA8E4FF))

    /** A faint seasonal light at the top of the screen (like the sky glow in anime backgrounds). */
    private fun DrawScope.glow(w: Float, h: Float) {
        val c = when (kind) {
            Seasons.SAKURA -> Color(0xFFFFB7D0); Seasons.LEAVES -> Color(0xFFFF9A4A); Seasons.SNOW -> Color(0xFFBFE2FF)
            Seasons.MOTES -> Color(0xFFFFE2A0); Seasons.FIREFLIES -> Color(0xFF9CFF8A); else -> return
        }
        drawRect(Brush.verticalGradient(listOf(c.copy(alpha = 0.10f), Color.Transparent), 0f, h * 0.35f), size = Size(w, h * 0.35f))
    }

    private fun DrawScope.drawStars(w: Float, h: Float, dp: Float) {
        for (s in stars) {
            val tw = 0.5f + 0.5f * sin(time * s.tw + s.phase)
            val a = 0.12f + 0.55f * tw * (s.r / 1.6f)
            val c = (if (s.warm) Color(0xFFFFF0D2) else Color(0xFFE6F0FF)).copy(alpha = a)
            val p = Offset(s.x * w, s.y * h)
            if (s.r > 1.2f) drawCircle(Brush.radialGradient(listOf(c.copy(alpha = a * 0.5f), Color.Transparent), p, s.r * dp * 4f), s.r * dp * 4f, p)
            drawCircle(c, s.r * dp, p)
            if (s.r > 1.3f) {  // the brightest ones get a four-point sparkle
                val k = s.r * dp * (3f + 2.5f * tw)
                drawLine(c.copy(alpha = a * 0.6f), Offset(p.x - k, p.y), Offset(p.x + k, p.y), 0.7f * dp)
                drawLine(c.copy(alpha = a * 0.6f), Offset(p.x, p.y - k), Offset(p.x, p.y + k), 0.7f * dp)
            }
        }
        val t = time - shootAt
        if (t in 0f..1.0f) {  // a shooting star: about a second, down and to the left
            val fade = sin(PI.toFloat() * t)
            val hx = (shootX - 0.32f * t) * w; val hy = (shootY + 0.14f * t) * h
            val tail = 0.35f * max(0.3f, fade)  // seconds of path behind the head
            val tx = hx + 0.32f * w * tail; val ty = hy - 0.14f * h * tail
            drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.8f * fade), Color.Transparent), Offset(hx, hy), Offset(tx, ty)),
                Offset(hx, hy), Offset(tx, ty), 1.6f * dp, cap = StrokeCap.Round)
            drawCircle(Color.White.copy(alpha = 0.9f * fade), 1.8f * dp, Offset(hx, hy))
        }
    }
}
