package com.yoruc.mangacracker.core

import com.sun.jna.Pointer
import com.yoruc.mangacracker.platform.Desktop
import com.yoruc.mangacracker.platform.Mpv
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.cbrt

/**
 * Sounds while reading: a quiet loop under the pages (rain, café, fireplace, night crickets, or the user's
 * own audio file). One player for the app; it fades in and out, pauses while the window is minimised and stops when
 * the reader closes. The built-in loops are the phone's (made from scratch by the sounds script, 60 s each, seamless).
 * Windows: a sound-only mpv (the video player's libmpv) in place of ExoPlayer; the loops are unpacked from the app
 * into its data folder, the own sound is a file path.
 */
object Ambient {
    class Sound(val id: String, val name: String, val file: String)

    const val OWN = "own"
    val sounds = listOf(
        Sound("rain", "Rain", "ambient_rain.ogg"),
        Sound("cafe", "Café", "ambient_cafe.ogg"),
        Sound("fire", "Fireplace", "ambient_fire.ogg"),
        Sound("night", "Night crickets", "ambient_night.ogg"),
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private var player: SoundPlayer? = null
    private var loaded: String? = null
    private var fade: Job? = null
    /** 0..1, what the fade last set (linear, like the phone's ExoPlayer volume). */
    @Volatile private var level = 0f
    private val _error = MutableStateFlow<String?>(null)
    /** Set when the chosen file can't be played (moved, deleted, not audio). */
    val error: StateFlow<String?> = _error

    /** The built-in loop unpacked next to the app's data (once), or the user's own file. */
    fun fileOf(s: Settings = Repo.settings.value): File? = when (s.readerSound) {
        "" -> null
        OWN -> s.readerSoundUri.takeIf { it.isNotEmpty() }?.let(::File)
        else -> sounds.firstOrNull { it.id == s.readerSound }?.let { unpack(it.file) }
    }

    private fun unpack(name: String): File? = runCatching {
        val f = File(Desktop.root, "sounds/$name")
        if (!f.isFile) {
            f.parentFile.mkdirs()
            val tmp = File(f.path + ".part")
            Ambient::class.java.getResourceAsStream("/sounds/$name")!!.use { i -> tmp.outputStream().use { i.copyTo(it) } }
            tmp.renameTo(f)
        }
        f
    }.getOrNull()

    /** Plays what Settings say, or fades out when nothing is chosen. Call again whenever the choice or volume changes. */
    fun sync(who: Any? = null) {
        if (who != null) owner = who
        val s = Repo.settings.value
        val f = fileOf(s) ?: return stop()
        synchronized(lock) {
            val p = player ?: runCatching { SoundPlayer { onError() } }.getOrElse {
                _error.value = "Couldn't start the sound player"
                return
            }.also { player = it; level = 0f; it.volume(0f) }
            if (f.path != loaded) {
                loaded = f.path
                _error.value = null
                p.load(f.path)
            }
            p.pause(false)
        }
        fadeTo(s.readerSoundVolume.coerceIn(0f, 1f))
    }

    /** The reader that last asked for the sound. Previous / Next chapter opens the new reader before the old one
     *  leaves; the old one's pause / stop must not silence the new one's sound. */
    @Volatile private var owner: Any? = null

    /** The window was minimised: fade out and hold. */
    fun pause(who: Any? = null) {
        if (who != null && owner !== who) return
        fadeTo(0f) { player?.pause(true) }
    }

    /** The reader closed: fade out and let the player go. */
    fun stop(who: Any? = null) {
        if (who != null && owner !== who) return
        owner = null
        fadeTo(0f) { release() }
    }

    private fun onError() {
        _error.value = if (Repo.settings.value.readerSound == OWN) "Couldn't play your sound file. Choose it again?" else "Couldn't play this sound"
        scope.launch { release() }
    }

    private fun release() = synchronized(lock) {
        fade?.cancel()
        player?.close()
        player = null
        loaded = null
    }

    private fun fadeTo(v: Float, then: (() -> Unit)? = null) {
        val p = player ?: return
        fade?.cancel()
        fade = scope.launch {
            val from = level
            val steps = 16
            for (i in 1..steps) {
                level = from + (v - from) * i / steps
                p.volume(level)
                delay(45)
            }
            then?.invoke()
        }
    }

    /** A sound-only mpv that loops one file; [onError] when the file can't be played. */
    private class SoundPlayer(onError: () -> Unit) {
        private val l = Mpv.lib ?: throw IllegalStateException("mpv (libmpv-2.dll) wasn't found")
        private val h: Pointer = l.mpv_create() ?: throw IllegalStateException("mpv didn't start")
        @Volatile private var alive = true

        init {
            l.mpv_set_option_string(h, "vid", "no")
            l.mpv_set_option_string(h, "video", "no")
            l.mpv_set_option_string(h, "loop-file", "inf")
            l.mpv_set_option_string(h, "idle", "yes")
            l.mpv_set_option_string(h, "input-default-bindings", "no")
            l.mpv_set_option_string(h, "ytdl", "no")
            l.mpv_set_option_string(h, "audio-client-name", "MangaCracker")
            l.mpv_set_option_string(h, "volume-max", "100")
            // test runs (off-screen): never any sound
            if (System.getenv("MC_OFFSCREEN") == "1") l.mpv_set_option_string(h, "ao", "null")
            if (l.mpv_initialize(h) < 0) { l.mpv_terminate_destroy(h); throw IllegalStateException("mpv didn't start") }
            // events: a file that ends with an error (looping files never end otherwise). This thread also frees the
            // player once mpv has shut down, so nothing waits on a destroyed handle.
            Thread({
                while (true) {
                    val e = Mpv.Event(l.mpv_wait_event(h, 1.0))
                    if (e.event_id == Mpv.EVENT_SHUTDOWN) break
                    if (alive && e.event_id == Mpv.EVENT_END_FILE && e.data?.getInt(0) == END_ERROR) onError()
                }
                synchronized(this) { l.mpv_terminate_destroy(h) }
            }, "ambient-events").apply { isDaemon = true }.start()
        }

        @Synchronized fun load(path: String) { if (alive) l.mpv_command(h, arrayOf("loadfile", path, "replace", null)) }
        @Synchronized fun pause(p: Boolean) { if (alive) l.mpv_set_property_string(h, "pause", if (p) "yes" else "no") }

        /** [v] is linear loudness (0..1, as on the phone); mpv's volume is cubic, so its cube root. */
        @Synchronized fun volume(v: Float) { if (alive) l.mpv_set_property_string(h, "volume", "%.1f".format(java.util.Locale.ROOT, 100f * cbrt(v.coerceIn(0f, 1f)))) }

        /** Asks mpv to quit; the event thread frees it on the shutdown event. */
        @Synchronized fun close() {
            if (!alive) return
            alive = false
            l.mpv_command(h, arrayOf("quit", null))
        }

        companion object { const val END_ERROR = 4 }  // mpv_end_file_reason MPV_END_FILE_REASON_ERROR
    }
}
