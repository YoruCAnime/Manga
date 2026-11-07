package com.yoruc.mangacracker.ui

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.util.Size
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * What the player made: GIFs (Pictures/MangaCracker/GIFs), snapshots (Pictures/MangaCracker/Snapshots) and
 * recordings (Movies/MangaCracker), read from the phone's media store. Only the app's own files are listed (Android
 * shows an app what it saved without any permission).
 */
object Gallery {
    enum class Kind(val label: String, val plural: String) { GIF("GIF", "GIFs"), SNAP("Snapshot", "Snapshots"), CLIP("Clip", "Clips") }
    data class Item(val uri: Uri, val name: String, val kind: Kind, val time: Long, val size: Long, val durationMs: Long, val mime: String)

    fun list(ctx: Context): List<Item> {
        val out = ArrayList<Item>()
        val cr = ctx.contentResolver
        val img = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        cr.query(
            img,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.RELATIVE_PATH),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? OR ${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?",
            arrayOf("Pictures/MangaCracker/GIFs%", "Pictures/MangaCracker/Snapshots%"), null,
        )?.use { c ->
            while (c.moveToNext()) {
                val mime = c.getString(4).orEmpty()
                out += Item(ContentUris.withAppendedId(img, c.getLong(0)), c.getString(1).orEmpty(),
                    if (mime == "image/gif" || c.getString(5).orEmpty().contains("/GIFs")) Kind.GIF else Kind.SNAP,
                    c.getLong(2) * 1000, c.getLong(3), 0L, mime)
            }
        }
        val vid = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        cr.query(
            vid,
            arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.MIME_TYPE, MediaStore.Video.Media.DURATION),
            "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("Movies/MangaCracker%"), null,
        )?.use { c ->
            while (c.moveToNext()) out += Item(ContentUris.withAppendedId(vid, c.getLong(0)), c.getString(1).orEmpty(), Kind.CLIP,
                c.getLong(2) * 1000, c.getLong(3), c.getLong(5), c.getString(4) ?: "video/mp4")
        }
        return out.sortedByDescending { it.time }
    }

    fun thumb(ctx: Context, it: Item): ImageBitmap? =
        runCatching { ctx.contentResolver.loadThumbnail(it.uri, Size(480, 270), null).asImageBitmap() }.getOrNull()

    fun share(ctx: Context, it: Item) {
        val send = Intent(Intent.ACTION_SEND).setType(it.mime.ifEmpty { "*/*" }).putExtra(Intent.EXTRA_STREAM, it.uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(Intent.createChooser(send, "Share").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    /** Opens it in the phone's own app (a video player for clips). */
    fun open(ctx: Context, it: Item) {
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(it.uri, it.mime.ifEmpty { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        runCatching { ctx.startActivity(view) }.onFailure { Toast.makeText(ctx, "No app here opens this file", Toast.LENGTH_SHORT).show() }
    }

    fun delete(ctx: Context, it: Item): Boolean = runCatching { ctx.contentResolver.delete(it.uri, null, null) > 0 }.getOrDefault(false)
}

private fun mb(n: Long) = if (n >= 1_048_576) "%.1f MB".format(java.util.Locale.US, n / 1_048_576.0) else "${(n / 1024).coerceAtLeast(1)} KB"
private fun len(ms: Long) = "%d:%02d".format(ms / 60_000, ms / 1000 % 60)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun GalleryScreen(nav: Nav) {
    val ctx = LocalContext.current
    var all by remember { mutableStateOf<List<Gallery.Item>?>(null) }
    var tick by remember { mutableIntStateOf(0) }
    var filter by remember { mutableStateOf<Gallery.Kind?>(null) }
    var open by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(tick) { all = withContext(Dispatchers.IO) { runCatching { Gallery.list(ctx) }.getOrDefault(emptyList()) } }
    val shown = remember(all, filter) { all.orEmpty().filter { filter == null || it.kind == filter } }

    val o = open
    if (o != null && o in shown.indices) {
        Viewer(shown, o, onClose = { open = null }, onDeleted = { tick++; if (shown.size <= 1) open = null else if (o >= shown.size - 1) open = o - 1 })
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            Text("My GIFs & clips", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Mc.Text, modifier = Modifier.weight(1f))
        }
        val list = all
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Chip("All · ${list?.size ?: 0}", filter == null) { filter = null } }
            items(Gallery.Kind.entries.toList()) { k -> Chip("${k.plural} · ${list?.count { it.kind == k } ?: 0}", filter == k) { filter = k } }
        }
        when {
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Mc.Blue) }
            shown.isEmpty() -> EmptyBox(if (list.isEmpty()) "Nothing made yet" else "None of these yet",
                "GIFs, snapshots and recordings you make in the video player show up here.")
            else -> LazyVerticalGrid(GridCells.Adaptive(160.dp), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(shown, key = { it.uri.toString() }) { it -> Tile(it) { open = shown.indexOf(it) } }
            }
        }
    }
}

@Composable
private fun Chip(label: String, on: Boolean, onClick: () -> Unit) {
    Box(Modifier.clip(RoundedCornerShape(50)).background(if (on) Mc.Blue else Mc.Card2).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp)) {
        Text(label, color = if (on) Mc.Bg else Mc.Text, fontSize = 13.sp)
    }
}

@Composable
private fun Tile(it: Gallery.Item, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val thumb by produceState<ImageBitmap?>(null, it.uri) { value = withContext(Dispatchers.IO) { Gallery.thumb(ctx, it) } }
    Column {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Mc.Card2).clickable(onClick = onOpen)) {
            thumb?.let { img -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            Text(
                when (it.kind) { Gallery.Kind.GIF -> "GIF"; Gallery.Kind.SNAP -> it.name.substringAfterLast('.', "PNG").uppercase(); Gallery.Kind.CLIP -> if (it.durationMs > 0) len(it.durationMs) else "Clip" },
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xB3000000)).padding(horizontal = 6.dp, vertical = 2.dp),
            )
            if (it.kind == Gallery.Kind.CLIP) Icon(Icons.Rounded.PlayArrow, null, tint = Color.White,
                modifier = Modifier.align(Alignment.Center).size(36.dp).clip(RoundedCornerShape(50)).background(Color(0x80000000)).padding(5.dp))
        }
        Text(it.name.substringBeforeLast('.'), color = Mc.Text, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 5.dp))
        Text("${timeAgo(it.time)} · ${mb(it.size)}", color = Mc.Text3, fontSize = 11.sp)
    }
}

/** Full screen, swipe for the next / previous one: a GIF plays, a clip opens in the phone's video player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Viewer(list: List<Gallery.Item>, start: Int, onClose: () -> Unit, onDeleted: () -> Unit) {
    val ctx = LocalContext.current
    BackHandler(onBack = onClose)
    val pager = rememberPagerState(initialPage = start) { list.size }
    val it = list[pager.currentPage.coerceIn(list.indices)]
    var sure by remember(it.uri) { mutableStateOf(false) }
    LaunchedEffect(sure) { if (sure) { delay(3000); sure = false } }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text2) }
            Column(Modifier.weight(1f)) {
                Text(it.name.substringBeforeLast('.'), color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${it.kind.label} · ${timeAgo(it.time)} · ${mb(it.size)}" + (if (it.durationMs > 0) " · ${len(it.durationMs)}" else "") +
                    " · ${pager.currentPage + 1} of ${list.size}", color = Mc.Text3, fontSize = 12.sp)
            }
        }
        HorizontalPager(pager, Modifier.weight(1f).fillMaxWidth(), key = { list[it].uri.toString() }) { page ->
            val p = list[page]
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (p.kind == Gallery.Kind.CLIP) {
                    val thumb by produceState<ImageBitmap?>(null, p.uri) { value = withContext(Dispatchers.IO) { Gallery.thumb(ctx, p) } }
                    Box(Modifier.fillMaxSize().clickable { Gallery.open(ctx, p) }, contentAlignment = Alignment.Center) {
                        thumb?.let { img -> Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                        Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.White, modifier = Modifier.size(72.dp).clip(RoundedCornerShape(50)).background(Color(0x99000000)).padding(10.dp))
                    }
                } else AsyncImage(p.uri, p.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)   // GIFs play
            }
        }
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.navigationBars).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
            ViewerBtn(Icons.Rounded.Share, "Share") { Gallery.share(ctx, it) }
            ViewerBtn(if (it.kind == Gallery.Kind.CLIP) Icons.Rounded.PlayArrow else Icons.Rounded.OpenInNew, if (it.kind == Gallery.Kind.CLIP) "Play" else "Open") { Gallery.open(ctx, it) }
            ViewerBtn(Icons.Rounded.Delete, if (sure) "Tap again" else "Delete", Mc.Orange) {
                if (!sure) sure = true
                else if (Gallery.delete(ctx, it)) { Toast.makeText(ctx, "Deleted", Toast.LENGTH_SHORT).show(); onDeleted() }
                else Toast.makeText(ctx, "Couldn't delete it", Toast.LENGTH_SHORT).show()
            }
        }
    }
}

@Composable
private fun ViewerBtn(icon: ImageVector, label: String, color: Color = Mc.Text, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(10.dp)).background(Mc.Card2).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = color, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = color, fontSize = 14.sp)
    }
}
