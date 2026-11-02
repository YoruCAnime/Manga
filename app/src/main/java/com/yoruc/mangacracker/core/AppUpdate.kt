package com.yoruc.mangacracker.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import com.yoruc.mangacracker.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import java.security.MessageDigest

/** A MangaCracker release on GitHub. */
@Serializable
data class AppRelease(
    val version: String,
    val notes: String = "",
    val apkUrl: String = "",
    val size: Long = 0L,
    val page: String = "",
    val published: Long = 0L,
)

sealed interface AppUpdateState {
    data object Idle : AppUpdateState
    data object Checking : AppUpdateState
    data class UpToDate(val at: Long) : AppUpdateState
    data class Available(val rel: AppRelease) : AppUpdateState
    data class Downloading(val rel: AppRelease, val progress: Float) : AppUpdateState
    data class Ready(val rel: AppRelease, val file: File) : AppUpdateState
    data class Failed(val rel: AppRelease?, val message: String) : AppUpdateState

    /** The newer release this state is about, if any. */
    val release: AppRelease? get() = when (this) {
        is Available -> rel
        is Downloading -> rel
        is Ready -> rel
        is Failed -> rel
        else -> null
    }
}

/**
 * App updates from the GitHub releases of [REPO]: check the latest release, download its APK into the cache and hand
 * it to Android's installer (Android always asks the user to confirm an install; the same signing key lets it
 * install over the old app and keep all data).
 */
object AppUpdate {
    const val REPO = "YoruCAnime/Manga"
    const val RELEASES = "https://github.com/$REPO/releases"
    /** Automatic checks at most this often (GitHub allows 60 anonymous API calls an hour). */
    private const val AUTO_GAP = 3 * 3600_000L
    val current: String = BuildConfig.VERSION_NAME

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    /** Why the installer didn't open last time (for tests / support). */
    var lastInstallError: String? = null
        private set
    private val _state = MutableStateFlow<AppUpdateState>(AppUpdateState.Idle)
    val state: StateFlow<AppUpdateState> = _state

    /** The newest known release when it is newer than this app, else null. */
    val pending: AppRelease? get() = _state.value.release ?: Repo.settings.value.appRelease?.takeIf { isNewer(it.version, current) }

    /** Called at start: show what the last check found (works offline), drop APKs already installed. */
    fun init(context: Context) {
        val known = Repo.settings.value.appRelease
        if (known != null && isNewer(known.version, current)) {
            val f = apkFile(context, known)
            _state.value = if (f.exists() && f.length() == known.size) AppUpdateState.Ready(known, f) else AppUpdateState.Available(known)
        }
        runCatching {
            dir(context).listFiles()?.filter { f ->
                // .part is a download that never finished (or was interrupted);
                // it is never installable, so do not keep it around.
                f.name.endsWith(".part") ||
                    !isNewer(f.name.removePrefix("MangaCracker-").removeSuffix(".apk"), current)
            }?.forEach { it.delete() }
        }
    }

    /** "v1.10.0" > "1.9.2"; missing parts count as 0. */
    fun isNewer(a: String, b: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split('.', '-').map { it.toIntOrNull() ?: 0 }
        val x = parts(a); val y = parts(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    suspend fun fetchLatest(): AppRelease? {
        val (code, body) = Http.getRaw(
            "https://api.github.com/repos/$REPO/releases/latest",
            mapOf("Accept" to "application/vnd.github+json", "Cache-Control" to "no-cache", "User-Agent" to "MangaCracker/$current (Android)")
        )
        if (code == 404) return null
        if (code != 200) throw SourceException(if (code == 403 || code == 429) "GitHub is busy, try again later" else "HTTP $code from GitHub")
        val j = Http.json.parseToJsonElement(body)
        val apk = j["assets"].arr.firstOrNull { it.str("name").endsWith(".apk", true) }
        return AppRelease(
            version = j.str("tag_name").removePrefix("v"),
            notes = j.str("body"),
            apkUrl = apk.str("browser_download_url"),
            size = apk.long("size"),
            page = j.str("html_url"),
            published = isoToMillis(j.str("published_at")),
        )
    }

    /** Looks for a newer release. Automatic checks ([force] false) are skipped when one ran in the last 3 hours. */
    suspend fun check(force: Boolean = false): AppRelease? {
        val s = Repo.settings.value
        if (!force && System.currentTimeMillis() - s.lastAppCheck < AUTO_GAP) return pending
        if (_state.value is AppUpdateState.Downloading) return pending
        val before = _state.value
        _state.value = AppUpdateState.Checking
        return try {
            val rel = fetchLatest()
            Repo.updateSettings { it.copy(lastAppCheck = System.currentTimeMillis(), appRelease = rel) }
            val newer = rel?.takeIf { isNewer(it.version, current) && it.apkUrl.isNotEmpty() }
            _state.value = when {
                newer == null -> AppUpdateState.UpToDate(System.currentTimeMillis())
                before is AppUpdateState.Ready && before.rel.version == newer.version -> before
                else -> AppUpdateState.Available(newer)
            }
            newer
        } catch (e: Exception) {
            _state.value = if (force) AppUpdateState.Failed(pending, e.message ?: "Couldn't reach GitHub") else before
            null
        }
    }

    /** PC tests only: put the updater into a given state. */
    internal fun showForTest(s: AppUpdateState) { _state.value = s }

    fun checkInBackground(force: Boolean = false) {
        scope.launch { check(force) }
    }

    private fun dir(context: Context) = File(context.cacheDir, "updates")
    private fun apkFile(context: Context, rel: AppRelease) = File(dir(context), "MangaCracker-${rel.version}.apk")

    /** Downloads the APK (progress in [state]) and opens the installer when done. */
    fun downloadAndInstall(context: Context, rel: AppRelease) {
        val ctx = context.applicationContext
        if (job?.isActive == true) return
        job = scope.launch {
            val file = apkFile(ctx, rel)
            try {
                if (!(file.exists() && file.length() == rel.size)) download(rel, file)
                verify(ctx, file)
                _state.value = AppUpdateState.Ready(rel, file)
            } catch (e: Exception) {
                file.delete()
                _state.value = AppUpdateState.Failed(rel, e.message ?: "Download failed")
                return@launch
            }
            // a good download stays (Ready: "Install" tries again) even if the installer can't open
            withContext(Dispatchers.Main) {
                runCatching { install(ctx, file) }.onFailure { lastInstallError = it.toString() }
            }
        }
    }

    private suspend fun download(rel: AppRelease, file: File) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val part = File(file.path + ".part")
        _state.value = AppUpdateState.Downloading(rel, 0f)
        Http.imageClient.newCall(Http.request(rel.apkUrl)).execute().use { r ->
            if (!r.isSuccessful) throw SourceException("HTTP ${r.code} while downloading")
            val body = r.body ?: throw SourceException("Empty download")
            val total = body.contentLength().takeIf { it > 0 } ?: rel.size
            var got = 0L
            var shown = -1
            body.byteStream().use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        got += n
                        val pct = if (total > 0) (got * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; _state.value = AppUpdateState.Downloading(rel, pct / 100f) }
                    }
                }
            }
            if (rel.size > 0 && got != rel.size) { part.delete(); throw SourceException("Download cut off, try again") }
        }
        file.delete()
        if (!part.renameTo(file)) throw SourceException("Couldn't save the update")
    }

    /**
     * The file must be a MangaCracker APK signed by the same key as this install. Android enforces the signature
     * when the installer runs, but by then the only message is a generic "App not installed"; checking here lets a
     * wrong-key download fail with something a user can act on. The old package-name-only check accepted a
     * repackaged APK and left the failure to the installer.
     */
    private fun verify(ctx: Context, file: File) {
        val pm = ctx.packageManager
        val info = runCatching {
            pm.getPackageArchiveInfo(file.path, PackageManager.GET_SIGNING_CERTIFICATES)
        }.getOrNull()
        // Robolectric (the PC test harness) can't read APKs; a real phone always can
        if (info == null) {
            if (android.os.Build.FINGERPRINT == "robolectric") return
            throw SourceException("The download isn't a MangaCracker update")
        }
        if (info.packageName != ctx.packageName) throw SourceException("The download isn't a MangaCracker update")

        val expected = runCatching {
            signerDigests(pm.getPackageInfo(ctx.packageName, PackageManager.GET_SIGNING_CERTIFICATES))
        }.getOrDefault(emptySet())
        val actual = signerDigests(info)
        if (expected.isNotEmpty() && actual.isNotEmpty() && expected != actual) {
            throw SourceException("This update is signed with a different key. Uninstall MangaCracker once, then install it.")
        }
    }

    private fun signerDigests(info: PackageInfo): Set<String> {
        val signers = info.signingInfo?.apkContentsSigners ?: return emptySet()
        return signers.mapTo(mutableSetOf()) { cert ->
            MessageDigest.getInstance("SHA-256").digest(cert.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }

    /** Opens Android's installer for [file]. The first time, Android asks to allow installs from MangaCracker. */
    fun install(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(i)
    }
}
