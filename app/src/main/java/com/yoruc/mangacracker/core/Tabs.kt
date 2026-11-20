package com.yoruc.mangacracker.core

import android.content.Context
import android.net.Uri
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.io.File

/**
 * One open title: the page it is on ([route]), and the manga or video it belongs to. A tab with a [place] is a list
 * you were browsing (a source, the Videos tab) instead: it keeps that list while its titles open in tabs beside it.
 */
@Serializable
data class OpenTab(val id: Long, val route: String, val manga: MangaRef? = null, val video: VideoRef? = null, val place: String = "") {
    val title: String get() = place.ifEmpty { manga?.title ?: video?.title.orEmpty() }
    val cover: String get() = manga?.cover ?: video?.cover.orEmpty()
    val isPlace: Boolean get() = place.isNotEmpty()
}

/**
 * The open tabs (kept between runs). Only the tab on screen is loaded: the others are just their place, and the reader
 * and player pick up from the saved page / second when one is opened again. The app's navigation does the showing
 * (Nav.openTab); this only keeps the list and follows the page the active tab moves to.
 */
object Tabs {
    const val MAX = 30
    private val _list = MutableStateFlow<List<OpenTab>>(emptyList())
    val list: StateFlow<List<OpenTab>> = _list
    private val _active = MutableStateFlow(0L)
    /** The tab on screen (0 = none). */
    val active: StateFlow<Long> = _active
    private val _here = MutableStateFlow<String?>(null)
    /** The title page on screen (null = one of the main screens). */
    val here: StateFlow<String?> = _here
    /** The phone's list of tabs is showing. */
    val sheetOpen = MutableStateFlow(false)
    private var hereManga: MangaRef? = null
    private var hereVideo: VideoRef? = null
    /** The list on screen that can be kept as a tab (route and name), null on other screens. */
    private var placeRoute: String? = null
    private var placeName = ""
    private var file: File? = null
    private var lastId = 0L

    fun init(ctx: Context) {
        val f = File(ctx.filesDir, "data/tabs.json").also { file = it; it.parentFile?.mkdirs() }
        val saved = runCatching { Http.json.decodeFromString(ListSerializer(OpenTab.serializer()), f.readText()) }.getOrNull().orEmpty()
        _list.value = saved.take(MAX)
        _active.value = 0L
        _here.value = null
        placeRoute = null
        lastId = saved.maxOfOrNull { it.id } ?: 0L
    }

    private fun persist() {
        val f = file ?: return
        runCatching {
            val tmp = File(f.path + ".tmp")
            tmp.writeText(Http.json.encodeToString(ListSerializer(OpenTab.serializer()), _list.value))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun detailsRoute(key: String) = "details/${Uri.encode(key)}"
    fun readerRoute(key: String, chapterId: String, grid: Boolean = false) = "reader/${Uri.encode(key)}/${Uri.encode(chapterId)}?grid=${if (grid) 1 else 0}"
    fun playerRoute(key: String) = "player/${Uri.encode(key)}"
    fun seriesRoute(key: String) = "vseries/${Uri.encode(key)}"
    fun browseRoute(source: String, search: Boolean = false) = "browse/$source?search=${if (search) 1 else 0}"
    const val VIDEOS = "videos"

    /**
     * A new tab, not shown yet; the page already in a tab gives that tab. Null = no room left. Opened from a list
     * (a source, Videos), the list becomes a tab too and stays as it is: the new tab goes right after it and its
     * other titles, so the way back to the list is always in the strip.
     */
    private fun add(route: String, manga: MangaRef?, video: VideoRef?): OpenTab? {
        _list.value.firstOrNull { it.route == route }?.let { return it }
        val from = placeRoute
        val keep = from != null && _list.value.none { it.route == from }
        if (_list.value.size + (if (keep) 1 else 0) >= MAX) return null
        if (keep) {
            val p = OpenTab(++lastId, from!!, place = placeName)
            _list.value = _list.value + p
            _active.value = p.id
        }
        val t = OpenTab(++lastId, route, manga, video)
        val l = _list.value
        val i = if (from == null) -1 else l.indexOfFirst { it.route == from }
        // after the list's tab and the titles already opened from it
        var at = if (i < 0) l.size else i + 1
        while (i >= 0 && at < l.size && !l[at].isPlace && l[at].id > l[i].id) at++
        _list.value = l.subList(0, at) + t + l.subList(at, l.size)
        persist()
        return t
    }

    fun addManga(ref: MangaRef): OpenTab? { Repo.remember(ref); return add(detailsRoute(ref.key), ref, null) }
    fun addChapter(ref: MangaRef, chapter: Chapter): OpenTab? { Repo.remember(ref); return add(readerRoute(ref.key, chapter.id), ref, null) }
    fun addVideo(v: VideoRef): OpenTab? { VideoRepo.remember(v); return add(if (v.show) seriesRoute(v.key) else playerRoute(v.key), null, v) }

    /** Makes the page on screen a tab and marks it as the one showing. */
    fun pinHere(): OpenTab? {
        val r = _here.value ?: return null
        val t = add(r, hereManga, hereVideo) ?: return null
        _active.value = t.id
        return t
    }

    fun activate(id: Long) { _active.value = id }

    /** Removes a tab. If it was showing, gives the tab to show instead (null = none left, or it wasn't showing). */
    fun close(id: Long): OpenTab? {
        val l = _list.value
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return null
        val rest = l.filter { it.id != id }
        _list.value = rest
        persist()
        if (_active.value != id) return null
        _active.value = 0L
        return rest.getOrNull(i) ?: rest.lastOrNull()
    }

    fun clear() { _list.value = emptyList(); _active.value = 0L; persist() }

    /** The tab [delta] places from the one showing, wrapping round. */
    fun step(delta: Int): OpenTab? {
        val l = _list.value
        if (l.isEmpty()) return null
        val i = l.indexOfFirst { it.id == _active.value }
        return l[if (i < 0) 0 else (i + delta + l.size) % l.size]
    }

    /** Where to open [t]: a reader tab goes to the chapter last read (the reader moves on by itself as you scroll). */
    fun latestRoute(t: OpenTab): String {
        if (t.isPlace) return t.route
        val m = t.manga
        if (m != null && t.route.startsWith("reader/")) Repo.history.value[m.key]?.let { return readerRoute(m.key, it.chapter.id) }
        return t.route
    }

    /** The small line under a tab's title in the phone's list. */
    fun subtitle(t: OpenTab): String {
        if (t.isPlace) return if (t.route == VIDEOS) "Video list" else "Source"
        t.video?.let { v -> return if (v.episode.isNotEmpty()) "Episode ${v.episode}" else "Video" }
        val m = t.manga ?: return ""
        return if (t.route.startsWith("reader/")) Repo.history.value[m.key]?.chapter?.name ?: "Reading" else "Details"
    }

    /**
     * From the app's navigation: this screen is now showing. [source] and [place] = the source and its name when the
     * screen is a source's list (the Videos tab needs only its name).
     */
    fun noteScreen(pattern: String?, key: String, chapter: String, grid: Boolean, source: String = "", search: Boolean = false, place: String = "") {
        val p = pattern.orEmpty()
        placeRoute = when {
            p.startsWith("browse/") && source.isNotEmpty() -> browseRoute(source, search)
            p == VIDEOS -> VIDEOS
            else -> null
        }
        placeName = place
        val r = when {
            p.startsWith("details/") -> detailsRoute(key)
            p.startsWith("reader/") -> readerRoute(key, chapter, grid)
            p.startsWith("player/") -> playerRoute(key)
            p.startsWith("vseries/") -> seriesRoute(key)
            else -> null
        }
        _here.value = r
        if (r == null) {
            hereManga = null; hereVideo = null
            // a list that is a tab lights its tab up
            _active.value = placeRoute?.let { pr -> _list.value.firstOrNull { it.route == pr }?.id } ?: 0L
            return
        }
        val video = p.startsWith("player/") || p.startsWith("vseries/")
        hereManga = if (video) null else Repo.refs[key]
        hereVideo = if (video) VideoRepo.refs[key] else null
        follow(r)
    }

    /** The player moved to another episode by itself (the route stays the first one's). */
    fun noteVideo(v: VideoRef) {
        val r = playerRoute(v.key)
        if (_here.value == r) { hereVideo = v; return }
        _here.value = r
        hereVideo = v
        hereManga = null
        follow(r)
    }

    /** The tab showing moves along with the page; a page that is already a tab lights that tab up. */
    private fun follow(route: String) {
        val id = _active.value
        val cur = _list.value.firstOrNull { it.id == id }
        // a list's tab never turns into the title opened from it
        if (cur == null || cur.isPlace) { _active.value = _list.value.firstOrNull { it.route == route }?.id ?: 0L; return }
        val manga = hereManga ?: cur.manga.takeIf { hereVideo == null }
        val video = hereVideo ?: cur.video.takeIf { hereManga == null }
        if (cur.route == route && cur.manga == manga && cur.video == video) return
        _list.update { l -> l.map { if (it.id == id) it.copy(route = route, manga = manga, video = video) else it } }
        persist()
    }
}
