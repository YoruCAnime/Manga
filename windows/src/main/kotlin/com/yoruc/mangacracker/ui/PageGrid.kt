package com.yoruc.mangacracker.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.SelectAll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yoruc.mangacracker.core.ImageSaver
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.launch

/**
 * Every page of a chapter / gallery as a grid. Tap = jump there in the reader. Long-press = pick pages
 * (then tap to add / remove) and save the picked ones in one go.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PageGrid(
    title: String,
    folder: String,
    pages: List<ReaderItem.PageItem>,
    current: Int,
    vm: ReaderVM,
    onJump: (ReaderItem.PageItem) -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val picked = remember { mutableStateListOf<Int>() }
    var picking by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf<String?>(null) }
    val grid = rememberLazyGridState()

    LaunchedEffect(Unit) { if (current > 0) grid.scrollToItem((current - 3).coerceAtLeast(0)) }
    BackHandler { if (picking) { picking = false; picked.clear() } else onClose() }

    fun toggle(i: Int) { if (i in picked) picked.remove(i) else picked.add(i); if (picked.isEmpty()) picking = false }

    Column(Modifier.fillMaxSize().background(Mc.Bg)) {
        Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (picking) { picking = false; picked.clear() } else onClose() }) { Icon(Icons.Rounded.Close, "Close", tint = Mc.Text) }
            Column(Modifier.weight(1f)) {
                Text(if (picking) "${picked.size} picked" else title, color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (picking) "Tap pages to add or remove" else "${pages.size} pages · tap to jump, hold to pick", color = Mc.Text3, fontSize = 12.sp)
            }
            if (picking) {
                IconButton(onClick = { if (picked.size == pages.size) picked.clear().also { picking = false } else { picked.clear(); picked.addAll(pages.indices) } }) {
                    Icon(Icons.Rounded.SelectAll, "Pick all", tint = Mc.Text)
                }
            } else {
                IconButton(onClick = { picking = true; picked.clear(); picked.addAll(pages.indices) }) { Icon(Icons.Rounded.SelectAll, "Pick all", tint = Mc.Text2) }
            }
        }
        Box(Modifier.weight(1f)) {
            LazyVerticalGrid(
                state = grid,
                columns = GridCells.Adaptive(108.dp),
                contentPadding = PaddingValues(start = 10.dp, end = 10.dp, top = 4.dp, bottom = 96.dp),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
                    val on = i in picked
                    val ratio = if (p.page.width > 0 && p.page.height > 0) (p.page.width.toFloat() / p.page.height).coerceIn(0.4f, 1.6f) else 0.7f
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(0.7f).clip(RoundedCornerShape(8.dp)).background(Mc.Card)
                            .then(if (i == current && !picking) Modifier.border(2.dp, Mc.Blue, RoundedCornerShape(8.dp)) else Modifier)
                            .then(if (on) Modifier.border(3.dp, Mc.Pink, RoundedCornerShape(8.dp)) else Modifier)
                            .combinedClickable(
                                onClick = { if (picking) toggle(i) else onJump(p) },
                                onLongClick = { picking = true; if (i !in picked) picked.add(i) },
                            )
                    ) {
                        var url by remember(p.key) { mutableStateOf(p.page.url) }
                        LaunchedEffect(p.key) { if (url.isEmpty()) url = runCatching { vm.urlOf(p.page) }.getOrDefault("") }
                        if (url.isNotEmpty()) {
                            AsyncImage(
                                model = ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(com.yoruc.mangacracker.core.Downloads.model(url)).size(320, (320 / ratio).toInt()).build(),
                                contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            )
                        }
                        if (on) Box(Modifier.fillMaxSize().background(Color(0x55FF5C8A)))
                        Text(
                            "${p.page.index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.BottomStart).padding(5.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 1.dp)
                        )
                        if (picking) Icon(
                            Icons.Rounded.CheckCircle, null, tint = if (on) Mc.Pink else Color(0x88FFFFFF),
                            modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).size(22.dp).clip(CircleShape).background(if (on) Color.White else Color(0x44000000))
                        )
                    }
                }
            }
            if (picking && picked.isNotEmpty()) {
                Button(
                    onClick = {
                        if (saving != null) return@Button
                        val chosen = picked.sorted().map { pages[it] }
                        scope.launch {
                            var ok = 0
                            chosen.forEachIndexed { n, p ->
                                saving = "Saving ${n + 1} / ${chosen.size}"
                                runCatching { ImageSaver.save(ctx, vm.urlOf(p.page), folder, "${p.chapter.name} - %03d".format(p.page.index + 1)) }.onSuccess { ok++ }
                            }
                            saving = null
                            Toast.makeText(ctx, "Saved $ok picture${if (ok == 1) "" else "s"} to Pictures/MangaCracker", Toast.LENGTH_LONG).show()
                            picking = false; picked.clear()
                        }
                    },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(16.dp).fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue, contentColor = Mc.Bg),
                    shape = RoundedCornerShape(12.dp),
                ) {
                    Icon(Icons.Rounded.Download, null)
                    Spacer(Modifier.width(8.dp))
                    Text(saving ?: "Save ${picked.size} picture${if (picked.size == 1) "" else "s"}", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
