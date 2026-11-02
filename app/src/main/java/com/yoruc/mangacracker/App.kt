package com.yoruc.mangacracker

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.yoruc.mangacracker.core.AppUpdate
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.core.UpdateWorker

class App : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Http.init(this)
        Repo.init(this)
        AppUpdate.init(this)
        com.yoruc.mangacracker.sources.ComixBridge.init(this)
        getSystemService(NotificationManager::class.java).createNotificationChannels(listOf(
            NotificationChannel(UpdateWorker.CHANNEL, "New chapters", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(UpdateWorker.APP_CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT),
        ))
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
        // animated GIF / WebP / PNG: Android's own decoder (smooth, hardware), the plain GIF one only on the PC harness
        .components { add(if (android.os.Build.FINGERPRINT == "robolectric") GifDecoder.Factory() else ImageDecoderDecoder.Factory()) }
        .build()
}
