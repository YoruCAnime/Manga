package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.yoruc.mangacracker.ui.theme.Mc

/**
 * Windows: the chapter's pages as small pictures in the free space beside the page column (Exos 2026-11-14: "on the
 * black area ... gallery preview so i can easily go to that page fast"). The page on screen is outlined and kept in
 * view; a click goes to that page. Taps don't take the keyboard from the reader (arrow keys keep scrolling it).
 */
@Composable
fun ReaderPageStrip(modifier: Modifier = Modifier) {
    val pages = ReaderSide.pages
    val vm = ReaderSide.vm ?: return
    if (pages.size < 2) return
    val current = ReaderSide.current
    val list = rememberLazyListState()
    // keep the page on screen in view (a little below the top, so the next ones show)
    LaunchedEffect(current, pages.size) {
        if (current < 0) return@LaunchedEffect
        val vis = list.layoutInfo.visibleItemsInfo
        val inView = vis.any { it.index == current && it.offset >= 0 && it.offset + it.size <= list.layoutInfo.viewportEndOffset }
        if (!inView) list.animateScrollToItem((current - 1).coerceAtLeast(0))
    }
    // test runs: MC_STRIP_JUMP=<n> goes to page n+1 as a click on its picture would
    LaunchedEffect(pages.firstOrNull()?.chapter?.id) {
        val n = System.getenv("MC_STRIP_JUMP")?.toIntOrNull() ?: return@LaunchedEffect
        kotlinx.coroutines.delay(5000)
        ReaderSide.pages.getOrNull(n)?.let { ReaderSide.jump(it) }
    }
    LazyColumn(
        state = list, modifier = modifier,
        contentPadding = PaddingValues(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        itemsIndexed(pages, key = { _, p -> p.key }) { i, p ->
            val here = i == current
            val ratio = (vm.ratioOf(p.page) ?: 0.7f).coerceIn(0.45f, 1.6f)
            Box(
                Modifier.fillMaxWidth().aspectRatio(ratio).clip(RoundedCornerShape(6.dp)).background(Color(0xFF1A2230))
                    .then(if (here) Modifier.border(2.dp, Mc.Blue, RoundedCornerShape(6.dp)) else Modifier)
                    .pointerInput(p.key) { detectTapGestures(onTap = { ReaderSide.jump(p) }) },
            ) {
                // a page whose address takes a page load to find (E-Hentai) shows its picture once the reader has
                // found it: the strip never spends the site's page loads itself, so the pages you read come first
                val tick = vm.resolvedTick.intValue
                val url = remember(p.key, tick) { p.page.url.ifEmpty { vm.resolved[p.page.pageUrl].orEmpty() } }
                if (url.isNotEmpty()) {
                    AsyncImage(
                        model = ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(com.yoruc.mangacracker.core.Downloads.model(url))
                            .size(240, (240 / ratio).toInt()).build(),
                        contentDescription = "Page ${p.page.index + 1}", contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().alpha(if (here) 1f else 0.82f),
                    )
                }
                Text(
                    "${p.page.index + 1}", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).clip(RoundedCornerShape(5.dp))
                        .background(if (here) Mc.Blue else Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 1.dp),
                )
            }
        }
    }
}
