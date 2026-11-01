package com.yoruc.mangacracker.core

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Cache
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Thrown when a site shows a Cloudflare check we could not pass in the background. */
class CloudflareException(val url: String) : IOException("Cloudflare check: open the site once in the web view")

/** Cookies shared with the in-app web view, so a login or a Cloudflare pass there works for the app too. */
object WebCookieJar : CookieJar {
    /** null outside Android (the JVM test harness): then cookies live in [plain]. */
    private val cm: CookieManager? by lazy { runCatching { CookieManager.getInstance()?.apply { setAcceptCookie(true) } }.getOrNull() }
    private val plain = ConcurrentHashMap<String, MutableMap<String, String>>()

    private fun hostOf(url: String) = runCatching { java.net.URI(url).host }.getOrNull().orEmpty()

    private fun raw(url: String): String? = cm?.getCookie(url) ?: run {
        val h = hostOf(url)
        plain.entries.filter { h == it.key || h.endsWith("." + it.key) }.flatMap { it.value.entries }
            .joinToString("; ") { "${it.key}=${it.value}" }.ifEmpty { null }
    }

    private fun put(url: String, cookie: String) {
        val m = cm
        if (m != null) { m.setCookie(url, cookie); return }
        val c = Cookie.parse(url.toHttpUrl(), cookie) ?: return
        plain.getOrPut(c.domain) { ConcurrentHashMap() }[c.name] = c.value
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val u = url.toString()
        cookies.forEach { put(u, it.toString()) }
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val r = raw(url.toString()) ?: return emptyList()
        return r.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    fun get(url: String, name: String): String? =
        raw(url)?.split(";")?.map { it.trim() }?.firstOrNull { it.startsWith("$name=") }?.substringAfter("=")

    fun set(url: String, name: String, value: String, domain: String) {
        put(url, "$name=$value; Domain=$domain; Path=/; Max-Age=31536000")
    }

    fun flush() { cm?.flush() }
}

object Http {
    lateinit var userAgent: String
        private set
    lateinit var client: OkHttpClient
        private set
    /** Plain client for pictures (Coil), with the same cookies, referers and rate limits. */
    lateinit var imageClient: OkHttpClient
        private set
    private lateinit var appContext: Context

    /** host suffix -> Referer for requests that need one (image CDNs). */
    val referers = ConcurrentHashMap<String, String>()
    /** host suffix -> User-Agent for sites that turn browser-like agents away (Danbooru). */
    val userAgents = ConcurrentHashMap<String, String>()
    /** host suffix (optionally with a path prefix) -> minimum gap between requests in ms. */
    val rateLimits = ConcurrentHashMap<String, Long>()
    private val nextSlot = ConcurrentHashMap<String, Long>()

    val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

    private const val FALLBACK_UA = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"

    fun init(context: Context) {
        appContext = context.applicationContext
        userAgent = runCatching { WebSettings.getDefaultUserAgent(appContext) }.getOrNull() ?: FALLBACK_UA
        build(File(appContext.cacheDir, "http"))
    }

    /** The JVM test harness: same client, no Android. */
    fun initForTests(cacheDir: File) {
        userAgent = FALLBACK_UA
        build(cacheDir)
    }

    private fun build(cacheDir: File) {
        val base = OkHttpClient.Builder()
            .cookieJar(WebCookieJar)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(40, TimeUnit.SECONDS)
            .addInterceptor(HeaderInterceptor)
            .addInterceptor(RateLimitInterceptor)
        client = base
            .cache(Cache(cacheDir, 30L * 1024 * 1024))
            .addInterceptor(CloudflareInterceptor)
            .build()
        imageClient = client.newBuilder().cache(null).build()
    }

    private object HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val b = req.newBuilder()
            val host = req.url.host
            if (req.header("User-Agent") == null) {
                b.header("User-Agent", userAgents.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.value ?: userAgent)
            }
            if (req.header("Referer") == null) {
                referers.entries.firstOrNull { host == it.key || host.endsWith("." + it.key) }?.let { b.header("Referer", it.value) }
            }
            if (req.header("Accept-Language") == null) b.header("Accept-Language", "en-GB,en;q=0.9")
            return chain.proceed(b.build())
        }
    }

    private object RateLimitInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val url = chain.request().url
            val host = url.host
            val full = host + url.encodedPath
            // the most specific rule wins: "nhentai.net/api/v2/search" before "nhentai.net"
            val rule = rateLimits.entries.filter { r ->
                if ('/' in r.key) full.startsWith(r.key) || full.contains("." + r.key) else host == r.key || host.endsWith("." + r.key)
            }.maxByOrNull { it.key.length }
            if (rule != null) {
                val wait: Long
                synchronized(nextSlot) {
                    val now = System.currentTimeMillis()
                    val slot = maxOf(now, nextSlot[rule.key] ?: 0L)
                    nextSlot[rule.key] = slot + rule.value
                    wait = slot - now
                }
                if (wait > 0) Thread.sleep(wait)
            }
            return chain.proceed(chain.request())
        }
    }

    private object CloudflareInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            val res = chain.proceed(req)
            if (!isChallenge(res)) return res
            res.close()
            if (!solveInWebView(req.url.toString())) throw CloudflareException(req.url.toString())
            val again = chain.proceed(req)
            if (isChallenge(again)) { again.close(); throw CloudflareException(req.url.toString()) }
            return again
        }

        private fun isChallenge(res: Response): Boolean =
            (res.code == 403 || res.code == 503 || res.code == 429) &&
                (res.header("cf-mitigated") == "challenge" ||
                    (res.header("Server")?.contains("cloudflare", true) == true &&
                        res.peekBody(4096).string().let { "Just a moment" in it || "challenge-platform" in it }))
    }

    /** Loads the page in a hidden web view until Cloudflare hands out cf_clearance (max ~25 s). */
    @SuppressLint("SetJavaScriptEnabled")
    fun solveInWebView(url: String): Boolean {
        if (!::appContext.isInitialized || Looper.getMainLooper() == null || Looper.myLooper() == Looper.getMainLooper()) return false
        val before = WebCookieJar.get(url, "cf_clearance")
        val done = CountDownLatch(1)
        val main = Handler(Looper.getMainLooper())
        var view: WebView? = null
        main.post {
            view = WebView(appContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = userAgent
                webViewClient = WebViewClient()
                loadUrl(url)
            }
        }
        var ok = false
        val until = System.currentTimeMillis() + 25_000
        while (System.currentTimeMillis() < until) {
            Thread.sleep(500)
            val now = WebCookieJar.get(url, "cf_clearance")
            if (now != null && now != before) { ok = true; Thread.sleep(800); break }
        }
        main.post { view?.stopLoading(); view?.destroy(); done.countDown() }
        done.await(3, TimeUnit.SECONDS)
        WebCookieJar.flush()
        return ok
    }

    // ---------- helpers used by the sources ----------

    fun request(url: String, headers: Map<String, String> = emptyMap()): Request =
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()

    suspend fun get(url: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers)).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("HTTP ${r.code} from ${r.request.url.host}")
            r.body?.string() ?: ""
        }
    }

    /** GET that returns the body and code without throwing on 4xx (for sites that say why in the body). */
    suspend fun getRaw(url: String, headers: Map<String, String> = emptyMap()): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request(url, headers)).execute().use { r -> r.code to (r.body?.string() ?: "") }
    }

    suspend fun postJson(url: String, body: String, headers: Map<String, String> = emptyMap()): String = withContext(Dispatchers.IO) {
        val req = request(url, headers).newBuilder().post(body.toRequestBody("application/json".toMediaType())).build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("HTTP ${r.code} from ${r.request.url.host}")
            r.body?.string() ?: ""
        }
    }

    suspend fun getJson(url: String, headers: Map<String, String> = emptyMap()): JsonElement =
        json.parseToJsonElement(get(url, mapOf("Accept" to "application/json") + headers))

    suspend fun getDoc(url: String, headers: Map<String, String> = emptyMap()): Document {
        val html = get(url, headers)
        return Jsoup.parse(html, url)
    }

    /** Raw bytes of a picture (for saving / sharing). */
    suspend fun bytes(url: String): ByteArray = withContext(Dispatchers.IO) {
        imageClient.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("HTTP ${r.code} for the picture")
            r.body!!.bytes()
        }
    }
}
