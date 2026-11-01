package com.yoruc.mangacracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.UpdateWorker

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        Repo.init(this)
        com.yoruc.mangacracker.sources.ComixBridge.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(UpdateWorker.CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT)
        )
        runCatching { UpdateWorker.schedule(this) }
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Http.imageClient }
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.30).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("images")).maxSizeBytes(600L * 1024 * 1024).build() }
        .respectCacheHeaders(false)
        .crossfade(true)
        // Robolectric (the PC screenshot harness) can't draw GPU bitmaps
        .allowHardware(android.os.Build.FINGERPRINT != "robolectric")
        .components { add(GifDecoder.Factory()) }
        .build()
}
