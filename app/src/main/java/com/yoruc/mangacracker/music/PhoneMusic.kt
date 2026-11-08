package com.yoruc.mangacracker.music

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Music tab's player on the phone: one ExoPlayer for the app (audio only: kara.moe's music videos play as sound,
 * their picture is never decoded), its queue = the list, Android's shuffle / repeat. [MusicService] puts it in a media
 * session, so it keeps playing in the background and with the screen off, with play / pause / next in the
 * notification and on the lock screen; it pauses for calls and other apps' sound (audio focus) and when headphones
 * come out.
 */
@OptIn(UnstableApi::class)
object PhoneMusic : MusicPlayer {
    private lateinit var app: Context
    private var exo: ExoPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    private val _now = MutableStateFlow<Track?>(null)
    private val _playing = MutableStateFlow(false)
    private val _position = MutableStateFlow(0L)
    private val _duration = MutableStateFlow(0L)
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    private val _index = MutableStateFlow(0)
    private val _shuffle = MutableStateFlow(false)
    private val _repeat = MutableStateFlow(REPEAT_ALL)
    override val now: StateFlow<Track?> = _now
    override val playing: StateFlow<Boolean> = _playing
    override val position: StateFlow<Long> = _position
    override val duration: StateFlow<Long> = _duration
    override val queue: StateFlow<List<Track>> = _queue
    override val index: StateFlow<Int> = _index
    override val shuffle: StateFlow<Boolean> = _shuffle
    override val repeat: StateFlow<Int> = _repeat

    fun init(context: Context) { app = context.applicationContext }

    /** The player (made on first use, on the main thread). */
    fun player(): ExoPlayer = exo ?: ExoPlayer.Builder(app)
        .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(Http.client)))
        .setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build().also { p ->
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true).build()
            p.volume = Repo.settings.value.musicVolume
            p.repeatMode = Player.REPEAT_MODE_ALL
            p.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) { _playing.value = isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING) }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) { _playing.value = playWhenReady && p.playbackState != Player.STATE_ENDED }
                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) { sync() }
                override fun onPlaybackStateChanged(state: Int) { if (state == Player.STATE_ENDED) _playing.value = false; sync() }
                override fun onShuffleModeEnabledChanged(on: Boolean) { _shuffle.value = on }
                override fun onRepeatModeChanged(mode: Int) {
                    _repeat.value = when (mode) { Player.REPEAT_MODE_ONE -> REPEAT_ONE; Player.REPEAT_MODE_OFF -> REPEAT_OFF; else -> REPEAT_ALL }
                }
            })
            exo = p
            tick()
        }

    private fun sync() {
        val p = exo ?: return
        val i = p.currentMediaItemIndex
        _index.value = i
        _now.value = _queue.value.getOrNull(i)
        val d = p.duration
        _duration.value = if (d == C.TIME_UNSET || _now.value?.live == true) 0L else d
    }

    /** The bar's clock, twice a second while something is loaded. */
    private fun tick() {
        main.postDelayed({
            exo?.let { p ->
                if (p.mediaItemCount > 0) {
                    _position.value = p.currentPosition
                    if (_duration.value == 0L && p.duration != C.TIME_UNSET && _now.value?.live != true) _duration.value = p.duration
                }
                tick()
            }
        }, 500)
    }

    private fun item(t: Track): MediaItem = MediaItem.Builder()
        .setMediaId(t.id).setUri(Uri.parse(t.url))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(t.title).setArtist(t.artist.ifEmpty { null }).setAlbumTitle(t.anime)
            .setArtworkUri(t.cover.takeIf { it.isNotEmpty() }?.let(Uri::parse)).build())
        .build()

    override fun play(list: List<Track>, start: Int, shuffled: Boolean) {
        if (list.isEmpty()) return
        val p = player()
        _queue.value = list
        if (list.first().live) Music.watchRadio()
        p.setMediaItems(list.map(::item), start.coerceIn(list.indices), 0L)
        p.shuffleModeEnabled = shuffled
        p.prepare()
        p.play()
        sync()
        // the media session (notification, lock screen, background) lives in the service
        runCatching { app.startService(Intent(app, MusicService::class.java)) }
    }

    override fun toggle() {
        val p = exo ?: return
        when {
            p.isPlaying || (p.playWhenReady && p.playbackState == Player.STATE_BUFFERING) -> p.pause()
            p.playbackState == Player.STATE_ENDED -> { p.seekTo(0, 0L); p.play() }   // the end of the list (no loop): from the start
            _now.value?.live == true -> { p.seekToDefaultPosition(); p.prepare(); p.play() }   // live: back to now
            else -> { if (p.playbackState == Player.STATE_IDLE) p.prepare(); p.play() }
        }
    }

    override fun pause() { exo?.pause() }

    override fun next() { exo?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() else it.seekTo(0, 0L) } }

    override fun previous() { exo?.seekToPrevious() }

    override fun seek(ms: Long) { if (_now.value?.live != true) exo?.seekTo(ms) }

    override fun setShuffle(on: Boolean) { exo?.shuffleModeEnabled = on }

    override fun setRepeat(mode: Int) {
        exo?.repeatMode = when (mode) { REPEAT_ONE -> Player.REPEAT_MODE_ONE; REPEAT_OFF -> Player.REPEAT_MODE_OFF; else -> Player.REPEAT_MODE_ALL }
        _repeat.value = mode
    }

    override fun stop() {
        exo?.let { it.stop(); it.clearMediaItems() }
        _now.value = null; _playing.value = false; _queue.value = emptyList(); _position.value = 0; _duration.value = 0
        Music.unwatchRadio()
        runCatching { app.stopService(Intent(app, MusicService::class.java)) }
    }

    override fun setVolume(v: Float) {
        val x = v.coerceIn(0f, 1f)
        Repo.updateSettings { it.copy(musicVolume = x) }
        exo?.volume = x
    }
}

/**
 * Keeps the music going with the app in the background: the media session over [PhoneMusic]'s player, which gives the
 * notification with play / pause / previous / next, the lock-screen controls and headset buttons. Android shows it as
 * a foreground (media playback) service while music plays.
 */
@OptIn(UnstableApi::class)
class MusicService : MediaSessionService() {
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val s = MediaSession.Builder(this, PhoneMusic.player()).setId("music").build()
        session = s
        addSession(s)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    /** Swiped away from the recent apps: stop unless music is playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val p = session?.player
        if (p == null || !p.playWhenReady || p.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        session?.release()   // the player stays (PhoneMusic owns it)
        session = null
        super.onDestroy()
    }
}
