package com.yoruc.mangacracker.core

import android.content.Context
import com.yoruc.mangacracker.video.VideoSafety
import com.yoruc.mangacracker.video.VideoSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class CheckStatus { WAITING, RUNNING, OK, SLOW, VPN, BROWSER, BROKEN, SKIPPED }

/** One source's result in "Test all sources". */
data class CheckRow(
    val id: String,
    val name: String,
    val video: Boolean,
    val status: CheckStatus = CheckStatus.WAITING,
    val note: String = "",
    val ms: Long = 0L,
)

/**
 * A quick health check of one source, the way the app uses it: the latest page loads and has titles, the first cover
 * answers, one title page opens (for videos: list, cover, details). Age checks and UK blocks are "needs VPN": the
 * sources stop at them (they never read past an age check or a 451 body), and so does this.
 */
object SourceCheck {
    const val SLOW_MS = 8_000L
    const val TIMEOUT_MS = 45_000L

    /** Sources that can't run where this runs, with the reason (PC tests: comix needs a real WebView). */
    var unavailable: Map<String, String> = emptyMap()

    private class Step(val what: String, cause: Throwable) : Exception(cause.message, cause)

    private suspend fun <T> step(what: String, block: suspend () -> T): T =
        try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { throw Step(what, e) }

    /** A cover counts when its bytes decode as a picture (Skia, like the app's image loader). */
    @Suppress("UNUSED_PARAMETER")
    private suspend fun cover(ctx: Context, urls: List<String>): Boolean {
        for (u in urls.filter { it.isNotBlank() }.take(3)) {
            val ok = runCatching { org.jetbrains.skia.Image.makeFromEncoded(Http.bytes(u)).use { it.width > 0 } }.getOrDefault(false)
            if (ok) return true
        }
        return false
    }

    suspend fun manga(ctx: Context, s: Source): CheckRow = check(s.id, s.name, false) {
        val list = step("Latest page") { s.latest(1) }.items
        if (list.isEmpty()) throw Step("Latest page", SourceException("no titles in it"))
        val shown = Safety.filter(list)
        val first = shown.firstOrNull() ?: list.first()
        step("Cover") { if (!cover(ctx, shown.take(3).map { it.cover })) throw SourceException("the first covers didn't load") }
        step("Title page") { s.details(first) }
        "${list.size} titles · cover · title page"
    }

    suspend fun video(ctx: Context, s: VideoSource): CheckRow = check(s.id, s.name, true) {
        val list = step("Latest videos") { s.latest(1) }.items
        if (list.isEmpty()) throw Step("Latest videos", SourceException("no videos in it"))
        val shown = list.filterNot { VideoSafety.blocked(it) }
        val first = shown.firstOrNull() ?: throw Step("Latest videos", SourceException("every video was hidden"))
        step("Cover") { if (!cover(ctx, listOf(first.cover) + first.altCovers)) throw SourceException("the first cover didn't load") }
        step("Video page") { s.details(first) }
        "${list.size} videos · cover · video page"
    }

    private suspend fun check(id: String, name: String, video: Boolean, body: suspend () -> String): CheckRow {
        unavailable[id]?.let { return CheckRow(id, name, video, CheckStatus.SKIPPED, it) }
        val t0 = System.currentTimeMillis()
        return try {
            val note = withTimeout(TIMEOUT_MS) { withContext(Dispatchers.IO) { body() } }
            val ms = System.currentTimeMillis() - t0
            CheckRow(id, name, video, if (ms > SLOW_MS) CheckStatus.SLOW else CheckStatus.OK, note, ms)
        } catch (e: TimeoutCancellationException) {
            CheckRow(id, name, video, CheckStatus.BROKEN, "No answer within ${TIMEOUT_MS / 1000} s", System.currentTimeMillis() - t0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            val (st, why) = classify(e)
            CheckRow(id, name, video, st, why, System.currentTimeMillis() - t0)
        }
    }

    /** Status + short reason for a failure. */
    fun classify(e: Throwable): Pair<CheckStatus, String> {
        val where = (e as? Step)?.what
        val cause = (e as? Step)?.cause ?: e
        val msg = cause.message.orEmpty()
        return when {
            cause is CloudflareException -> CheckStatus.BROWSER to "The site wants a quick browser check: open it once from Browse"
            // the sources' own friendly messages for age checks (Rule34Video) and UK blocks (E-Hentai, hitomi 451)
            msg.contains("VPN", ignoreCase = true) || Regex("\\b451\\b").containsMatchIn(msg) -> CheckStatus.VPN to msg
            cause is java.net.UnknownHostException -> CheckStatus.BROKEN to "${where ?: "Site"}: can't reach ${cause.message ?: "the site"} (offline?)"
            cause is java.net.SocketTimeoutException -> CheckStatus.BROKEN to "${where ?: "Site"}: timed out"
            else -> CheckStatus.BROKEN to listOfNotNull(where, msg.ifEmpty { cause.javaClass.simpleName }).joinToString(": ").take(160)
        }
    }
}
