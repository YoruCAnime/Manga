package com.yoruc.mangacracker

import android.content.Context
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.navigation.compose.rememberNavController
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.Repo
import com.yoruc.mangacracker.ui.Nav
import com.yoruc.mangacracker.ui.VideosScreen
import com.yoruc.mangacracker.video.VideoRef
import com.yoruc.mangacracker.video.VideoRepo
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File

/** Videos tab on Anikoto: its own "Continue on Anikoto" row, the other sites' shows in the row after it. */
class ContinueRowTest {
    @Test fun rows() {
        val dir = File("build/continue_row").apply { deleteRecursively(); mkdirs() }
        Context(dir).also { Http.init(it); Repo.init(it); VideoRepo.init(it) }
        VideoRepo.save(VideoRef("anikoto", "a1", "Aoki Denshou: Welsh & Shedar - Episode 8", "", series = "Aoki Denshou"), 600_000, 1_424_000)
        VideoRepo.save(VideoRef("anikoto", "a2", "Mahou Shoujo Ikusei Keikaku: Restart - Episode 3", "", series = "Mahou Shoujo"), 300_000, 1_430_000)
        VideoRepo.save(VideoRef("animeheaven", "h1", "The Cold Sato-san is Only Sweet to Me - Episode 2", "", series = "Sato-san"), 400_000, 1_420_000)
        val scene = ImageComposeScene(1280, 720, Density(1f)) { VideosScreen(Nav(rememberNavController())) }
        var t = 0L
        repeat(80) { scene.render(t); t += 50_000_000L; Thread.sleep(50) }
        File("build/shots").mkdirs()
        File("build/shots/continue_on_anikoto.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        println("[shot] continue_on_anikoto")
    }
}
