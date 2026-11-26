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
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
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
import com.yoruc.mangacracker.gif.BlurBrush
import com.yoruc.mangacracker.gif.BlurStroke
import com.yoruc.mangacracker.gif.BlurredFrames
import androidx.compose.ui.input.pointer.isSecondaryPressed
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
    var crop by remember { mutableStateOf<ClipRecorder.Crop?>(null) }   // the part of the picture in the GIF (null = all)
    var cropShape by remember { mutableStateOf(0f) }     // the crop box's width / height in pixels, 0 = any
    // the blur brush: painted strokes (shares of the whole picture), painting on / off, brush size, erase, how strong
    var strokes by remember { mutableStateOf<List<BlurStroke>>(emptyList()) }
    var painting by remember { mutableStateOf(false) }
    var brush by remember { mutableStateOf(BRUSHES[1].second) }
    var erasing by remember { mutableStateOf(false) }
    var blurStrength by remember { mutableStateOf(BlurBrush.STRONG) }
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

    // the preview with the painted areas blurred (the mask at the preview frames' size, the whole picture)
    val previewMask = remember(frames, strokes) { frames?.let { BlurBrush.mask(strokes, it.width, it.height) } }
    fun previewPx(f: FileFrames, i: Int) = BlurBrush.apply(f.frame(i), f.width, f.height, previewMask, blurStrength)

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
    LaunchedEffect(frames, kept, speed, pingPong, playing, previewMask, blurStrength) {
        val f = frames ?: return@LaunchedEffect
        val list = kept.ifEmpty { return@LaunchedEffect }
        var dir = 1
        var k = list.indexOf(head)
        // paused: the shown frame stays where it is, even outside the part (it was picked in the frame list)
        if (k < 0) { k = 0; if (playing) head = list[0] }
        while (true) {
            // a read that fails (the frames just replaced) keeps the last picture instead of closing the app
            shown = withContext(Dispatchers.Default) { runCatching { Pixels.image(previewPx(f, head).withAlpha(), f.width, f.height) }.getOrNull() } ?: shown
            if (!playing) return@LaunchedEffect
            delay((1000.0 / (f.fps * speed)).toLong().coerceAtLeast(10))
            if (pingPong && list.size > 1) {
                if (k + dir !in list.indices) dir = -dir
                k = (k + dir).coerceIn(list.indices)
            } else k = (k + 1) % list.size
            head = list[k]
        }
    }
    LaunchedEffect(head, playing, previewMask, blurStrength) {
        if (!playing) frames?.let { f -> shown = withContext(Dispatchers.Default) { runCatching { Pixels.image(previewPx(f, head).withAlpha(), f.width, f.height) }.getOrNull() } ?: shown }
    }

    fun secs(i: Int) = winStart + i / (frames?.fps ?: 24.0)
    fun make() {
        if (making != null) return
        val f = frames ?: return
        if (kept.isEmpty()) return
        val first = kept.first(); val last = kept.last()
        val from = secs(first); val to = secs(last) + 1 / f.fps
        val cut = deleted.filter { it in first..last }.toSet(); val ws = winStart; val wfps = f.fps
        val part = crop?.takeUnless { it.full }
        val blurNow = strokes.takeIf { l -> l.any { !it.erase } }; val strengthNow = blurStrength
        note("make: kept ${kept.size}, #${first + 1}-#${last + 1}, $from to $to, fps $wfps, ${cut.size} cut inside" + (part?.let { ", crop $it" } ?: "") +
            (blurNow?.let { ", blur ${it.size} strokes" } ?: ""))
        val out =Desktop.freeFile(Desktop.clips, Desktop.clean("${src.title} ${clockS(from)}-${clockS(to)}".replace(':', '.')), "gif")
        making = "Getting the frames" to 0f
        scope.launch {
            runCatching {
                // full size frames of the picked part only (every frame of a 60 fps video for slow motion / 50 fps; long
                // parts a little smaller), then the phone's GIF engine
                val keep = if (speed < 1f || gifFps > 30) 60.0 else 30.0
                val count = (to - from) * minOf(keep, if (src.fps > 1) src.fps else 30.0)
                // a cropped part: its own shape, and never drawn bigger than the video has it
                val pw = src.width * (part?.w ?: 1f).toDouble(); val ph = src.height * (part?.h ?: 1f).toDouble()
                val aspect = maxOf(pw, ph) / minOf(pw, ph).coerceAtLeast(1.0)
                val budgetLong = Math.sqrt(FRAMES_BUDGET / 3.0 / count * aspect).toInt()
                var long = minOf(size.maxWidth.coerceAtLeast(PREVIEW_LONG), budgetLong).coerceAtLeast(240)
                if (part != null) long = minOf(long, maxOf(pw, ph).toInt().coerceAtLeast(64))
                val big = ClipRecorder.frames(src.input, from, to, src.width, src.height, src.fps, long, "make", keep, part) { making = "Getting the frames" to it * 0.3f }
                try {
                    // frames deleted in the list: the full-size frames that fall in them are left out (by time: the two
                    // readings can have different frame rates); the GIF draws nothing in between across the gaps
                    val keepIdx = if (cut.isEmpty()) null else keptBigFrames(big.count, big.fps, from, ws, wfps, cut)
                    if (keepIdx != null && keepIdx.isEmpty()) error("every frame of the part is deleted")
                    val picked = if (keepIdx == null) big else PickedFrames(big, keepIdx)
                    // the painted areas blurred on every frame (the frames show the crop: the strokes are placed in it)
                    val final = blurNow?.let { BlurredFrames(picked, it, part?.let { c -> floatArrayOf(c.x, c.y, c.w, c.h) }, strengthNow) } ?: picked
                    ClipRecorder.gif(final, GifOptions(speed, pingPong, size, gifFps), out) { what, p -> making = what to 0.3f + 0.7f * p }
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
                        Key.Z -> if (!e.isCtrlPressed) return@onPreviewKeyEvent false
                            else if (painting && strokes.isNotEmpty()) strokes = strokes.dropLast(1) else undoDelete()
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
                    else -> shown?.let { img ->
                        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                            // cropping: the box's shape, its size in the video, back to the whole picture
                            crop?.let { c ->
                                Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Mc.Card).padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Crop: drag the box or its edges", color = Mc.Text2, fontSize = 12.sp)
                                    Spacer(Modifier.width(4.dp))
                                    CROP_SHAPES.forEach { (label, s) ->
                                        Chip(label, cropShape == s) { cropShape = s; crop = startCrop(s, img.width.toFloat() / img.height); runCatching { keys.requestFocus() } }
                                    }
                                    Text("${(c.w * src.width).roundToInt()} × ${(c.h * src.height).roundToInt()} px", color = Mc.Text3, fontSize = 12.sp)
                                    SmallBtn("No crop", enabled = true) { crop = null; runCatching { keys.requestFocus() } }
                                }
                            }
                            // painting the blur: brush size, erase, how strong, undo / clear
                            if (painting) {
                                Row(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(8.dp)).background(Mc.Card).padding(horizontal = 10.dp, vertical = 5.dp),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("Blur: paint over what to hide · right-drag erases", color = Mc.Text2, fontSize = 12.sp)
                                    Spacer(Modifier.width(4.dp))
                                    BRUSHES.forEach { (label, v) -> Chip(label, brush == v) { brush = v; runCatching { keys.requestFocus() } } }
                                    Chip("Erase", erasing) { erasing = !erasing; runCatching { keys.requestFocus() } }
                                    Spacer(Modifier.width(4.dp))
                                    Chip("Light", blurStrength == BlurBrush.LIGHT) { blurStrength = BlurBrush.LIGHT; runCatching { keys.requestFocus() } }
                                    Chip("Strong", blurStrength == BlurBrush.STRONG) { blurStrength = BlurBrush.STRONG; runCatching { keys.requestFocus() } }
                                    SmallBtn("Undo", enabled = strokes.isNotEmpty()) { strokes = strokes.dropLast(1); runCatching { keys.requestFocus() } }
                                    SmallBtn("Clear", enabled = strokes.isNotEmpty()) { strokes = emptyList(); runCatching { keys.requestFocus() } }
                                    SmallBtn("Done", enabled = true) { painting = false; runCatching { keys.requestFocus() } }
                                }
                            }
                            // where the blur is, lightly tinted while painting (flat areas look the same blurred)
                            val tint = remember(previewMask, painting) {
                                val m = previewMask
                                val pf = frames
                                if (!painting || m == null || pf == null) null
                                else Pixels.image(IntArray(m.size) { k -> ((m[k] * 70f).toInt().coerceIn(0, 255) shl 24) or 0x5CB8FF }, pf.width, pf.height)
                            }
                            CropView(img, crop, cropShape, Modifier.weight(1f).fillMaxWidth().padding(8.dp), cropEdit = !painting, tint = tint, paint = if (!painting) null else {
                                { BlurPaint(strokes, brush, erasing) { strokes = it } }
                            }) { crop = it }
                        }
                    }
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
                    Spacer(Modifier.width(10.dp))
                    Chip("✂ Crop", crop != null) {
                        painting = false
                        val f0 = frames
                        crop = if (crop != null) null else startCrop(cropShape, if (f0 != null) f0.width.toFloat() / f0.height else src.width.toFloat() / src.height)
                    }
                    Chip(if (strokes.any { !it.erase }) "Blur brush · on" else "Blur brush", painting) { painting = !painting; runCatching { keys.requestFocus() } }
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
        // folders the save folder links to with a Windows shortcut ("old - Shortcut"): a Move to button for each
        val moveTo by androidx.compose.runtime.produceState(emptyList<File>()) { value = withContext(Dispatchers.IO) { Gallery.linkedFolders() } }
        done?.let { f -> SavedBox(f, moveTo, onDelete = {
            if (f.delete() || !f.exists()) Desktop.toast("GIF deleted") else Desktop.toast("Couldn't delete the GIF (is it open somewhere?)")
            done = null; playing = true
        }, onMove = { dir ->
            Gallery.moveTo(f, dir)?.let { done = it; Desktop.toast("Moved to ${dir.name}") } ?: Desktop.toast("Couldn't move the GIF (is it open somewhere?)")
        }, onAnother = { done = null; playing = true }, onClose = onClose) }
    }
}

/** The blur brush's sizes: its radius as a share of the picture's long side. */
private val BRUSHES = listOf("Thin" to 0.015f, "Medium" to 0.035f, "Thick" to 0.07f, "Huge" to 0.12f)

/**
 * The blur brush over the preview: drag to paint (right-drag or [erasing] to erase), the brush shown as a ring at the
 * mouse. Each drag is one stroke (one Undo step); points are shares of the whole picture.
 */
@Composable
private fun BlurPaint(strokes: List<BlurStroke>, brush: Float, erasing: Boolean, onStrokes: (List<BlurStroke>) -> Unit) {
    val now by rememberUpdatedState(strokes)
    val set by rememberUpdatedState(onStrokes)
    val br by rememberUpdatedState(brush)
    val er by rememberUpdatedState(erasing)
    var hover by remember { mutableStateOf<Offset?>(null) }
    var rubbing by remember { mutableStateOf(false) }
    Canvas(Modifier.fillMaxSize()
        .pointerHoverIcon(PointerIcon(java.awt.Cursor(java.awt.Cursor.CROSSHAIR_CURSOR)))
        .pointerInput(Unit) {
            awaitPointerEventScope {
                var drawing = false; var last = Offset.Zero
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull() ?: continue
                    val w = size.width.toFloat(); val h = size.height.toFloat()
                    if (w <= 0f || h <= 0f) continue
                    val nx = (p.position.x / w).coerceIn(0f, 1f); val ny = (p.position.y / h).coerceIn(0f, 1f)
                    hover = p.position
                    when (e.type) {
                        PointerEventType.Press -> {
                            val erase = er || e.buttons.isSecondaryPressed
                            rubbing = erase
                            set(now + BlurStroke(floatArrayOf(nx), floatArrayOf(ny), br, erase))
                            drawing = true; last = p.position
                            p.consume()
                        }
                        PointerEventType.Move -> if (drawing && p.pressed) {
                            val s = now.lastOrNull()
                            if (s != null && (p.position - last).getDistance() >= 2f) {
                                set(now.dropLast(1) + s.plus(nx, ny)); last = p.position
                            }
                            p.consume()
                        }
                        PointerEventType.Release -> { drawing = false; rubbing = false }
                        PointerEventType.Exit -> if (!p.pressed) hover = null
                    }
                }
            }
        }) {
        hover?.let { at ->
            val r = br * maxOf(size.width, size.height)
            drawCircle(if (er || rubbing) Color(0x40FF5C8A) else Color(0x405CB8FF), r, at)
            drawCircle(Color.White.copy(alpha = 0.9f), r, at, style = Stroke(1.5f))
        }
    }
}

/** The crop box's shapes: width / height in pixels (0 = any). */
internal val CROP_SHAPES = listOf("Free" to 0f, "1:1" to 1f, "4:3" to 4f / 3, "16:9" to 16f / 9, "9:16" to 9f / 16)

/** A new crop box: [shape] as big as the picture (width / height [aspect]) allows, centred; any shape = 80 % of it. */
internal fun startCrop(shape: Float, aspect: Float): ClipRecorder.Crop {
    if (shape <= 0f) return ClipRecorder.Crop(0.1f, 0.1f, 0.8f, 0.8f)
    // in a picture [aspect] wide and 1 high
    var w = aspect; var h = aspect / shape
    if (h > 1f) { h = 1f; w = shape }
    val cw = (w / aspect).coerceAtMost(1f)
    return ClipRecorder.Crop((1f - cw) / 2, (1f - h) / 2, cw, h)
}

internal const val CROP_L = 1
internal const val CROP_T = 2
internal const val CROP_R = 4
internal const val CROP_B = 8
internal const val CROP_MOVE = 16

/** What a point is on: the box's edges / corners ([grab] px around them), inside it (move), or nothing (0). */
internal fun cropHit(c: ClipRecorder.Crop, x: Float, y: Float, W: Float, H: Float, grab: Float): Int {
    val l = c.x * W; val t = c.y * H; val r = (c.x + c.w) * W; val b = (c.y + c.h) * H
    val inX = x in l - grab..r + grab; val inY = y in t - grab..b + grab
    var f = 0
    if (inY && kotlin.math.abs(x - l) < grab) f = f or CROP_L
    else if (inY && kotlin.math.abs(x - r) < grab) f = f or CROP_R
    if (inX && kotlin.math.abs(y - t) < grab) f = f or CROP_T
    else if (inX && kotlin.math.abs(y - b) < grab) f = f or CROP_B
    if (f == 0 && x in l..r && y in t..b) f = CROP_MOVE
    return f
}

/**
 * [c] after a drag of ([dx], [dy]) px on a picture [W] x [H] px holding [what] ([cropHit]'s): moved, or its edges /
 * corners pulled, kept inside the picture and at least 6 % of it; with a [shape] the box keeps it (an edge pulls the
 * other two sides along about the middle, a corner follows the larger pull).
 */
internal fun dragCrop(c: ClipRecorder.Crop, what: Int, dx: Float, dy: Float, W: Float, H: Float, shape: Float): ClipRecorder.Crop {
    var l = c.x * W; var t = c.y * H; var r = (c.x + c.w) * W; var b = (c.y + c.h) * H
    val min = minOf(W, H) * 0.06f
    if (what == CROP_MOVE) {
        val ox = dx.coerceIn(-l, W - r); val oy = dy.coerceIn(-t, H - b)
        l += ox; r += ox; t += oy; b += oy
    } else {
        val left = what and CROP_L != 0; val top = what and CROP_T != 0
        val sideX = what and (CROP_L or CROP_R) != 0; val sideY = what and (CROP_T or CROP_B) != 0
        if (left) l = (l + dx).coerceIn(0f, r - min)
        if (what and CROP_R != 0) r = (r + dx).coerceIn(l + min, W)
        if (top) t = (t + dy).coerceIn(0f, b - min)
        if (what and CROP_B != 0) b = (b + dy).coerceIn(t + min, H)
        if (shape > 0f) {
            var w = r - l; var h = b - t
            when {
                sideX && sideY -> {
                    if (w / h > shape) h = w / shape else w = h * shape
                    val maxW = if (left) r else W - l; val maxH = if (top) b else H - t
                    if (w > maxW) { w = maxW; h = w / shape }
                    if (h > maxH) { h = maxH; w = h * shape }
                    if (left) l = r - w else r = l + w
                    if (top) t = b - h else b = t + h
                }
                sideX -> {
                    // the height follows about the box's middle
                    val cy = (c.y + c.h / 2) * H
                    h = w / shape
                    val maxH = 2 * minOf(cy, H - cy)
                    if (h > maxH) { h = maxH; w = h * shape; if (left) l = r - w else r = l + w }
                    t = cy - h / 2; b = cy + h / 2
                }
                sideY -> {
                    val cx = (c.x + c.w / 2) * W
                    w = h * shape
                    val maxW = 2 * minOf(cx, W - cx)
                    if (w > maxW) { w = maxW; h = w / shape; if (top) t = b - h else b = t + h }
                    l = cx - w / 2; r = cx + w / 2
                }
            }
        }
    }
    val x = (l / W).coerceIn(0f, 1f); val y = (t / H).coerceIn(0f, 1f)
    return ClipRecorder.Crop(x, y, ((r - l) / W).coerceIn(0.01f, 1f - x), ((b - t) / H).coerceIn(0.01f, 1f - y))
}

/**
 * The preview picture, as big as fits; with [crop] the box over it: drag inside to move it, its edges / corners to size
 * it, or anywhere outside to draw a new one. The rest of the picture is dimmed, and the mouse shows what a drag does.
 */
@Composable
private fun CropView(
    img: ImageBitmap, crop: ClipRecorder.Crop?, shape: Float, modifier: Modifier, cropEdit: Boolean = true,
    tint: ImageBitmap? = null, paint: (@Composable () -> Unit)? = null, onCrop: (ClipRecorder.Crop) -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val a = img.width.toFloat() / img.height.coerceAtLeast(1)
        val fitW = if (maxHeight.value > 0f && maxWidth / maxHeight > a) maxHeight * a else maxWidth
        val fitH = if (maxHeight.value > 0f && maxWidth / maxHeight > a) maxHeight else maxWidth / a
        Box(Modifier.width(fitW).height(fitH)) {
            Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
            tint?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
            if (crop != null) CropBox(crop, shape, cropEdit, onCrop)
            // the blur brush on top: it takes the mouse while painting (the crop box only shows)
            paint?.invoke()
        }
    }
}

@Composable
private fun CropBox(crop: ClipRecorder.Crop, shape: Float, edit: Boolean, onCrop: (ClipRecorder.Crop) -> Unit) {
    val now by rememberUpdatedState(crop)
    val set by rememberUpdatedState(onCrop)
    val d = LocalDensity.current
    val grab = with(d) { 12.dp.toPx() }
    var over by remember { mutableIntStateOf(0) }
    val cursor = when (over) {
        CROP_L or CROP_T, CROP_R or CROP_B -> java.awt.Cursor.NW_RESIZE_CURSOR
        CROP_R or CROP_T, CROP_L or CROP_B -> java.awt.Cursor.NE_RESIZE_CURSOR
        CROP_L, CROP_R -> java.awt.Cursor.E_RESIZE_CURSOR
        CROP_T, CROP_B -> java.awt.Cursor.N_RESIZE_CURSOR
        CROP_MOVE -> java.awt.Cursor.MOVE_CURSOR
        else -> java.awt.Cursor.CROSSHAIR_CURSOR
    }
    Canvas(Modifier.fillMaxSize().then(if (!edit) Modifier else Modifier
        .pointerHoverIcon(PointerIcon(java.awt.Cursor(cursor)))
        .pointerInput(shape) {
            awaitPointerEventScope {
                var what = 0; var start = now; var from = Offset.Zero; var fresh = false
                while (true) {
                    val e = awaitPointerEvent()
                    val p = e.changes.firstOrNull() ?: continue
                    val W = size.width.toFloat(); val H = size.height.toFloat()
                    when (e.type) {
                        PointerEventType.Press -> {
                            from = p.position; start = now
                            what = cropHit(now, p.position.x, p.position.y, W, H, grab)
                            // outside the box: a new one, drawn from here once the mouse moves
                            fresh = what == 0
                            p.consume()
                        }
                        PointerEventType.Move -> if (p.pressed) {
                            val dx = p.position.x - from.x; val dy = p.position.y - from.y
                            if (fresh && (kotlin.math.abs(dx) > grab / 2 || kotlin.math.abs(dy) > grab / 2)) {
                                // a tiny box at the press point, its far corner pulled to the mouse
                                val m = minOf(W, H) * 0.06f
                                val x0 = (if (dx < 0) from.x - m else from.x).coerceIn(0f, W - m)
                                val y0 = (if (dy < 0) from.y - m else from.y).coerceIn(0f, H - m)
                                start = ClipRecorder.Crop(x0 / W, y0 / H, m / W, m / H)
                                what = (if (dx < 0) CROP_L else CROP_R) or (if (dy < 0) CROP_T else CROP_B)
                                from = Offset(if (dx < 0) x0 else x0 + m, if (dy < 0) y0 else y0 + m)
                                fresh = false
                            }
                            if (what != 0) { set(dragCrop(start, what, p.position.x - from.x, p.position.y - from.y, W, H, shape)); p.consume() }
                        } else over = cropHit(now, p.position.x, p.position.y, W, H, grab)
                        PointerEventType.Release -> { what = 0; fresh = false; over = cropHit(now, p.position.x, p.position.y, W, H, grab) }
                        PointerEventType.Exit -> if (!p.pressed) over = 0
                    }
                }
            }
        })) {
        val l = crop.x * size.width; val t = crop.y * size.height
        val r = (crop.x + crop.w) * size.width; val b = (crop.y + crop.h) * size.height
        val dim = Color(0xA6000000)
        drawRect(dim, Offset.Zero, Size(size.width, t))
        drawRect(dim, Offset(0f, b), Size(size.width, size.height - b))
        drawRect(dim, Offset(0f, t), Size(l, b - t))
        drawRect(dim, Offset(r, t), Size(size.width - r, b - t))
        // thirds, the frame, and handles on the corners and edges
        val thin = Color.White.copy(alpha = 0.28f)
        for (k in 1..2) {
            drawLine(thin, Offset(l + (r - l) * k / 3, t), Offset(l + (r - l) * k / 3, b), 1f)
            drawLine(thin, Offset(l, t + (b - t) * k / 3), Offset(r, t + (b - t) * k / 3), 1f)
        }
        drawRect(Color.White, Offset(l, t), Size(r - l, b - t), style = Stroke(with(d) { 1.5.dp.toPx() }))
        val hs = with(d) { 8.dp.toPx() }
        for (hx in listOf(l, (l + r) / 2, r)) for (hy in listOf(t, (t + b) / 2, b)) {
            if (hx == (l + r) / 2 && hy == (t + b) / 2) continue
            drawRect(Color.White, Offset(hx - hs / 2, hy - hs / 2), Size(hs, hs))
            drawRect(Mc.Blue, Offset(hx - hs / 2, hy - hs / 2), Size(hs, hs), style = Stroke(1.5f))
        }
    }
}

/**
 * Over the whole editor once a GIF is made: the GIF playing (as big as the window allows), its size, and Show in
 * folder / Copy / Move to <linked folder> (once moved, the other buttons work on the moved file) / Delete (not happy
 * with it: the file goes, the editor stays on the same part to change and make again) / Make another / Done. Clicks
 * don't reach the editor under it.
 */
@Composable
private fun SavedBox(f: File, moveTo: List<File>, onDelete: () -> Unit, onMove: (File) -> Unit, onAnother: () -> Unit, onClose: () -> Unit) {
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
                moveTo.take(2).forEach { dir ->
                    val there = runCatching { f.parentFile?.canonicalFile == dir.canonicalFile }.getOrDefault(false)
                    Button(onClick = { onMove(dir) }, enabled = !there,
                        colors = ButtonDefaults.buttonColors(containerColor = Mc.Card2, contentColor = Mc.Text, disabledContainerColor = Mc.Card2, disabledContentColor = Mc.Green)) {
                        Text(if (there) "In ${dir.name} ✓" else "Move to ${dir.name}")
                    }
                }
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

