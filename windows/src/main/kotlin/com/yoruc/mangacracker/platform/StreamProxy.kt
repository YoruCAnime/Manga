package com.yoruc.mangacracker.platform

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.video.VideoDownloads
import com.yoruc.mangacracker.video.VideoSources
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * A relay on 127.0.0.1 between mpv and the video sites: every request goes through the source's own player client
 * (its cookies, referer, key unlock and re-unlock on 403, exactly as on the phone). HLS playlists are rewritten so
 * every piece, key and sub-playlist also comes through here; [maxHeight] drops variants taller than the chosen quality.
 */
object StreamProxy {
    private const val TS_SYNC: Byte = 0x47
    /** What sites name their video pieces to look like something else (Anikoto: .jpg, .html, .js ...). */
    private val FALSE_NAMES = setOf("jpg", "jpeg", "png", "webp", "gif", "ico", "svg", "html", "htm", "js", "css", "txt", "woff", "woff2")
    /** ffmpeg on a plain video file: pick up again after a dropped connection or a busy answer, and never wait for ever. */
    private val RECOVER = listOf("-reconnect", "1", "-reconnect_on_network_error", "1", "-reconnect_on_http_error", "429,5xx",
        "-reconnect_delay_max", "4", "-rw_timeout", "30000000")
    /** How often one answer may be picked up again in a row. */
    private const val RESUME_TRIES = 5
    private var server: HttpServer? = null
    private var port = 0
    @Volatile var maxHeight = 0
    /** the last request that failed (for tests and the player's error text) */
    @Volatile var lastError: String? = null

    @Synchronized
    private fun start() {
        if (server != null) return
        val s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 32)
        // a thread per open request: a player may leave one request unread while it opens the next
        s.executor = Executors.newCachedThreadPool { r -> Thread(r, "mc-relay").apply { isDaemon = true } }
        s.createContext("/p") { ex -> runCatching { serve(ex) }.onFailure { e -> lastError = e.toString(); runCatching { ex.sendResponseHeaders(502, -1); ex.close() } } }
        s.start()
        server = s; port = s.address.port
    }

    /** The relay URL mpv opens for [url] of video source [sourceId]. */
    fun url(sourceId: String, url: String): String {
        start()
        return "http://127.0.0.1:$port/p?s=${enc(sourceId)}&u=${enc(url)}"
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)

    /**
     * ffmpeg input options for [url] of [sourceId] (Record / GIF): HLS through the relay (keys, re-unlock); a plain
     * MP4 / WebM straight from the site with its cookies, referer and User-Agent, because ffmpeg can't jump inside a
     * file through the relay (it read the whole video to reach the cut).
     */
    fun ffmpegInput(sourceId: String, url: String, kind: String): List<String> {
        if (kind == "hls" || url.substringBefore('?').endsWith(".m3u8")) {
            return listOf("-extension_picky", "0", "-allowed_extensions", "ALL", "-i", url(sourceId, url))
        }
        val u = okhttp3.HttpUrl.Companion.run { url.toHttpUrlOrNull() } ?: return listOf("-i", url)
        val host = u.host
        val ua = Http.userAgents.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value ?: Http.userAgent
        val referer = Http.referers.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value
        val cookies = com.yoruc.mangacracker.core.WebCookieJar.loadForRequest(u).joinToString("; ") { "${it.name}=${it.value}" }
        val headers = buildString {
            referer?.let { append("Referer: $it\r\n") }
            if (cookies.isNotEmpty()) append("Cookie: $cookies\r\n")
        }
        return listOf("-user_agent", ua) + (if (headers.isNotEmpty()) listOf("-headers", headers) else emptyList()) + RECOVER + listOf("-i", url)
    }

    private fun serve(ex: HttpExchange) {
        val q = ex.requestURI.rawQuery.orEmpty().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('=', ""), Charsets.UTF_8) }
        val sourceId = q["s"].orEmpty(); val url = q["u"].orEmpty()
        val src = VideoSources.byId(sourceId)
        // the source's own client (cookies, keys, re-unlock) as it is: forcing HTTP/1.1 made Cloudflare challenge
        // MuchoHentai's key requests. (Record / GIF of plain MP4s go to ffmpeg directly, see [ffmpegInput].)
        val client = src?.playerClient ?: Http.imageClient
        val headers = buildMap { ex.requestHeaders.getFirst("Range")?.let { put("Range", it) } }
        client.newCall(Http.request(url, headers)).execute().use { r ->
            val type = r.header("Content-Type").orEmpty()
            val body = r.body ?: run { ex.sendResponseHeaders(r.code, -1); ex.close(); return }
            val isPlaylist = "mpegurl" in type.lowercase() || url.substringBefore('?').endsWith(".m3u8")
            if (isPlaylist && r.isSuccessful) {
                val text = rewrite(body.string(), url, sourceId)
                val bytes = text.toByteArray()
                ex.responseHeaders.add("Content-Type", "application/vnd.apple.mpegurl")
                ex.sendResponseHeaders(200, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
                return
            }
            // MPEG-TS pieces under a false name and type (Anikoto: .jpg as image/jpeg, .html, .js): ffmpeg believes the
            // type, reads them as that and finds no video, so say what they are (a TS sync byte every 188 bytes)
            val src2 = body.source()
            val isTs = r.isSuccessful && src2.request(189) && src2.buffer[0] == TS_SYNC && src2.buffer[188] == TS_SYNC
            if (isTs) ex.responseHeaders.add("Content-Type", "video/mp2t")
            listOf("Content-Type", "Content-Range").filter { !isTs || it != "Content-Type" }.forEach { h -> r.header(h)?.let { ex.responseHeaders.add(h, it) } }
            // say seeking works (the relay passes Range on): without it ffmpeg reads a whole file from the start to
            // reach a point in it (a 5 s clip at 20 s fetched all 159 MB of the video)
            ex.responseHeaders.add("Accept-Ranges", r.header("Accept-Ranges") ?: "bytes")
            val len = body.contentLength()
            ex.sendResponseHeaders(r.code, if (len >= 0) len else 0)
            // a video file whose connection drops half way (or stalls) is asked again from the byte it reached, so the
            // player just waits a moment instead of being left with a cut-off file
            val canResume = r.isSuccessful && len > 0 && (r.code == 206 || r.header("Accept-Ranges").equals("bytes", true))
            val start = r.header("Content-Range")?.let { Regex("bytes (\\d+)-").find(it)?.groupValues?.get(1)?.toLongOrNull() } ?: 0L
            ex.responseBody.use { out ->
                copyResuming(body.byteStream(), out, len, canResume) { sent ->
                    client.newCall(Http.request(url, mapOf("Range" to "bytes=${start + sent}-${start + len - 1}"))).execute()
                }
            }
        }
    }

    /**
     * [first] into [out] ([len] bytes, -1 = unknown). A site that stops early or fails is asked again ([reopen] gets the
     * bytes sent so far) up to [RESUME_TRIES] times in a row; only a real part (206) is used. A player that went away
     * (a write that fails) ends it at once.
     */
    internal fun copyResuming(first: InputStream, out: OutputStream, len: Long, canResume: Boolean, reopen: (Long) -> okhttp3.Response) {
        val buf = ByteArray(64 * 1024)
        var input = first
        var open: okhttp3.Response? = null
        var sent = 0L
        var tries = 0
        try {
            while (len < 0 || sent < len) {
                val n = try { input.read(buf) } catch (e: IOException) { if (canResume) -1 else throw e }
                if (n > 0) { out.write(buf, 0, n); sent += n; tries = 0; continue }
                if (n < 0 && len < 0) return
                if (!canResume || ++tries > RESUME_TRIES) throw IOException("the site stopped sending at byte $sent of $len")
                Thread.sleep(700L * tries)
                open?.close()
                open = runCatching { reopen(sent) }.getOrNull()
                input = open?.takeIf { it.code == 206 }?.body?.byteStream() ?: InputStream.nullInputStream()
            }
        } finally { open?.close(); runCatching { first.close() } }
    }

    /** Every URI in the playlist -> through the relay (absolute); variants over [maxHeight] left out. */
    private fun rewrite(text: String, base: String, sourceId: String): String {
        val lines = text.lines()
        val out = StringBuilder()
        val vs = VideoDownloads.variants(text, base)
        val keep = if (maxHeight > 0 && vs.isNotEmpty()) VideoDownloads.pickVariant(vs, maxHeight)?.url else null
        var i = 0
        while (i < lines.size) {
            val l = lines[i]
            if (l.startsWith("#EXT-X-STREAM-INF") && keep != null) {
                val uri = lines.drop(i + 1).firstOrNull { it.isNotBlank() && !it.startsWith("#") }?.trim()
                if (uri != null && VideoDownloads.resolve(base, uri) != keep) {
                    // skip this variant and its URI line
                    i++
                    while (i < lines.size && (lines[i].isBlank() || lines[i].startsWith("#"))) i++
                    i++
                    continue
                }
            }
            out.append(
                when {
                    l.startsWith("#") && "URI=\"" in l -> Regex("URI=\"([^\"]+)\"").replace(l) { m -> "URI=\"" + url(sourceId, VideoDownloads.resolve(base, m.groupValues[1])) + "\"" }
                    l.isNotBlank() && !l.startsWith("#") -> VideoDownloads.resolve(base, l.trim()).let { u ->
                        // ffmpeg goes by the end of the URL too (.jpg = the image reader): such a piece's ends in .ts
                        url(sourceId, u) + if (u.substringBefore('?').substringAfterLast('.').lowercase() in FALSE_NAMES) "&x=.ts" else ""
                    }
                    else -> l
                }
            ).append('\n')
            i++
        }
        return out.toString()
    }
}
