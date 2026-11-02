package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.yoruc.mangacracker.core.DlState
import com.yoruc.mangacracker.core.DlTask
import com.yoruc.mangacracker.core.DownloadRunner
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SourceMatch
import com.yoruc.mangacracker.core.TitleDownloads
import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.OfflineVideo
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.delay

/**
 * More › Downloads: the queue (pause / resume / cancel / retry), downloaded titles with their chapters and size,
 * downloaded videos, the space used and left. Deleting needs a second tap on the same button (no pop-ups).
 */
@Composable
fun DownloadsScreen(nav: Nav) {
    val ctx = LocalContext.current
    val tasks by Downloads.queue.tasks.collectAsState()
    val titles by Downloads.titles.collectAsState()
    val videos by VideoDownloads.videos.collectAsState()
    val s by Repo.settings.collectAsState()
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    /** The delete button tapped once (a second tap within 4 s deletes). */
    var arm by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(arm) { if (arm != null) { delay(4000); arm = null } }
    // free space / Wi-Fi state, looked at again every few seconds
    var free by remember { mutableStateOf(Downloads.freeBytes()) }
    var metered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { while (true) { free = Downloads.freeBytes(); metered = Downloads.onMeteredOrNone(ctx); delay(3000) } }
    val used = remember(titles, videos) { Downloads.usedBytes() }

    Column(Modifier.fillMaxSize()) {
        TopBar("Downloads", nav)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 32.dp)) {
            // ---- storage + settings ----
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(16.dp)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(Downloads.size(used), color = Mc.Blue, fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("  used by downloads", color = Mc.Text2, fontSize = 13.sp, modifier = Modifier.padding(bottom = 3.dp))
                    }
                    val total = (used + free).coerceAtLeast(1)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Mc.Card2)) {
                        Box(Modifier.fillMaxWidth((used.toFloat() / total).coerceIn(0.01f, 1f)).fillMaxHeight().background(Mc.Blue))
                    }
                    Spacer(Modifier.height(6.dp))
                    Text("${Downloads.size(free)} free on the phone", color = if (free < Downloads.MIN_FREE_START) Mc.Pink else Mc.Text3, fontSize = 12.5.sp)
                    if (free < Downloads.MIN_FREE_START) Text("Too little space: new downloads won't start", color = Mc.Pink, fontSize = 12.5.sp)
                    SwitchRow("Wi-Fi only", s.downloadWifiOnly, sub = "Downloads wait for Wi-Fi (no mobile data)") { v ->
                        Repo.updateSettings { it.copy(downloadWifiOnly = v) }
                        if (Downloads.queue.hasWaiting()) Downloads.kick(ctx, replace = true)
                    }
                    SwitchRow("Delete chapters after reading", s.deleteAfterReading, sub = "A downloaded chapter goes once you've read it and leave the reader") { v ->
                        Repo.updateSettings { it.copy(deleteAfterReading = v) }
                    }
                }
            }

            // ---- the queue ----
            if (tasks.isNotEmpty()) {
                item {
                    val anyActive = tasks.any { it.state == DlState.QUEUED || it.state == DlState.RUNNING }
                    SectionHeader("Queue · ${tasks.size}", action = if (anyActive) "Pause all" else "Resume all", onAction = {
                        if (anyActive) Downloads.pauseAll() else Downloads.resumeAll(ctx)
                    })
                    val waitingWifi = s.downloadWifiOnly && metered && tasks.any { it.state == DlState.QUEUED }
                    when {
                        DownloadRunner.lowSpace -> HintLine("Paused: the phone is almost full. Free some space, then Resume all.", Mc.Pink)
                        waitingWifi -> HintLine("Waiting for Wi-Fi (switch off \"Wi-Fi only\" above to use mobile data)", Mc.Orange)
                    }
                }
                items(tasks, key = { "q" + it.id }) { t -> QueueRow(t, onPause = { Downloads.pause(t.id) }, onResume = { Downloads.resume(ctx, t.id) }, onCancel = { Downloads.cancel(t.id) }) }
                if (tasks.size > 1) item {
                    Text(
                        if (arm == "cancelall") "Tap again to cancel everything" else "Cancel all", color = if (arm == "cancelall") Mc.Pink else Mc.Text3,
                        fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(6.dp))
                            .clickable { if (arm == "cancelall") { Downloads.cancelAll(); arm = null } else arm = "cancelall" }.padding(6.dp)
                    )
                }
            }

            // ---- manga ----
            val list = titles.values.sortedBy { it.ref.title.lowercase() }
            item { SectionHeader("Manga · ${list.size}") }
            if (list.isEmpty()) item {
                Text("Nothing downloaded yet. On a title's page: Download (next 5, all unread or all), or long-press a chapter.",
                    color = Mc.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            list.forEach { t ->
                item(key = "t" + t.ref.key) {
                    TitleRow(t, open == t.ref.key, arm == "t" + t.ref.key,
                        onOpen = { open = if (open == t.ref.key) null else t.ref.key },
                        onTitle = { nav.details(t.ref) },
                        onDelete = { if (arm == "t" + t.ref.key) { Downloads.deleteTitle(t.ref.key); arm = null; Notes.show("Deleted ${t.ref.title}") } else arm = "t" + t.ref.key })
                }
                if (open == t.ref.key) {
                    val chs = t.saved.values.sortedWith(compareByDescending<com.yoruc.mangacracker.core.SavedChapter> { SourceMatch.number(it.chapter) ?: -1f }.thenByDescending { it.time })
                    items(chs, key = { "c" + t.ref.key + "#" + it.chapter.id }) { c ->
                        Row(
                            Modifier.fillMaxWidth().clickable { nav.reader(t.ref, c.chapter) }.padding(start = 84.dp, end = 8.dp, top = 2.dp, bottom = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.chapter.name, color = Mc.Text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${c.files.size} pages · ${Downloads.size(c.bytes)}", color = Mc.Text3, fontSize = 11.5.sp)
                            }
                            IconButton(onClick = { Downloads.deleteChapter(t.ref.key, c.chapter.id) }) { Icon(Icons.Rounded.Close, "Delete chapter", tint = Mc.Text3) }
                        }
                    }
                }
            }

            // ---- videos ----
            val vids = videos.values.sortedByDescending { it.saved.time }
            item { SectionHeader("Videos · ${vids.size}") }
            if (vids.isEmpty()) item {
                Text("Download a video from the player's ↓ button to watch it with no connection.", color = Mc.Text3, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
            }
            items(vids, key = { "v" + it.saved.ref.key }) { v ->
                VideoRow(v, arm == "v" + v.saved.ref.key,
                    onPlay = { VideoRepo.remember(v.saved.ref); nav.player(v.saved.ref) },
                    onDelete = { if (arm == "v" + v.saved.ref.key) { VideoDownloads.delete(v.saved.ref.key); arm = null } else arm = "v" + v.saved.ref.key })
            }
        }
    }
}

@Composable
private fun HintLine(text: String, color: Color) {
    Text(text, color = color, fontSize = 12.5.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp))
}

@Composable
private fun QueueRow(t: DlTask, onPause: () -> Unit, onResume: () -> Unit, onCancel: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(t.title, color = Mc.Text, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val site = (Sources.byId(t.site)?.name ?: VideoSources.byId(t.site)?.name ?: t.site)
            val state = when (t.state) {
                DlState.RUNNING -> if (t.isVideo) "${t.done / 10}% · ${Downloads.size(t.bytes)}" else if (t.total > 0) "${t.done} / ${t.total} pages" else "Starting…"
                DlState.QUEUED -> if (t.retryAt > System.currentTimeMillis()) "Trying again soon · ${t.error}" else "Waiting"
                DlState.PAUSED -> "Paused" + (if (t.total > 0 && !t.isVideo) " · ${t.done} / ${t.total}" else "")
                DlState.FAILED -> "Failed · ${t.error}"
            }
            Text("$site · $state", color = if (t.state == DlState.FAILED) Mc.Pink else Mc.Text3, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (t.state == DlState.RUNNING || (t.state == DlState.PAUSED && t.total > 0)) {
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { t.fraction }, modifier = Modifier.fillMaxWidth().height(3.dp),
                    color = if (t.state == DlState.RUNNING) Mc.Blue else Mc.Text3, trackColor = Mc.Card2, drawStopIndicator = {},
                )
            }
        }
        when (t.state) {
            DlState.RUNNING, DlState.QUEUED -> IconButton(onClick = onPause) { Icon(Icons.Rounded.Pause, "Pause", tint = Mc.Text2) }
            DlState.PAUSED -> IconButton(onClick = onResume) { Icon(Icons.Rounded.PlayArrow, "Resume", tint = Mc.Blue) }
            DlState.FAILED -> IconButton(onClick = onResume) { Icon(Icons.Rounded.Refresh, "Retry", tint = Mc.Blue) }
        }
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, "Cancel download", tint = Mc.Text3) }
    }
}

@Composable
private fun TitleRow(t: TitleDownloads, expanded: Boolean, armed: Boolean, onOpen: () -> Unit, onTitle: () -> Unit, onDelete: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(52.dp).height(72.dp).clip(RoundedCornerShape(5.dp)).background(Mc.Card).clickable(onClick = onTitle)) {
            if (t.cover != null) AsyncImage(t.cover, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
            else Cover(t.ref.cover, Modifier.fillMaxSize())
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(t.ref.title, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val src = Sources.byId(t.ref.source)?.name ?: t.ref.source
            val n = t.saved.size
            Text("$src · ${if (src.isNotEmpty() && Sources.byId(t.ref.source)?.hasChapters == false) "gallery" else "$n chapter${if (n == 1) "" else "s"}"} · ${Downloads.size(t.bytes)}",
                color = Mc.Text3, fontSize = 12.sp, maxLines = 1)
        }
        if (armed) Text("Tap again", color = Mc.Pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete downloads", tint = if (armed) Mc.Pink else Mc.Text3) }
        Icon(if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, if (expanded) "Hide chapters" else "Show chapters", tint = Mc.Text3, modifier = Modifier.padding(end = 8.dp))
    }
}

@Composable
private fun VideoRow(v: OfflineVideo, armed: Boolean, onPlay: () -> Unit, onDelete: () -> Unit) {
    val r = v.saved.ref
    Row(Modifier.fillMaxWidth().clickable(onClick = onPlay).padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Mc.Card)) { VideoCover(r, Modifier.fillMaxSize()) }
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(r.series.ifEmpty { r.title }, color = Mc.Text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(r.episode.ifEmpty { null }?.let { "Episode $it" }, VideoSources.byId(r.source)?.name,
                v.saved.height.takeIf { it > 0 }?.let { "${it}p" }, Downloads.size(v.bytes)).joinToString(" · "),
                color = Mc.Text3, fontSize = 12.sp, maxLines = 1)
        }
        if (armed) Text("Tap again", color = Mc.Pink, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        IconButton(onClick = onDelete) { Icon(Icons.Rounded.Delete, "Delete video", tint = if (armed) Mc.Pink else Mc.Text3) }
    }
}
