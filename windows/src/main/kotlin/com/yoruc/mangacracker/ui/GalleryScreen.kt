package com.yoruc.mangacracker.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.platform.Avif
import com.yoruc.mangacracker.platform.ClipRecorder
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.Codec
import org.jetbrains.skia.Data
import java.io.File
import java.security.MessageDigest

/** What the player made: GIFs, snapshots and recordings, read from their folders (newest first). */
object Gallery {
    enum class Kind(val label: String, val plural: String) { GIF("GIF", "GIFs"), SNAP("Snapshot", "Snapshots"), CLIP("Clip", "Clips") }
    data class Item(val file: File, val kind: Kind, val time: Long, val size: Long)

    private val PICS = setOf("png", "jpg", "jpeg", "webp")
    private val MOVIES = setOf("mp4", "mkv", "webm", "mov")

    /** The folders Settings names now, and the defaults (things made before a folder was changed). */
    private fun folders(): List<File> = listOf(Desktop.clips, Desktop.videos, Desktop.snapshots, File(Desktop.pictures, "Snapshots"), File(Desktop.pictures, "GIFs"))
        .filter { it.isDirectory }.distinctBy { runCatching { it.canonicalPath }.getOrDefault(it.absolutePath).lowercase() }

    fun list(): List<Item> = folders().flatMap { d ->
        d.listFiles().orEmpty().mapNotNull { f ->
            if (!f.isFile || f.name.contains(".part.")) return@mapNotNull null
            val ext = f.extension.lowercase()
            val kind = when { ext == "gif" -> Kind.GIF; ext in PICS -> Kind.SNAP; ext in MOVIES -> Kind.CLIP; else -> null } ?: return@mapNotNull null
            Item(f, kind, f.lastModified(), f.length())
        }
    }.distinctBy { it.file.absolutePath.lowercase() }.sortedByDescending { it.time }

    // thumbnails: 320 px wide, kept on disk (cache\gallery) and the last 60 in memory
    private val mem = object : LinkedHashMap<String, Pair<ImageBitmap, Long>>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<ImageBitmap, Long>>?) = size > 60
    }
    private val busy = Semaphore(2)

    private fun id(f: File): String {
        val md = MessageDigest.getInstance("MD5").digest("${f.absolutePath}|${f.length()}|${f.lastModified()}".toByteArray())
        return md.joinToString("") { "%02x".format(it) }.take(20)
    }

    /** The picture for a tile and, for clips, their length in ms (0 when unknown). */
    suspend fun thumb(it: Item): Pair<ImageBitmap, Long>? {
        val key = id(it.file)
        synchronized(mem) { mem[key] }?.let { return it }
        return busy.withPermit {
            withContext(Dispatchers.IO) {
                runCatching {
                    val dir = File(Desktop.ctx.cacheDir, "gallery").apply { mkdirs() }
                    val jpg = File(dir, "$key.jpg"); val len = File(dir, "$key.len")
                    if (!jpg.isFile) {
                        if (it.kind == Kind.CLIP) clipThumb(it.file, jpg, len)
                        else {
                            val src = javax.imageio.ImageIO.read(it.file) ?: return@runCatching null
                            val w = minOf(320, src.width); val h = (src.height.toLong() * w / src.width.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                            val out = java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB)
                            out.createGraphics().apply {
                                setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                                drawImage(src, 0, 0, w, h, null); dispose()
                            }
                            javax.imageio.ImageIO.write(out, "jpg", jpg)
                        }
                    }
                    val img = javax.imageio.ImageIO.read(jpg) ?: return@runCatching null
                    val r = img.toComposeImageBitmap() to (len.takeIf { f -> f.isFile }?.readText()?.trim()?.toLongOrNull() ?: 0L)
                    synchronized(mem) { mem[key] = r }
                    r
                }.getOrNull()
            }
        }
    }

    /** One frame a second in (the first for very short clips) and the clip's length, read by ffmpeg. */
    private fun clipThumb(f: File, jpg: File, len: File) {
        val ff = Avif.ffmpeg ?: return
        val p = ProcessBuilder(ff.path, "-hide_banner", "-nostdin", "-y", "-ss", "1", "-i", f.path, "-frames:v", "1", "-an", "-vf", "scale=320:-2", jpg.path)
            .redirectErrorStream(true).start()
        ClipRecorder.lower(p)
        val log = p.inputStream.bufferedReader().readText()
        p.waitFor()
        Regex("Duration: (\\d+):(\\d+):(\\d+(?:\\.\\d+)?)").find(log)?.destructured?.let { (h, m, s) ->
            len.writeText(((h.toLong() * 3600 + m.toLong() * 60) * 1000 + (s.toDouble() * 1000).toLong()).toString())
        }
        if (!jpg.isFile) {   // shorter than a second: the first frame
            val q = ProcessBuilder(ff.path, "-hide_banner", "-loglevel", "error", "-nostdin", "-y", "-i", f.path, "-frames:v", "1", "-an", "-vf", "scale=320:-2", jpg.path)
                .redirectErrorStream(true).start()
            ClipRecorder.lower(q); q.inputStream.readAllBytes(); q.waitFor()
        }
    }

    /** Recycle Bin, so a mistake can be undone in Explorer. */
    fun delete(f: File): Boolean = runCatching { java.awt.Desktop.getDesktop().moveToTrash(f) }.getOrDefault(false)

    /** Opens the file in Windows' own app for it (Photos, Media Player...). */
    fun open(f: File) { runCatching { java.awt.Desktop.getDesktop().open(f) } }

    /** The file itself on the clipboard: paste it into Discord, a chat or a folder. */
    fun copy(f: File) {
        val t = object : java.awt.datatransfer.Transferable {
            override fun getTransferDataFlavors() = arrayOf(java.awt.datatransfer.DataFlavor.javaFileListFlavor)
            override fun isDataFlavorSupported(fl: java.awt.datatransfer.DataFlavor) = fl == java.awt.datatransfer.DataFlavor.javaFileListFlavor
            override fun getTransferData(fl: java.awt.datatransfer.DataFlavor): Any = listOf(f)
        }
        java.awt.Toolkit.getDefaultToolkit().systemClipboard.setContents(t, null)
    }
}

private fun mb(n: Long) = if (n >= 1_048_576) "%.1f MB".format(java.util.Locale.US, n / 1_048_576.0) else "${(n / 1024).coerceAtLeast(1)} KB"
private fun len(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

/** Test runs: the gallery's list as it is shown (filter applied). */
internal var galleryProbe: ((List<Gallery.Item>, Int?) -> Unit)? = null
/** Test runs (MC_OPEN demo:gallery:<n>): open the viewer on the n-th item. */
internal var galleryStartItem: Int? = null

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(nav: Nav) {
    var all by remember { mutableStateOf<List<Gallery.Item>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<Gallery.Kind?>(null) }
    var open by remember { mutableStateOf<Int?>(galleryStartItem.also { galleryStartItem = null }) }
    LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { Gallery.list() } }
    val shown = remember(all, filter) { all.orEmpty().filter { filter == null || it.kind == filter } }
    LaunchedEffect(shown, open) { galleryProbe?.invoke(shown, open) }
    val scope = rememberCoroutineScope()
    fun delete(it: Gallery.Item) {
        if (Gallery.delete(it.file)) { Desktop.toast("Moved to the Recycle Bin"); tick++ } else Desktop.toast("Couldn't move it to the Recycle Bin")
    }

    val o = open
    if (o != null && o in shown.indices) {
        GalleryViewer(shown, o, onIndex = { open = it }, onClose = { open = null }, onDelete = { delete(it); if (shown.size <= 1) open = null else if (o >= shown.size - 1) open = o - 1 })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            Text("My GIFs & clips", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
            IconButton(onClick = { tick++ }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = Mc.Text2) }
            IconButton(onClick = { Desktop.reveal(if (filter == Gallery.Kind.SNAP) Desktop.snapshots else Desktop.clips) }) {
                Icon(Icons.Rounded.FolderOpen, "Open the folder", tint = Mc.Text2)
            }
        }
        val list = all
        Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Chip("All · ${list?.size ?: 0}", filter == null) { filter = null }
            Gallery.Kind.entries.forEach { k -> Chip("${k.plural} · ${list?.count { it.kind == k } ?: 0}", filter == k) { filter = k } }
            Spacer(Modifier.width(8.dp))
            if (list != null) Text(mb(shown.sumOf { it.size }), color = Mc.Text3, fontSize = 12.5.sp)
        }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mc.Blue) }
            shown.isEmpty() -> EmptyBox(if (list.isEmpty()) "Nothing made yet" else "None of these yet",
                "GIFs, snapshots and recordings you make in the video player show up here.")
            else -> LazyVerticalGrid(GridCells.Adaptive(230.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                items(shown, key = { it.file.absolutePath }) { it ->
                    Tile(it, onOpen = { open = shown.indexOf(it) }, onDelete = { scope.launch { delete(it) } })
                }
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Mc.Blue else Mc.Card2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        Text(label, color = if (on) Mc.Bg else Mc.Text, fontSize = 12.5.sp)
    }
}

@Composable
private fun Tile(it: Gallery.Item, onOpen: () -> Unit, onDelete: () -> Unit) {
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    val thumb by produceState<Pair<ImageBitmap, Long>?>(null, it.file.absolutePath, it.time) { value = Gallery.thumb(it) }
    Column(Modifier.hoverable(hover)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Mc.Card2).clickable(onClick = onOpen)) {
            // a GIF plays while the mouse is over it
            if (hovered && it.kind == Gallery.Kind.GIF) AnimatedGif(it.file, Modifier.fillMaxSize(), ContentScale.Crop)
            else thumb?.let { (img, _) -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                when (it.kind) { Gallery.Kind.GIF -> "GIF"; Gallery.Kind.SNAP -> it.file.extension.uppercase(); Gallery.Kind.CLIP -> thumb?.second?.takeIf { it > 0 }?.let { len(it) } ?: "Clip" },
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (it.kind == Gallery.Kind.CLIP) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(40.dp).clip(RoundedCornerShape(50)).background(Color(0x80000000)).padding(6.dp))
            if (hovered) Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallIcon(Icons.Rounded.ContentCopy, "Copy") { Gallery.copy(it.file); Desktop.toast("Copied: paste it in Discord, a chat or a folder") }
                SmallIcon(Icons.Rounded.FolderOpen, "Show in folder") { Desktop.reveal(it.file) }
                SmallIcon(Icons.Rounded.Delete, "Delete") { onDelete() }
            }
        }
        Text(it.file.nameWithoutExtension, color = Mc.Text, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        Text("${timeAgo(it.time)} · ${mb(it.size)}", color = Mc.Text3, fontSize = 11.5.sp)
    }
}

@Composable
private fun SmallIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(Color(0xCC0B1622)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, label, tint = Color.White, modifier = Modifier.size(17.dp))
    }
}

/** One item big: a GIF plays, a clip shows its picture and plays in Windows' own player. ← → step (through the filter's items), Esc closes. */
@Composable
internal fun GalleryViewer(list: List<Gallery.Item>, index: Int, onIndex: (Int) -> Unit, onClose: () -> Unit, onDelete: (Gallery.Item) -> Unit) {
    val it = list[index]
    val focus = remember { FocusRequester() }
    androidx.activity.compose.BackHandler(onBack = onClose)
    // the keys stay with the viewer after each step and after a button was clicked
    LaunchedEffect(index) { runCatching { focus.requestFocus() } }
    val thumb by produceState<Pair<ImageBitmap, Long>?>(null, it.file.absolutePath) { value = Gallery.thumb(it) }
    Column(
        Modifier.fillMaxSize().background(Mc.Bg).focusRequester(focus).focusable().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when (e.key) {
                Key.DirectionLeft -> { if (index > 0) onIndex(index - 1); true }
                Key.DirectionRight -> { if (index < list.size - 1) onIndex(index + 1); true }
                Key.Delete -> { onDelete(it); true }
                else -> false
            }
        },
    ) {
        Row(Modifier.fillMaxWidth().padding(end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text2) }
            Column(Modifier.weight(1f)) {
                Text(it.file.nameWithoutExtension, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${it.kind.label} · ${timeAgo(it.time)} · ${mb(it.size)}" + (thumb?.second?.takeIf { it > 0 }?.let { " · ${len(it)}" } ?: "") +
                    " · ${index + 1} of ${list.size}", color = Mc.Text3, fontSize = 12.sp)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(enabled = index > 0, onClick = { onIndex(index - 1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, "Previous", tint = if (index > 0) Mc.Text else Mc.Line, modifier = Modifier.size(36.dp)) }
            Box(Modifier.weight(1f).fillMaxSize().padding(8.dp), contentAlignment = Alignment.Center) {
                when (it.kind) {
                    Gallery.Kind.GIF -> AnimatedGif(it.file, Modifier.fillMaxSize(), ContentScale.Fit)
                    Gallery.Kind.SNAP -> {
                        val full by produceState<ImageBitmap?>(null, it.file.absolutePath) {
                            value = withContext(Dispatchers.IO) { runCatching { javax.imageio.ImageIO.read(it.file)?.toComposeImageBitmap() }.getOrNull() }
                        }
                        (full ?: thumb?.first)?.let { img -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    }
                    Gallery.Kind.CLIP -> Box(Modifier.fillMaxSize().clickable { Gallery.open(it.file) }, contentAlignment = Alignment.Center) {
                        thumb?.first?.let { img -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(10.dp))
                    }
                }
            }
            IconButton(enabled = index < list.size - 1, onClick = { onIndex(index + 1) }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, "Next", tint = if (index < list.size - 1) Mc.Text else Mc.Line, modifier = Modifier.size(36.dp)) }
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            ViewerBtn(Icons.Rounded.ContentCopy, "Copy") { Gallery.copy(it.file); Desktop.toast("Copied: paste it in Discord, a chat or a folder") }
            ViewerBtn(Icons.Rounded.FolderOpen, "Show in folder") { Desktop.reveal(it.file) }
            ViewerBtn(if (it.kind == Gallery.Kind.CLIP) Icons.Rounded.PlayArrow else Icons.Rounded.OpenInNew, if (it.kind == Gallery.Kind.CLIP) "Play" else "Open") { Gallery.open(it.file) }
            ViewerBtn(Icons.Rounded.Delete, "Delete", Mc.Orange) { onDelete(it) }
        }
    }
}

@Composable
private fun ViewerBtn(icon: ImageVector, label: String, color: Color = Mc.Text, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Mc.Card2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 13.5.sp)
    }
}

/** A GIF playing at its own speed (one picture in memory, decoded frame by frame). */
@Composable
internal fun AnimatedGif(file: File, modifier: Modifier, scale: ContentScale) {
    var pic by remember(file) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(file) {
        val codec = withContext(Dispatchers.IO) { runCatching { Codec.makeFromData(Data.makeFromBytes(file.readBytes())) }.getOrNull() } ?: return@LaunchedEffect
        val n = codec.frameCount.coerceAtLeast(1)
        val durations = IntArray(n) { i -> runCatching { codec.getFrameInfo(i).duration }.getOrDefault(100).let { if (it <= 10) 100 else it } }
        val bmp = Bitmap().apply { allocN32Pixels(codec.width, codec.height) }
        try {
            var i = 0
            while (true) {
                val started = System.nanoTime()
                pic = withContext(Dispatchers.Default) {
                    runCatching {
                        if (i == 0) codec.readPixels(bmp, 0) else codec.readPixels(bmp, i, i - 1)
                        org.jetbrains.skia.Image.makeFromBitmap(bmp).use { it.toComposeImageBitmap() }
                    }.getOrNull()
                } ?: pic
                if (n == 1) break
                delay((durations[i] - (System.nanoTime() - started) / 1_000_000).coerceAtLeast(5))
                i = (i + 1) % n
            }
        } finally { bmp.close(); codec.close() }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        pic?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = scale) } ?: CircularProgressIndicator(color = Mc.Blue, modifier = Modifier.size(28.dp))
    }
}
