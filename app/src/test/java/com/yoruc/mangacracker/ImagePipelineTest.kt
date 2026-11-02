package com.yoruc.mangacracker

import androidx.test.core.app.ApplicationProvider
import coil.imageLoader
import coil.request.ErrorResult
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Does a cover go through Coil + our OkHttp client and decode? */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class ImagePipelineTest {
    @Test fun covers() {
        val ctx = ApplicationProvider.getApplicationContext<App>()
        // the test thread is Android's main thread: run Coil elsewhere and keep the main looper turning
        val t = Thread { runBlocking { load(ctx) } }
        t.start()
        val until = System.currentTimeMillis() + 60_000
        while (t.isAlive && System.currentTimeMillis() < until) { Thread.sleep(50); org.robolectric.shadows.ShadowLooper.idleMainLooper() }
        println("[img] done alive=${t.isAlive}")
    }

    private suspend fun load(ctx: App) {
        // every cover of each video source's first page, decoded like the grid does (all at once)
        for (s in com.yoruc.mangacracker.video.VideoSources.all) {
            val covers = runCatching { s.latest(1).items.map { it.cover } }.getOrDefault(emptyList())
            val t0 = System.currentTimeMillis()
            val res = kotlinx.coroutines.coroutineScope {
                covers.map { u -> async { ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(u).size(540, 304).build()) } }.map { it.await() }
            }
            val bad = res.filterIsInstance<ErrorResult>()
            println("[img] ${s.id}: ${res.size - bad.size}/${covers.size} covers decoded in ${System.currentTimeMillis() - t0} ms" +
                (bad.firstOrNull()?.let { " first error: ${it.throwable.javaClass.simpleName} ${it.throwable.message?.take(60)}" } ?: ""))
        }
        val videos = emptyList<String>()
        for (u in listOf(
            "https://cdn.atsu.moe/static/posters/3i6P1lYIogbwab92-large.webp",
            "https://cdn.atsu.moe/static/posters/3i6P1lYIogbwab92.jpg",
        ) + videos) {
            val r = ctx.imageLoader.execute(ImageRequest.Builder(ctx).data(u).size(300, 450).build())
            when (r) {
                is SuccessResult -> println("[img] OK ${r.drawable.intrinsicWidth}x${r.drawable.intrinsicHeight} $u")
                is ErrorResult -> println("[img] ERROR ${r.throwable} $u")
            }
        }
    }
}
