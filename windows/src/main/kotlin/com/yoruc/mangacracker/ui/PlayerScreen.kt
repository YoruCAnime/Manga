package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Fullscreen
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.sun.jna.Native
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.platform.Mpv
import com.yoruc.mangacracker.platform.StreamProxy
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.VideoDetails
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Same as the phone: the episode, its details and stream, the episode list (downloaded videos play from the disk). */
class PlayerVM(startKey: String) : ViewModel() {
    var ref by mutableStateOf(VideoRepo.refs[startKey]); private set
    val src = ref?.let { VideoSources.byId(it.source) }
    var details by mutableStateOf<VideoDetails?>(null); private set
    var error by mutableStateOf<Throwable?>(null); private set

    init { ref?.let { open(it) } }

    fun open(r: VideoRef) {
        ref = r; error = null; details = null
        VideoRepo.remember(r)
        val s = src ?: return
        viewModelScope.launch {
            try {
                VideoDownloads.offline(r.key)?.let { o ->
                    if (VideoSafety.blocked(o.saved.ref)) throw SourceException("This video is hidden")
                    val online = VideoRepo.details[r.key]
                    if (ref?.key == r.key) details = VideoDetails(online?.ref ?: o.saved.ref, VideoDownloads.stream(o), online?.episodes ?: listOf(o.saved.ref), online?.tags.orEmpty())
                    if (online == null) launch {
                        val d = runCatching { withContext(Dispatchers.IO) { s.details(r) } }.getOrNull() ?: return@launch
                        VideoRepo.details[r.key] = d
                        if (ref?.key == r.key) details = details?.copy(episodes = d.episodes, tags = d.tags)
                    }
                    return@launch
                }
                val d = VideoRepo.details[r.key] ?: withContext(Dispatchers.IO) { s.details(r) }
                VideoRepo.details[r.key] = d
                withContext(Dispatchers.IO) { s.beforePlay() }
                if (ref?.key == r.key) details = d
            } catch (e: Throwable) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                error = e
            }
        }
    }

    fun neighbour(step: Int): VideoRef? {
        val d = details ?: return null
        val i = d.episodes.indexOfFirst { it.id == d.ref.id }
        return if (i < 0) null else d.episodes.getOrNull(i + step)
    }
}

private val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

/**
 * The video player (Windows): mpv draws the video in a panel; the controls sit under it (a native video panel can't
 * have app controls on top). Keys: Space / K play-pause, ← → 1 s (keeps playing), [ ] one frame back / forward
 * (pauses), J / L 10 s, ↑ ↓ volume, M mute, F / F11 full screen, N / P next / previous episode, R record, G GIF
 * editor. Streams go through [StreamProxy], downloads play from the disk.
 */
@Composable
fun PlayerScreen(nav: Nav, key: String) {
    val vm: PlayerVM = viewModel(key = "player-$key") { PlayerVM(key) }
    val settings by Repo.settings.collectAsState()
    var player by remember { mutableStateOf<Mpv.Player?>(null) }
    var pos by remember { mutableDoubleStateOf(0.0) }
    var dur by remember { mutableDoubleStateOf(0.0) }
    var cached by remember { mutableDoubleStateOf(0.0) }
    var paused by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var volume by remember { mutableFloatStateOf(Repo.settings.value.videoVolume) }
    var muted by remember { mutableStateOf(Repo.settings.value.videoMuted) }
    var speed by remember { mutableFloatStateOf(1f) }
    var quality by remember { mutableStateOf(Repo.settings.value.videoQuality) }
    var subsOn by remember { mutableStateOf(Repo.settings.value.videoSubtitles) }
    var loadedKey by remember { mutableStateOf<String?>(null) }
    var scrubbing by remember { mutableStateOf(false) }
    var fav by remember(vm.ref?.key) { mutableStateOf(vm.ref?.let { VideoRepo.isFav(it.key) } ?: false) }
    /** the stream's own sizes (HLS variants), tallest first; empty = one size (MP4) */
    var heights by remember { mutableStateOf<List<Int>>(emptyList()) }
    /** Record: where the clip starts (null = not recording); the clip being saved (0..1) */
    var recStart by remember { mutableStateOf<Double?>(null) }
    var saving by remember { mutableStateOf<Float?>(null) }
    /** GIF: same two presses; the size it must fit; what the GIF maker is doing */
    /** the GIF editor is open at this spot (it takes the video's place) */
    var gifAt by remember { mutableStateOf<Double?>(null) }
    var gifSrc by remember { mutableStateOf<GifSource?>(null) }
    /** picture: saturation / contrast, -100..100, remembered */
    var saturation by remember { mutableStateOf(Repo.settings.value.videoSaturation) }
    var contrast by remember { mutableStateOf(Repo.settings.value.videoContrast) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val keys = remember { FocusRequester() }
    val canvas = remember { java.awt.Canvas().apply { background = java.awt.Color.BLACK } }

    if (!Mpv.available) {
        Column(Modifier.fillMaxSize().background(Mc.Bg).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("The video player needs mpv (libmpv-2.dll), which wasn't found next to the app.", color = Mc.Text)
            Text("Back", color = Mc.Blue, modifier = Modifier.clickable { nav.back() })
        }
        return
    }

    // the player is made once the panel has a native window
    LaunchedEffect(Unit) {
        while (!canvas.isDisplayable) delay(50)
        player = Mpv.Player(Native.getComponentID(canvas))
    }
    DisposableEffect(Unit) {
        onDispose {
            val p = player
            vm.details?.let { d -> if (dur > 0) VideoRepo.save(d.ref, (pos * 1000).toLong(), (dur * 1000).toLong()) }
            Repo.updateSettings { it.copy(videoVolume = volume, videoMuted = muted, videoQuality = quality, videoSubtitles = subsOn) }
            Desktop.scope.launch(Dispatchers.IO) { p?.destroy() }
            com.yoruc.mangacracker.platform.MemoryKeeper.release()
        }
    }

    // load the episode when its stream is ready (or the quality changes)
    fun load(at: Double? = null) {
        val p = player ?: return
        val d = vm.details ?: return
        val s = d.stream
        StreamProxy.maxHeight = if (quality > 0) quality else 0
        val local = s.url.startsWith("file:")
        val url = if (local) File(java.net.URI(s.url)).path else StreamProxy.url(d.ref.source, s.url)
        val start = at ?: VideoRepo.state(d.ref.key)?.let { w -> if (w.durationMs > 0 && w.positionMs < w.durationMs - 15_000) w.positionMs / 1000.0 else 0.0 } ?: 0.0
        p.set("hls-bitrate", if (quality == -1) "max" else if (quality == 0) "no" else "max")
        p.set("volume", (volume * 100).toInt().toString())
        p.set("mute", if (muted) "yes" else "no")
        p.set("speed", speed.toString())
        p.set("saturation", saturation.toString())
        p.set("contrast", contrast.toString())
        VideoDownloads.offline(d.ref.key)?.let { o -> VideoDownloads.audioFile(o)?.let { p.set("audio-files", it.path) } }
        p.load(url, start)
        loadedKey = d.ref.key
        buffering = true
    }
    // the sizes this video comes in (for the quality menu)
    LaunchedEffect(vm.details?.ref?.key) {
        heights = emptyList()
        val d = vm.details ?: return@LaunchedEffect
        if (d.stream.kind != "hls" || d.stream.url.startsWith("file:")) return@LaunchedEffect
        val src = VideoSources.byId(d.ref.source) ?: return@LaunchedEffect
        heights = withContext(Dispatchers.IO) {
            runCatching {
                val text = src.playerClient.newCall(com.yoruc.mangacracker.core.Http.request(d.stream.url)).execute().use { it.body?.string().orEmpty() }
                VideoDownloads.variants(text, d.stream.url).map { it.height }.filter { it > 0 }.distinct().sortedDescending()
            }.getOrDefault(emptyList())
        }
    }
    LaunchedEffect(player, vm.details) {
        if (player != null && vm.details != null && loadedKey != vm.details?.ref?.key) {
            load()
            // subtitles once the file is open
            val d = vm.details!!
            delay(1500)
            d.stream.subs.forEach { sub ->
                val u = if (sub.url.startsWith("file:")) File(java.net.URI(sub.url)).path else StreamProxy.url(d.ref.source, sub.url)
                player?.cmd("sub-add", u, "auto", sub.label, sub.lang)
            }
            player?.set("sub-visibility", if (subsOn) "yes" else "no")
        }
    }
    // clock: position, length, pause, buffering; saves the watch spot; next episode at the end
    LaunchedEffect(player) {
        var n = 0
        while (true) {
            delay(250)
            val p = player ?: continue
            if (!scrubbing) pos = p.getDouble("time-pos") ?: pos
            dur = p.getDouble("duration") ?: dur
            cached = p.getDouble("demuxer-cache-time") ?: cached
            paused = p.getString("pause") == "yes"
            buffering = p.getString("paused-for-cache") == "yes" || p.getString("idle-active") == "yes"
            if (++n % 20 == 0 && !paused && dur > 0) vm.details?.let { VideoRepo.save(it.ref, (pos * 1000).toLong(), (dur * 1000).toLong()) }
            if (p.getString("eof-reached") == "yes" && dur > 0) {
                vm.details?.let { VideoRepo.save(it.ref, (dur * 1000).toLong(), (dur * 1000).toLong()) }
                val next = vm.neighbour(1)
                if (next != null && loadedKey == vm.details?.ref?.key) { loadedKey = null; vm.open(next) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { keys.requestFocus() } }

    fun seekBy(s: Double) { player?.cmd("seek", s.toString(), "relative+exact") }
    fun togglePause() { player?.cmd("cycle", "pause") }
    /** The app's own volume / mute, kept for every video and the next start of the app. */
    fun keepVolume() = Repo.updateSettings { it.copy(videoVolume = volume, videoMuted = muted) }
    fun setVol(v: Float, save: Boolean = true) {
        volume = v.coerceIn(0f, 1f); player?.set("volume", (volume * 100).toInt().toString())
        if (muted && v > 0) { muted = false; player?.set("mute", "no") }
        if (save) keepVolume()
    }
    fun toggleMute() { muted = !muted; player?.set("mute", if (muted) "yes" else "no"); keepVolume() }
    fun snapshot() {
        val d = vm.details ?: return
        val dir = Desktop.snapshots
        val f = Desktop.freeFile(dir, Desktop.clean("${d.ref.title} ${clock((pos * 1000).toLong()).replace(':', '.')}"), "png")
        player?.cmd("screenshot-to-file", f.path, "video")
        Desktop.toast("Snapshot saved to ${dir.path}")
    }
    /** ffmpeg's input for this video: the downloaded file, or the site's stream with its cookies / keys. */
    fun ffInput(): List<String>? {
        val d = vm.details ?: return null
        val s = d.stream
        return if (s.url.startsWith("file:")) listOf("-i", File(java.net.URI(s.url)).path) else StreamProxy.ffmpegInput(d.ref.source, s.url, s.kind)
    }
    /** GIF: pauses the video and opens the GIF editor around the current spot. */
    fun gif() {
        val d = vm.details ?: return
        val p = player ?: return
        val input = ffInput() ?: return
        val w = (p.getDouble("width") ?: 0.0).toInt(); val h = (p.getDouble("height") ?: 0.0).toInt()
        if (w <= 0 || h <= 0) { Desktop.toast("Wait until the video plays"); return }
        p.set("pause", "yes")
        gifSrc = GifSource(input, w, h, p.getDouble("container-fps") ?: 24.0, dur, d.ref.title, d.ref.key)
        gifAt = pos
    }
    /** Record: first press marks the start, the second cuts start..now into an MP4 (Videos\MangaCracker). */
    fun record() {
        val d = vm.details ?: return
        val from = recStart
        if (saving != null) return
        if (from == null) { recStart = pos; Desktop.toast("Recording… press Record again to stop"); return }
        recStart = null
        val to = pos
        if (to - from < 0.5) { Desktop.toast("Too short to save"); return }
        val input = ffInput() ?: return
        val out = Desktop.freeFile(Desktop.clips, Desktop.clean("${d.ref.title} ${clock((from * 1000).toLong())}-${clock((to * 1000).toLong())}".replace(':', '.')), "mp4")
        saving = 0f
        scope.launch {
            runCatching { com.yoruc.mangacracker.platform.ClipRecorder.cut(input, from, to, out) { f -> saving = f } }
                .onSuccess { f -> Desktop.toast("Clip saved to ${f.parentFile.path}") }
                .onFailure { Desktop.toast("Couldn't save the clip: ${it.message}") }
            saving = null
        }
    }

    // test runs: MC_DEMO_GIF=1 opens the GIF editor once the video plays
    if (System.getenv("MC_DEMO_GIF") == "1") LaunchedEffect(player) {
        while (dur <= 0 || pos < 8) delay(500)
        gif()
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(keys).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || gifAt != null) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.Spacebar, Key.K -> togglePause()
                    // 1 s back / forward without pausing; [ ] one frame back / forward (that pauses)
                    Key.DirectionLeft -> seekBy(-1.0)
                    Key.DirectionRight -> seekBy(1.0)
                    Key.LeftBracket -> player?.cmd("frame-back-step")
                    Key.RightBracket -> player?.cmd("frame-step")
                    Key.J -> seekBy(-10.0)
                    Key.L -> seekBy(10.0)
                    Key.R -> record()
                    Key.G -> gif()
                    Key.DirectionUp -> setVol(volume + 0.05f)
                    Key.DirectionDown -> setVol(volume - 0.05f)
                    Key.M -> toggleMute()
                    Key.F -> Desktop.toggleFullscreen()
                    Key.N -> vm.neighbour(1)?.let { loadedKey = null; vm.open(it) }
                    Key.P -> vm.neighbour(-1)?.let { loadedKey = null; vm.open(it) }
                    else -> return@onPreviewKeyEvent false
                }
                true
            }
    ) {
        // top: back, title, favourite
        Row(Modifier.fillMaxWidth().background(Mc.Bg).padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Mc.Text2) }
            // the title opens every episode of the show
            Column(Modifier.weight(1f).clip(RoundedCornerShape(6.dp)).clickable { (vm.details?.ref ?: vm.ref)?.let { nav.videoSeries(it) } }.padding(horizontal = 4.dp, vertical = 2.dp)) {
                Text(vm.details?.ref?.title ?: vm.ref?.title.orEmpty(), color = Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                vm.details?.ref?.let { r ->
                    val line = listOfNotNull(r.episode.ifBlank { null }?.let { "Episode $it" }, com.yoruc.mangacracker.video.langName(r.lang).ifEmpty { null },
                        com.yoruc.mangacracker.video.VideoSources.byId(r.source)?.name).joinToString(" · ")
                    if (line.isNotEmpty()) Text(line, color = Mc.Text3, fontSize = 12.sp)
                }
            }
            IconButton(onClick = { vm.ref?.let { fav = VideoRepo.toggleFav(it) } }) {
                Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = if (fav) Mc.Pink else Mc.Text2)
            }
        }
        // the video
        val editing = gifAt
        val gs = gifSrc
        LaunchedEffect(editing) { canvas.isVisible = editing == null }
        if (editing != null && gs != null) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                GifEditor(gs, editing) { gifAt = null; runCatching { keys.requestFocus() } }
            }
        }
        Box(if (editing != null) Modifier.height(1.dp).fillMaxWidth() else Modifier.weight(1f).fillMaxWidth()) {
            SwingPanel(background = Color.Black, factory = { canvas }, modifier = Modifier.fillMaxSize())
            when {
                vm.error != null -> Text(vm.error?.message ?: "This video can't be played", color = Mc.Orange,
                    modifier = Modifier.align(Alignment.Center).background(Color(0xCC0B1622)).padding(16.dp))
                vm.details == null -> CircularProgressIndicator(color = Mc.Blue, modifier = Modifier.align(Alignment.Center))
            }
        }
        // controls
        if (editing == null) Column(Modifier.fillMaxWidth().background(Mc.Bg).padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(clock((pos * 1000).toLong()), color = Mc.Text2, fontSize = 12.sp, modifier = Modifier.width(56.dp))
                SeekBar(
                    (pos * 1000).toLong(), (dur * 1000).toLong(), ((pos + cached) * 1000).toLong(), Modifier.weight(1f),
                    onDrag = { scrubbing = it }, onScrub = { pos = it / 1000.0 },
                    onSeek = { ms -> player?.cmd("seek", (ms / 1000.0).toString(), "absolute+exact"); pos = ms / 1000.0 },
                )
                Text(clock((dur * 1000).toLong()), color = Mc.Text2, fontSize = 12.sp, modifier = Modifier.width(56.dp).padding(start = 8.dp))
            }
            // left: volume · middle: previous, −1s, play, +1s, next · right: speed, quality, subtitles, snapshot, record,
            // full screen (equal weights on both sides keep the middle group in the middle)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { toggleMute() }) {
                        Icon(if (muted || volume == 0f) Icons.AutoMirrored.Rounded.VolumeOff else if (volume < 0.5f) Icons.AutoMirrored.Rounded.VolumeDown else Icons.AutoMirrored.Rounded.VolumeUp,
                            "Mute", tint = Mc.Text2)
                    }
                    VolumeBar(if (muted) 0f else volume, Modifier.width(110.dp), onChange = { setVol(it, save = false) }, onDone = { keepVolume() })
                    Text("${(volume * 100).toInt()}%", color = Mc.Text3, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp))
                }
                IconButton(enabled = vm.neighbour(-1) != null, onClick = { vm.neighbour(-1)?.let { loadedKey = null; vm.open(it) } }) {
                    Icon(Icons.Rounded.SkipPrevious, "Previous episode", tint = if (vm.neighbour(-1) != null) Mc.Text else Mc.Text3)
                }
                TextBtn("−1s") { seekBy(-1.0) }
                Box(Modifier.padding(horizontal = 6.dp).size(54.dp).clip(RoundedCornerShape(50)).background(Mc.Blue).clickable { togglePause() }, contentAlignment = Alignment.Center) {
                    if (buffering && !paused && vm.details != null) CircularProgressIndicator(color = Mc.Bg, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
                    else Icon(if (paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause, if (paused) "Play" else "Pause", tint = Mc.Bg, modifier = Modifier.size(32.dp))
                }
                TextBtn("+1s") { seekBy(1.0) }
                IconButton(enabled = vm.neighbour(1) != null, onClick = { vm.neighbour(1)?.let { loadedKey = null; vm.open(it) } }) {
                    Icon(Icons.Rounded.SkipNext, "Next episode", tint = if (vm.neighbour(1) != null) Mc.Text else Mc.Text3)
                }
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    Menu("${if (speed == 1f) "1" else speed.toString().trimEnd('0').trimEnd('.')}×", SPEEDS.map { "${it}×" to { speed = it; player?.set("speed", it.toString()); Unit } })
                    val qs = listOf(-1 to "Best", 0 to "Auto") + heights.map { it to "${it}p" }
                    val label = if (vm.details?.stream?.kind != "hls" && heights.isEmpty()) "Original"
                        else qs.firstOrNull { it.first == quality }?.second ?: "${quality}p"
                    Menu(label, if (heights.isEmpty() && vm.details?.stream?.kind != "hls") listOf("Original" to {})
                        else qs.map { (q, l) -> l to { if (quality != q) { quality = q; load(pos) }; Unit } })
                    PictureMenu(saturation, contrast) { sat, con ->
                        saturation = sat; contrast = con
                        player?.set("saturation", sat.toString()); player?.set("contrast", con.toString())
                        Repo.updateSettings { it.copy(videoSaturation = sat, videoContrast = con) }
                    }
                    IconButton(onClick = { snapshot() }) { Icon(Icons.Rounded.PhotoCamera, "Snapshot", tint = Mc.Text2) }
                    // Record: red while recording (with its length), the saving progress after
                    val rec = recStart
                    Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(50)).background(if (rec != null) Color(0xFFE53950) else Mc.Card2)
                        .clickable { record() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            when {
                                saving != null -> "Saving ${((saving ?: 0f) * 100).toInt()}%"
                                rec != null -> "● ${clock(((pos - rec).coerceAtLeast(0.0) * 1000).toLong())}"
                                else -> "● Record"
                            },
                            color = if (rec != null) Color.White else Mc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Box(Modifier.padding(horizontal = 2.dp).clip(RoundedCornerShape(50)).background(Mc.Card2).clickable { gif() }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text("GIF", color = Mc.Text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    IconButton(onClick = { subsOn = !subsOn; player?.set("sub-visibility", if (subsOn) "yes" else "no") }) {
                        Icon(if (subsOn) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled, "Subtitles", tint = Mc.Text2)
                    }
                    IconButton(onClick = { Desktop.toggleFullscreen() }) { Icon(Icons.Rounded.Fullscreen, "Full screen", tint = Mc.Text2) }
                }
            }
        }
    }
}

/** Saturation and contrast sliders (mpv's -100..100), with a reset. */
@Composable
private fun PictureMenu(sat: Int, con: Int, onChange: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Rounded.Tune, "Picture", tint = if (sat != 0 || con != 0) Mc.Blue else Mc.Text2) }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Mc.Card) {
            Column(Modifier.width(260.dp).padding(horizontal = 14.dp, vertical = 6.dp)) {
                Text("Saturation  ${if (sat > 0) "+" else ""}$sat", color = Mc.Text, fontSize = 13.sp)
                androidx.compose.material3.Slider(sat.toFloat(), { onChange(it.toInt(), con) }, valueRange = -100f..100f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2))
                Text("Contrast  ${if (con > 0) "+" else ""}$con", color = Mc.Text, fontSize = 13.sp)
                androidx.compose.material3.Slider(con.toFloat(), { onChange(sat, it.toInt()) }, valueRange = -100f..100f,
                    colors = androidx.compose.material3.SliderDefaults.colors(thumbColor = Mc.Blue, activeTrackColor = Mc.Blue, inactiveTrackColor = Mc.Card2))
                Text("Reset", color = Mc.Blue, fontSize = 13.sp, modifier = Modifier.clickable { onChange(0, 0) }.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun TextBtn(label: String, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(50)).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(label, color = Mc.Text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun Menu(label: String, items: List<Pair<String, () -> Unit>>) {
    var open by remember { mutableStateOf(false) }
    Box {
        Box(Modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(8.dp)).background(Mc.Card2).clickable { open = true }.padding(horizontal = 10.dp, vertical = 6.dp)) {
            Text(label, color = Mc.Text, fontSize = 13.sp)
        }
        DropdownMenu(open, onDismissRequest = { open = false }, containerColor = Mc.Card) {
            items.forEach { (l, go) -> DropdownMenuItem(text = { Text(l, color = Mc.Text) }, onClick = { open = false; go() }) }
        }
    }
}

