package com.yoruc.mangacracker.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yoruc.mangacracker.core.CloudflareException
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.MangaRef
import com.yoruc.mangacracker.core.ReadMark
import com.yoruc.mangacracker.core.ReadMarks
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc

val CardShape = RoundedCornerShape(6.dp)

/** Soft moving shimmer used while covers and pages load. */
@Composable
fun Shimmer(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "shimmer")
    val x = t.animateFloat(0f, 1f, infiniteRepeatable(tween(1200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    // read while drawing only: a screen full of loading covers repaints, it doesn't rebuild itself every frame
    Box(
        modifier.drawBehind {
            drawRect(
                Brush.linearGradient(
                    listOf(Mc.Card, Mc.Card2, Mc.Card),
                    start = androidx.compose.ui.geometry.Offset(x.value * 1200f - 600f, 0f),
                    end = androidx.compose.ui.geometry.Offset(x.value * 1200f, 600f),
                )
            )
        }
    )
}

@Composable
fun Cover(url: String, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    // the plain picture loader (grids hold dozens of these): 0 = loading, 1 = shown, 2 = no picture
    var src by remember(url) { mutableStateOf(url) }
    var state by remember(url) { mutableIntStateOf(0) }
    val request = remember(src) { ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(src.ifEmpty { null }).crossfade(220).build() }
    Box(modifier) {
        if (state == 0) Shimmer(Modifier.fillMaxSize())
        if (state == 2) Box(Modifier.fillMaxSize().background(Mc.Card2), contentAlignment = Alignment.Center) {
            Text("MC", color = Mc.Text3, fontWeight = FontWeight.Bold)
        } else AsyncImage(
            model = request, contentDescription = null, contentScale = contentScale, modifier = Modifier.fillMaxSize(),
            onSuccess = { state = 1 },
            onError = {
                // booru samples only exist for big pictures: fall back to the thumbnail
                val thumb = if ("/samples/" in url && "sample_" in url) url.replace("/samples/", "/thumbnails/").replace("sample_", "thumbnail_").replaceAfterLast('.', "jpg") else ""
                if (src == url && thumb.isNotEmpty()) src = thumb else state = 2
            },
        )
    }
}

/** A grid / row card: cover on top, title under it (AniList style), optional badges and read bar. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MangaCard(
    ref: MangaRef,
    modifier: Modifier = Modifier,
    ratio: Float = 0.70f,
    badge: String? = null,
    corner: String? = null,
    progress: Float? = null,
    inLibrary: Boolean = false,
    showSource: Boolean = false,
    /** Show how far the title is read (from history / progress). */
    readMark: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val marks by ReadMarks.state.collectAsState()
    val mark = if (readMark) marks[ref.key] else null
    Column(modifier.tabClick { added(com.yoruc.mangacracker.core.Tabs.addManga(ref)) }.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(ratio).clip(CardShape).background(Mc.Card)) {
            Cover(ref.cover, Modifier.fillMaxSize())
            Box(
                Modifier.fillMaxWidth().height(48.dp).align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xAA05101A))))
            )
            if (badge != null || mark != null) {
                Column(Modifier.padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (badge != null) Text(
                        badge, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Mc.Pink).padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                    if (mark != null) ReadPill(mark)
                }
            }
            val dls by Downloads.titles.collectAsState()
            val downloaded = dls[ref.key]?.saved?.isNotEmpty() == true
            if (inLibrary || downloaded) {
                Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (inLibrary) Box(Modifier.size(18.dp).clip(CircleShape).background(Mc.Blue), contentAlignment = Alignment.Center) {
                        Text("♥", color = Color.White, fontSize = 10.sp)
                    }
                    // chapters saved on the phone
                    if (downloaded) Box(Modifier.size(18.dp).clip(CircleShape).background(Mc.Green), contentAlignment = Alignment.Center) {
                        Text("↓", color = Mc.Bg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            if (corner != null) {
                Text(
                    corner, color = Mc.Text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
                )
            }
            if (showSource) {
                Sources.byId(ref.source)?.let { s ->
                    Text(
                        s.name, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(5.dp).clip(RoundedCornerShape(3.dp))
                            .background(Color(s.color).copy(alpha = 0.85f)).padding(horizontal = 5.dp, vertical = 1.dp)
                    )
                }
            }
            val bar = progress ?: mark?.takeIf { !it.finished }?.fraction?.takeIf { it > 0f }
            if (bar != null) {
                LinearProgressIndicator(
                    progress = { bar.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(3.dp).align(Alignment.BottomCenter),
                    color = Mc.Blue, trackColor = Color(0x55000000), drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            ref.title, style = androidx.compose.material3.MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.SemiBold),
            color = Mc.Text2, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 12.5.sp, lineHeight = 16.sp,
        )
        if (ref.subtitle.isNotEmpty()) {
            Text(ref.subtitle, color = Mc.Text3, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Small read badge on a cover: "✓ Read" when every known chapter is read, else "Ch. 12/40" or "Reading". */
@Composable
private fun ReadPill(m: ReadMark) {
    val (label, color) = when {
        m.finished -> "✓ Read" to Mc.Green
        m.total > 1 && m.read > 0 -> "Ch. ${m.read}/${m.total}" to Mc.Text
        m.read > 0 -> "${m.read} read" to Mc.Text
        m.total > 1 -> "Reading" to Mc.Blue
        else -> return
    }
    Text(
        label, color = color, fontSize = 10.5.sp, fontWeight = FontWeight.Bold, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(Color(0xD90B1622)).padding(horizontal = 5.dp, vertical = 1.dp)
    )
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title.uppercase(), color = Mc.Text2, fontWeight = FontWeight.Bold, fontSize = 13.sp, letterSpacing = 0.6.sp, modifier = Modifier.weight(1f))
        if (action != null && onAction != null) {
            Text(action, color = Mc.Text3, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onAction).padding(4.dp))
        }
    }
}

@Composable
fun GenrePill(name: String, modifier: Modifier = Modifier, selected: Boolean = false, onClick: (() -> Unit)? = null) {
    val c = Mc.genreColor(name)
    Text(
        name.replaceFirstChar { it.uppercase() },
        color = if (selected) Color.White else c,
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(if (selected) c else c.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 11.dp, vertical = 5.dp)
    )
}

@Composable
fun Loading(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = Mc.Blue, strokeWidth = 3.dp, modifier = Modifier.size(34.dp))
    }
}

/** Friendly error with Retry, and an "Open site" button when Cloudflare wants a browser check. */
@Composable
fun ErrorBox(error: Throwable, onRetry: () -> Unit, modifier: Modifier = Modifier, onOpenWeb: ((String) -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(Icons.Rounded.CloudOff, null, tint = Mc.Text3, modifier = Modifier.size(42.dp))
        Text(
            if (error is CloudflareException) "This site wants a quick browser check." else (error.message ?: "Something went wrong"),
            color = Mc.Text2, textAlign = TextAlign.Center
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Mc.Blue)) {
                Icon(Icons.Rounded.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Retry")
            }
            if (error is CloudflareException && onOpenWeb != null) {
                OutlinedButton(onClick = { onOpenWeb(error.url) }) { Text("Open site") }
            }
        }
    }
}

@Composable
fun EmptyBox(title: String, body: String, modifier: Modifier = Modifier, height: Dp = 260.dp) {
    Column(modifier.fillMaxWidth().height(height).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Text("(｡•́︿•̀｡)", color = Mc.Text3, fontSize = 26.sp)
        Spacer(Modifier.height(10.dp))
        Text(title, color = Mc.Text, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(body, color = Mc.Text3, textAlign = TextAlign.Center, fontSize = 13.sp)
    }
}

fun timeAgo(ms: Long): String {
    if (ms <= 0) return ""
    val d = (System.currentTimeMillis() - ms) / 1000
    return when {
        d < 60 -> "just now"
        d < 3600 -> "${d / 60}m ago"
        d < 86400 -> "${d / 3600}h ago"
        d < 86400 * 30 -> "${d / 86400}d ago"
        d < 86400 * 365 -> "${d / (86400 * 30)}mo ago"
        else -> "${d / (86400 * 365)}y ago"
    }
}

/** Windows: "covers per row" (2-5) picks a cover size; the grid fits as many as the window holds. */
fun coverWidth(perRow: Int): androidx.compose.ui.unit.Dp = when (perRow.coerceIn(2, 5)) {
    2 -> 260.dp; 3 -> 200.dp; 4 -> 160.dp; else -> 128.dp
}
