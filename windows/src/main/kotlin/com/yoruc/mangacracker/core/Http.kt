package com.yoruc.mangacracker.core

import android.content.Context
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
import java.util.concurrent.TimeUnit

/** Thrown when a site shows a Cloudflare check we could not pass in the background. */
class CloudflareException(val url: String) : IOException("Cloudflare check: open the site once in the web view")

/**
 * The app's cookies (Windows): kept in memory and saved to files/cookies.json, so logins and Cloudflare passes last
 * between runs. The browser window used for sign-ins and Cloudflare checks hands its cookies over with [putAll].
 */
object WebCookieJar : CookieJar {
    private val all = java.util.concurrent.CopyOnWriteArrayList<Cookie>()
    private var file: File? = null
    @Volatile private var dirty = false

    fun load(f: File) {
        file = f
        if (!f.exists()) return
        runCatching {
            val now = System.currentTimeMillis()
            Http.json.parseToJsonElement(f.readText()).let { it as kotlinx.serialization.json.JsonArray }.forEach { e ->
                val o = e as kotlinx.serialization.json.JsonObject
                fun str(k: String) = (o[k] as? kotlinx.serialization.json.JsonPrimitive)?.content.orEmpty()
                val exp = str("expires").toLongOrNull() ?: Long.MAX_VALUE
                if (exp <= now) return@forEach
                val b = Cookie.Builder().name(str("name")).value(str("value")).path(str("path").ifEmpty { "/" }).expiresAt(exp)
                if (str("hostOnly") == "true") b.hostOnlyDomain(str("domain")) else b.domain(str("domain"))
                if (str("secure") == "true") b.secure()
                if (str("httpOnly") == "true") b.httpOnly()
                all += b.build()
            }
        }
    }

    private fun key(c: Cookie) = "${c.name}|${c.domain}|${c.path}"

    private fun store(c: Cookie) {
        all.removeAll { key(it) == key(c) }
        if (c.expiresAt > System.currentTimeMillis()) all += c
        dirty = true
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        cookies.forEach { store(it) }
        flush()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return all.filter { it.expiresAt > now && it.matches(url) }
    }

    fun get(url: String, name: String): String? {
        val u = url.toHttpUrl()
        return loadForRequest(u).firstOrNull { it.name == name }?.value
    }

    fun set(url: String, name: String, value: String, domain: String) {
        Cookie.parse(url.toHttpUrl(), "$name=$value; Domain=$domain; Path=/; Max-Age=31536000")?.let { store(it) }
        flush()
    }

    /** Cookies from the browser window (sign-ins, Cloudflare passes). */
    fun putAll(cookies: List<Cookie>) { cookies.forEach { store(it) }; flush() }

    /** Every cookie whose domain ends with one of [hosts] except [keep] names (sign out). */
    fun clear(hosts: List<String>, keep: Set<String> = emptySet()) {
        all.removeAll { c -> c.name !in keep && hosts.any { h -> c.domain == h || c.domain.endsWith(".$h") } }
        dirty = true
        flush()
    }

    fun flush() {
        val f = file ?: return
        if (!dirty) return
        dirty = false
        runCatching {
            val arr = kotlinx.serialization.json.buildJsonArray {
                all.filter { it.persistent || it.name == "cf_clearance" }.forEach { c ->
                    add(kotlinx.serialization.json.buildJsonObject {
                        put("name", kotlinx.serialization.json.JsonPrimitive(c.name)); put("value", kotlinx.serialization.json.JsonPrimitive(c.value))
                        put("domain", kotlinx.serialization.json.JsonPrimitive(c.domain)); put("path", kotlinx.serialization.json.JsonPrimitive(c.path))
                        put("expires", kotlinx.serialization.json.JsonPrimitive(c.expiresAt.toString()))
                        put("hostOnly", kotlinx.serialization.json.JsonPrimitive(c.hostOnly.toString()))
                        put("secure", kotlinx.serialization.json.JsonPrimitive(c.secure.toString()))
                        put("httpOnly", kotlinx.serialization.json.JsonPrimitive(c.httpOnly.toString()))
                    })
                }
            }
            val tmp = File(f.path + ".tmp"); tmp.writeText(arr.toString()); tmp.renameTo(f) || run { f.delete(); tmp.renameTo(f) }
        }
    }
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

    /**
     * Opens [url] in the browser window until Cloudflare hands out cf_clearance (set by the Windows app: Edge in app
     * mode). Returns true when the pass was received. Null = no browser: the site shows "open it in the browser".
     */
    @Volatile var solver: ((String) -> Boolean)? = null

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
        userAgent = FALLBACK_UA
        WebCookieJar.load(File(appContext.filesDir, "cookies.json"))
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
        imageClient = client.newBuilder().cache(null).addInterceptor(RetryBusyInterceptor).build()
    }

    /** Pictures: a server that says 429 "too many requests" gets up to 3 calm retries instead of an empty cover. */
    private object RetryBusyInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            var res = chain.proceed(chain.request())
            var n = 0
            while (res.code == 429 && n < 3) {
                val wait = res.header("Retry-After")?.toLongOrNull()?.times(1000)?.coerceAtMost(4000) ?: (400L + n * 600)
                res.close()
                Thread.sleep(wait)
                n++
                res = chain.proceed(chain.request())
            }
            return res
        }
    }

    /** Tests: every request fails as if the phone were offline (the offline reader / player checks). */
    @Volatile var blockNetwork = false

    private object HeaderInterceptor : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val req = chain.request()
            if (blockNetwork) throw java.net.UnknownHostException("offline (test): ${req.url.host}")
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

    /** Asks the browser window for a Cloudflare pass (see [solver]). */
    fun solveInWebView(url: String): Boolean = runCatching { solver?.invoke(url) ?: false }.getOrDefault(false)

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
        // a downloaded page (Downloads.LOCAL)
        if (url.startsWith("file:")) return@withContext File(url.removePrefix("file:")).readBytes()
        imageClient.newCall(request(url)).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("HTTP ${r.code} for the picture")
            r.body!!.bytes()
        }
    }
}
