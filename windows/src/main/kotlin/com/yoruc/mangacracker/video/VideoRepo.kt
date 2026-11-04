package com.yoruc.mangacracker.video

import android.content.Context
import com.yoruc.mangacracker.core.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where a video was left (resume + Continue watching). */
@Serializable
data class WatchState(val ref: VideoRef, val positionMs: Long, val durationMs: Long, val time: Long) {
    val done: Boolean get() = durationMs > 0 && positionMs >= (if (durationMs < 60_000) durationMs * 9 / 10 else durationMs - 30_000)
    val percent: Int get() = if (durationMs > 0) (positionMs * 100 / durationMs).toInt().coerceIn(0, 100) else 0
}

/** A favourite video (Library → Favourite videos). */
@Serializable
data class FavVideo(val ref: VideoRef, val added: Long)

object VideoRepo {
    private var file: File? = null
    private var favFile: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private var favJob: Job? = null
    private val ser = MapSerializer(String.serializer(), WatchState.serializer())
    private val favSer = MapSerializer(String.serializer(), FavVideo.serializer())

    private val _favs = MutableStateFlow<Map<String, FavVideo>>(emptyMap())
    val favs: StateFlow<Map<String, FavVideo>> = _favs

    private val _watch = MutableStateFlow<Map<String, WatchState>>(emptyMap())
    val watch: StateFlow<Map<String, WatchState>> = _watch
    /** Videos opened this run (routes carry only the key). */
    val refs = ConcurrentHashMap<String, VideoRef>()
    /** Details already loaded this run. */
    val details = ConcurrentHashMap<String, VideoDetails>()

    fun init(context: Context) {
        file = File(context.filesDir, "data/videos.json").apply { parentFile?.mkdirs() }
        _watch.value = runCatching { Http.json.decodeFromString(ser, file!!.readText()) }.getOrDefault(emptyMap())
        _watch.value.values.forEach { refs[it.ref.key] = it.ref }
        favFile = File(context.filesDir, "data/video_favs.json")
        _favs.value = runCatching { Http.json.decodeFromString(favSer, favFile!!.readText()) }.getOrDefault(emptyMap())
        _favs.value.values.forEach { refs.putIfAbsent(it.ref.key, it.ref) }
        // shows / videos opened lately: their screens come back after Android closed the app in the background
        recentFile = File(context.filesDir, "data/video_recent.json")
        runCatching { Http.json.decodeFromString(ListSerializer(VideoRef.serializer()), recentFile!!.readText()) }.getOrNull()
            ?.forEach { r -> refs.putIfAbsent(r.key, r); synchronized(recent) { recent[r.key] = r } }
    }

    private var recentFile: File? = null
    private var recentJob: Job? = null
    private val recent = LinkedHashMap<String, VideoRef>()

    fun isFav(key: String) = key in _favs.value

    /** [ref] with the tags its details brought (lists often have none): what Recommended for you learns from. */
    private fun withTags(ref: VideoRef): VideoRef {
        val t = details[ref.key]?.tags.orEmpty()
        return if (t.isEmpty() || t.all { it in ref.tags }) ref else ref.copy(tags = (ref.tags + t).distinct())
    }

    /** Adds or removes; returns true when it is a favourite now. */
    fun toggleFav(ref: VideoRef): Boolean {
        val now = ref.key !in _favs.value
        _favs.update { if (now) it + (ref.key to FavVideo(withTags(ref), System.currentTimeMillis())) else it - ref.key }
        persistFavs()
        return now
    }

    private fun persistFavs() {
        val f = favFile ?: return
        favJob?.cancel()
        favJob = scope.launch {
            delay(300)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Http.json.encodeToString(favSer, _favs.value))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** Restored resume points and favourites (see Backup) replace what is here. */
    fun restore(watch: Map<String, WatchState>, favs: Map<String, FavVideo>) {
        _watch.value = watch
        _favs.value = favs
        watch.values.forEach { refs[it.ref.key] = it.ref }
        favs.values.forEach { refs.putIfAbsent(it.ref.key, it.ref) }
        persist(); persistFavs()
    }

    /** Every watched video, newest first (Library → Watched). */
    fun watched(): List<WatchState> = _watch.value.values.sortedByDescending { it.time }

    fun clearWatched() { _watch.update { emptyMap() }; persist() }

    fun remember(ref: VideoRef) {
        refs[ref.key] = ref
        val list = synchronized(recent) {
            recent.remove(ref.key); recent[ref.key] = ref
            while (recent.size > 80) recent.remove(recent.keys.first())
            recent.values.toList()
        }
        val f = recentFile ?: return
        recentJob?.cancel()
        recentJob = scope.launch {
            delay(1000)
            runCatching {
                val tmp = File(f.path + ".tmp")
                tmp.writeText(Http.json.encodeToString(ListSerializer(VideoRef.serializer()), list))
                if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
            }
        }
    }

    fun state(key: String): WatchState? = _watch.value[key]

    fun save(ref: VideoRef, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        _watch.update { it + (ref.key to WatchState(withTags(ref), positionMs, durationMs, System.currentTimeMillis())) }
        persist()
    }

    fun remove(key: String) { _watch.update { it - key }; persist() }

    /** Unfinished videos, newest first, one per series. */
    fun continueList(): List<WatchState> = _watch.value.values.sortedByDescending { it.time }
        .filter { !it.done && it.positionMs > 5_000 }
        .distinctBy { it.ref.source + "|" + it.ref.series.ifEmpty { it.ref.id } }

    private fun persist() {
        val f = file ?: return
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(500)
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Http.json.encodeToString(ser, _watch.value))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }
}
