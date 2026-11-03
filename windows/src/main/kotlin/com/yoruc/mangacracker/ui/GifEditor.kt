package com.yoruc.mangacracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.gif.FileFrames
import com.yoruc.mangacracker.gif.GIF_FPS
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.gif.GifSize
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.platform.Pixels
import com.yoruc.mangacracker.platform.Rife
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** What the editor needs from the player. [key] = the video's (where its last GIF was is remembered by it). */
class GifSource(
    val input: List<String>, val width: Int, val height: Int, val fps: Double, val duration: Double, val title: String,
    val key: String = "",
)

/** Start / end (s) of the last GIF made of each video, so the next GIF of it opens there. */
private object GifSpots {
    private val file get() = File(Desktop.ctx.filesDir, "data/gif_spots.properties")
    private val props by lazy { java.util.Properties().apply { runCatching { file.inputStream().use { load(it) } } } }

    @Synchronized fun get(key: String): Pair<Double, Double>? {
        if (key.isEmpty()) return null
        val v = props.getProperty(key)?.split(' ')?.mapNotNull { it.toDoubleOrNull() } ?: return null
        return if (v.size == 2 && v[1] > v[0]) v[0] to v[1] else null
    }

    @Synchronized fun put(key: String, from: Double, to: Double) {
        if (key.isEmpty()) return
        props.setProperty(key, "%.3f %.3f".format(java.util.Locale.US, from, to))
        runCatching { file.parentFile.mkdirs(); file.outputStream().use { props.store(it, null) } }
    }
}

private const val WINDOW_S = 36.0          // the filmstrip shows this much of the video (a 30 s GIF and some around it)
private const val PREVIEW_LONG = 480       // preview frames' long side
private const val FRAMES_BUDGET = 450_000_000L   // bytes of full-size frames for the GIF (long ones are read smaller)

/**
 * The PC GIF maker: a filmstrip of [WINDOW_S] seconds around where the video was, start and end handles to drag,
 * a looping preview of exactly what the GIF will be, keys for frame-exact picking, size / speed / ping-pong, then the
 * phone's GIF engine at full GIF size. Click Start or End: the preview shows that frame; then click the strip: that end
 * moves to the clicked frame. Keys: ← → move the active handle one frame (Shift 10), Tab swaps handle, [ / ] put
 * start / end at the preview spot, Space plays / pauses the preview, Esc closes.
 */
@Composable
fun GifEditor(src: GifSource, at: Double, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // a GIF of this video made before: open on that part again (the strip centred on it)
    var resume by remember { mutableStateOf(GifSpots.get(src.key)) }
    var winStart by remember {
        mutableStateOf(resume?.let { (f, t) -> (f - (WINDOW_S - (t - f)) / 2).coerceAtLeast(0.0) } ?: (at - WINDOW_S * 2 / 3).coerceAtLeast(0.0))
    }
    val resumed = remember { resume != null }
    var frames by remember { mutableStateOf<FileFrames?>(null) }
    var loading by remember { mutableStateOf<Float?>(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var a by remember { mutableIntStateOf(0) }           // start frame (in the window)
    var b by remember { mutableIntStateOf(0) }           // end frame (inclusive)
    var head by remember { mutableIntStateOf(0) }        // preview frame
    var active by remember { mutableIntStateOf(1) }      // 0 = start handle, 1 = end handle
    var playing by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(GifSize.SMALL) }
    var speed by remember { mutableStateOf(1f) }
    var pingPong by remember { mutableStateOf(false) }
    var gifFps by remember { mutableIntStateOf(0) }      // 0 = auto
    var making by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var done by remember { mutableStateOf<File?>(null) }
    var thumbs by remember { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val keys = remember { FocusRequester() }
    val maxFrames = { f: FileFrames -> (ClipRecorder.MAX_GIF_SECONDS * f.fps).toInt() }

    BackHandlerCompat(onClose)
    DisposableEffect(Unit) { onDispose { frames?.close() } }

    // the window's frames (small), then the handles: the last 3 s before where the video was
    LaunchedEffect(winStart) {
        frames?.close(); frames = null; thumbs = emptyList(); error = null; loading = 0f
        val end = (winStart + WINDOW_S).let { if (src.duration > 0) it.coerceAtMost(src.duration) else it }
        runCatching {
            ClipRecorder.frames(src.input, winStart, end, src.width, src.height, src.fps, PREVIEW_LONG, "preview") { loading = it }
        }.onSuccess { f ->
            frames = f
            val n = f.count
            val atFrame = ((at - winStart) * f.fps).roundToInt().coerceIn(0, n - 1)
            val r = resume
            if (r != null) {
                resume = null
                a = ((r.first - winStart) * f.fps).roundToInt().coerceIn(0, n - 2)
                b = ((r.second - winStart) * f.fps).roundToInt() - 1
            } else if (b == 0 || b >= n) { b = atFrame.coerceAtLeast(1); a = (b - (3 * f.fps).toInt()).coerceAtLeast(0) }
            a = a.coerceIn(0, n - 1); b = b.coerceIn(a, n - 1)
            head = a
            thumbs = withContext(Dispatchers.Default) {
                val k = 14
                (0 until k).map { i -> f.frame((i * (n - 1) / (k - 1).coerceAtLeast(1))) }.map { Pixels.image(it.withAlpha(), f.width, f.height) }
            }
            loading = null
            runCatching { keys.requestFocus() }
        }.onFailure { error = it.message ?: "Couldn't read the video"; loading = null }
    }

    // the looping preview: speed and ping-pong as the GIF will have them
    LaunchedEffect(frames, a, b, speed, pingPong, playing) {
        val f = frames ?: return@LaunchedEffect
        var dir = 1
        if (head !in a..b) head = a
        while (true) {
            shown = withContext(Dispatchers.Default) { Pixels.image(f.frame(head).withAlpha(), f.width, f.height) }
            if (!playing) return@LaunchedEffect
            delay((1000.0 / (f.fps * speed)).toLong().coerceAtLeast(10))
            if (pingPong) {
                if (head + dir > b || head + dir < a) dir = -dir
                head = (head + dir).coerceIn(a, b)
            } else head = if (head >= b) a else head + 1
        }
    }
    LaunchedEffect(head, playing) { if (!playing) frames?.let { f -> shown = withContext(Dispatchers.Default) { Pixels.image(f.frame(head).withAlpha(), f.width, f.height) } } }

    fun secs(i: Int) = winStart + i / (frames?.fps ?: 24.0)
    fun move(handle: Int, to: Int) {
        val f = frames ?: return
        val n = f.count
        val len = b - a
        // the start put past the end (or the end before the start): the whole part goes there, keeping its length
        if (handle == 0 && to >= b) { a = to.coerceIn(0, n - 2); b = (a + len).coerceIn(a + 1, n - 1); head = a; return }
        if (handle == 1 && to <= a) { b = to.coerceIn(1, n - 1); a = (b - len).coerceIn(0, b - 1); head = b; return }
        if (handle == 0) { a = to.coerceIn(0, b - 1).coerceAtLeast(b - maxFrames(f)); head = a }
        else { b = to.coerceIn(a + 1, n - 1).coerceAtMost(a + maxFrames(f)); head = b }
    }

    fun make() {
        val f = frames ?: return
        val from = secs(a); val to = secs(b) + 1 / f.fps
        GifSpots.put(src.key, from, to)
        val out =Desktop.freeFile(Desktop.clips, Desktop.clean("${src.title} ${clockS(from)}-${clockS(to)}".replace(':', '.')), "gif")
        making = "Getting the frames" to 0f
        scope.launch {
            runCatching {
                // full size frames of the picked part only (every frame of a 60 fps video for slow motion / 50 fps; long
                // parts a little smaller), then the phone's GIF engine
                val keep = if (speed < 1f || gifFps > 30) 60.0 else 30.0
                val count = (to - from) * minOf(keep, if (src.fps > 1) src.fps else 30.0)
                val aspect = maxOf(src.width, src.height).toDouble() / minOf(src.width, src.height).coerceAtLeast(1)
                val budgetLong = Math.sqrt(FRAMES_BUDGET / 3.0 / count * aspect).toInt()
                val long = minOf(size.maxWidth.coerceAtLeast(PREVIEW_LONG), budgetLong).coerceAtLeast(240)
                val big = ClipRecorder.frames(src.input, from, to, src.width, src.height, src.fps, long, "make", keep) { making = "Getting the frames" to it * 0.3f }
                try { ClipRecorder.gif(big, GifOptions(speed, pingPong, size, gifFps), out) { what, p -> making = what to 0.3f + 0.7f * p } } finally { big.close() }
            }.onSuccess { done = it }.onFailure { Desktop.toast("Couldn't make the GIF: ${it.message}") }
            making = null
        }
    }

    Column(
        Modifier.fillMaxSize().background(Mc.Bg).focusRequester(keys).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || frames == null) return@onPreviewKeyEvent false
                val step = if (e.isShiftPressed) 10 else 1
                when (e.key) {
                    Key.DirectionLeft -> move(active, (if (active == 0) a else b) - step)
                    Key.DirectionRight -> move(active, (if (active == 0) a else b) + step)
                    Key.Tab -> active = 1 - active
                    Key.LeftBracket -> { active = 0; move(0, head) }
                    Key.RightBracket -> { active = 1; move(1, head) }
                    Key.Spacebar -> playing = !playing
                    Key.Enter -> make()
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        // the preview
        Box(Modifier.weight(1f).fillMaxWidth().background(Color.Black), contentAlignment = Alignment.Center) {
            when {
                error != null -> Text(error!!, color = Mc.Orange, modifier = Modifier.padding(20.dp))
                loading != null -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Mc.Blue)
                    Spacer(Modifier.height(10.dp))
                    Text("Reading the video… ${((loading ?: 0f) * 100).toInt()}%", color = Mc.Text2)
                }
                else -> shown?.let { Image(it, null, Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
            }
            done?.let { f ->
                Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xF00B1622)).padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("GIF saved · ${f.length() / 1024} KB", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(f.parentFile.path, color = Mc.Text3, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Desktop.reveal(f) }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Show in folder") }
                        Button(onClick = { copyFile(f); Desktop.toast("GIF copied: paste it in Discord, a chat or a folder") },
                            colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Copy") }
                        Button(onClick = { done = null }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Make another") }
                        Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Done") }
                    }
                }
            }
        }
        val f = frames
        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // times
            Row(verticalAlignment = Alignment.CenterVertically) {
                // picking an end shows its frame (paused there); a click on the strip then moves that end
                TimeTag("Start", if (f != null) clockS(secs(a)) else "–", active == 0) { active = 0; head = a; playing = false }
                Spacer(Modifier.width(8.dp))
                TimeTag("End", if (f != null) clockS(secs(b)) else "–", active == 1) { active = 1; head = b; playing = false }
                Spacer(Modifier.width(12.dp))
                Text(if (f != null) "%.1f s · %d frames".format(java.util.Locale.US, (b - a + 1) / f.fps / speed, b - a + 1) else "", color = Mc.Text2, fontSize = 13.sp)
                // where the preview is in the whole video
                if (f != null) Text("   ▶ ${clockS(secs(head))}" + (if (src.duration > 0) " of ${clockS(src.duration).substringBefore('.')}" else ""),
                    color = Color(0xFFFF5C8A), fontSize = 13.sp)
                Spacer(Modifier.weight(1f))
                Text("Pick Start / End, click the strip · ← → one frame (Shift 10) · Tab other end · Space play", color = Mc.Text3, fontSize = 11.5.sp)
            }
            // the filmstrip with the two handles
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallBtn("◀ Earlier", enabled = loading == null && winStart > 0) { winStart = (winStart - WINDOW_S / 2).coerceAtLeast(0.0); a = 0; b = 0 }
                Filmstrip(thumbs, f?.count ?: 0, a, b, head, active, winStart, f?.fps ?: 24.0, Modifier.weight(1f).height(84.dp).padding(horizontal = 8.dp),
                    // a click moves the picked end (Start / End) there and shows that frame; dragging takes the nearest handle
                    onPick = { which, i -> playing = false; if (which == -1) move(active, i) else { active = which; move(which, i) } })
                SmallBtn("Later ▶", enabled = loading == null && (src.duration <= 0 || winStart + WINDOW_S < src.duration)) { winStart += WINDOW_S / 2; a = 0; b = 0 }
            }
            // opened on the last GIF's part: one click back to where the video is
            if (resumed) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Opened where your last GIF of this video was", color = Mc.Text3, fontSize = 11.5.sp)
                Spacer(Modifier.width(8.dp))
                SmallBtn("Go to where the video is", enabled = loading == null) {
                    a = 0; b = 0
                    val w = (at - WINDOW_S * 2 / 3).coerceAtLeast(0.0)
                    if (w == winStart) winStart = w + 1e-6 else winStart = w
                }
            }
            // options + make
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Size", color = Mc.Text2, fontSize = 13.sp)
                GifSize.entries.forEach { g -> Chip("${g.label} · ${g.maxBytes / 1_000_000} MB", size == g) { size = g } }
                Spacer(Modifier.width(10.dp))
                Text("Speed", color = Mc.Text2, fontSize = 13.sp)
                listOf(0.25f, 0.5f, 1f, 1.5f, 2f).forEach { s -> Chip(if (s == 1f) "1×" else "$s×", speed == s) { speed = s } }
                Spacer(Modifier.width(10.dp))
                Chip("Ping-pong", pingPong) { pingPong = !pingPong }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("FPS", color = Mc.Text2, fontSize = 13.sp)
                GIF_FPS.forEach { v -> Chip(if (v == 0) "Auto" else "$v", gifFps == v) { gifFps = v } }
                Text(when {
                    gifFps <= 30 && speed >= 1f -> ""
                    Rife.available -> "  in-between frames drawn by RIFE (graphics card): smooth"
                    else -> "  extra frames made in between: smooth"
                }, color = Mc.Text3, fontSize = 12.sp)
                Spacer(Modifier.weight(1f))
                making?.let { (what, p) ->
                    Column(Modifier.width(180.dp)) {
                        Text("$what…", color = Mc.Text2, fontSize = 12.sp)
                        LinearProgressIndicator(progress = { p }, Modifier.fillMaxWidth(), color = Mc.Blue, trackColor = Mc.Card2)
                    }
                }
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Close") }
                Button(enabled = f != null && making == null, onClick = { make() },
                    colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Make GIF") }
            }
        }
    }
}

/**
 * The strip: thumbnails, the picked part lit, start / end handles (drag them, or click: the picked end moves there), the
 * preview spot, a ruler of video times along the bottom ([start] = the strip's first frame in the video, s) and the time
 * under the mouse.
 */
@Composable
private fun Filmstrip(
    thumbs: List<ImageBitmap>, n: Int, a: Int, b: Int, head: Int, active: Int, start: Double, fps: Double, modifier: Modifier,
    onPick: (Int, Int) -> Unit,
) {
    var dragging by remember { mutableIntStateOf(-1) }
    var hover by remember { mutableStateOf<Float?>(null) }
    val text = rememberTextMeasurer()
    Canvas(modifier.clip(RoundedCornerShape(8.dp)).background(Mc.Card)
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val e = awaitPointerEvent()
                    hover = if (e.type == PointerEventType.Exit) null else e.changes.firstOrNull()?.position?.x
                }
            }
        }
        .pointerInput(n, a, b) {
            fun idx(x: Float) = if (n <= 1) 0 else (x / size.width * (n - 1)).roundToInt().coerceIn(0, n - 1)
            detectTapGestures { o -> onPick(-1, idx(o.x)) }
        }
        .pointerInput(n, a, b) {
            fun idx(x: Float) = if (n <= 1) 0 else (x / size.width * (n - 1)).roundToInt().coerceIn(0, n - 1)
            fun xOf(i: Int) = if (n <= 1) 0f else i.toFloat() / (n - 1) * size.width
            detectDragGestures(
                onDragStart = { o -> dragging = if (kotlin.math.abs(o.x - xOf(a)) < kotlin.math.abs(o.x - xOf(b))) 0 else 1; onPick(dragging, idx(o.x)) },
                onDrag = { c, _ -> onPick(dragging, idx(c.position.x)) },
                onDragEnd = { dragging = -1 },
            )
        }
    ) {
        val w = size.width; val h = size.height
        if (thumbs.isNotEmpty()) {
            val tw = w / thumbs.size
            thumbs.forEachIndexed { i, t ->
                drawImage(t, srcOffset = IntOffset.Zero, srcSize = IntSize(t.width, t.height),
                    dstOffset = IntOffset((i * tw).roundToInt(), 0), dstSize = IntSize(tw.roundToInt() + 1, h.roundToInt()))
            }
        }
        if (n > 1) {
            val xa = a.toFloat() / (n - 1) * w; val xb = b.toFloat() / (n - 1) * w
            drawRect(Color(0xAA0B1622), Offset.Zero, Size(xa, h))
            drawRect(Color(0xAA0B1622), Offset(xb, 0f), Size(w - xb, h))
            drawRect(Color(0xFF3DB4F2), Offset(xa, 0f), Size(xb - xa, h), style = Stroke(3f))
            listOf(xa to 0, xb to 1).forEach { (x, which) ->
                drawRect(if (which == active) Color(0xFF3DB4F2) else Color.White, Offset(x - 4f, 0f), Size(8f, h))
            }
            val xh = head.toFloat() / (n - 1) * w
            drawLine(Color(0xFFFF5C8A), Offset(xh, 0f), Offset(xh, h), strokeWidth = 2f)
            // the ruler: a mark every 1 / 2 / 5 / 10 ... s, as far apart as fits
            val band = 16.dp.toPx()
            drawRect(Color(0xCC0B1622), Offset(0f, h - band), Size(w, band))
            val pxPerS = w / ((n - 1) / fps).toFloat()
            val every = listOf(1, 2, 5, 10, 15, 30, 60).firstOrNull { it * pxPerS >= 56.dp.toPx() } ?: 120
            var t = Math.ceil(start / every) * every
            val style = TextStyle(color = Color(0xFFB8C4D2), fontSize = 10.sp)
            while (true) {
                val x = ((t - start) * pxPerS).toFloat()
                if (x > w) break
                drawLine(Color(0xFFB8C4D2), Offset(x, h - band), Offset(x, h - band + 5f), strokeWidth = 1.5f)
                val label = text.measure(clockM(t), style)
                if (x + 3f + label.size.width <= w) drawText(label, topLeft = Offset(x + 3f, h - band + (band - label.size.height) / 2))
                t += every
            }
            // the time under the mouse
            hover?.let { hx ->
                val x = hx.coerceIn(0f, w)
                val i = (x / w * (n - 1)).roundToInt()
                drawLine(Color.White.copy(alpha = 0.8f), Offset(x, 0f), Offset(x, h - band), strokeWidth = 1f)
                val label = text.measure(clockS(start + i / fps), TextStyle(color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold))
                val lx = (x - label.size.width / 2f).coerceIn(2f, w - label.size.width - 2f)
                drawRect(Color(0xE60B1622), Offset(lx - 4f, 2f), Size(label.size.width + 8f, label.size.height + 2f))
                drawText(label, topLeft = Offset(lx, 3f))
            }
        }
    }
}

/** m:ss for the ruler. */
private fun clockM(s: Double): String {
    val t = s.roundToInt().coerceAtLeast(0)
    return "%d:%02d".format(t / 60, t % 60)
}

@Composable
private fun TimeTag(label: String, value: String, on: Boolean, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).background(if (on) Mc.Blue else Mc.Card2).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text("$label  ", color = if (on) Mc.Bg else Mc.Text3, fontSize = 12.sp)
        Text(value, color = if (on) Mc.Bg else Mc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Mc.Blue else Mc.Card2).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 5.dp)) {
        Text(label, color = if (on) Mc.Bg else Mc.Text, fontSize = 12.5.sp)
    }
}

@Composable
private fun SmallBtn(label: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(Mc.Card2).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 8.dp, vertical = 8.dp)) {
        Text(label, color = if (enabled) Mc.Text else Mc.Text3, fontSize = 12.sp)
    }
}

/** Esc closes the editor (it sits above the player's own Esc handling). */
@Composable
private fun BackHandlerCompat(onBack: () -> Unit) = androidx.activity.compose.BackHandler(onBack = onBack)

private fun IntArray.withAlpha() = IntArray(size) { this[it] or (0xFF shl 24) }

private fun clockS(s: Double): String {
    val ms = (s * 1000).toLong().coerceAtLeast(0)
    return "%d:%02d.%d".format(ms / 60000, ms / 1000 % 60, ms / 100 % 10)
}

/** Puts the file itself on the clipboard (paste into Discord, a chat, Explorer). */
private fun copyFile(f: File) {
    val t = object : java.awt.datatransfer.Transferable {
        override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
        override fun isDataFlavorSupported(fl: java.awt.datatransfer.DataFlavor) = fl == java.awt.datatransfer.DataFlavor.javaFileListFlavor
        override fun getTransferData(fl: java.awt.datatransfer.DataFlavor): Any = listOf(f)
    }
    java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(t, null)
}
