package com.yoruc.mangacracker.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.TextureView
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ClosedCaption
import androidx.compose.material.icons.rounded.ClosedCaptionDisabled
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material.icons.rounded.GifBox
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.ScreenRotation
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.SubtitleView
import coil.compose.AsyncImage
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.gif.GifOptions
import com.yoruc.mangacracker.video.ClipSaver
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.core.DlTask
import com.yoruc.mangacracker.core.Downloads
import com.yoruc.mangacracker.core.SourceException
import com.yoruc.mangacracker.video.GifClip
import com.yoruc.mangacracker.video.GifDone
import com.yoruc.mangacracker.video.GifRequest
import com.yoruc.mangacracker.video.VideoDetails
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.VideoSources
import com.yoruc.mangacracker.video.clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PlayerVM(startKey: String) : ViewModel() {
    var ref by mutableStateOf(VideoRepo.refs[startKey]); private set
    val src = ref?.let { VideoSources.byId(it.source) }
    var details by mutableStateOf<VideoDetails?>(null); private set
    var error by mutableStateOf<Throwable?>(null); private set

    init { ref?.let { open(it) } }

    /** Load (or switch to) an episode: details, stream key unlock. */
    fun open(r: VideoRef) {
        ref = r; error = null; details = null
        VideoRepo.remember(r)
        val s = src ?: return
        viewModelScope.launch {
            try {
                // downloaded: play from the phone (no network needed); the site is asked for the episode list only
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

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f, 3f)

/** For the PC screen tests (Robolectric can't decode video): a made-up clock and a stand-in for the GIF job. */
internal object PlayerTestHooks {
    /** position, duration, buffered (ms) */
    var clock: (() -> LongArray)? = null
    var gifJob: (suspend (GifRequest, (String, Float) -> Unit) -> GifDone)? = null
}

@OptIn(UnstableApi::class)
@kotlin.OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(nav: Nav, key: String) {
    val vm: PlayerVM = viewModel(key = "player-$key") { PlayerVM(key) }
    val src = vm.src ?: return
    val ctx = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()
    val settings by Repo.settings.collectAsState()
    val activity = ctx as Activity

    val player = remember {
        ExoPlayer.Builder(ctx)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ctx).setDataSourceFactory(OkHttpDataSource.Factory(src.playerClient)))
            .setSeekBackIncrementMs(1000).setSeekForwardIncrementMs(1000)
            .setHandleAudioBecomingNoisy(true)
            // the PC harness (Robolectric) has no media-metrics service
            .setUsePlatformDiagnostics(android.os.Build.FINGERPRINT != "robolectric")
            .build().apply {
                setAudioAttributes(androidx.media3.common.AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
                // the app's own volume (the phone's media volume is not touched)
                Repo.settings.value.let { volume = if (it.videoMuted) 0f else it.videoVolume }
            }
    }

    // ---- player state mirrored for the UI ----
    var playing by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var ended by remember { mutableStateOf(false) }
    var ratio by remember { mutableFloatStateOf(16f / 9f) }
    var cues by remember { mutableStateOf<List<Cue>>(emptyList()) }
    var pos by remember { mutableLongStateOf(0L) }
    var dur by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var playError by remember { mutableStateOf<String?>(null) }

    // ---- controls ----
    var controls by remember { mutableStateOf(true) }
    var poke by remember { mutableIntStateOf(0) }
    var speed by remember { mutableFloatStateOf(1f) }
    var holding by remember { mutableStateOf(false) }
    var showSpeeds by remember { mutableStateOf(false) }
    var showEpisodes by remember { mutableStateOf(false) }
    var showQuality by remember { mutableStateOf(false) }
    var qualityCap by remember { mutableIntStateOf(Repo.settings.value.videoQuality) }
    var volume by remember { mutableFloatStateOf(Repo.settings.value.videoVolume) }
    var muted by remember { mutableStateOf(Repo.settings.value.videoMuted) }
    var landscape by remember { mutableStateOf(true) }
    var seekBadge by remember { mutableStateOf<Pair<Int, Int>?>(null) } // side, seconds
    var seekTick by remember { mutableIntStateOf(0) }
    var scrubbing by remember { mutableStateOf(false) }
    var scrubPos by remember { mutableLongStateOf(0L) }
    var lastScrub by remember { mutableLongStateOf(0L) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var flash by remember { mutableStateOf(false) }
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var recStart by remember { mutableStateOf<Long?>(null) }
    var saving by remember { mutableStateOf<String?>(null) }
    var upNext by remember { mutableIntStateOf(-1) }
    var videoH by remember { mutableIntStateOf(0) }
    var gifStart by remember { mutableStateOf<Long?>(null) }
    var gif by remember { mutableStateOf<GifEdit?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var noteTick by remember { mutableIntStateOf(0) }

    fun show() { controls = true; poke++ }
    /** In-window note at the top (no system toast / pop-up). */
    fun toast(s: String) { note = s; noteTick++ }
    fun now(): Long = PlayerTestHooks.clock?.invoke()?.get(0) ?: player.currentPosition

    DisposableEffect(player) {
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                buffering = state == Player.STATE_BUFFERING
                ended = state == Player.STATE_ENDED
                if (state == Player.STATE_READY) playError = null
            }
            override fun onVideoSizeChanged(size: androidx.media3.common.VideoSize) {
                if (size.width > 0 && size.height > 0) { ratio = size.width * size.pixelWidthHeightRatio / size.height; videoH = size.height }
            }
            override fun onCues(cueGroup: CueGroup) { cues = cueGroup.cues }
            override fun onPlayerError(error: PlaybackException) { playError = error.errorCodeName.removePrefix("ERROR_CODE_").replace('_', ' ').lowercase() }
        }
        player.addListener(l)
        onDispose { player.removeListener(l) }
    }

    // ---- full screen, landscape, awake; pause in the background ----
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(Unit) {
        val win = activity.window
        val ctl = WindowCompat.getInsetsController(win, view)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        view.keepScreenOn = true
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) player.pause() }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            vm.details?.let { VideoRepo.save(it.ref, player.currentPosition, player.duration.coerceAtLeast(0)) }
            player.release()
            GifClip.cleanup(ctx)
            view.keepScreenOn = false
            ctl.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // forced by the app: SENSOR_* turns the screen even with the phone's auto-rotate switched off (USER_* would not);
    // the sensor only picks which way up. The Rotate button flips between landscape and portrait.
    LaunchedEffect(landscape) {
        activity.requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
    }

    // ---- load the stream when the episode's details arrive ----
    LaunchedEffect(vm.details) {
        val d = vm.details ?: return@LaunchedEffect
        ended = false; upNext = -1; playError = null
        val item = VideoDownloads.mediaItem(d.stream) {
            setSubtitleConfigurations(d.stream.subs.map { s ->
                MediaItem.SubtitleConfiguration.Builder(android.net.Uri.parse(s.url)).setMimeType(MimeTypes.TEXT_VTT)
                    .setLanguage(s.lang).setLabel(s.label).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
            })
        }
        // the stream's own reader: the site (player client), or the file / HLS cache of a download
        player.setMediaSource(DefaultMediaSourceFactory(ctx).setDataSourceFactory(VideoDownloads.dataSourceFactory(ctx, src, d.stream)).createMediaSource(item))
        player.trackSelectionParameters = qualityParams(player.trackSelectionParameters.buildUpon(), qualityCap)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !settings.videoSubtitles)
            .setPreferredTextLanguage(d.stream.subs.firstOrNull()?.lang).build()
        player.prepare()
        VideoRepo.state(d.ref.key)?.takeIf { !it.done && it.positionMs > 10_000 }?.let { player.seekTo(it.positionMs) }
        player.playWhenReady = true
        show()
    }

    // ---- clock + save progress every 5 s ----
    LaunchedEffect(player) {
        var n = 0
        while (true) {
            val fake = PlayerTestHooks.clock?.invoke()
            if (fake != null) { pos = fake[0]; dur = fake[1]; buffered = fake[2] }
            else { pos = player.currentPosition; dur = player.duration.coerceAtLeast(0); buffered = player.bufferedPosition }
            if (++n % 20 == 0 && playing) vm.details?.let { VideoRepo.save(it.ref, pos, dur) }
            delay(250)
        }
    }
    // controls hide 3 s after the last touch while playing
    LaunchedEffect(controls, poke, playing, showSpeeds, showQuality, scrubbing) {
        if (controls && playing && !showSpeeds && !showQuality && !scrubbing) { delay(3000); controls = false; showSpeeds = false }
    }
    LaunchedEffect(seekTick) { if (seekBadge != null) { delay(700); seekBadge = null } }
    LaunchedEffect(noteTick) { if (note != null) { delay(2600); note = null } }
    // next episode countdown
    val next = vm.neighbour(1)
    val prev = vm.neighbour(-1)
    LaunchedEffect(ended) {
        if (!ended) return@LaunchedEffect
        vm.details?.let { VideoRepo.save(it.ref, dur, dur) }
        if (next == null) { show(); return@LaunchedEffect }
        for (s in 5 downTo 1) { upNext = s; delay(1000); if (upNext < 0) return@LaunchedEffect }
        upNext = -1; vm.open(next)
    }

    fun seekBy(ms: Long) {
        player.seekTo((player.currentPosition + ms).coerceIn(0, player.duration.coerceAtLeast(0)))
        val side = if (ms > 0) 1 else -1
        val prevB = seekBadge
        seekBadge = side to (if (prevB != null && prevB.first == side) prevB.second + 1 else 1)
        seekTick++
    }
    fun togglePlay() { if (ended) { player.seekTo(0); player.play() } else if (player.isPlaying) player.pause() else player.play(); show() }
    fun openEpisode(r: VideoRef) {
        vm.details?.let { VideoRepo.save(it.ref, player.currentPosition, player.duration.coerceAtLeast(0)) }
        player.stop(); recStart = null; gifStart = null; vm.open(r)
    }
    fun setSpeed(s: Float) { speed = s; player.setPlaybackSpeed(s); showSpeeds = false; show() }
    /** The app's own volume; saved when the finger lifts (not on every drag step). */
    fun setVolume(v: Float, save: Boolean) {
        volume = v.coerceIn(0f, 1f); muted = false; player.volume = volume; show()
        if (save) Repo.updateSettings { it.copy(videoVolume = volume, videoMuted = false) }
    }
    fun toggleMute() {
        muted = !muted; player.volume = if (muted) 0f else volume; show()
        Repo.updateSettings { it.copy(videoMuted = muted) }
    }

    // ---- seek bar: key frames while dragging (throttled, paused), an exact seek on release ----
    fun scrubDrag(on: Boolean) {
        scrubbing = on
        if (on) { resumeAfterScrub = player.isPlaying; player.pause(); scrubPos = pos }
        show()
    }
    fun scrubTo(ms: Long) {
        scrubPos = ms
        val t = android.os.SystemClock.uptimeMillis()
        if (t - lastScrub >= 120) { lastScrub = t; player.setSeekParameters(SeekParameters.CLOSEST_SYNC); player.seekTo(ms) }
    }
    fun seekExact(ms: Long) {
        player.setSeekParameters(SeekParameters.EXACT); player.seekTo(ms); pos = ms
        if (resumeAfterScrub || ended) player.play()
        resumeAfterScrub = false; show()
    }

    // ---- GIF: tap = start capturing, tap again = stop and open the panel ----
    fun clipName(a: Long): String {
        val r = vm.details?.ref ?: vm.ref
        return "${r?.series?.ifEmpty { null } ?: r?.title.orEmpty()} ep${r?.episode.orEmpty()} ${clock(a / 1000).replace(':', '-')}"
    }
    fun stopGif() {
        val s = gifStart ?: return
        gifStart = null
        val t = now()
        val b = maxOf(s, t); val a = maxOf(minOf(s, t), b - GifClip.MAX_CAPTURE_MS)
        if (b - a < 500) { toast("Too short: capture at least half a second"); return }
        player.pause()
        controls = false; showSpeeds = false; showQuality = false; note = null
        gif = GifEdit(a, b)
    }
    fun gifButton() {
        if (vm.details == null || gif != null) return
        if (gifStart != null) { stopGif(); return }
        gifStart = now()
        if (!player.isPlaying) player.play()
        toast("Capturing for a GIF: tap GIF again to stop")
        show()
    }
    fun grabThumb(g: GifEdit, which: Int) {
        val tv = texture ?: return
        val bmp = runCatching { tv.getBitmap(192, (192 / ratio).toInt().coerceAtLeast(2)) }.getOrNull() ?: return
        if (which == 0) g.thumbA = bmp.asImageBitmap() else g.thumbB = bmp.asImageBitmap()
    }
    fun gifPreview(g: GifEdit, ms: Long, exact: Boolean, which: Int) {
        val t = android.os.SystemClock.uptimeMillis()
        if (exact || t - lastScrub >= 120) {
            lastScrub = t
            player.setSeekParameters(if (exact) SeekParameters.EXACT else SeekParameters.CLOSEST_SYNC)
            player.seekTo(ms)
        }
        if (exact) scope.launch { delay(450); grabThumb(g, which) }
    }
    fun makeGif(g: GifEdit) {
        val d = vm.details ?: return
        val req = GifRequest(g.a, g.b, GifOptions(g.speed, g.pingPong, g.size))
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        var lastPct = -1; var lastStage = ""
        val progress: (String, Float) -> Unit = { stage, f ->
            val pct = (f * 100).toInt()
            if (pct != lastPct || stage != lastStage) { lastPct = pct; lastStage = stage; main.post { if (g.working != null) { g.working = stage; g.fraction = f } } }
        }
        g.result = null; g.working = "Getting the clip"; g.fraction = 0f
        g.job = scope.launch {
            try {
                g.result = PlayerTestHooks.gifJob?.invoke(req, progress) ?: GifClip.make(ctx, src, d.stream, videoH, req, progress)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                toast("Couldn't make the GIF: ${e.message ?: e.javaClass.simpleName}")
            } finally { g.working = null }
        }
    }
    fun closeGif() {
        gif?.job?.cancel()
        gif = null
        GifClip.cleanup(ctx)
        show()
    }
    // auto stop after 30 s of capturing
    LaunchedEffect(pos, gifStart) { gifStart?.let { if (pos - it >= GifClip.MAX_CAPTURE_MS) stopGif() } }
    // the panel opens with both ends' pictures; the player then waits on the start
    LaunchedEffect(gif) {
        val g = gif ?: return@LaunchedEffect
        player.setSeekParameters(SeekParameters.EXACT)
        player.seekTo(g.b); delay(500); grabThumb(g, 1)
        player.seekTo(g.a); delay(500); grabThumb(g, 0)
    }

    BackHandler(enabled = showSpeeds || showQuality) { showSpeeds = false; showQuality = false }
    BackHandler(enabled = gif != null) { val g = gif; if (g?.working != null) { g.job?.cancel(); g.working = null } else closeGif() }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- picture ----
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val wide = maxWidth / maxHeight > ratio
            Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = wide)) {
                // TextureView (not SurfaceView) so Snapshot can read the frame; the PC harness can't draw one
                if (android.os.Build.FINGERPRINT != "robolectric")
                    AndroidView(factory = { c -> TextureView(c).also { player.setVideoTextureView(it); texture = it } }, modifier = Modifier.fillMaxSize())
                AndroidView(
                    factory = { c ->
                        SubtitleView(c).apply {
                            setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_OUTLINE, android.graphics.Color.BLACK, null))
                            setFractionalTextSize(0.055f)
                            setApplyEmbeddedStyles(false)
                        }
                    },
                    update = { it.setCues(cues) },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (flash) Box(Modifier.fillMaxSize().background(Color(0x66FFFFFF)))

        // ---- gestures: tap = controls, double-tap sides = -1 s / +1 s (stacks), centre = play / pause, hold = 2x ----
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (gif == null) { if (controls) controls = false else show() } },
                    onDoubleTap = { o ->
                        if (gif == null) when {
                            o.x < size.width * 0.38f -> seekBy(-1000)
                            o.x > size.width * 0.62f -> seekBy(1000)
                            else -> togglePlay()
                        }
                    },
                    onPress = {
                        if (gif != null) return@detectTapGestures
                        val job = scope.launch { delay(450); holding = true; player.setPlaybackSpeed(2f) }
                        tryAwaitRelease()
                        job.cancel()
                        if (holding) { holding = false; player.setPlaybackSpeed(speed) }
                    },
                )
            }
        )

        // seek bubbles
        seekBadge?.let { (side, n) ->
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.38f).align(if (side < 0) Alignment.CenterStart else Alignment.CenterEnd), contentAlignment = Alignment.Center) {
                Text(if (side < 0) "« ${n}s" else "${n}s »", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x66000000)).padding(horizontal = 18.dp, vertical = 10.dp))
            }
        }
        if (holding) Pill("2×  ▸▸", Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 18.dp))
        // loading: a ring around the play button while the controls show, a small spinner otherwise
        if (buffering && !controls && gif == null && vm.error == null && playError == null) CircularProgressIndicator(Modifier.align(Alignment.Center).size(48.dp), color = Mc.Blue, strokeWidth = 3.dp)
        recStart?.let { s ->
            Row(Modifier.align(Alignment.TopStart).statusBarsPadding().padding(16.dp).clip(RoundedCornerShape(50)).background(Color(0xCC000000)).padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(0xFFFF3B3B)))
                Spacer(Modifier.width(8.dp))
                Text("REC  ${clock(((pos - s).coerceAtLeast(0)) / 1000)}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }
        saving?.let { Pill(it, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp)) }

        // ---- errors ----
        val err = vm.error
        if (err != null || playError != null) {
            Column(Modifier.align(Alignment.Center).padding(24.dp).clip(RoundedCornerShape(14.dp)).background(Mc.Card).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Can't play this", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Text(err?.message ?: "Playback error: $playError", color = Mc.Text2, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Try again", Modifier.clickable { vm.ref?.let { VideoRepo.details.remove(it.key); vm.open(it) } }, Mc.Blue)
                    Pill("Open site", Modifier.clickable { vm.ref?.let { nav.web(src.webUrl(it)) } })
                }
            }
        }

        // ---- controls ----
        AnimatedVisibility(controls && gif == null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xB0000000), Color(0x22000000), Color(0x22000000), Color(0xC0000000))))) {
                val d = vm.details
                val r = d?.ref ?: vm.ref
                // top
                Row(Modifier.fillMaxWidth().safePad(top = true).padding(horizontal = 6.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { nav.back() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = Color.White) }
                    // tap the title: the series page (every episode, every language)
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { r?.let { nav.videoSeries(it) } }.padding(vertical = 2.dp)) {
                        Text((r?.series?.ifEmpty { null } ?: r?.title.orEmpty()) + "  ›", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(listOfNotNull(r?.episode?.ifEmpty { null }?.let { "Episode $it" }, r?.lang?.ifEmpty { null }, src.name).joinToString(" · "),
                            color = Color(0xCCFFFFFF), fontSize = 12.5.sp, maxLines = 1)
                    }
                    if (d != null && d.stream.subs.isNotEmpty()) {
                        CtlIcon(if (settings.videoSubtitles) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionDisabled, "Subtitles") {
                            val on = !settings.videoSubtitles
                            Repo.updateSettings { it.copy(videoSubtitles = on) }
                            player.trackSelectionParameters = player.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, !on).build()
                            show()
                        }
                    }
                    if (d?.stream?.kind == "hls") CtlIcon(Icons.Rounded.HighQuality, "Quality") { showQuality = !showQuality; showSpeeds = false; show() }
                    if (d != null) {
                        val favs by VideoRepo.favs.collectAsState()
                        val fav = d.ref.key in favs
                        IconButton(onClick = {
                            val on = VideoRepo.toggleFav(d.ref)
                            Toast.makeText(ctx, if (on) "Added to favourite videos" else "Removed from favourite videos", Toast.LENGTH_SHORT).show()
                            show()
                        }) { Icon(if (fav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Favourite", tint = if (fav) Mc.Pink else Color.White) }
                    }
                    if (d != null && r != null) {
                        // download for offline: ↓ / progress / done
                        val vids by VideoDownloads.videos.collectAsState()
                        val queue by Downloads.queue.tasks.collectAsState()
                        val task = queue.firstOrNull { it.id == DlTask.videoId(r.key) }
                        when {
                            r.key in vids -> CtlIcon(Icons.Rounded.DownloadDone, "Downloaded") { toast("Saved on the phone · More › Downloads"); show() }
                            task != null -> Text(if (task.state == com.yoruc.mangacracker.core.DlState.FAILED) "↓ failed" else "↓ ${task.done / 10}%",
                                color = if (task.state == com.yoruc.mangacracker.core.DlState.FAILED) Mc.Pink else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { nav.downloads() }.padding(horizontal = 8.dp, vertical = 6.dp))
                            else -> CtlIcon(Icons.Rounded.Download, "Download") { toast(Downloads.enqueueVideo(ctx, r)); show() }
                        }
                    }
                    if ((d?.episodes?.size ?: 0) > 1) CtlIcon(Icons.Rounded.VideoLibrary, "Episodes") { showEpisodes = true }
                }
                // middle: previous, -1 s, play / pause (a ring while loading), +1 s, next
                Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    RoundBtn(Icons.Rounded.SkipPrevious, "Previous episode", 48.dp, enabled = prev != null) { prev?.let { openEpisode(it) } }
                    TextBtn("−1s", 52.dp) { seekBy(-1000); show() }
                    Box(contentAlignment = Alignment.Center) {
                        if (buffering && vm.error == null && playError == null) CircularProgressIndicator(Modifier.size(80.dp), color = Mc.Blue, strokeWidth = 3.dp)
                        Box(Modifier.size(68.dp).clip(CircleShape).background(Mc.Blue).clickable { togglePlay() }, contentAlignment = Alignment.Center) {
                            Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (playing) "Pause" else "Play", tint = Mc.Bg, modifier = Modifier.size(44.dp))
                        }
                    }
                    TextBtn("+1s", 52.dp) { seekBy(1000); show() }
                    RoundBtn(Icons.Rounded.SkipNext, "Next episode", 48.dp, enabled = next != null) { next?.let { openEpisode(it) } }
                }
                // bottom: volume and the buttons, then the seek bar
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safePad(top = false).padding(start = 10.dp, end = 10.dp, bottom = 2.dp)) {
                    if (showSpeeds) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(SPEEDS) { s -> Pill(speedLabel(s), Modifier.clickable { setSpeed(s) }, if (s == speed) Mc.Blue else Color(0xCC1C2838)) }
                        }
                    }
                    if (showQuality) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
                            items(listOf(-1, 0, 1080, 720, 480)) { h ->
                                Pill(qualityLabel(h), Modifier.clickable {
                                    qualityCap = h; showQuality = false; show()
                                    player.trackSelectionParameters = qualityParams(player.trackSelectionParameters.buildUpon(), h).build()
                                    // the choice stays for the next videos
                                    Repo.updateSettings { it.copy(videoQuality = h) }
                                }, if (h == qualityCap) Mc.Blue else Color(0xCC1C2838))
                            }
                        }
                    }
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val labels = maxWidth >= 560.dp
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            // the app's own volume: tap the speaker to mute, drag to set
                            val shown = if (muted) 0f else volume
                            IconButton(onClick = { toggleMute() }) {
                                Icon(when { shown == 0f -> Icons.AutoMirrored.Rounded.VolumeOff; shown < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown; else -> Icons.AutoMirrored.Rounded.VolumeUp },
                                    if (muted) "Unmute" else "Mute", tint = Color.White)
                            }
                            // portrait has no room for the % (the bar is shorter there)
                            VolumeBar(shown, Modifier.width(if (labels) 160.dp else 84.dp), onChange = { setVolume(it, false) }, onDone = { setVolume(volume, true) })
                            if (labels) Text("${(shown * 100).roundToInt()}%", color = Color(0xCCFFFFFF), fontSize = 12.sp, modifier = Modifier.padding(start = 2.dp).width(36.dp))
                            Spacer(Modifier.weight(1f))
                            BarBtn(Icons.Rounded.PhotoCamera, "Snapshot", labels = labels) {
                                val bmp = texture?.bitmap ?: return@BarBtn
                                val name = "${r?.series?.ifEmpty { null } ?: r?.title.orEmpty()} ep${r?.episode.orEmpty()} ${clock(pos / 1000).replace(':', '-')}"
                                scope.launch {
                                    flash = true; delay(120); flash = false
                                    runCatching { ClipSaver.saveSnapshot(ctx, bmp, name) }
                                        .onSuccess { Toast.makeText(ctx, "Snapshot saved to $it", Toast.LENGTH_SHORT).show() }
                                        .onFailure { Toast.makeText(ctx, "Couldn't save: ${it.message}", Toast.LENGTH_SHORT).show() }
                                }
                                show()
                            }
                            val rec = recStart != null
                            BarBtn(if (rec) Icons.Rounded.Stop else Icons.Rounded.FiberManualRecord, if (rec) "Stop recording" else "Record", tint = Color(0xFFFF4D4D), labels = labels) {
                                val d2 = vm.details ?: return@BarBtn
                                val start = recStart
                                if (start == null) { recStart = player.currentPosition; show(); return@BarBtn }
                                recStart = null
                                val a = minOf(start, player.currentPosition); val b = maxOf(start, player.currentPosition)
                                if (b - a < 1000) { Toast.makeText(ctx, "Too short: record at least 1 second", Toast.LENGTH_SHORT).show(); return@BarBtn }
                                val name = "${d2.ref.series.ifEmpty { d2.ref.title }} ep${d2.ref.episode} ${clock(a / 1000).replace(':', '-')}"
                                saving = "Saving clip…"
                                ClipSaver.record(ctx, src, d2.stream, a, b, name, onProgress = { saving = "Saving clip $it%" }) { res ->
                                    saving = null
                                    res.onSuccess { Toast.makeText(ctx, "Clip saved to $it", Toast.LENGTH_LONG).show() }
                                        .onFailure { Toast.makeText(ctx, "Couldn't save the clip: ${it.message}", Toast.LENGTH_LONG).show() }
                                }
                                show()
                            }
                            val capturing = gifStart != null
                            BarBtn(Icons.Rounded.GifBox, if (capturing) "Stop GIF" else "GIF", tint = if (capturing) Color(0xFFFF4D4D) else Color.White, labels = labels) { gifButton() }
                            Pill(speedLabel(speed), Modifier.padding(horizontal = 4.dp).clickable { showSpeeds = !showSpeeds; showQuality = false; show() }, if (speed != 1f) Mc.Blue else Color(0xCC1C2838))
                            BarBtn(Icons.Rounded.ScreenRotation, "Rotate", labels = labels) { landscape = !landscape; show() }
                        }
                    }
                    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(clock((if (scrubbing) scrubPos else pos) / 1000), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 6.dp))
                        SeekBar(pos, dur, buffered, Modifier.weight(1f).padding(horizontal = 6.dp),
                            onDrag = { scrubDrag(it) }, onScrub = { scrubTo(it) }, onSeek = { seekExact(it) })
                        Text(clock(dur / 1000), color = Color(0xCCFFFFFF), fontSize = 13.sp)
                    }
                }
            }
        }

        // ---- GIF panel (inside the player) and in-window notes ----
        gif?.let { g ->
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().safePad(top = false), contentAlignment = Alignment.BottomCenter) {
                GifPanel(
                    g, Modifier.widthIn(max = 900.dp),
                    onPreview = { ms, exact, which -> gifPreview(g, ms, exact, which) },
                    onMake = { makeGif(g) },
                    onCancelMake = { g.job?.cancel(); g.working = null },
                    onSave = {
                        g.result?.let { res ->
                            scope.launch {
                                runCatching { ClipSaver.saveGif(ctx, res.file, clipName(g.a)) }
                                    .onSuccess { toast("GIF saved to $it") }.onFailure { toast("Couldn't save: ${it.message}") }
                            }
                        }
                    },
                    onShare = {
                        g.result?.let { res ->
                            scope.launch { runCatching { ClipSaver.shareGif(ctx, res.file, clipName(g.a)) }.onFailure { toast("Couldn't share: ${it.message}") } }
                        }
                    },
                    onClose = { closeGif() },
                )
            }
        }
        gifStart?.let { s -> GifRecBadge((pos - s).coerceAtLeast(0), Modifier.align(Alignment.TopCenter).safePad(top = true).padding(top = 64.dp)) }
        note?.let { Pill(it, Modifier.align(Alignment.TopCenter).safePad(top = true).padding(top = 14.dp)) }

        // ---- up next ----
        if (upNext > 0 && next != null) {
            Row(Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(20.dp).clip(RoundedCornerShape(12.dp)).background(Mc.Card).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                VideoCover(next, Modifier.width(96.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)))
                Column(Modifier.padding(horizontal = 10.dp)) {
                    Text("Up next · Episode ${next.episode}", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("Playing in $upNext", color = Mc.Text3, fontSize = 12.sp)
                }
                Pill("Play", Modifier.clickable { upNext = -1; openEpisode(next) }, Mc.Blue)
                Spacer(Modifier.width(6.dp))
                Pill("Cancel", Modifier.clickable { upNext = -1; show() })
            }
        }
    }

    if (showEpisodes) {
        val d = vm.details
        ModalBottomSheet(onDismissRequest = { showEpisodes = false }, containerColor = Mc.Card) {
            LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
                item { Text(d?.ref?.series ?: "", color = Mc.Text, fontWeight = FontWeight.Bold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 18.dp, vertical = 6.dp)) }
                items(d?.episodes.orEmpty(), key = { it.key }) { e ->
                    val cur = e.id == d?.ref?.id
                    val w = VideoRepo.state(e.key)
                    Row(Modifier.fillMaxWidth().clickable { showEpisodes = false; if (!cur) openEpisode(e) }.background(if (cur) Mc.Blue.copy(alpha = 0.12f) else Color.Transparent)
                        .padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.width(120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)).background(Mc.Card2)) {
                            VideoCover(e, Modifier.fillMaxSize())
                            if (w != null) Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(w.percent / 100f).height(3.dp).background(Mc.Pink))
                        }
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(if (e.episode.isNotEmpty()) "Episode ${e.episode}" else e.title, color = if (cur) Mc.Blue else Mc.Text, fontWeight = FontWeight.SemiBold, maxLines = 1)
                            Text(listOfNotNull(e.lang.ifEmpty { null }, e.duration.ifEmpty { null }, if (cur) "playing" else w?.let { if (it.done) "watched" else "${it.percent}%" }).joinToString(" · "),
                                color = Mc.Text3, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun qualityLabel(h: Int) = when (h) { -1 -> "Best"; 0 -> "Auto"; else -> "${h}p" }

/** HLS quality: Best = always the highest stream the phone can play, Auto = by connection speed, else at most [h]p. */
private fun qualityParams(b: TrackSelectionParameters.Builder, h: Int): TrackSelectionParameters.Builder =
    b.setForceHighestSupportedBitrate(h == -1).setMaxVideoSize(Int.MAX_VALUE, if (h <= 0) Int.MAX_VALUE else h + 10)

private fun speedLabel(s: Float) = (if (s % 1f == 0f) s.toInt().toString() else s.toString().trimEnd('0')) + "×"

@Composable
private fun Pill(text: String, modifier: Modifier = Modifier, bg: Color = Color(0xCC1C2838)) {
    Text(text, color = if (bg == Mc.Blue) Mc.Bg else Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.clip(RoundedCornerShape(50)).background(bg).padding(horizontal = 14.dp, vertical = 7.dp))
}

@Composable
private fun CtlIcon(icon: ImageVector, desc: String, onClick: () -> Unit) {
    IconButton(onClick = onClick) { Icon(icon, desc, tint = Color.White) }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, size: androidx.compose.ui.unit.Dp, enabled: Boolean = true, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 2.dp).size(size).clip(CircleShape).background(Color(0x44000000)).clickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = if (enabled) Color.White else Color(0x55FFFFFF), modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
private fun TextBtn(label: String, size: androidx.compose.ui.unit.Dp = 40.dp, onClick: () -> Unit) {
    Box(Modifier.padding(horizontal = 2.dp).size(size).clip(CircleShape).background(Color(0x44000000)).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = if (size > 44.dp) 16.sp else 14.sp)
    }
}

@Composable
private fun BarBtn(icon: ImageVector, desc: String, tint: Color = Color.White, labels: Boolean = true, onClick: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick).padding(horizontal = if (labels) 10.dp else 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, desc, tint = tint, modifier = Modifier.size(24.dp))
        if (labels) Text(desc, color = Color(0xCCFFFFFF), fontSize = 10.5.sp)
    }
}

/** Keeps controls clear of the display cutout and any system bar on the sides and the top or bottom. */
@Composable
private fun Modifier.safePad(top: Boolean) =
    windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + if (top) WindowInsetsSides.Top else WindowInsetsSides.Bottom))
