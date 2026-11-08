package com.yoruc.mangacracker.core

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.ExoPlayer
import com.yoruc.mangacracker.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Sounds while reading: a quiet loop under the pages (rain, café, fireplace, night crickets, or the user's
 * own audio file). One player for the app; it fades in and out, gives way to calls / other music (audio focus), pauses
 * while the app is in the background and stops when the reader closes. The built-in loops are made from scratch by
 * the sounds script (no recordings), 60 s each and seamless.
 */
object Ambient {
    class Sound(val id: String, val name: String, val raw: Int)

    const val OWN = "own"
    val sounds = listOf(
        Sound("rain", "Rain", R.raw.ambient_rain),
        Sound("cafe", "Café", R.raw.ambient_cafe),
        Sound("fire", "Fireplace", R.raw.ambient_fire),
        Sound("night", "Night crickets", R.raw.ambient_night),
    )

    private val main = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: ExoPlayer? = null
    private var loaded: String? = null
    private var fade: Job? = null
    private val _error = MutableStateFlow<String?>(null)
    /** Set when the chosen file can't be played (moved, deleted, not audio). */
    val error: StateFlow<String?> = _error

    fun uriOf(ctx: Context, s: Settings = Repo.settings.value): Uri? = when (s.readerSound) {
        "" -> null
        OWN -> s.readerSoundUri.takeIf { it.isNotEmpty() }?.let(Uri::parse)
        else -> sounds.firstOrNull { it.id == s.readerSound }?.let { RawResourceDataSource.buildRawResourceUri(it.raw) }
    }

    /** Plays what Settings say, or fades out when nothing is chosen. Call again whenever the choice or volume changes. */
    fun sync(ctx: Context, who: Any? = null) {
        if (who != null) owner = who
        val s = Repo.settings.value
        val uri = uriOf(ctx, s) ?: return stop()
        val p = player ?: ExoPlayer.Builder(ctx.applicationContext).build().also {
            it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            it.repeatMode = Player.REPEAT_MODE_ONE
            it.volume = 0f
            it.addListener(object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    _error.value = if (Repo.settings.value.readerSound == OWN) "Couldn't play your sound file. Choose it again?" else "Couldn't play this sound"
                    release()
                }
            })
            player = it
        }
        if (uri.toString() != loaded) {
            loaded = uri.toString()
            _error.value = null
            p.setMediaItem(MediaItem.fromUri(uri))
            p.prepare()
        }
        p.playWhenReady = true
        fadeTo(s.readerSoundVolume.coerceIn(0f, 1f))
    }

    /** The reader that last asked for the sound. Previous / Next chapter opens the new reader before the old one
     *  leaves; the old one's pause / stop must not silence the new one's sound. */
    private var owner: Any? = null

    /** The app went to the background: fade out and hold. */
    fun pause(who: Any? = null) {
        if (who != null && owner !== who) return
        fadeTo(0f) { player?.pause() }
    }

    /** The reader closed: fade out and let the player go. */
    fun stop(who: Any? = null) {
        if (who != null && owner !== who) return
        owner = null
        fadeTo(0f) { release() }
    }

    private fun release() {
        fade?.cancel()
        player?.release()
        player = null
        loaded = null
    }

    private fun fadeTo(v: Float, then: (() -> Unit)? = null) {
        val p = player ?: return
        fade?.cancel()
        fade = main.launch {
            val from = p.volume
            val steps = 16
            for (i in 1..steps) {
                p.volume = from + (v - from) * i / steps
                delay(45)
            }
            then?.invoke()
        }
    }
}
