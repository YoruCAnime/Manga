package com.yoruc.mangacracker.core

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.yoruc.mangacracker.MainActivity

import com.yoruc.mangacracker.sources.Sources
import com.yoruc.mangacracker.video.VideoDownloads
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** Works the download queue while it has something to do (see [Downloads]); a progress notification meanwhile. */
class DownloadWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        runCatching { setForeground(info(ctx, Downloads.queue.tasks.value)) }
        DownloadRunner.run(ctx) { tasks -> runCatching { setForeground(info(ctx, tasks)) } }
        DownloadRunner.notifyDone(ctx)
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = info(applicationContext, Downloads.queue.tasks.value)

    private fun info(ctx: Context, tasks: List<DlTask>) =
        ForegroundInfo(DownloadRunner.NOTE_ID, DownloadRunner.notification(ctx, tasks), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
}

object DownloadRunner {
    const val CHANNEL = "downloads"
    const val NOTE_ID = 7003
    /** At most this many downloads at once, never two from the same site. */
    const val PARALLEL = 2
    /** Pages of one chapter fetched at once. */
    private const val PAGE_PARALLEL = 2

    /**
     * Runs until nothing is waiting (paused / failed tasks stay). Pause / cancel cancel a task's job ([Downloads.jobs]);
     * the worker being stopped puts running tasks back in the queue. [onTick] about once a second (notification).
     */
    suspend fun run(ctx: Context?, onTick: suspend (List<DlTask>) -> Unit = {}) = coroutineScope {
        val q = Downloads.queue
        val running = HashMap<String, Pair<DlTask, Job>>()
        var ticks = 0
        while (true) {
            running.entries.removeAll { !it.value.second.isActive }
            if (Downloads.freeBytes() < Downloads.MIN_FREE_RUN) {
                q.tasks.value.filter { it.state == DlState.QUEUED || it.state == DlState.RUNNING }.forEach { t ->
                    q.pause(t.id); q.progress(t.id, t.done, t.total, t.bytes)
                }
                running.values.forEach { it.second.cancel() }
                lowSpace = true
                break
            }
            lowSpace = false
            while (running.size < PARALLEL) {
                val t = q.take(running.values.map { it.first.site }.toSet()) ?: break
                val job = launch(Dispatchers.IO) { runOne(ctx, t) }
                Downloads.jobs[t.id] = job
                running[t.id] = t to job
            }
            if (running.isEmpty()) {
                val wake = q.nextRetry() ?: break
                delay((wake - System.currentTimeMillis()).coerceIn(300L, 60_000L))
                continue
            }
            if (ticks++ % 3 == 0) onTick(q.tasks.value)
            delay(350)
        }
    }

    /** The last run stopped because the phone is nearly full (the Downloads screen says so). */
    @Volatile var lowSpace = false

    private suspend fun runOne(ctx: Context?, t: DlTask) {
        val q = Downloads.queue
        try {
            if (t.isVideo) VideoDownloads.download(ctx, t) { done, total, bytes -> q.progress(t.id, done, total, bytes) }
            else chapter(t) { done, total, bytes -> q.progress(t.id, done, total, bytes) }
            q.finished(t.id)
        } catch (e: CancellationException) {
            q.interrupted(t.id)
            if (q[t.id] == null) Downloads.removePartial(t)
            throw e
        } catch (e: PermanentDownloadError) {
            q.failed(t.id, e.message ?: "Can't download this", permanent = true)
        } catch (e: Throwable) {
            q.failed(t.id, friendly(e))
        } finally {
            Downloads.jobs.remove(t.id)
        }
    }

    fun friendly(e: Throwable): String = when (e) {
        is CloudflareException -> "The site wants a browser check: open it once from Browse"
        is java.net.UnknownHostException -> "No connection"
        is java.net.SocketTimeoutException -> "The site timed out"
        else -> e.message?.take(140) ?: e.javaClass.simpleName
    }

    // ---------- one chapter ----------

    /** Picture types the reader can show (anything else is an error page, not a picture). */
    fun imageExt(b: ByteArray): String? = when {
        b.size > 3 && b[0] == 0xFF.toByte() && b[1] == 0xD8.toByte() -> "jpg"
        b.size > 8 && b[0] == 0x89.toByte() && b[1] == 'P'.code.toByte() && b[2] == 'N'.code.toByte() -> "png"
        b.size > 6 && b[0] == 'G'.code.toByte() && b[1] == 'I'.code.toByte() && b[2] == 'F'.code.toByte() -> "gif"
        b.size > 12 && String(b, 0, 4, Charsets.ISO_8859_1) == "RIFF" && String(b, 8, 4, Charsets.ISO_8859_1) == "WEBP" -> "webp"
        b.size > 12 && String(b, 4, 4, Charsets.ISO_8859_1) == "ftyp" && String(b, 8, 4, Charsets.ISO_8859_1).let { it == "avif" || it == "avis" || it == "mif1" } -> "avif"
        b.size > 2 && b[0] == 0xFF.toByte() && b[1] == 0x0A.toByte() -> "jxl"
        else -> null
    }

    /**
     * The same request the reader's Coil makes: Http.imageClient (cookies, per-host User-Agent and Referer, rate
     * limits; comix gets no Referer, Gelbooru / hitomi theirs). Two tries more on a failure.
     */
    private suspend fun fetchImage(url: String, page: Int): ByteArray {
        var last: Throwable? = null
        repeat(3) { n ->
            if (n > 0) delay(1500L * n)
            try {
                return withContext(Dispatchers.IO) {
                    val call = Http.imageClient.newCall(Http.request(url))
                    val job = currentCoroutineContext()[Job]
                    val h = job?.invokeOnCompletion { if (it != null) call.cancel() }
                    try {
                        call.execute().use { r ->
                            if (!r.isSuccessful) throw IOException("Page ${page + 1}: HTTP ${r.code}")
                            r.body!!.bytes()
                        }
                    } finally { h?.dispose() }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                currentCoroutineContext().ensureActive()
            }
        }
        throw last ?: IOException("Page ${page + 1} didn't load")
    }

    /** Downloads one chapter's pages (resolving addresses now: hitomi's change over time) into its folder. */
    suspend fun chapter(t: DlTask, progress: (Int, Int, Long) -> Unit) {
        val ref = t.manga ?: throw PermanentDownloadError("Nothing to download")
        val ch = t.chapter ?: throw PermanentDownloadError("Nothing to download")
        val src = Sources.byId(ref.source) ?: throw PermanentDownloadError("This site is no longer in the app")
        val details = Repo.detailsCache[ref.key] ?: Downloads.savedDetails(ref.key)
        if (Safety.blocked(ref) || (details != null && Safety.blocked(details))) throw PermanentDownloadError("This title is hidden")
        val pages = withContext(Dispatchers.IO) { src.pages(ref, ch) }
        if (pages.isEmpty()) throw SourceException("This chapter has no pages")
        val dir = Downloads.chapterDir(ref, ch.id).apply { mkdirs() }
        // a cover for the Downloads screen and the title page offline (once)
        if (Downloads.titles.value[ref.key]?.cover == null) saveCover(ref, details)

        val names = arrayOfNulls<String>(pages.size)
        // pages already here from an earlier try stay
        dir.listFiles()?.forEach { f ->
            val i = f.name.substringBefore('.').toIntOrNull()
            if (i != null && i in 1..pages.size && !f.name.endsWith(".part") && f.length() > 0) names[i - 1] = f.name
        }
        val done = AtomicInteger(names.count { it != null })
        val bytes = AtomicLong(names.filterNotNull().sumOf { File(dir, it).length() })
        progress(done.get(), pages.size, bytes.get())
        val gate = Semaphore(PAGE_PARALLEL)
        coroutineScope {
            pages.mapIndexed { i, p ->
                async {
                    if (names[i] != null) return@async
                    gate.withPermit {
                        val url = p.url.ifEmpty { withContext(Dispatchers.IO) { src.imageUrl(p) } }
                        val data = fetchImage(url, i)
                        val ext = imageExt(data) ?: throw IOException("Page ${i + 1} isn't a picture (the site sent something else)")
                        if (Downloads.freeBytes() < Downloads.MIN_FREE_RUN) throw PermanentDownloadError("The phone is almost full")
                        val name = "%04d.%s".format(i + 1, ext)
                        val part = File(dir, "$name.part")
                        withContext(Dispatchers.IO) {
                            part.writeBytes(data)
                            if (!part.renameTo(File(dir, name))) throw IOException("Couldn't write page ${i + 1}")
                        }
                        names[i] = name
                        progress(done.incrementAndGet(), pages.size, bytes.addAndGet(data.size.toLong()))
                    }
                }
            }.awaitAll()
        }
        val files = names.map { it ?: throw IOException("A page is missing") }
        Downloads.chapterFinished(ref, ch, files, files.sumOf { File(dir, it).length() })
    }

    private suspend fun saveCover(ref: MangaRef, details: MangaDetails?) {
        val url = details?.ref?.cover?.ifEmpty { null } ?: Repo.library.value[ref.key]?.ref?.cover?.ifEmpty { null } ?: ref.cover.ifEmpty { null } ?: return
        runCatching {
            val b = Http.bytes(url)
            val ext = imageExt(b) ?: return
            val dir = Downloads.mangaDir(ref).apply { mkdirs() }
            File(dir, "cover.$ext").writeBytes(b)
            Downloads.saveTitleInfo(ref, cover = "cover.$ext")
        }
    }

    // ---------- notification ----------

    fun notification(ctx: Context, tasks: List<DlTask>): Notification {
        val active = tasks.filter { it.state == DlState.RUNNING || it.state == DlState.QUEUED }
        val cur = tasks.firstOrNull { it.state == DlState.RUNNING }
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(if (active.isEmpty()) "Downloads" else "Downloading · ${active.size} left")
            .setContentText(cur?.let { c -> c.title + (if (c.total > 0 && !c.isVideo) "  ${c.done}/${c.total}" else if (c.total > 0) "  ${c.done / 10}%" else "") } ?: "Starting…")
            .setProgress(cur?.total ?: 0, cur?.done ?: 0, cur == null || cur.total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .setColor(0xFF3DB4F2.toInt())
            .build()
    }

    /** The queue ran out: a quiet "done" note (or why it stopped). */
    fun notifyDone(ctx: Context) {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val failed = Downloads.queue.tasks.value.count { it.state == DlState.FAILED }
        val text = when {
            lowSpace -> "Paused: the phone is almost full"
            failed > 0 -> "$failed download${if (failed == 1) "" else "s"} failed · tap to see"
            else -> "All downloads finished"
        }
        val open = PendingIntent.getActivity(
            ctx, 3, Intent(ctx, MainActivity::class.java).putExtra("open", "downloads"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Downloads")
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(true)
            .setColor(0xFF3DB4F2.toInt())
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTE_ID + 1, n) }
    }
}
