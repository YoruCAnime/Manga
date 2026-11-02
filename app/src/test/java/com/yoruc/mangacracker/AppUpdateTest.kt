package com.yoruc.mangacracker

import android.app.Application
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.ExperimentalRoborazziApi
import com.github.takahirom.roborazzi.captureScreenRoboImage
import com.yoruc.mangacracker.core.AppRelease
import com.yoruc.mangacracker.core.AppUpdate
import com.yoruc.mangacracker.core.AppUpdateState
import com.yoruc.mangacracker.ui.AppUpdateCard
import com.yoruc.mangacracker.ui.AppUpdatePopup
import com.yoruc.mangacracker.ui.theme.MangaCrackerTheme
import com.yoruc.mangacracker.ui.theme.Mc
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLooper
import java.io.File
import java.security.MessageDigest

/**
 * App updates against the real GitHub releases: version order, the latest release, the APK download and the
 * hand-off to Android's installer, plus screenshots of the pop-up and the Settings card.
 * Run: gradlew :app:testDebugUnitTest --tests '*AppUpdateTest*'
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w400dp-h860dp-xhdpi")
class AppUpdateTest {
    @get:Rule val rule = createComposeRule()
    private val out = File("../_dev_tools/out/screens").apply { mkdirs() }

    private fun md5(f: File) = MessageDigest.getInstance("MD5").digest(f.readBytes()).joinToString("") { "%02x".format(it) }

    @Test fun versionOrder() {
        assertTrue(AppUpdate.isNewer("1.1.0", "1.0.0"))
        assertTrue(AppUpdate.isNewer("v1.10.0", "1.9.2"))
        assertTrue(AppUpdate.isNewer("2", "1.9.9"))
        assertFalse(AppUpdate.isNewer("1.0", "1.0.0"))
        assertFalse(AppUpdate.isNewer("1.0.0", "1.1.0"))
    }

    @Test fun liveReleaseDownloadAndInstall() {
        val ctx = ApplicationProvider.getApplicationContext<Application>()
        val rel = runBlocking { AppUpdate.fetchLatest() }!!
        println("[latest] $rel".take(300))
        assertTrue(rel.apkUrl.endsWith(".apk"))
        assertTrue(rel.size > 1_000_000)

        // this build is newer than anything on GitHub yet, so a check finds nothing
        val found = runBlocking { AppUpdate.check(force = true) }
        println("[check] state=${AppUpdate.state.value} found=$found")
        if (!AppUpdate.isNewer(rel.version, AppUpdate.current)) assertTrue(AppUpdate.state.value is AppUpdateState.UpToDate)

        // download the real release as if it were newer and hand it to the installer
        AppUpdate.downloadAndInstall(ctx, rel)
        val until = System.currentTimeMillis() + 90_000
        var seen = 0
        while (System.currentTimeMillis() < until) {
            Thread.sleep(100)
            ShadowLooper.idleMainLooper()
            val s = AppUpdate.state.value
            if (s is AppUpdateState.Downloading) seen++
            if (s is AppUpdateState.Ready || s is AppUpdateState.Failed) break
        }
        ShadowLooper.idleMainLooper()
        val s = AppUpdate.state.value
        println("[download] $s (progress updates seen: $seen)")
        assertTrue("state $s", s is AppUpdateState.Ready)
        val f = (s as AppUpdateState.Ready).file
        assertEquals(rel.size, f.length())
        val local = File("../MangaCracker-${rel.version}.apk")
        if (local.exists()) assertEquals(md5(local), md5(f))

        // the installer opens on the main thread right after the download
        var started: Intent? = null
        val wait = System.currentTimeMillis() + 10_000
        while (started == null && System.currentTimeMillis() < wait) {
            Thread.sleep(100); ShadowLooper.idleMainLooper()
            started = shadowOf(ctx).nextStartedActivity
        }
        println("[after] ${AppUpdate.state.value::class.simpleName} installError=${AppUpdate.lastInstallError}")
        assertTrue("a good download must stay Ready", AppUpdate.state.value is AppUpdateState.Ready)
        if (started == null && File.separatorChar == '\\' && AppUpdate.lastInstallError?.contains("Failed to find configured root") == true) {
            // FileProvider joins its roots with '/', so on a Windows PC it never matches; on a phone it does
            println("[installer] skipped on Windows: FileProvider can't match Windows paths (works on Android)")
            return
        }
        started!!
        println("[installer] action=${started?.action} type=${started?.type} data=${started?.data} flags=${started?.flags}")
        assertEquals(Intent.ACTION_VIEW, started.action)
        assertEquals("application/vnd.android.package-archive", started.type)
        assertEquals("content", started.data?.scheme)
        assertTrue(started.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
    }

    @OptIn(ExperimentalRoborazziApi::class)
    @Test fun screens() {
        val rel = AppRelease(
            version = "1.2.0", size = 3_900_000, apkUrl = "https://example.invalid/x.apk",
            notes = "**Accounts**: sign up with any name\n\n- Your library and reading spots sync to your account\n- Image boards no longer show up in Recommended\n- Faster covers",
        )
        AppUpdate.showForTest(AppUpdateState.Available(rel))
        rule.setContent {
            MangaCrackerTheme {
                Box(Modifier.fillMaxSize().background(Mc.Bg)) {
                    Column(Modifier.padding(top = 40.dp)) { AppUpdateCard() }
                    AppUpdatePopup(onClose = {})
                }
            }
        }
        rule.waitForIdle()
        captureScreenRoboImage(File(out, "11_update_popup.png").path)
        AppUpdate.showForTest(AppUpdateState.Downloading(rel, 0.45f))
        rule.waitForIdle()
        captureScreenRoboImage(File(out, "12_update_downloading.png").path)
    }
}
