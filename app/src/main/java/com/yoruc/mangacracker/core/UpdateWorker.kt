package com.yoruc.mangacracker.core

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.yoruc.mangacracker.MainActivity
import com.yoruc.mangacracker.R
import com.yoruc.mangacracker.sources.Sources
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** Looks for new chapters of every library title (chaptered sources only). */
class UpdateWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val fresh = checkLibrary()
        if (fresh.isNotEmpty() && Repo.settings.value.notifyUpdates) notify(applicationContext, fresh)
        return Result.success()
    }

    companion object {
        const val CHANNEL = "updates"
        private const val NAME = "library-updates"

        fun schedule(ctx: Context) {
            val hours = Repo.settings.value.updateHours
            val wm = WorkManager.getInstance(ctx)
            if (hours <= 0) { wm.cancelUniqueWork(NAME); return }
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(hours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.UPDATE, req)
        }

        /** Check every chaptered library title, 3 at a time. Returns the new chapters found. */
        suspend fun checkLibrary(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<UpdateItem> = withContext(Dispatchers.IO) {
            val entries = Repo.library.value.values.filter { Sources.byId(it.ref.source)?.hasChapters == true }
            val gate = Semaphore(3)
            val done = java.util.concurrent.atomic.AtomicInteger()
            val found = coroutineScope {
                entries.map { e ->
                    async {
                        gate.withPermit {
                            val src = Sources.byId(e.ref.source)!!
                            val chapters = runCatching { src.latestChapters(e.ref) }.getOrNull()
                            onProgress(done.incrementAndGet(), entries.size)
                            if (chapters.isNullOrEmpty()) emptyList()
                            else {
                                Repo.applyChapterCheck(e.ref.key, chapters).map { UpdateItem(e.ref, it, System.currentTimeMillis()) }
                            }
                        }
                    }
                }.awaitAll().flatten()
            }
            Repo.updateSettings { it.copy(lastLibraryCheck = System.currentTimeMillis()) }
            found
        }

        fun notify(ctx: Context, items: List<UpdateItem>) {
            if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
            val titles = items.groupBy { it.ref.title }
            val text = titles.entries.take(5).joinToString("\n") { (t, l) -> if (l.size == 1) "$t · ${l[0].chapter.name}" else "$t · ${l.size} new" }
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java).putExtra("open", "updates"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("${items.size} new chapter${if (items.size == 1) "" else "s"}")
                .setContentText(text.lineSequence().first())
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setColor(0xFF3DB4F2.toInt())
                .build()
            NotificationManagerCompat.from(ctx).notify(7001, n)
        }
    }
}
