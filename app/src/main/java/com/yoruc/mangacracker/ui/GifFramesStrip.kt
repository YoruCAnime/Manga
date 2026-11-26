package com.yoruc.mangacracker.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowLeft
import androidx.compose.material.icons.rounded.KeyboardDoubleArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yoruc.mangacracker.gif.FrameSource
import com.yoruc.mangacracker.gif.Resample
import com.yoruc.mangacracker.gif.fillBetween
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * "✂ Frames" in the GIF panel: the picked range's frames one by one in a row (like the PC GIF editor). Tap a frame to
 * look at it (the video behind shows it) and pick / unpick it, ✕ on a frame deletes just that one. Buttons: Delete all
 * before, Delete, Delete all after (from the frame tapped last), Select between (first to last picked), None, Undo,
 * Restore all. Deleted frames leave the row and the GIF; numbers stay as they were.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun GifFramesView(g: GifEdit, onShow: (Long) -> Unit, onMake: () -> Unit, onCancel: () -> Unit) {
    val s = g.strip
    val loading = g.stripLoading
    // header: what the GIF is now, back to the settings
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("✂ Frames", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(Modifier.width(10.dp))
        if (s != null && loading == null) {
            val n = g.keptCount
            Text("Your GIF: $n frames · ${"%.1f".format(java.util.Locale.US, n / s.frames.fps / g.speed)} s" +
                if (g.deleted.isNotEmpty()) " · ${g.deleted.size} deleted" else "", color = Mc.Text2, fontSize = 13.sp)
        }
        g.note?.let { Text("   $it", color = Mc.Orange, fontSize = 12.sp) }
        Spacer(Modifier.weight(1f))
        StripBtn("Done", bg = Mc.Card2) { g.framesOpen = false }
        Spacer(Modifier.width(8.dp))
        StripBtn("Make GIF", bg = Mc.Blue, enabled = s != null && loading == null, onClick = onMake)
    }
    if (loading != null || s == null) {
        Column(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${g.stripStage}… ${((loading ?: 0f) * 100).toInt()}%", color = Mc.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                StripBtn("Cancel", bg = Mc.Card2, onClick = onCancel)
            }
            LinearProgressIndicator(progress = { (loading ?: 0f).coerceIn(0f, 1f) }, color = Mc.Blue, trackColor = Mc.Line,
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(6.dp).clip(RoundedCornerShape(3.dp)))
        }
        return
    }
    val f = s.frames
    val shown = remember(f.count, g.deleted) { (0 until f.count).filter { it !in g.deleted } }
    val head = g.head
    val before = (0 until head).filter { it !in g.deleted }
    val after = (head + 1 until f.count).filter { it !in g.deleted }
    val these = if (g.sel.isNotEmpty()) g.sel else setOf(head)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        StripBtn("Delete all before #${head + 1}", color = Mc.Orange, enabled = before.isNotEmpty(), icon = Icons.Rounded.KeyboardDoubleArrowLeft) {
            g.deleteFrames(before.toSet()); g.sel = emptySet()
        }
        StripBtn(if (g.sel.size > 1) "Delete ${g.sel.size} picked" else "Delete #${these.first() + 1}", color = Mc.Orange) { g.deleteFrames(these) }
        StripBtn("Delete all after #${head + 1}", color = Mc.Orange, enabled = after.isNotEmpty(), iconAfter = Icons.Rounded.KeyboardDoubleArrowRight) {
            g.deleteFrames(after.toSet()); g.sel = emptySet()
        }
        StripBtn("Select between", enabled = g.sel.size >= 2) { g.sel = fillBetween(g.sel, g.deleted) }
        StripBtn("None", enabled = g.sel.isNotEmpty()) { g.sel = emptySet() }
        StripBtn("Undo", enabled = g.undo.isNotEmpty()) { g.undoDelete() }
        StripBtn("Restore all", enabled = g.deleted.isNotEmpty()) { g.restoreAll() }
    }
    val list = rememberLazyListState()
    // small pictures of the frames seen lately
    val cache = remember(f) {
        object : LinkedHashMap<Int, ImageBitmap>(64, 0.75f, true) {
            override fun removeEldestEntry(e: MutableMap.MutableEntry<Int, ImageBitmap>?) = size > 160
        }
    }
    // the tapped frame stays in view after deletes
    LaunchedEffect(head, shown) {
        val k = shown.indexOf(head)
        if (k >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == k }) list.animateScrollToItem((k - 2).coerceAtLeast(0))
    }
    LazyRow(state = list, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp).height(84.dp).semantics { contentDescription = "GIF frames" }) {
        items(shown.size, key = { shown[it] }) { k ->
            val i = shown[k]
            FrameCard(f, i, tenths(g.stripMs(i)), picked = i in g.sel, isShown = i == head, cache = cache,
                onTap = { g.head = i; g.sel = if (i in g.sel) g.sel - i else g.sel + i; onShow(g.stripMs(i)) },
                onDelete = { g.deleteFrames(setOf(i)) })
        }
    }
}

@Composable
private fun FrameCard(
    f: FrameSource, i: Int, time: String, picked: Boolean, isShown: Boolean,
    cache: MutableMap<Int, ImageBitmap>, onTap: () -> Unit, onDelete: () -> Unit,
) {
    var pic by remember(f, i) { mutableStateOf(synchronized(cache) { cache[i] }) }
    if (pic == null) LaunchedEffect(f, i) {
        // a read that fails (the strip just replaced) leaves the box blank instead of closing the app
        pic = withContext(Dispatchers.Default) {
            runCatching {
                val tw = 120; val th = (tw * f.height / f.width.coerceAtLeast(1)).coerceAtLeast(1)
                val px = Resample.resize(f.frame(i), f.width, f.height, tw, th)
                for (q in px.indices) px[q] = px[q] or (0xFF shl 24)
                Bitmap.createBitmap(px, tw, th, Bitmap.Config.ARGB_8888).asImageBitmap()
            }.getOrNull()
        }?.also { p -> synchronized(cache) { cache[i] = p } }
    }
    val shape = RoundedCornerShape(6.dp)
    Column(
        Modifier.width(96.dp).clip(shape)
            .background(if (picked) Color(0x553DB4F2) else Mc.Card2)
            .border(2.dp, if (picked) Mc.Blue else if (isShown) Color(0xFFFF5C8A) else Color.Transparent, shape)
            .clickable(onClick = onTap).padding(3.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(f.width.toFloat() / f.height.coerceAtLeast(1)).background(Color.Black)) {
            pic?.let { Image(it, "Frame ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
            // one tap deletes just this frame (a finger-sized spot)
            Box(Modifier.align(Alignment.TopEnd).size(26.dp).clickable(onClick = onDelete).semantics { contentDescription = "Delete frame ${i + 1}" },
                contentAlignment = Alignment.Center) {
                Box(Modifier.size(18.dp).clip(RoundedCornerShape(9.dp)).background(Color(0xCC0B1622)), contentAlignment = Alignment.Center) {
                    Text("✕", color = Color(0xFFFF8A80), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("#${i + 1}", color = if (picked) Mc.Text else Mc.Text2, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
            Spacer(Modifier.weight(1f))
            Text(time, color = Mc.Text3, fontSize = 9.5.sp)
        }
    }
}

@Composable
private fun StripBtn(
    label: String, enabled: Boolean = true, color: Color = Mc.Text, bg: Color = Mc.Card2,
    icon: ImageVector? = null, iconAfter: ImageVector? = null, onClick: () -> Unit,
) {
    val fg = when { !enabled -> Mc.Text3; bg == Mc.Blue -> Mc.Bg; else -> color }
    Row(Modifier.clip(RoundedCornerShape(50)).background(bg).clickable(enabled = enabled, onClick = onClick)
        .padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        icon?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp).padding(end = 2.dp)) }
        Text(label, color = fg, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        iconAfter?.let { Icon(it, null, tint = fg, modifier = Modifier.size(16.dp).padding(start = 2.dp)) }
    }
}
