package com.yoruc.mangacracker.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
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
import androidx.compose.ui.input.key.isCtrlPressed
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
import com.yoruc.mangacracker.gif.PickedFrames
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.platform.Pixels
import com.yoruc.mangacracker.platform.Rife
import com.yoruc.mangacracker.ui.theme.Mc
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** What the editor needs from the player. */
class GifSource(
    val input: List<String>, val width: Int, val height: Int, val fps: Double, val duration: Double, val title: String,
)

/** Tests: gets the shown frame after every change. */
internal var gifHeadProbe: ((Int) -> Unit)? = null
/** Tests: the frame list's kept frames, deleted frames and picked frames (window indices) after every change. */
internal var gifFramesProbe: ((List<Int>, Set<Int>, Set<Int>) -> Unit)? = null
/** Tests: the editor's delete and make actions, and the saved GIF. */
internal var gifActionsProbe: (((Set<Int>) -> Unit, () -> Unit) -> Unit)? = null
internal var gifDoneProbe: ((File) -> Unit)? = null

private const val ROW_S = ClipRecorder.MAX_GIF_SECONDS   // the row: this much of the video from the paused frame
private const val PREVIEW_LONG = 480       // preview frames' long side
private const val FRAMES_BUDGET = 450_000_000L   // bytes of full-size frames for the GIF (long ones are read smaller)

/**
 * The PC GIF maker, like ScreenToGif: every frame from where the video was paused in a row of boxes, each with its
 * number and time. Delete what you don't want (before, after, or anywhere in between); whatever is left is the GIF, shown
 * looping in the preview. Then size / speed / ping-pong and the phone's GIF
 * engine at full GIF size. Keys: ← → one frame (Shift 10), Delete, Ctrl+Z, Space plays / pauses, Enter makes the GIF, Esc closes.
 */
@Composable
fun GifEditor(src: GifSource, at: Double, savedDemo: File? = null, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    // always opens on the paused frame (in the last seconds of a video: its last 3 s)
    val winStart = remember { (if (src.duration > 0) minOf(at, src.duration - 3) else at).coerceAtLeast(0.0) }
    var frames by remember { mutableStateOf<FileFrames?>(null) }
    var loading by remember { mutableStateOf<Float?>(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var head by remember { mutableIntStateOf(0) }        // preview frame (the shown one when paused)
    var playing by remember { mutableStateOf(true) }
    var size by remember { mutableStateOf(GifSize.SMALL) }
    var speed by remember { mutableStateOf(1f) }
    var pingPong by remember { mutableStateOf(false) }
    var gifFps by remember { mutableIntStateOf(0) }      // 0 = auto
    var making by remember { mutableStateOf<Pair<String, Float>?>(null) }
    var done by remember { mutableStateOf(savedDemo) }        // the saved GIF's box ([savedDemo]: tests open on it)
    var shown by remember { mutableStateOf<ImageBitmap?>(null) }
    val keys = remember { FocusRequester() }
    // the frame list: frames left out of the GIF, the picked ones, undo steps
    var deleted by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var undo by remember { mutableStateOf<List<Set<Int>>>(emptyList()) }
    var sel by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var typing by remember { mutableStateOf(false) }      // a From / To box has the keys
    // the GIF: every frame still in the row
    val kept = remember(frames, deleted) { (0 until (frames?.count ?: 0)).filter { it !in deleted } }

    BackHandlerCompat(onClose)
    SideEffect { gifHeadProbe?.invoke(head); gifFramesProbe?.invoke(kept, deleted, sel) }

    // what was done in the last editor, for when a GIF looks wrong (cache\gif\last_gif.log)
    val logFile = remember { File(Desktop.ctx.cacheDir, "gif/last_gif.log").apply { parentFile?.mkdirs(); runCatching { writeText("paused at $at, row from $winStart\n") } } }
    fun note(s: String) { runCatching { logFile.appendText(s + "\n") } }

    /** Deletes [these] (any frames of the row, undo-able). */
    fun deleteFrames(these: Set<Int>) {
        val n = frames?.count ?: return
        val d = these - deleted
        if (d.isEmpty()) return
        val now = deleted + d
        if ((0 until n).all { it in now }) { Desktop.toast("That's every frame: keep at least one"); return }
        undo = undo + listOf(deleted); deleted = now; sel = sel - d
        if (head in now) head = (head + 1 until n).firstOrNull { it !in now } ?: (head downTo 0).firstOrNull { it !in now } ?: head
        note("deleted ${d.size} (#${d.min() + 1}-#${d.max() + 1}), shown #${head + 1}, left #${(0 until n).first { it !in now } + 1}-#${(0 until n).last { it !in now } + 1}")
    }
    fun undoDelete() {
        val u = undo.lastOrNull() ?: return
        deleted = u; undo = undo.dropLast(1)
    }
    /** The shown frame [step] frames on (deleted ones skipped). */
    fun stepShown(step: Int) {
        val n = frames?.count ?: return
        var i = head; var left = kotlin.math.abs(step); val d = if (step < 0) -1 else 1
        while (left > 0) { var j = i + d; while (j in 0 until n && j in deleted) j += d; if (j !in 0 until n) break; i = j; left-- }
        playing = false; head = i
    }
    DisposableEffect(Unit) { onDispose { frames?.close() } }

    // the row's frames (small)
    LaunchedEffect(Unit) {
        val end = (winStart + ROW_S).let { if (src.duration > 0) it.coerceAtMost(src.duration) else it }
        runCatching {
            ClipRecorder.frames(src.input, winStart, end, src.width, src.height, src.fps, PREVIEW_LONG, "preview") { loading = it }
        }.onSuccess { f ->
            frames = f
            loading = null
            runCatching { keys.requestFocus() }
        }.onFailure { error = it.message ?: "Couldn't read the video"; loading = null }
    }

    // the looping preview: speed and ping-pong as the GIF will have them, deleted frames skipped
    LaunchedEffect(frames, kept, speed, pingPong, playing) {
        val f = frames ?: return@LaunchedEffect
        val list = kept.ifEmpty { return@LaunchedEffect }
        var dir = 1
        var k = list.indexOf(head)
        // paused: the shown frame stays where it is, even outside the part (it was picked in the frame list)
        if (k < 0) { k = 0; if (playing) head = list[0] }
        while (true) {
            // a read that fails (the frames just replaced) keeps the last picture instead of closing the app
            shown = withContext(Dispatchers.Default) { runCatching { Pixels.image(f.frame(head).withAlpha(), f.width, f.height) }.getOrNull() } ?: shown
            if (!playing) return@LaunchedEffect
            delay((1000.0 / (f.fps * speed)).toLong().coerceAtLeast(10))
            if (pingPong && list.size > 1) {
                if (k + dir !in list.indices) dir = -dir
                k = (k + dir).coerceIn(list.indices)
            } else k = (k + 1) % list.size
            head = list[k]
        }
    }
    LaunchedEffect(head, playing) {
        if (!playing) frames?.let { f -> shown = withContext(Dispatchers.Default) { runCatching { Pixels.image(f.frame(head).withAlpha(), f.width, f.height) }.getOrNull() } ?: shown }
    }

    fun secs(i: Int) = winStart + i / (frames?.fps ?: 24.0)
    fun make() {
        if (making != null) return
        val f = frames ?: return
        if (kept.isEmpty()) return
        val first = kept.first(); val last = kept.last()
        val from = secs(first); val to = secs(last) + 1 / f.fps
        val cut = deleted.filter { it in first..last }.toSet(); val ws = winStart; val wfps = f.fps
        note("make: kept ${kept.size}, #${first + 1}-#${last + 1}, $from to $to, fps $wfps, ${cut.size} cut inside")
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
                try {
                    // frames deleted in the list: the full-size frames that fall in them are left out (by time: the two
                    // readings can have different frame rates); the GIF draws nothing in between across the gaps
                    val keepIdx = if (cut.isEmpty()) null else keptBigFrames(big.count, big.fps, from, ws, wfps, cut)
                    if (keepIdx != null && keepIdx.isEmpty()) error("every frame of the part is deleted")
                    val picked = if (keepIdx == null) big else PickedFrames(big, keepIdx)
                    ClipRecorder.gif(picked, GifOptions(speed, pingPong, size, gifFps), out) { what, p -> making = what to 0.3f + 0.7f * p }
                } finally { big.close() }
            }.onSuccess { done = it; playing = false; gifDoneProbe?.invoke(it) }.onFailure {
                // the editor closed while making: no message on the screen it left
                if (it !is kotlinx.coroutines.CancellationException && it !is com.yoruc.mangacracker.gif.GifCancelled) Desktop.toast("Couldn't make the GIF: ${it.message}")
            }
            making = null
        }
    }

    SideEffect { gifActionsProbe?.invoke(::deleteFrames, ::make) }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().background(Mc.Bg).focusRequester(keys).focusable()
                .onPreviewKeyEvent { e ->
                    // while the saved GIF's box is up, the editor's keys rest
                    if (e.type != KeyEventType.KeyDown || frames == null || done != null || typing) return@onPreviewKeyEvent false
                    val step = if (e.isShiftPressed) 10 else 1
                    when (e.key) {
                        // the picked frames, or (nothing picked) the shown one: pressing it again deletes the next
                        Key.Delete -> if (sel.isEmpty() && playing) playing = false else deleteFrames(if (sel.isEmpty()) setOf(head) else sel)
                        Key.Z -> if (e.isCtrlPressed) undoDelete() else return@onPreviewKeyEvent false
                        Key.DirectionLeft -> stepShown(-step)
                        Key.DirectionRight -> stepShown(step)
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
            }
            val f = frames
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // what the GIF is right now, and the frame on show
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallBtn(if (playing) "⏸ Pause" else "▶ Play", enabled = f != null) { playing = !playing; runCatching { keys.requestFocus() } }
                    if (f != null) {
                        Text("Your GIF: ${kept.firstOrNull()?.let { clockS(secs(it)) }.orEmpty()} to ${kept.lastOrNull()?.let { clockS(secs(it)) }.orEmpty()} · " +
                            "%.1f s · %d frames".format(java.util.Locale.US, kept.size / f.fps / speed, kept.size),
                            color = Mc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        if (deleted.isNotEmpty()) Text("${deleted.size} deleted", color = Mc.Text3, fontSize = 13.sp)
                        Text("▶ #${head + 1} · ${clockS(secs(head))}" + (if (src.duration > 0) " of ${clockS(src.duration).substringBefore('.')}" else ""),
                            color = Color(0xFFFF5C8A), fontSize = 13.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("Delete what you don't want: what's left in the row is your GIF", color = Mc.Text3, fontSize = 11.5.sp)
                }
                // every frame one by one: all the editing happens here
                if (f != null) FramesPanel(
                    f, deleted, sel, head, playing, { i -> clockC(secs(i)) }, undo.isNotEmpty(), deleted.isNotEmpty(),
                    // every click in the list gives the keys (Delete, Ctrl+Z, arrows) back to the editor
                    onSelect = { sel = it; runCatching { keys.requestFocus() } }, onShow = { i -> playing = false; head = i },
                    onDelete = { deleteFrames(it); runCatching { keys.requestFocus() } },
                    onUndo = { undoDelete(); runCatching { keys.requestFocus() } },
                    onRestore = { undo = undo + listOf(deleted); deleted = emptySet(); runCatching { keys.requestFocus() } },
                    onTyping = { typing = it }, modifier = Modifier.fillMaxWidth())
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
        done?.let { f -> SavedBox(f, onDelete = {
            if (f.delete() || !f.exists()) Desktop.toast("GIF deleted") else Desktop.toast("Couldn't delete the GIF (is it open somewhere?)")
            done = null; playing = true
        }, onAnother = { done = null; playing = true }, onClose = onClose) }
    }
}

/**
 * Over the whole editor once a GIF is made: the GIF playing (as big as the window allows), its size, and Show in
 * folder / Copy / Delete (not happy with it: the file goes, the editor stays on the same part to change and make
 * again) / Make another / Done. Clicks don't reach the editor under it.
 */
@Composable
private fun SavedBox(f: File, onDelete: () -> Unit, onAnother: () -> Unit, onClose: () -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().background(Color(0xCC060B12)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center) {
        val previewH = (maxHeight - 170.dp).coerceIn(80.dp, 340.dp)
        Column(Modifier.clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            var info by remember(f) { mutableStateOf("") }
            GifPlayer(f, previewH, Modifier.widthIn(max = 640.dp)) { info = it }
            Text("GIF saved · ${f.length() / 1024} KB" + (if (info.isNotEmpty()) " · $info" else ""), color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(f.path, color = Mc.Text3, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { Desktop.reveal(f) }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Show in folder") }
                Button(onClick = { copyFile(f); Desktop.toast("GIF copied: paste it in Discord, a chat or a folder") },
                    colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Copy") }
                Button(onClick = onDelete, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Orange)) { Text("Delete") }
                Button(onClick = onAnother, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Make another") }
                Button(onClick = onClose, colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text)) { Text("Done") }
            }
        }
    }
}

/**
 * Plays a GIF file in a loop at its own timing (Skia decodes one frame at a time onto the last, so even a long GIF
 * holds only one picture). [onInfo] gets "W×H · N frames · S s".
 */
@Composable
private fun GifPlayer(file: File, maxHeight: Dp, modifier: Modifier, onInfo: (String) -> Unit) {
    var pic by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    var aspect by remember(file) { mutableStateOf(16f / 9f) }
    var natural by remember(file) { mutableStateOf(0) }       // the GIF's own height, px: never shown bigger than that
    LaunchedEffect(file) {
        val codec = withContext(Dispatchers.IO) { runCatching { Codec.makeFromData(Data.makeFromBytes(file.readBytes())) }.getOrNull() } ?: return@LaunchedEffect
        aspect = codec.width.toFloat() / codec.height.coerceAtLeast(1)
        natural = codec.height
        val n = codec.frameCount.coerceAtLeast(1)
        val durations = IntArray(n) { i -> runCatching { codec.getFrameInfo(i).duration }.getOrDefault(100).let { if (it <= 10) 100 else it } }
        onInfo("${codec.width}×${codec.height} · $n frames · ${"%.1f".format(java.util.Locale.US, durations.sum() / 1000.0)} s")
        val bmp = Bitmap().apply { allocN32Pixels(codec.width, codec.height) }
        try {
            var i = 0
            while (true) {
                val started = System.nanoTime()
                pic = withContext(Dispatchers.Default) {
                    runCatching {
                        // each frame is drawn over the one before (GIF frames only hold what changed)
                        if (i == 0) codec.readPixels(bmp, 0) else codec.readPixels(bmp, i, i - 1)
                        org.jetbrains.skia.Image.makeFromBitmap(bmp).use { it.toComposeImageBitmap() }
                    }.getOrNull()
                } ?: pic
                delay((durations[i] - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(5))
                i = (i + 1) % n
            }
        } finally { bmp.close(); codec.close() }
    }
    val h = if (natural > 0) minOf(maxHeight, with(LocalDensity.current) { natural.toDp() }) else maxHeight
    Box(modifier.height(h).aspectRatio(aspect, matchHeightConstraintsFirst = true), contentAlignment = Alignment.Center) {
        pic?.let { Image(it, "The GIF", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            ?: CircularProgressIndicator(color = Mc.Blue)
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

/** One undo step: the deleted frames and Start / End before ([a], [b]) and right after ([a2], [b2]) the change. */

