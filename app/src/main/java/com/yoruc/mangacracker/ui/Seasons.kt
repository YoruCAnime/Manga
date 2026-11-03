package com.yoruc.mangacracker.ui

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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import com.yoruc.mangacracker.core.Repo
import java.time.LocalDateTime
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * Home screen ambience: a few sakura petals (spring), light motes by day / fireflies by night (summer), falling
 * leaves (autumn) or snow (winter) drifting over Home, plus twinkling stars and now and then a shooting star at
 * night. Subtle on purpose: few, small, see-through. Drawn in the draw phase only (no recomposition per frame, about
 * 30 fps), never takes touches, and off when the phone's animations are switched off.
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
    var depth = 1f; var tint = 0
}

private class Star(val x: Float, val y: Float, val r: Float, val tw: Float, val phase: Float, val warm: Boolean)

private class Field(val scene: Seasons.Scene) {
    var time = 0f
    private val rnd = java.util.Random()
    private fun r(a: Float, b: Float) = a + rnd.nextFloat() * (b - a)

    private val kind = scene.kind
    private val bits = Array(
        when (kind) { Seasons.SAKURA -> 16; Seasons.LEAVES -> 10; Seasons.SNOW -> 30; Seasons.MOTES -> 12; Seasons.FIREFLIES -> 12; else -> 0 }
    ) { Bit().also { spawn(it, first = true) } }
    private val stars = if (scene.stars) Array(46) {
        val y = r(0f, 1f).pow(1.6f) * 0.5f  // more near the top
        Star(r(0f, 1f), y, r(0.5f, 1.5f), r(0.6f, 2.2f), r(0f, 6.28f), rnd.nextFloat() < 0.2f)
    } else emptyArray()
    private var shootAt = r(6f, 20f)
    private var shootX = 0f; private var shootY = 0f

    fun spawn(b: Bit, first: Boolean) {
        b.depth = r(0.45f, 1f)
        b.x = r(-0.05f, 1.05f)
        b.phase = r(0f, 6.28f)
        b.tint = rnd.nextInt(4)
        b.rot = r(0f, 360f)
        b.spin = r(-40f, 40f)
        b.flipW = r(0.8f, 2.2f)
        b.swayW = r(0.4f, 1.1f)
        val speed = 0.6f + 0.4f * b.depth  // nearer = faster
        when (kind) {
            Seasons.SAKURA -> { b.size = r(11f, 16f) * (0.55f + 0.45f * b.depth); b.vy = r(0.030f, 0.050f) * speed; b.swayAmp = r(10f, 26f); b.y = if (first) r(-0.05f, 1f) else r(-0.12f, -0.04f) }
            Seasons.LEAVES -> { b.size = r(15f, 22f) * (0.55f + 0.45f * b.depth); b.vy = r(0.045f, 0.07f) * speed; b.swayAmp = r(18f, 40f); b.spin = r(-70f, 70f); b.y = if (first) r(-0.05f, 1f) else r(-0.12f, -0.04f) }
            Seasons.SNOW -> { b.size = r(1.8f, 4.2f) * (0.5f + 0.5f * b.depth); b.vy = r(0.020f, 0.040f) * speed; b.swayAmp = r(6f, 16f); b.y = if (first) r(-0.05f, 1f) else r(-0.08f, -0.02f) }
            Seasons.MOTES -> { b.size = r(6f, 13f) * (0.5f + 0.5f * b.depth); b.vy = -r(0.008f, 0.016f); b.swayAmp = r(8f, 20f); b.y = if (first) r(0f, 1.05f) else r(1.02f, 1.08f) }
            Seasons.FIREFLIES -> { b.size = r(9f, 14f) * b.depth; b.y = r(0.1f, 1f); b.vx = r(-0.02f, 0.02f); b.vy = r(-0.015f, 0.015f) }
        }
    }

    fun step(dt: Float) {
        time += dt
        val wind = 0.004f + 0.006f * sin(time * 0.06f)
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
                if (b.y > 1.06f || b.x > 1.12f) spawn(b, first = false)
            }
        }
        if (stars.isNotEmpty() && time > shootAt + 1.2f) {
            shootAt = time + r(14f, 40f)
            shootX = r(0.35f, 1f); shootY = r(0.02f, 0.25f)
        }
    }

    fun draw(d: DrawScope) = with(d) {
        val w = size.width; val h = size.height; val dp = density
        if (stars.isNotEmpty()) drawStars(w, h, dp)
        for (b in bits) {
            val cx = b.x * w + sin(time * b.swayW + b.phase) * b.swayAmp * dp
            val cy = b.y * h
            when (kind) {
                Seasons.SAKURA -> {
                    val flip = cos(time * b.flipW + b.phase)
                    val c = lerp(Color(0xFFFFA9C2), Color(0xFFFFE3EC), (flip + 1) / 2).copy(alpha = 0.45f + 0.4f * b.depth)
                    translate(cx, cy) {
                        rotate(b.rot, Offset.Zero) {
                            scale(b.size * dp * (0.3f + 0.7f * abs(flip)), b.size * dp, Offset.Zero) { drawPath(PETAL, c) }
                        }
                    }
                }
                Seasons.LEAVES -> {
                    val flip = cos(time * b.flipW + b.phase)
                    val base = LEAF_COLORS[b.tint]
                    val c = lerp(base, Color(0xFF5A2E14), (1 - abs(flip)) * 0.3f).copy(alpha = 0.42f + 0.4f * b.depth)
                    translate(cx, cy) {
                        rotate(b.rot, Offset.Zero) {
                            scale(b.size * dp * (0.25f + 0.75f * abs(flip)), b.size * dp, Offset.Zero) {
                                drawPath(LEAF, c)
                                drawLine(Color(0xFF4A2410).copy(alpha = c.alpha * 0.7f), Offset(0f, -0.42f), Offset(0f, 0.62f), strokeWidth = 0.06f, cap = StrokeCap.Round)
                            }
                        }
                    }
                }
                Seasons.SNOW -> {
                    val r = b.size * dp
                    val a = 0.3f + 0.5f * b.depth
                    if (b.depth > 0.8f) drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = a), Color.Transparent), Offset(cx, cy), r * 1.8f), r * 1.8f, Offset(cx, cy))
                    else drawCircle(Color.White.copy(alpha = a), r, Offset(cx, cy))
                }
                Seasons.MOTES -> {
                    val r = b.size * dp
                    val a = (0.07f + 0.13f * (0.5f + 0.5f * sin(time * b.flipW * 0.7f + b.phase))) * (0.5f + 0.5f * b.depth)
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFE9B0).copy(alpha = a), Color.Transparent), Offset(cx, cy), r), r, Offset(cx, cy))
                }
                Seasons.FIREFLIES -> {
                    val blink = (0.5f + 0.5f * sin(time * b.flipW + b.phase)).pow(3)
                    val r = b.size * dp
                    val x = b.x * w; val y = b.y * h
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFD9FF7A).copy(alpha = 0.42f * blink), Color.Transparent), Offset(x, y), r), r, Offset(x, y))
                    drawCircle(Color(0xFFF6FFC4).copy(alpha = 0.9f * blink), 1.6f * dp, Offset(x, y))
                }
            }
        }
    }

    private fun DrawScope.drawStars(w: Float, h: Float, dp: Float) {
        for (s in stars) {
            val tw = 0.5f + 0.5f * sin(time * s.tw + s.phase)
            val a = 0.12f + 0.5f * tw * (s.r / 1.5f)
            val c = (if (s.warm) Color(0xFFFFF0D2) else Color(0xFFE6F0FF)).copy(alpha = a)
            val p = Offset(s.x * w, s.y * h)
            drawCircle(c, s.r * dp, p)
            if (s.r > 1.25f) {  // the brightest ones get a small four-point sparkle
                val k = s.r * dp * (2.5f + 2f * tw)
                drawLine(c.copy(alpha = a * 0.6f), Offset(p.x - k, p.y), Offset(p.x + k, p.y), 0.6f * dp)
                drawLine(c.copy(alpha = a * 0.6f), Offset(p.x, p.y - k), Offset(p.x, p.y + k), 0.6f * dp)
            }
        }
        val t = time - shootAt
        if (t in 0f..1.0f) {  // a shooting star: about a second, down and to the left
            val fade = sin(PI.toFloat() * t)
            val hx = (shootX - 0.32f * t) * w; val hy = (shootY + 0.14f * t) * h
            val tail = 0.35f * max(0.3f, fade)  // seconds of path behind the head
            val tx = hx + 0.32f * w * tail; val ty = hy - 0.14f * h * tail
            drawLine(Brush.linearGradient(listOf(Color.White.copy(alpha = 0.75f * fade), Color.Transparent), Offset(hx, hy), Offset(tx, ty)),
                Offset(hx, hy), Offset(tx, ty), 1.4f * dp, cap = StrokeCap.Round)
        }
    }

    companion object {
        /** A petal 1 unit long with the little notch at its tip, centred on 0, 0. */
        val PETAL = Path().apply {
            moveTo(0f, 0.5f)
            cubicTo(0.36f, 0.3f, 0.4f, -0.25f, 0.15f, -0.5f)
            lineTo(0f, -0.37f)
            lineTo(-0.15f, -0.5f)
            cubicTo(-0.4f, -0.25f, -0.36f, 0.3f, 0f, 0.5f)
            close()
        }
        val LEAF = Path().apply {
            moveTo(0f, -0.5f)
            cubicTo(0.34f, -0.28f, 0.32f, 0.26f, 0f, 0.5f)
            cubicTo(-0.32f, 0.26f, -0.34f, -0.28f, 0f, -0.5f)
            close()
        }
        val LEAF_COLORS = listOf(Color(0xFFE8A23A), Color(0xFFE07035), Color(0xFFC9452F), Color(0xFFD49A4B))
    }
}
