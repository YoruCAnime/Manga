package com.yoruc.mangacracker.video

import com.yoruc.mangacracker.core.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.jsoup.Jsoup

/**
 * Helpers for the live-action sources: Dean Edwards' "packer" undone, labels that mean a minor in live-action titles,
 * and the outside video hosts the Japanese sites play from (the hosts' page layouts as the open-source Aniyomi
 * extensions read them; written here from scratch).
 */
object LiveHosts {
    /** Words in live-action titles / tags that put a minor in it (role play included): such videos are never shown. */
    private val MINOR = Regex(
        "\\b(jk|joshi\\s?kou?sei|school\\s?girls?|schoolgirls?|high\\s?school(er)?s?|middle\\s?school|junior\\s?high|" +
            "underage|under\\s?age|minors?|kogal|loli\\w*|shota\\w*|child\\w*|kids?|preteen|pre-teen|elementary)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun minor(s: String) = MINOR.containsMatchIn(s)
    fun minor(r: VideoRef) = minor(r.title) || r.tags.any { minor(it) }

    /** eval(function(p,a,c,k,e,d){...}('payload',base,count,'w|o|r|d|s'.split('|'),...)) -> the script it builds. */
    fun unpack(script: String): String {
        val m = Regex("""\}\('(.*)',\s*(\d+),\s*(\d+),\s*'(.*?)'\.split\('\|'\)""", RegexOption.DOT_MATCHES_ALL).find(script) ?: return ""
        val payload = m.groupValues[1].replace("\\'", "'")
        val base = m.groupValues[2].toInt()
        val words = m.groupValues[4].split('|')
        fun num(w: String): Int {
            var n = 0
            for (c in w) {
                val d = when (c) { in '0'..'9' -> c - '0'; in 'a'..'z' -> c - 'a' + 10; in 'A'..'Z' -> c - 'A' + 36; else -> return -1 }
                if (d >= base) return -1
                n = n * base + d
            }
            return n
        }
        return Regex("\\b\\w+\\b").replace(payload) { w ->
            val i = num(w.value)
            if (i in words.indices && words[i].isNotEmpty()) words[i] else w.value
        }
    }

    private val noRedirect by lazy { Http.client.newBuilder().followRedirects(false).build() }

    /** Where [url] points (its Location), without following it. */
    suspend fun location(url: String, referer: String): String? = withContext(Dispatchers.IO) {
        noRedirect.newCall(Request.Builder().url(url).header("Referer", referer).build()).execute().use { it.header("location") }
    }

    private fun refer(url: String, referer: String) {
        url.toHttpUrlOrNull()?.host?.let { Http.referers[it] = referer }
    }

    /** A player host's page -> its video, or null when the host is unknown / gives nothing. */
    suspend fun stream(host: String, referer: String): VideoStream? {
        val h = host.toHttpUrlOrNull()?.host ?: return null
        return runCatching {
            when {
                "streamtape" in h || "strtape" in h || "stape" in h -> streamtape(host)
                listOf("dood", "ds2play", "d000d", "d0o0d").any { it in h } -> dood(host)
                "mixdrop" in h || "mixdroop" in h || "mxdrop" in h -> mixdrop(host)
                "emturbovid" in h || "turbovid" in h || "turboplay" in h -> urlPlay(host)
                else -> packedFile(host, referer)
            }
        }.getOrNull()
    }

    /** Streamtape: the robotlink line puts the file address together from two parts. */
    private suspend fun streamtape(url: String): VideoStream? {
        val id = Regex("/[ev]/([A-Za-z0-9]+)").find(url)?.groupValues?.get(1) ?: return null
        val page = "https://streamtape.com/e/$id"
        val html = Http.get(page)
        val line = html.substringAfter("document.getElementById('robotlink').innerHTML = '", "").ifEmpty { return null }
        val file = "https:" + line.substringBefore("'") + line.substringAfter("+ ('xcd", "").substringBefore("'")
        refer(file, "https://streamtape.com/")
        return VideoStream("$file&stream=1", "mp4")
    }

    /** Dood: /pass_md5/ gives the start of the address; the rest is random letters + the token. */
    private suspend fun dood(url: String): VideoStream? = withContext(Dispatchers.IO) {
        val r = Http.client.newCall(Request.Builder().url(url).build()).execute()
        val finalUrl = r.request.url
        val body = r.use { it.body?.string().orEmpty() }
        val pass = Regex("/pass_md5/[^'\"]+").find(body)?.value ?: return@withContext null
        val origin = "${finalUrl.scheme}://${finalUrl.host}"
        val start = Http.client.newCall(Request.Builder().url(origin + pass).header("Referer", finalUrl.toString()).build()).execute().use { it.body?.string().orEmpty() }
        if (!start.startsWith("http")) return@withContext null
        val letters = (1..10).map { (('A'..'Z') + ('a'..'z') + ('0'..'9')).random() }.joinToString("")
        val file = "$start$letters?token=${pass.substringAfterLast('/')}&expiry=${System.currentTimeMillis()}"
        refer(file, "$origin/")
        VideoStream(file, "mp4")
    }

    /** MixDrop: the packed script names the file (Core.wurl). */
    private suspend fun mixdrop(url: String): VideoStream? {
        val html = Http.get(url, mapOf("Referer" to "https://mixdrop.co/"))
        val script = Jsoup.parse(html).select("script").map { it.data() }.firstOrNull { "MDCore" in it && "eval" in it } ?: return null
        val wurl = unpack(script).substringAfter("Core.wurl=\"", "").substringBefore("\"").ifEmpty { return null }
        val file = if (wurl.startsWith("//")) "https:$wurl" else wurl
        refer(file, url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: "https://mixdrop.co/")
        return VideoStream(file, "mp4")
    }

    /** Hosts whose page has "urlPlay = '...m3u8'" (TurboVid / supjav's TV player). */
    suspend fun urlPlay(url: String): VideoStream? {
        val html = Http.get(url)
        val play = Regex("urlPlay\\s*=\\s*['\"]([^'\"]+)['\"]").find(html)?.groupValues?.get(1) ?: return null
        refer(play, url)
        return VideoStream(play, if (".m3u8" in play) "hls" else "mp4")
    }

    /** StreamWish-like / MaxStream: a packed (or plain) script with the .m3u8 / file:"..." in it. */
    private suspend fun packedFile(url: String, referer: String): VideoStream? {
        val html = Http.get(url, mapOf("Referer" to referer))
        val scripts = Jsoup.parse(html).select("script").map { it.data() }
        for (s in scripts) {
            val text = if ("eval(function(p,a,c,k,e,d)" in s) unpack(s) else s
            val m3u8 = Regex("https?://[^\"'\\s]+\\.m3u8[^\"'\\s]*").find(text)?.value
                ?: Regex("file\\s*:\\s*\"(https?://[^\"]+)\"").find(text)?.groupValues?.get(1)
            if (m3u8 != null) {
                refer(m3u8, url.toHttpUrlOrNull()?.let { "${it.scheme}://${it.host}/" } ?: referer)
                return VideoStream(m3u8, if (".m3u8" in m3u8) "hls" else "mp4")
            }
        }
        return null
    }
}
