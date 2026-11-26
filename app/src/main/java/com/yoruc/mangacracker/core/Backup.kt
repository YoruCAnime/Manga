package com.yoruc.mangacracker.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.yoruc.mangacracker.video.FavVideo
import com.yoruc.mangacracker.video.VideoRepo
import com.yoruc.mangacracker.video.WatchState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.time.LocalDate

class BackupException(message: String) : Exception(message)

/**
 * One JSON file with everything that only lives on the phone: library, history, reading progress, settings, video
 * resume points and favourite videos. Logins, cookies and API keys are never written (they stay on the phone that
 * has them). Reading is forgiving: unknown fields are ignored, a broken entry is skipped and counted.
 */
object Backup {
    /** Bump when the layout changes in a way an older app can't read; [read] then migrates older files. */
    const val FORMAT = 1
    const val MAGIC = "MangaCracker backup"
    const val FOLDER = "MangaCracker"
    private const val KEEP_AUTO = 4
    private const val WEEK = 7 * 24 * 3600_000L

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }
    private val libSer = MapSerializer(String.serializer(), LibraryEntry.serializer())
    private val histSer = MapSerializer(String.serializer(), HistoryEntry.serializer())
    private val progSer = MapSerializer(String.serializer(), ReadState.serializer())
    private val watchSer = MapSerializer(String.serializer(), WatchState.serializer())
    private val favSer = MapSerializer(String.serializer(), FavVideo.serializer())

    /** PC tests: files go here instead of MediaStore's Download folder. */
    var folderOverride: File? = null

    /** What a backup file holds (the restore panel shows the counts). */
    data class Contents(
        val format: Int,
        val created: Long,
        val appVersion: String,
        val library: Map<String, LibraryEntry> = emptyMap(),
        val history: Map<String, HistoryEntry> = emptyMap(),
        val progress: Map<String, ReadState> = emptyMap(),
        val settings: Settings? = null,
        val watch: Map<String, WatchState> = emptyMap(),
        val favs: Map<String, FavVideo> = emptyMap(),
        /** Entries that could not be read and were left out. */
        val skipped: Int = 0,
    ) {
        val chaptersRead: Int get() = progress.values.count { it.read }
        /** Written by a newer app: whatever this one understands is read, the rest left out. */
        val newer: Boolean get() = format > FORMAT
        val isEmpty: Boolean get() = library.isEmpty() && history.isEmpty() && progress.isEmpty() && settings == null && watch.isEmpty() && favs.isEmpty()
    }

    enum class Mode { MERGE, REPLACE }

    /** Settings that belong to this phone only: logins / keys and app-update bookkeeping. */
    private fun withDeviceOnly(s: Settings, device: Settings) = s.copy(
        gelbooruUser = device.gelbooruUser, gelbooruKey = device.gelbooruKey,
        danbooruUser = device.danbooruUser, danbooruKey = device.danbooruKey,
        useExHentai = device.useExHentai,
        appRelease = device.appRelease, lastAppCheck = device.lastAppCheck,
        dismissedAppVersion = device.dismissedAppVersion, notifiedAppVersion = device.notifiedAppVersion,
        lastLibraryCheck = device.lastLibraryCheck, lastAutoBackup = device.lastAutoBackup,
        readerSoundUri = device.readerSoundUri, readerSoundName = device.readerSoundName,
    )

    private fun shareable(s: Settings) = withDeviceOnly(s, Settings())

    // ---------- write ----------

    fun export(appVersion: String = AppUpdate.current, now: Long = System.currentTimeMillis()): String = buildJsonObject {
        put("app", MAGIC)
        put("format", FORMAT)
        put("created", now)
        put("appVersion", appVersion)
        put("library", json.encodeToJsonElement(libSer, Repo.library.value))
        put("history", json.encodeToJsonElement(histSer, Repo.history.value))
        put("progress", json.encodeToJsonElement(progSer, Repo.progress.value))
        put("settings", json.encodeToJsonElement(Settings.serializer(), shareable(Repo.settings.value)))
        put("videoWatch", json.encodeToJsonElement(watchSer, VideoRepo.watch.value))
        put("videoFavs", json.encodeToJsonElement(favSer, VideoRepo.favs.value))
    }.toString()

    fun fileName(auto: Boolean = false, day: LocalDate = LocalDate.now()) = "MangaCracker-${if (auto) "auto" else "backup"}-$day.json"

    /** Writes [text] to Download/MangaCracker/[name]; returns where it went (for the note). */
    suspend fun saveToDownloads(ctx: Context, text: String, name: String): String = withContext(Dispatchers.IO) {
        folderOverride?.let { dir ->
            dir.mkdirs(); File(dir, name).writeText(text)
            return@withContext "Download/$FOLDER/$name"
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/$FOLDER")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw BackupException("Couldn't create the file in Downloads")
        try {
            cr.openOutputStream(uri)!!.use { it.write(text.toByteArray()) }
        } catch (e: Exception) {
            runCatching { cr.delete(uri, null, null) }
            throw e
        }
        values.clear(); values.put(MediaStore.Downloads.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        "Download/$FOLDER/$name"
    }

    /** A place the user picked in the system file picker. */
    suspend fun writeTo(ctx: Context, uri: Uri, text: String) = withContext(Dispatchers.IO) {
        (ctx.contentResolver.openOutputStream(uri, "wt") ?: throw BackupException("Couldn't open that file")).use { it.write(text.toByteArray()) }
    }

    suspend fun readFrom(ctx: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        val input = ctx.contentResolver.openInputStream(uri) ?: throw BackupException("Couldn't open that file")
        input.use {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > 64 * 1024 * 1024) throw BackupException("That file is far too big for a MangaCracker backup")
            }
            String(out.toByteArray())
        }
    }

    // ---------- read ----------

    fun read(text: String): Contents {
        val root = runCatching { json.parseToJsonElement(text.trimStart('﻿')) }.getOrNull() as? JsonObject
            ?: throw BackupException("This file isn't a MangaCracker backup (it isn't readable JSON).")
        if (root["app"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } != MAGIC)
            throw BackupException("This file isn't a MangaCracker backup.")
        val format = root["format"]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() } ?: 0
        if (format < 1) throw BackupException("This backup's format isn't known.")
        var skipped = 0
        fun <T> section(name: String, ser: KSerializer<T>, key: (T) -> String): Map<String, T> {
            val o = root[name] as? JsonObject ?: return emptyMap()
            val out = LinkedHashMap<String, T>()
            for ((k, v) in o) {
                runCatching { json.decodeFromJsonElement(ser, v) }
                    .onSuccess { out[key(it).ifEmpty { k }] = it }
                    .onFailure { skipped++ }
            }
            return out
        }
        val library = section("library", LibraryEntry.serializer()) { it.ref.key }
        val history = section("history", HistoryEntry.serializer()) { it.ref.key }
        // progress keys are "<title key>#<chapter id>": keep them as they are
        val progress = section("progress", ReadState.serializer()) { "" }
        val watch = section("videoWatch", WatchState.serializer()) { it.ref.key }
        val favs = section("videoFavs", FavVideo.serializer()) { it.ref.key }
        val settings = (root["settings"] as? JsonObject)?.let { o ->
            runCatching { json.decodeFromJsonElement(Settings.serializer(), o) }.onFailure { skipped++ }.getOrNull()
        }
        return Contents(
            format = format,
            created = root["created"]?.let { runCatching { it.jsonPrimitive.longOrNull }.getOrNull() } ?: 0L,
            appVersion = root["appVersion"]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }.orEmpty(),
            library = library, history = history, progress = progress, settings = settings,
            watch = watch, favs = favs, skipped = skipped,
        )
    }

    // ---------- restore ----------

    /**
     * MERGE: everything in the backup is added; where both have the same title / chapter / video the newer one wins,
     * hidden tags are joined, other settings come from the backup. REPLACE: the backup's data instead of this phone's.
     * Logins, keys and update bookkeeping always stay as they are on this phone.
     */
    fun apply(c: Contents, mode: Mode) {
        val dev = Repo.settings.value
        val merge = mode == Mode.MERGE
        fun <T> newer(a: Map<String, T>, b: Map<String, T>, time: (T) -> Long): Map<String, T> {
            val out = LinkedHashMap(a)
            for ((k, v) in b) { val old = out[k]; if (old == null || time(v) > time(old)) out[k] = v }
            return out
        }
        val libTime = { e: LibraryEntry -> maxOf(e.added, e.lastChecked, e.lastUpdate) }
        val library = if (merge) newer(Repo.library.value, c.library, libTime) else c.library
        val history = if (merge) newer(Repo.history.value, c.history) { it.time } else c.history
        val progress = if (merge) newer(Repo.progress.value, c.progress) { it.time } else c.progress
        val settings = c.settings?.let { s ->
            withDeviceOnly(if (merge) s.copy(hiddenTags = (dev.hiddenTags + s.hiddenTags).distinct()) else s, dev)
        } ?: dev
        Repo.restore(library, history, progress, settings)
        val watch = if (merge) newer(VideoRepo.watch.value, c.watch) { it.time } else c.watch
        val favs = if (merge) VideoRepo.favs.value + c.favs.filterKeys { it !in VideoRepo.favs.value } else c.favs
        VideoRepo.restore(watch, favs)
    }

    // ---------- weekly backup ----------

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Called when the app starts and by the background check: a backup when the last one is a week old. */
    fun autoIfDue(ctx: Context) {
        val s = Repo.settings.value
        if (!s.autoBackup || System.currentTimeMillis() - s.lastAutoBackup < WEEK) return
        scope.launch { runCatching { autoNow(ctx) } }
    }

    suspend fun autoNow(ctx: Context): String {
        val where = saveToDownloads(ctx, export(), fileName(auto = true))
        Repo.updateSettings { it.copy(lastAutoBackup = System.currentTimeMillis()) }
        runCatching { pruneAuto(ctx) }
        return where
    }

    /** Keeps the newest few weekly files (only files this app made can be seen and removed). */
    private fun pruneAuto(ctx: Context) {
        folderOverride?.let { dir ->
            dir.listFiles { f -> f.name.startsWith("MangaCracker-auto-") }?.sortedByDescending { it.name }?.drop(KEEP_AUTO)?.forEach { it.delete() }
            return
        }
        val cr = ctx.contentResolver
        val ids = mutableListOf<Long>()
        cr.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf("MangaCracker-auto-%", "%/$FOLDER/%"), "${MediaStore.Downloads.DATE_ADDED} DESC",
        )?.use { c -> while (c.moveToNext()) ids += c.getLong(0) }
        ids.drop(KEEP_AUTO).forEach { id ->
            runCatching { cr.delete(android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id), null, null) }
        }
    }
}
