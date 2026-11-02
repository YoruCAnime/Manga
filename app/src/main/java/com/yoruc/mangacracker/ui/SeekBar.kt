package com.yoruc.mangacracker.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.clock

/**
 * The player's seek bar: a 48 dp touch strip around a slim track. Touch anywhere on it = jump there and keep dragging;
 * while dragging the track and thumb grow and a time bubble rides above the finger. [onScrub] fires while dragging
 * (the player shows key frames on the way), [onSeek] once on release (exact), [onDrag] when dragging starts / stops.
 */
private val INSET = 11.dp

@Composable
fun SeekBar(
    pos: Long, dur: Long, buffered: Long, modifier: Modifier = Modifier,
    onDrag: (Boolean) -> Unit = {}, onScrub: (Long) -> Unit = {}, onSeek: (Long) -> Unit,
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val d by rememberUpdatedState(dur)
    val scrub by rememberUpdatedState(onScrub)
    val seek by rememberUpdatedState(onSeek)
    val dragCb by rememberUpdatedState(onDrag)
    val frac = drag ?: if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
    val thumb by animateDpAsState(if (drag != null) 22.dp else 13.dp, label = "thumb")
    val track by animateDpAsState(if (drag != null) 7.dp else 4.dp, label = "track")
    BoxWithConstraints(
        modifier.height(48.dp).semantics { contentDescription = "Seek bar" }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val inset = INSET.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                down.consume()
                drag = at(down.position.x); dragCb(true); scrub((drag!! * d).toLong())
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    val f = at(c.position.x)
                    if (f != drag) { drag = f; scrub((f * d).toLong()) }
                    c.consume()
                }
                val f = drag
                drag = null; dragCb(false)
                if (f != null) seek((f * d).toLong())
            }
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        val bufFrac = if (dur > 0) (buffered.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Canvas(Modifier.fillMaxSize()) {
            val th = track.toPx(); val r = thumb.toPx() / 2
            val left = INSET.toPx(); val w = size.width - 2 * left   // the thumb stays inside the strip at both ends
            val y = size.height / 2 - th / 2
            val cr = CornerRadius(th / 2)
            drawRoundRect(Color(0x40FFFFFF), Offset(left, y), Size(w, th), cr)
            drawRoundRect(Color(0x66FFFFFF), Offset(left, y), Size(w * bufFrac, th), cr)
            drawRoundRect(Mc.Blue, Offset(left, y), Size(w * frac, th), cr)
            drawCircle(Mc.Blue, r, Offset(left + w * frac, size.height / 2))
            if (drag != null) drawCircle(Color(0x553DB4F2), r * 1.9f, Offset(left + w * frac, size.height / 2))
        }
        drag?.let { f ->
            // time bubble above the finger, kept on screen
            val bw = 64.dp
            val x = (INSET + (maxWidth - INSET * 2) * f - bw / 2).coerceIn(0.dp, maxWidth - bw)
            Box(Modifier.offset(x = x, y = (-36).dp).width(bw).clip(RoundedCornerShape(8.dp)).background(Color(0xE60B1622)).padding(vertical = 5.dp)) {
                Text(clock((f * dur).toLong() / 1000), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.align(Alignment.Center).width(bw))
            }
        }
    }
}

/**
 * The player's own volume, in the seek bar's style (white instead of blue): touch anywhere = set it there and keep
 * dragging. [onChange] fires while dragging, [onDone] once on release (that is when it is saved).
 */
@Composable
fun VolumeBar(value: Float, modifier: Modifier = Modifier, onChange: (Float) -> Unit, onDone: () -> Unit) {
    var drag by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val thumb by animateDpAsState(if (drag) 18.dp else 12.dp, label = "vthumb")
    Canvas(
        modifier.height(48.dp).semantics { contentDescription = "Volume" }.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown()
                val inset = INSET.toPx()
                fun at(x: Float) = ((x - inset) / (size.width - 2 * inset)).coerceIn(0f, 1f)
                down.consume()
                drag = true; change(at(down.position.x))
                while (true) {
                    val ev = awaitPointerEvent()
                    val c = ev.changes.firstOrNull { it.id == down.id } ?: break
                    if (!c.pressed) { c.consume(); break }
                    change(at(c.position.x))
                    c.consume()
                }
                drag = false; done()
            }
        },
    ) {
        val th = 4.dp.toPx(); val r = thumb.toPx() / 2
        val left = INSET.toPx(); val w = size.width - 2 * left
        val y = size.height / 2 - th / 2
        val cr = CornerRadius(th / 2)
        drawRoundRect(Color(0x40FFFFFF), Offset(left, y), Size(w, th), cr)
        drawRoundRect(Color.White, Offset(left, y), Size(w * value.coerceIn(0f, 1f), th), cr)
        drawCircle(Color.White, r, Offset(left + w * value.coerceIn(0f, 1f), size.height / 2))
    }
}
