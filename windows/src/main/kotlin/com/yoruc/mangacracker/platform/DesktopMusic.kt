package com.yoruc.mangacracker.platform

import com.sun.jna.Pointer
import com.sun.jna.ptr.DoubleByReference
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.music.Music
import com.yoruc.mangacracker.music.MusicPlayer
import com.yoruc.mangacracker.music.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.cbrt

/**
 * The Music tab's player on Windows: its own sound-only mpv (the video player and the reader's sounds have theirs),
 * playing the queue one track after another; it keeps going on every tab and with the window minimised. Streams
 * (YouTube songs, found when they start; archive.org; LISTEN.moe) are read by mpv directly.
 */
object DesktopMusic : MusicPlayer {
    private val _now = MutableStateFlow<Track?>(null)
    private val _playing = MutableStateFlow(false)
    private val _position = MutableStateFlow(0L)
    private val _duration = MutableStateFlow(0L)
    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    private val _index = MutableStateFlow(0)
    private val _shuffle = MutableStateFlow(false)
    private val _repeat = MutableStateFlow(com.yoruc.mangacracker.music.REPEAT_ALL)
    override val now: StateFlow<Track?> = _now
    override val playing: StateFlow<Boolean> = _playing
    override val position: StateFlow<Long> = _position
    override val duration: StateFlow<Long> = _duration
    override val queue: StateFlow<List<Track>> = _queue
    override val index: StateFlow<Int> = _index
    override val shuffle: StateFlow<Boolean> = _shuffle
    override val repeat: StateFlow<Int> = _repeat

    override fun setRepeat(mode: Int) { _repeat.value = mode }

    /** The queue in play order ([order] = indices into [_queue], shuffled or not). */
    private var order: List<Int> = emptyList()
    private var at = 0
    private var mpv: Player? = null
    private val lock = Any()

    override fun play(list: List<Track>, start: Int, shuffled: Boolean) {
        if (list.isEmpty()) return
        synchronized(lock) {
            _queue.value = list
            _shuffle.value = shuffled
            order = makeOrder(list.size, start.coerceIn(list.indices), shuffled)
            at = 0
            if (list.first().live) Music.watchRadio()
            loadCurrent()
        }
    }

    private fun makeOrder(n: Int, start: Int, shuffled: Boolean): List<Int> =
        if (shuffled) listOf(start) + (0 until n).filter { it != start }.shuffled() else (start until n) + (0 until start)

    /** Counts loads: a YouTube song still being looked up when another is chosen is dropped. */
    private var loads = 0
    /** Songs in a row that couldn't be played: past the whole queue, it stops trying. */
    private var failures = 0
    /** The track whose stream address was renewed once already (an address that stopped working). */
    private var retried: String? = null

    private fun loadCurrent() {
        val t = _queue.value.getOrNull(order.getOrNull(at) ?: return) ?: return
        val p = mpv ?: runCatching { Player { reason -> onEnded(reason) } }.onFailure { Desktop.toast("Music can't play: ${it.message}") }.getOrNull() ?: return
        mpv = p
        p.volume(Repo.settings.value.musicVolume)
        val n = ++loads
        _now.value = t; _index.value = order[at]; _position.value = 0; _duration.value = t.durationS * 1000L; _playing.value = true
        if (t.url.isNotEmpty()) { p.load(t.url); p.pause(false); return }
        // a song from YouTube: its address is looked up now (1-3 s); the last song stops meanwhile
        p.stopFile()
        Thread({
            val r = runCatching { Music.resolve(t) }
            synchronized(lock) {
                if (n != loads || mpv !== p) return@synchronized
                r.onSuccess { s ->
                    failures = 0
                    _now.value = s.copy(url = ""); _duration.value = s.durationS * 1000L
                    _queue.value = _queue.value.map { if (it.id == s.id) it.copy(quality = s.quality, durationS = s.durationS) else it }
                    p.load(s.url); p.pause(!_playing.value)
                }.onFailure { e ->
                    Desktop.toast(e.message ?: "Couldn't play \"${t.title}\"")
                    if (++failures < order.size) { at = (at + 1) % order.size; loadCurrent() } else { failures = 0; _playing.value = false }
                }
            }
        }, "music-find").apply { isDaemon = true }.start()
    }

    /**
     * A track ended (or couldn't be played). Loop the list (default): the next one, after the last the list starts over;
     * loop this one: the same again; no loop: the next one, and after the last it stops (paused, the bar stays).
     */
    private fun onEnded(reason: Int) = synchronized(lock) {
        if (_now.value?.live == true) return@synchronized   // the radio dropped: it shows as paused
        if (order.isEmpty()) return@synchronized
        val cur = _now.value
        // a YouTube address that stopped working (they last a few hours): look it up again, once
        if (reason == 4 && cur != null && cur.url.isEmpty() && retried != cur.id) { retried = cur.id; Music.forget(cur.id); loadCurrent(); return@synchronized }
        when (_repeat.value) {
            com.yoruc.mangacracker.music.REPEAT_ONE -> loadCurrent()
            com.yoruc.mangacracker.music.REPEAT_OFF -> if (at < order.size - 1) { at++; loadCurrent() } else { _playing.value = false; _position.value = _duration.value }
            else -> { at = (at + 1) % order.size; loadCurrent() }
        }
    }

    override fun toggle() {
        val p = mpv ?: return
        val play = !_playing.value
        if (play && _now.value?.live == true) p.load(Music.RADIO_URL)   // live: back to now, not to where it was paused
        // stopped at the end of the list (no loop): play starts the list again
        else if (play && _repeat.value == com.yoruc.mangacracker.music.REPEAT_OFF && p.time() == null) synchronized(lock) { at = 0; loadCurrent(); return }
        p.pause(!play); _playing.value = play
    }

    override fun pause() { if (_playing.value) toggle() }

    override fun next() = synchronized(lock) { if (order.isNotEmpty()) { at = (at + 1) % order.size; loadCurrent() } }

    override fun previous() = synchronized(lock) {
        if (order.isEmpty()) return@synchronized
        // more than 3 s in: back to the start of this one
        if (_position.value > 3000) { mpv?.seek(0.0); return@synchronized }
        at = (at - 1 + order.size) % order.size; loadCurrent()
    }

    override fun seek(ms: Long) { if (_now.value?.live != true) mpv?.seek(ms / 1000.0) }

    override fun setShuffle(on: Boolean) = synchronized(lock) {
        val cur = order.getOrNull(at) ?: return@synchronized
        _shuffle.value = on
        order = makeOrder(_queue.value.size, cur, on); at = 0
    }

    override fun stop() = synchronized(lock) {
        mpv?.close(); mpv = null
        _now.value = null; _playing.value = false; _queue.value = emptyList(); order = emptyList(); _position.value = 0; _duration.value = 0
        Music.unwatchRadio()
    }

    override fun setVolume(v: Float) {
        Repo.updateSettings { it.copy(musicVolume = v.coerceIn(0f, 1f)) }
        mpv?.volume(v)
    }

    /** The bar's clock: position / length from mpv a few times a second while something is loaded. */
    private val ticker = Thread({
        while (true) {
            runCatching {
                mpv?.let { p ->
                    p.time()?.let { _position.value = (it * 1000).toLong() }
                    p.length()?.takeIf { it > 0 }?.let { _duration.value = (it * 1000).toLong() }
                }
            }
            Thread.sleep(400)
        }
    }, "music-clock").apply { isDaemon = true; start() }

    /** A sound-only mpv; [onEnded] when a file finishes or fails (not when it's replaced). */
    private class Player(onEnded: (Int) -> Unit) {
        private val l = Mpv.lib ?: throw IllegalStateException("mpv (libmpv-2.dll) wasn't found")
        private val h: Pointer = l.mpv_create() ?: throw IllegalStateException("mpv didn't start")
        @Volatile private var alive = true

        init {
            l.mpv_set_option_string(h, "vid", "no")
            l.mpv_set_option_string(h, "video", "no")
            l.mpv_set_option_string(h, "idle", "yes")
            l.mpv_set_option_string(h, "input-default-bindings", "no")
            l.mpv_set_option_string(h, "ytdl", "no")
            l.mpv_set_option_string(h, "audio-client-name", "MangaCracker music")
            l.mpv_set_option_string(h, "volume-max", "100")
            l.mpv_set_option_string(h, "cache", "yes")
            l.mpv_set_option_string(h, "user-agent", com.yoruc.mangacracker.core.Http.userAgent)
            // test runs (off-screen): never any sound
            if (System.getenv("MC_OFFSCREEN") == "1") l.mpv_set_option_string(h, "ao", "null")
            if (l.mpv_initialize(h) < 0) { l.mpv_terminate_destroy(h); throw IllegalStateException("mpv didn't start") }
            Thread({
                while (true) {
                    val e = Mpv.Event(l.mpv_wait_event(h, 1.0))
                    if (e.event_id == Mpv.EVENT_SHUTDOWN) break
                    // mpv_end_file_reason: 0 = end of file, 4 = error (2 = stopped / replaced: not an end)
                    if (alive && e.event_id == Mpv.EVENT_END_FILE) { val r = e.data?.getInt(0); if (r == 0 || r == 4) onEnded(r) }
                }
                synchronized(this) { l.mpv_terminate_destroy(h) }
            }, "music-events").apply { isDaemon = true }.start()
        }

        @Synchronized fun load(url: String) { if (alive) l.mpv_command(h, arrayOf("loadfile", url, "replace", null)) }
        @Synchronized fun stopFile() { if (alive) l.mpv_command(h, arrayOf("stop", null)) }
        @Synchronized fun pause(p: Boolean) { if (alive) l.mpv_set_property_string(h, "pause", if (p) "yes" else "no") }
        @Synchronized fun seek(s: Double) { if (alive) l.mpv_command(h, arrayOf("seek", "%.2f".format(java.util.Locale.ROOT, s), "absolute", null)) }
        /** [v] is linear loudness (0..1); mpv's volume is cubic, so its cube root. */
        @Synchronized fun volume(v: Float) { if (alive) l.mpv_set_property_string(h, "volume", "%.1f".format(java.util.Locale.ROOT, 100f * cbrt(v.coerceIn(0f, 1f)))) }
        @Synchronized fun time(): Double? = if (!alive) null else DoubleByReference().let { r -> if (l.mpv_get_property(h, "time-pos", 5, r) >= 0) r.value else null }
        @Synchronized fun length(): Double? = if (!alive) null else DoubleByReference().let { r -> if (l.mpv_get_property(h, "duration", 5, r) >= 0) r.value else null }
        @Synchronized fun close() { if (!alive) return; alive = false; l.mpv_command(h, arrayOf("quit", null)) }
    }
}
