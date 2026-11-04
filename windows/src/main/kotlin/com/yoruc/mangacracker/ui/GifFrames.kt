package com.yoruc.mangacracker.ui

import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.gif.FrameSource
import com.yoruc.mangacracker.gif.Resample
import com.yoruc.mangacracker.platform.Pixels
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Window frames numbered [from]..[to] (every frame keeps its own number, #1 = the strip's first frame, so deleting
 * doesn't renumber the rest), minus the ones in [gone]. Either order; a range running past an end stops there, one wholly outside picks nothing.
 */
internal fun framesByNumber(count: Int, gone: Set<Int>, from: Int, to: Int): Set<Int> {
    if (count <= 0) return emptySet()
    // a range wholly outside the row picks nothing (clamping it made "900 to 950" delete the last frame)
    if (maxOf(from, to) < 1 || minOf(from, to) > count) return emptySet()
    val lo = minOf(from, to).coerceIn(1, count); val hi = maxOf(from, to).coerceIn(1, count)
    return (lo - 1..hi - 1).filter { it !in gone }.toSet()
}

/** "Select between": every frame (not deleted) from the first picked one to the last. */
internal fun fillBetween(sel: Set<Int>, gone: Set<Int>): Set<Int> =
    if (sel.size < 2) sel else (sel.min()..sel.max()).filter { it !in gone }.toSet()

/**
 * Which of the GIF's full-size frames to keep: frame j (at [bigFps], starting at [from] s) is left out when it falls in
 * a window frame (at [winFps], the window starting at [winStart] s) listed in [cut]. The two readings can have
 * different frame rates (60 fps for slow motion), so this goes by time.
 */
internal fun keptBigFrames(count: Int, bigFps: Double, from: Double, winStart: Double, winFps: Double, cut: Set<Int>): IntArray =
    (0 until count).filter { j -> kotlin.math.floor((from + j / bigFps - winStart) * winFps + 1e-6).toInt() !in cut }.toIntArray()

/** m:ss.cc, a frame's time (hundredths: frames at 24-30 fps are 3-4 hundredths apart). */
internal fun clockC(s: Double): String {
    val cs = Math.round(s * 100).coerceAtLeast(0)
    return "%d:%02d.%02d".format(cs / 6000, cs / 100 % 60, cs % 100)
}

/**
 * Every frame around the picked part one by one (like ScreenToGif's frame list), each with its number and time; the
 * GIF's part (Start..End) is bright with START / END marks, the rest dimmed. Plain clicks only: a click shows the
 * frame and picks / unpicks it. Buttons: Set Start / Set End (at the shown frame), From .. to numbers (Pick them, or
 * Delete them straight away), Select between (first to last picked), All (the part), None, Delete, Undo, Restore all.
 */
@Composable
internal fun FramesPanel(
    frames: FrameSource, gone: Set<Int>, a: Int, b: Int, sel: Set<Int>, head: Int, playing: Boolean, timeOf: (Int) -> String,
    canUndo: Boolean, anyDeleted: Boolean,
    onSelect: (Set<Int>) -> Unit, onShow: (Int) -> Unit, onSetStart: () -> Unit, onSetEnd: () -> Unit,
    onDelete: (Set<Int>) -> Unit, onUndo: () -> Unit, onRestore: () -> Unit, onTyping: (Boolean) -> Unit, modifier: Modifier = Modifier,
) {
    val list = rememberLazyListState()
    var fromText by remember { mutableStateOf("") }
    var toText by remember { mutableStateOf("") }
    val shownFrames = remember(frames.count, gone) { (0 until frames.count).filter { it !in gone } }
    // small pictures of the frames seen lately (~37 KB each)
    val cache = remember(frames) {
        object : LinkedHashMap<Int, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, ImageBitmap>?) = size > 240
        }
    }
    fun typed(): Set<Int>? {
        val from = fromText.toIntOrNull() ?: return null
        return framesByNumber(frames.count, gone, from, toText.toIntOrNull() ?: from)
    }
    fun outside() = com.yoruc.mangacracker.platform.Desktop.toast("No frames there: this row has #1 – #${frames.count}")
    fun pickTyped() { typed()?.let { s -> if (s.isEmpty()) outside() else { onSelect(s); s.minOrNull()?.let(onShow) } } }
    // opening (or Earlier / Later): the row starts just before the part
    LaunchedEffect(frames) { shownFrames.indexOfFirst { it >= a }.takeIf { it >= 0 }?.let { list.scrollToItem((it - 2).coerceAtLeast(0)) } }
    // the shown frame (a click on the strip, the arrows, Start / End) is brought into view when it's off the row
    LaunchedEffect(head, playing, shownFrames) {
        if (playing) return@LaunchedEffect
        val k = shownFrames.indexOf(head)
        if (k >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == k }) list.animateScrollToItem((k - 3).coerceAtLeast(0))
    }

    Column(modifier.clip(RoundedCornerShape(8.dp)).background(Mc.Card).padding(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        // the frame the "before / after" buttons go from: the first / last picked one, or the one shown
        val fromFrame = if (sel.isEmpty()) head else sel.min()
        val toFrame = if (sel.isEmpty()) head else sel.max()
        val before = (0 until fromFrame).filter { it !in gone }
        val after = (toFrame + 1 until frames.count).filter { it !in gone }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            PanelBtn("Set Start", color = Mc.Blue, onClick = onSetStart)
            PanelBtn("Set End", color = Mc.Blue, onClick = onSetEnd)
            Group {
                // while the preview plays the shown frame keeps changing: these wait for a frame to be clicked
                val still = !playing || sel.isNotEmpty()
                PanelBtn(if (still) "Delete all before #${fromFrame + 1}" else "Delete all before", enabled = still && before.isNotEmpty(),
                    color = Mc.Orange, icon = Icons.Rounded.KeyboardDoubleArrowLeft) { onDelete(before.toSet()) }
                PanelBtn(if (still) "Delete all after #${toFrame + 1}" else "Delete all after", enabled = still && after.isNotEmpty(),
                    color = Mc.Orange, iconAfter = Icons.Rounded.KeyboardDoubleArrowRight) { onDelete(after.toSet()) }
            }
            Spacer(Modifier.weight(1f))
            PanelBtn("Undo", enabled = canUndo, onClick = onUndo)
            PanelBtn("Restore all", enabled = anyDeleted, onClick = onRestore)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // reads as a sentence: "Delete frames [first #] to [last #] → Delete" (the numbers under the squares)
            Group {
                Text("Delete frames", color = Mc.Text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                NumberField(fromText, "first #", { fromText = it }, onTyping, ::pickTyped)
                Text("to", color = Mc.Text2, fontSize = 12.sp)
                NumberField(toText, "last #", { toText = it }, onTyping, ::pickTyped)
                PanelBtn("Delete", enabled = fromText.isNotEmpty(), color = Mc.Orange) { typed()?.let { s -> if (s.isEmpty()) outside() else onDelete(s) } }
                PanelBtn("Pick only", enabled = fromText.isNotEmpty(), onClick = ::pickTyped)
            }
            // the frames clicked in the row
            Group {
                // fixed width: the buttons after it must not move as the count changes
                Text(if (sel.isEmpty()) "Click or drag across squares" else "Picked: ${sel.size} frame${if (sel.size == 1) "" else "s"}",
                    color = Mc.Text2, fontSize = 12.sp, maxLines = 1, modifier = Modifier.width(172.dp))
                PanelBtn("Select between", enabled = sel.size >= 2) { onSelect(fillBetween(sel, gone)) }
                PanelBtn("All", enabled = a < b) { onSelect((a..b).filter { it !in gone }.toSet()) }
                PanelBtn("None", enabled = sel.isNotEmpty()) { onSelect(emptySet()) }
                PanelBtn("Delete picked", enabled = sel.isNotEmpty(), color = Mc.Orange) { onDelete(sel) }
            }
        }
        val selNow by rememberUpdatedState(sel)
        val framesNow by rememberUpdatedState(shownFrames)
        val select by rememberUpdatedState(onSelect)
        val show by rememberUpdatedState(onShow)
        LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth().height(100.dp)
            // the mouse wheel scrolls the row sideways (a row doesn't take the up / down wheel by itself)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        if (e.type != PointerEventType.Scroll) continue
                        val d = e.changes.firstOrNull()?.scrollDelta ?: continue
                        list.dispatchRawDelta((d.y + d.x) * 120.dp.toPx())
                        e.changes.forEach { it.consume() }
                    }
                }
            }
            // a click picks / unpicks a square; pressing and sliding across squares picks that whole run
            .pointerInput(Unit) {
                fun at(x: Float) = list.layoutInfo.visibleItemsInfo.firstOrNull { x >= it.offset && x < it.offset + it.size }?.index
                awaitEachGesture {
                    val down = awaitFirstDown()          // the ✕ on a square takes its own clicks
                    val k0 = at(down.position.x) ?: return@awaitEachGesture
                    val fs = framesNow
                    if (k0 !in fs.indices) return@awaitEachGesture
                    down.consume()
                    var last = k0; var dragged = false
                    while (true) {
                        val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        val k = at(c.position.x)
                        if (k != null && k in fs.indices && k != last) {
                            dragged = true; last = k
                            select(fs.subList(minOf(k0, k), maxOf(k0, k) + 1).toSet()); show(fs[k])
                        }
                        c.consume()
                    }
                    if (!dragged) { val i = fs[k0]; select(if (i in selNow) selNow - i else selNow + i); show(i) }
                }
            }) {
            items(shownFrames.size, key = { shownFrames[it] }) { k ->
                val i = shownFrames[k]
                FrameCard(frames, i, timeOf(i), inPart = i in a..b, mark = if (i == a) "START" else if (i == b) "END" else null,
                    picked = i in sel, shown = i == head, cache = cache, onDeleteOne = { onDelete(setOf(i)) })
            }
        }
        HorizontalScrollbar(rememberScrollbarAdapter(list), Modifier.fillMaxWidth(), style = ScrollbarStyle(
            minimalHeight = 24.dp, thickness = 8.dp, shape = RoundedCornerShape(4.dp), hoverDurationMillis = 200,
            unhoverColor = Color.White.copy(alpha = 0.28f), hoverColor = Mc.Blue))
    }
}

@Composable
private fun FrameCard(
    f: FrameSource, i: Int, time: String, inPart: Boolean, mark: String?, picked: Boolean, shown: Boolean,
    cache: MutableMap<Int, ImageBitmap>, onDeleteOne: () -> Unit,
) {
    var pic by remember(f, i) { mutableStateOf(synchronized(cache) { cache[i] }) }
    if (pic == null) LaunchedEffect(f, i) {
        // a read that fails (the editor just loaded other frames) leaves the square blank instead of closing the app
        pic = withContext(Dispatchers.Default) {
            runCatching {
                val tw = 128; val th = (tw * f.height / f.width.coerceAtLeast(1)).coerceAtLeast(1)
                val px = Resample.resize(f.frame(i), f.width, f.height, tw, th)
                for (k in px.indices) px[k] = px[k] or (0xFF shl 24)
                Pixels.image(px, tw, th)
            }.getOrNull()
        }?.also { p -> synchronized(cache) { cache[i] = p } }
    }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.width(112.dp).alpha(if (inPart || picked || shown) 1f else 0.45f).clip(shape)
            .background(if (picked) Color(0x553DB4F2) else Mc.Card2)
            .border(2.dp, if (picked) Mc.Blue else if (shown) Color(0xFFFF5C8A) else Color.Transparent, shape)
            .padding(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(f.width.toFloat() / f.height.coerceAtLeast(1)).background(Color.Black)) {
            pic?.let { Image(it, "Frame ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            mark?.let { m ->
                Text(m, color = Mc.Bg, fontSize = 9.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.TopStart).background(Mc.Blue).padding(horizontal = 4.dp, vertical = 1.dp))
            }
            // one click deletes just this frame
            Box(Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(RoundedCornerShape(10.dp))
                .background(Color(0xCC0B1622)).clickable(onClick = onDeleteOne), contentAlignment = Alignment.Center) {
                Text("✕", color = Color(0xFFFF8A80), fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${i + 1}", color = if (picked) Mc.Text else Mc.Text2, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            Text(time, color = Mc.Text3, fontSize = 10.sp)
        }
    }
}

/** A few controls that belong together, on a slightly lighter strip. */
@Composable
private fun Group(content: @Composable () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(Color(0x14FFFFFF)).padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) { content() }
}

/**
 * A number box with a light border and a grey [hint] inside while empty (easy to see); Enter picks. [onTyping] tells
 * the editor to leave the keys (Delete, arrows) to the box.
 */
@Composable
private fun NumberField(value: String, hint: String, onChange: (String) -> Unit, onTyping: (Boolean) -> Unit, onEnter: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    androidx.compose.runtime.DisposableEffect(Unit) { onDispose { if (focused) onTyping(false) } }
    BasicTextField(
        value, { v -> onChange(v.filter { it.isDigit() }.take(4)) }, singleLine = true,
        textStyle = TextStyle(color = Mc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold), cursorBrush = SolidColor(Mc.Blue),
        modifier = Modifier.width(64.dp).clip(RoundedCornerShape(6.dp)).background(Mc.Bg)
            .border(1.5.dp, if (focused) Mc.Blue else Color(0x66FFFFFF), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
            .onFocusChanged { focused = it.isFocused; onTyping(it.isFocused) }
            // before the box's own keys: a one-line box would swallow Enter
            .onPreviewKeyEvent { e -> if (e.type == KeyEventType.KeyDown && e.key == Key.Enter) { onEnter(); true } else false },
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, color = Mc.Text3, fontSize = 12.sp, maxLines = 1)
                inner()
            }
        },
    )
}

@Composable
private fun PanelBtn(
    label: String, enabled: Boolean = true, color: Color = Mc.Text, icon: ImageVector? = null, iconAfter: ImageVector? = null,
    onClick: () -> Unit,
) {
    val c = if (enabled) color else Mc.Text3
    Row(Modifier.clip(RoundedCornerShape(6.dp)).background(Mc.Card2).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 9.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = c, modifier = Modifier.size(16.dp).padding(end = 2.dp)) }
        Text(label, color = c, fontSize = 12.sp, maxLines = 1)
        iconAfter?.let { Icon(it, null, tint = c, modifier = Modifier.size(16.dp).padding(start = 2.dp)) }
    }
}
