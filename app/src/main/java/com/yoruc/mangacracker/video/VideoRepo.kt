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
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Where a video was left (resume + Continue watching). */
@Serializable
data class WatchState(val ref: VideoRef, val positionMs: Long, val durationMs: Long, val time: Long) {
    val done: Boolean get() = durationMs > 0 && positionMs >= durationMs - 30_000
    val percent: Int get() = if (durationMs > 0) (positionMs * 100 / durationMs).toInt().coerceIn(0, 100) else 0
}

object VideoRepo {
    private var file: File? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveJob: Job? = null
    private val ser = MapSerializer(String.serializer(), WatchState.serializer())

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
    }

    fun remember(ref: VideoRef) { refs[ref.key] = ref }

    fun state(key: String): WatchState? = _watch.value[key]

    fun save(ref: VideoRef, positionMs: Long, durationMs: Long) {
        if (durationMs <= 0) return
        _watch.update { it + (ref.key to WatchState(ref, positionMs, durationMs, System.currentTimeMillis())) }
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
