package com.yoruc.mangacracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.GifClip
import com.yoruc.mangacracker.video.GifDone
import com.yoruc.mangacracker.video.clock
import kotlinx.coroutines.Job
import kotlin.math.abs

/** The GIF maker's state while its panel is open: the captured span, the chosen range and settings, the job. */
class GifEdit(val spanStart: Long, val spanEnd: Long) {
    var a by mutableLongStateOf(maxOf(spanStart, spanEnd - GifClip.MAX_RANGE_MS))
    var b by mutableLongStateOf(spanEnd)
    var speed by mutableFloatStateOf(1f)
    var pingPong by mutableStateOf(false)
    var size by mutableStateOf(GifSize.SMALL)
    var thumbA by mutableStateOf<ImageBitmap?>(null)
    var thumbB by mutableStateOf<ImageBitmap?>(null)
    /** What the maker is doing (null = not working) and how far it is (0..1). */
    var working by mutableStateOf<String?>(null)
    var fraction by mutableFloatStateOf(0f)
    var result by mutableStateOf<GifDone?>(null)
    var job: Job? = null

    /** Seconds the GIF will last. */
    val gifSeconds get() = (b - a) / 1000.0 / speed * (if (pingPong) 2 else 1)

    /** Moves one end ([which] 0 = start, 1 = end), keeping the range between 0.3 s and [GifClip.MAX_RANGE_MS]. */
    fun move(which: Int, ms: Long) {
        val v = ms.coerceIn(spanStart, spanEnd)
        if (which == 0) {
            a = v.coerceAtMost(b - MIN_RANGE).coerceAtLeast(spanStart)
            if (b - a > GifClip.MAX_RANGE_MS) b = a + GifClip.MAX_RANGE_MS
        } else {
            b = v.coerceAtLeast(a + MIN_RANGE).coerceAtMost(spanEnd)
            if (b - a > GifClip.MAX_RANGE_MS) a = b - GifClip.MAX_RANGE_MS
        }
    }

    companion object { const val MIN_RANGE = 300L }
}

val GIF_SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.5f, 2f)

/** "1:02.4" */
fun tenths(ms: Long) = "${clock(ms / 1000)}.${(ms % 1000) / 100}"

private fun secs(s: Double) = if (s < 10) String.format("%.1f s", s) else "${s.toInt()} s"

/** Red dot + running time while the GIF button is capturing. */
@Composable
fun GifRecBadge(elapsedMs: Long, modifier: Modifier = Modifier) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF3B3B)))
        Spacer(Modifier.width(8.dp))
        Text("GIF  ${clock(elapsedMs / 1000)} / ${clock(GifClip.MAX_CAPTURE_MS / 1000)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        Text("   tap GIF to stop", color = Color(0xB3FFFFFF), fontSize = 12.sp)
    }
}

/**
 * The GIF panel inside the player (not a pop-up): pick the range over the captured span (the video behind shows the
 * frame under the handle being dragged; small pictures show both ends), speed, ping-pong and size, then Make GIF;
 * progress here; then the finished GIF playing with its size, Save and Share.
 */
@Composable
fun GifPanel(
    g: GifEdit, modifier: Modifier = Modifier,
    onPreview: (ms: Long, exact: Boolean, which: Int) -> Unit, onMake: () -> Unit, onCancelMake: () -> Unit,
    onSave: () -> Unit, onShare: () -> Unit, onClose: () -> Unit,
) {
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp)).background(Color(0xF2151F2E))
        .padding(horizontal = 16.dp, vertical = 8.dp)) {
        // header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.GifBox, null, tint = Mc.Blue, modifier = Modifier.size(26.dp))
            Spacer(Modifier.width(6.dp))
            Text("Make a GIF", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.width(14.dp))
            Text("${secs((g.b - g.a) / 1000.0)} picked  →  GIF ${secs(g.gifSeconds)}" + if (g.speed != 1f) "  (${speedText(g.speed)})" else "",
                color = Mc.Text2, fontSize = 13.sp)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close GIF maker", tint = Mc.Text2) }
        }
        val res = g.result
        val work = g.working
        when {
            work != null -> Column(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("$work…", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Spacer(Modifier.width(8.dp))
                    Text("${(g.fraction * 100).toInt()}%", color = Mc.Text3, fontSize = 13.sp)
                    Spacer(Modifier.weight(1f))
                    Chip("Cancel", false, onCancelMake)
                }
                LinearProgressIndicator(progress = { g.fraction.coerceIn(0f, 1f) }, color = Mc.Blue, trackColor = Mc.Line,
                    modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
                Text("${speedText(g.speed)}${if (g.pingPong) " · ping-pong" else ""} · ${g.size.label} (up to ${g.size.maxBytes / 1_000_000} MB)",
                    color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            res != null -> Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.height(150.dp).aspectRatio(res.width.toFloat() / res.height).clip(RoundedCornerShape(10.dp)).background(Color.Black)) {
                    AsyncImage(model = res.file, contentDescription = "GIF preview", contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
                }
                Column(Modifier.padding(start = 16.dp).weight(1f)) {
                    Text(String.format("%.2f MB", res.bytes / 1_000_000.0), color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("${res.width} × ${res.height} · ${secs(res.durationMs / 1000.0)} · ${res.fps.toInt()} fps · loops",
                        color = Mc.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigChip(Icons.Rounded.Download, "Save", Mc.Blue, onSave)
                        BigChip(Icons.Rounded.Share, "Share", Mc.Card2, onShare)
                        BigChip(Icons.Rounded.Edit, "Change", Mc.Card2) { g.result = null }
                    }
                }
            }
            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Thumb(g.thumbA, "Start ${tenths(g.a)}")
                    RangeBar(g, onPreview, Modifier.weight(1f).padding(horizontal = 10.dp))
                    Thumb(g.thumbB, "End ${tenths(g.b)}")
                }
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Speed", color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    GIF_SPEEDS.forEach { s -> Chip(speedText(s), g.speed == s) { g.speed = s } }
                    Spacer(Modifier.width(10.dp))
                    Chip("⇄  Ping-pong", g.pingPong) { g.pingPong = !g.pingPong }
                }
                Row(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Size", color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.width(44.dp))
                    GifSize.entries.forEach { s -> Chip("${s.label} · ${s.maxBytes / 1_000_000} MB", g.size == s) { g.size = s } }
                    Spacer(Modifier.weight(1f))
                    BigChip(Icons.Rounded.GifBox, "Make GIF", Mc.Blue, onMake)
                }
            }
        }
    }
}

fun speedText(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

@Composable
private fun Chip(text: String, selected: Boolean, onClick: () -> Unit) {
    Text(text, color = if (selected) Mc.Bg else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) Mc.Blue else Mc.Card2).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp))
}

@Composable
private fun BigChip(icon: ImageVector, text: String, bg: Color, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically) {
        val fg = if (bg == Mc.Blue) Mc.Bg else Color.White
        Icon(icon, null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Thumb(img: ImageBitmap?, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.width(96.dp).height(54.dp).clip(RoundedCornerShape(6.dp)).background(Mc.Card2)) {
            if (img != null) Image(img, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        Text(label, color = Mc.Text2, fontSize = 11.5.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/** The captured span with the chosen range; drag either handle (or touch near it). */
@Composable
private fun RangeBar(g: GifEdit, onPreview: (Long, Boolean, Int) -> Unit, modifier: Modifier) {
    val preview by rememberUpdatedState(onPreview)
    BoxWithConstraints(modifier.height(54.dp).semantics { contentDescription = "GIF range" }.pointerInput(g) {
        awaitEachGesture {
            val down = awaitFirstDown()
            val span = (g.spanEnd - g.spanStart).coerceAtLeast(1)
            val pad = 12.dp.toPx(); val w = size.width - 2 * pad
            fun ms(x: Float) = g.spanStart + ((x - pad) / w * span).toLong()
            fun px(t: Long) = pad + (t - g.spanStart).toFloat() / span * w
            val which = if (abs(down.position.x - px(g.a)) <= abs(down.position.x - px(g.b))) 0 else 1
            down.consume()
            g.move(which, ms(down.position.x)); preview(if (which == 0) g.a else g.b, false, which)
            while (true) {
                val ev = awaitPointerEvent()
                val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                if (!c.pressed) { c.consume(); break }
                g.move(which, ms(c.position.x)); preview(if (which == 0) g.a else g.b, false, which)
                c.consume()
            }
            preview(if (which == 0) g.a else g.b, true, which)
        }
    }) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = 12.dp.toPx(); val w = size.width - 2 * pad
            val span = (g.spanEnd - g.spanStart).coerceAtLeast(1).toFloat()
            val xa = pad + (g.a - g.spanStart) / span * w; val xb = pad + (g.b - g.spanStart) / span * w
            val top = size.height * 0.18f; val h = size.height * 0.64f
            drawRoundRect(Color(0x26FFFFFF), Offset(pad, top), Size(w, h), CornerRadius(8.dp.toPx()))
            // one tick a second
            val secs = (span / 1000).toInt()
            if (secs in 1..60) for (s in 1..secs) {
                val x = pad + (s * 1000f) / span * w
                drawLine(Color(0x33FFFFFF), Offset(x, top + h * 0.3f), Offset(x, top + h * 0.7f), 1.dp.toPx())
            }
            drawRoundRect(Mc.Blue.copy(alpha = 0.22f), Offset(xa, top), Size(xb - xa, h))
            drawRoundRect(Mc.Blue, Offset(xa, top), Size(xb - xa, h), style = Stroke(2.dp.toPx()))
            for (x in listOf(xa, xb)) {
                val hw = 7.dp.toPx()
                drawRoundRect(Mc.Blue, Offset(x - hw, 0f), Size(hw * 2, size.height), CornerRadius(hw))
                drawLine(Mc.Bg, Offset(x, size.height * 0.35f), Offset(x, size.height * 0.65f), 2.dp.toPx())
            }
        }
    }
}
