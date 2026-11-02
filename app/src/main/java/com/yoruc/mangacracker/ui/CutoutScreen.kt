package com.yoruc.mangacracker.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.AddCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.RemoveCircle
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.ImageSaver
import com.yoruc.mangacracker.cutout.Cutter
import com.yoruc.mangacracker.cutout.CutoutModels
import com.yoruc.mangacracker.cutout.Nets
import com.yoruc.mangacracker.cutout.OrtNets
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.min
import kotlin.math.roundToInt

/** What the reader hands over: the page picture and where it was long-pressed (0..1 of the page). */
class CutoutRequest(val url: String, val fx: Float, val fy: Float, val folder: String, val name: String)

/** Builds the networks; tests swap in their own. */
var cutoutNets: (android.content.Context) -> Nets = { ctx -> OrtNets(CutoutModels.dir(ctx)) }

/**
 * Character cutout: the character under the long-press, cut out of its panel. The panel shows with everything that
 * is not cut out dimmed; "Add" / "Remove" + a tap on the picture fix the edges; Save writes a transparent PNG.
 */
@Composable
fun CutoutScreen(req: CutoutRequest, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var ready by remember { mutableStateOf(CutoutModels.ready(ctx)) }
    var downloading by remember { mutableStateOf<Pair<Long, Long>?>(null) }
    var phase by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var job by remember { mutableStateOf<Cutter.Job?>(null) }
    var cut by remember { mutableStateOf<Triple<IntArray, Int, Int>?>(null) }
    var shown by remember { mutableStateOf<Bitmap?>(null) }
    var keepMode by remember { mutableStateOf(true) }
    var preview by remember { mutableStateOf(false) }
    var alphaNow by remember { mutableStateOf<FloatArray?>(null) }
    val taps = remember { mutableStateListOf<Cutter.Point>() }
    var nets by remember { mutableStateOf<Nets?>(null) }
    DisposableEffect(Unit) { onDispose { nets?.close() } }

    suspend fun redo() {
        val j = job ?: return
        phase = "Cutting out…"
        val a = withContext(Dispatchers.Default) { j.alpha(taps.toList()) }
        alphaNow = a
        cut = withContext(Dispatchers.Default) { j.render(a) }
        phase = null
    }

    LaunchedEffect(alphaNow, preview) {
        val j = job; val a = alphaNow
        if (j != null && a != null) shown = withContext(Dispatchers.Default) { overlay(j, a, preview) }
    }

    LaunchedEffect(ready) {
        if (!ready) return@LaunchedEffect
        runCatching {
            phase = "Loading the page…"
            val (px, pw, ph, tapX, tapY) = withContext(Dispatchers.IO) { loadAround(Http.bytes(req.url), req.fx, req.fy) }
            phase = "Finding the character…"
            val n = withContext(Dispatchers.Default) { cutoutNets(ctx) }
            nets = n
            val j = withContext(Dispatchers.Default) { Cutter(n).start(px, pw, ph, tapX, tapY) }
            job = j
            redo()
        }.onFailure { error = it.message ?: it.javaClass.simpleName; phase = null }
    }

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(Modifier.fillMaxSize().background(Mc.Bg)) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text2) }
                Text("Character cutout", color = Mc.Text, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, modifier = Modifier.weight(1f))
                if (cut != null) {
                    IconButton(onClick = { preview = !preview }) {
                        Icon(if (preview) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (preview) "Show the panel" else "Preview", tint = if (preview) Mc.Blue else Mc.Text2)
                    }
                    IconButton(onClick = {
                        val c = cut ?: return@IconButton
                        scope.launch {
                            runCatching { ImageSaver.sharePng(ctx, Bitmap.createBitmap(c.first, c.second, c.third, Bitmap.Config.ARGB_8888)) }
                                .onFailure { Toast.makeText(ctx, "Couldn't share", Toast.LENGTH_SHORT).show() }
                        }
                    }) { Icon(Icons.Rounded.Share, "Share", tint = Mc.Text2) }
                }
            }
            when {
                !ready -> DownloadCard(downloading, error) {
                    error = null
                    scope.launch {
                        runCatching { CutoutModels.download(ctx) { d, t -> downloading = d to t } }
                            .onSuccess { downloading = null; ready = true }
                            .onFailure { downloading = null; error = it.message }
                    }
                }
                error != null -> Text(error!!, color = Mc.Orange, modifier = Modifier.padding(20.dp))
                else -> {
                    Box(Modifier.weight(1f).fillMaxWidth().padding(8.dp)) {
                        val b = shown
                        if (b != null) Picture(b, job, if (preview) emptyList() else taps) { x, y ->
                            if (phase == null && !preview) { taps += Cutter.Point(x, y, keepMode); scope.launch { redo() } }
                        }
                        phase?.let {
                            Column(Modifier.align(Alignment.Center).clip(RoundedCornerShape(12.dp)).background(Color(0xE60B1622)).padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Mc.Blue)
                                Spacer(Modifier.height(10.dp))
                                Text(it, color = Mc.Text)
                            }
                        }
                    }
                    if (job != null) {
                        Text(if (preview) "This is what Save keeps." else if (keepMode) "Tap a part that is missing to add it." else "Tap a part that shouldn't be there (a speech bubble goes whole).",
                            color = Mc.Text3, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp))
                        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ModeButton("Add", Icons.Rounded.AddCircle, keepMode) { keepMode = true }
                            ModeButton("Remove", Icons.Rounded.RemoveCircle, !keepMode) { keepMode = false }
                            IconButton(enabled = taps.isNotEmpty() && phase == null, onClick = { taps.removeAt(taps.lastIndex); scope.launch { redo() } }) {
                                Icon(Icons.AutoMirrored.Rounded.Undo, "Undo", tint = if (taps.isNotEmpty()) Mc.Text2 else Mc.Text3)
                            }
                            Spacer(Modifier.weight(1f))
                            Button(enabled = cut != null && phase == null, onClick = {
                                val c = cut ?: return@Button
                                scope.launch {
                                    runCatching { ImageSaver.savePng(ctx, Bitmap.createBitmap(c.first, c.second, c.third, Bitmap.Config.ARGB_8888), req.folder, req.name) }
                                        .onSuccess { Toast.makeText(ctx, "Saved to $it", Toast.LENGTH_SHORT).show(); onClose() }
                                        .onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                            }, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) {
                                Icon(Icons.Rounded.Download, null); Spacer(Modifier.width(6.dp)); Text("Save")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadCard(progress: Pair<Long, Long>?, error: String?, onStart: () -> Unit) {
    Column(Modifier.padding(16.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("One-time download", color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
        Text("Cutouts are made on your phone by two picture models (${CutoutModels.totalBytes / 1_000_000} MB, best on Wi-Fi). " +
            "Nothing you cut out leaves the phone.", color = Mc.Text2, fontSize = 13.5.sp)
        error?.let { Text(it, color = Mc.Orange, fontSize = 13.sp) }
        if (progress != null) {
            LinearProgressIndicator(progress = { progress.first / progress.second.coerceAtLeast(1).toFloat() }, Modifier.fillMaxWidth(), color = Mc.Blue, trackColor = Mc.Card2)
            Text("${progress.first / 1_000_000} / ${progress.second / 1_000_000} MB", color = Mc.Text3, fontSize = 12.sp)
        } else Button(onClick = onStart, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg)) { Text("Download") }
    }
}

@Composable
private fun ModeButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, on: Boolean, onClick: () -> Unit) {
    Button(onClick = onClick, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        colors = ButtonDefaults.buttonColors(containerColor = if (on) Mc.Blue else Mc.Card2, contentColor = if (on) Mc.Bg else Mc.Text)) {
        Icon(icon, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(label, fontSize = 13.sp)
    }
}

/** The panel fitted to the box, the add / remove taps on it; taps come back in panel pixels. */
@Composable
private fun Picture(b: Bitmap, job: Cutter.Job?, taps: List<Cutter.Point>, onTap: (Float, Float) -> Unit) {
    val img = remember(b) { b.asImageBitmap() }
    Canvas(Modifier.fillMaxSize().pointerInput(b) {
        detectTapGestures { o ->
            val s = min(size.width / b.width.toFloat(), size.height / b.height.toFloat())
            val ox = (size.width - b.width * s) / 2f; val oy = (size.height - b.height * s) / 2f
            val x = (o.x - ox) / s; val y = (o.y - oy) / s
            if (x in 0f..b.width.toFloat() && y in 0f..b.height.toFloat()) onTap(x, y)
        }
    }) {
        val s = min(size.width / b.width, size.height / b.height)
        val dw = b.width * s; val dh = b.height * s
        val ox = (size.width - dw) / 2f; val oy = (size.height - dh) / 2f
        // checkerboard under the picture, so see-through parts read as see-through
        val cell = 12.dp.toPx()
        var yy = 0f; var row = 0
        while (yy < dh) {
            var xx = 0f; var col = row % 2
            while (xx < dw) {
                drawRect(if (col % 2 == 0) Color(0xFF2A3546) else Color(0xFF1C2532), Offset(ox + xx, oy + yy), Size(min(cell, dw - xx), min(cell, dh - yy)))
                xx += cell; col++
            }
            yy += cell; row++
        }
        drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(b.width, b.height), dstOffset = IntOffset(ox.roundToInt(), oy.roundToInt()),
            dstSize = IntSize(dw.roundToInt(), dh.roundToInt()))
        if (job != null) taps.forEach { t ->
            val c = Offset(ox + t.x * s, oy + t.y * s)
            drawCircle(if (t.keep) Color(0xFF7BD555) else Color(0xFFFF5C8A), 9.dp.toPx(), c)
            drawCircle(Color.White, 9.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
    }
}

/** The panel with the cutout at full strength and the rest faint, for checking and fixing ([alone]: the cutout only). */
private fun overlay(j: Cutter.Job, a: FloatArray, alone: Boolean): Bitmap {
    val out = IntArray(j.w * j.h)
    for (y in 0 until j.h) for (x in 0 until j.w) {
        val i = y * j.w + x
        val al = a[i].coerceIn(0f, 1f)
        val p = j.px[(y + j.box.y0) * j.pw + x + j.box.x0]
        val k = if (alone) al else 0.22f + 0.78f * al
        out[i] = ((k * 255).roundToInt() shl 24) or (p and 0xFFFFFF)
    }
    return Bitmap.createBitmap(out, j.w, j.h, Bitmap.Config.ARGB_8888)
}

private data class Loaded(val px: IntArray, val w: Int, val h: Int, val x: Int, val y: Int)

/**
 * Decodes the page around the long-press: the whole page, or for a tall strip a band of 3 screen-widths either side,
 * so a 20 000 px manhwa strip never sits in memory whole.
 */
private fun loadAround(bytes: ByteArray, fx: Float, fy: Float): Loaded {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
    val w = o.outWidth; val h = o.outHeight
    if (w <= 0 || h <= 0) throw IllegalStateException("This picture can't be read")
    val tx = (fx * w).roundToInt().coerceIn(0, w - 1); val ty = (fy * h).roundToInt().coerceIn(0, h - 1)
    val band = 3 * w
    val y0 = if (h > 2 * band) (ty - band).coerceIn(0, h - 2 * band) else 0
    val y1 = if (h > 2 * band) y0 + 2 * band else h
    val bmp = (if (y0 == 0 && y1 == h) BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(bytes, 0, bytes.size, false)!!.decodeRegion(Rect(0, y0, w, y1), null))
        ?: throw IllegalStateException("This picture can't be read")
    val sw = bmp.width; val sh = bmp.height
    val px = IntArray(sw * sh); bmp.getPixels(px, 0, sw, 0, 0, sw, sh)
    bmp.recycle()
    return Loaded(px, sw, sh, tx, ty - y0)
}
