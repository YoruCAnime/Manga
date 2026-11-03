package com.yoruc.mangacracker

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import com.yoruc.mangacracker.ui.GifEditor
import com.yoruc.mangacracker.ui.GifSource
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Test
import java.io.File

/** The GIF editor's controls drawn off-screen at a normal and a narrow window width (no video: the preview shows an error). */
class GifEditorShotTest {
    @Test fun controls() {
        for ((w, name) in listOf(1280 to "gif_editor_1280", 960 to "gif_editor_960")) {
            val scene = ImageComposeScene(w, 560, Density(1f)) {
                GifEditor(GifSource(listOf("-i", "missing.mp4"), 1280, 720, 24.0, 100.0, "test"), 10.0) {}
            }
            var t = 0L
            repeat(30) { scene.render(t); t += 50_000_000L; Thread.sleep(30) }
            val img = scene.render(t)
            File("build/shots").mkdirs()
            File("build/shots/$name.png").writeBytes(img.encodeToData(EncodedImageFormat.PNG)!!.bytes)
            scene.close()
            println("[shot] $name")
        }
    }

    /** With a real video (build/rife_test/clip.mp4, skipped when missing): the time ruler, and the time under the mouse. */
    @Test fun ruler() {
        val clip = File("build/rife_test/clip.mp4")
        org.junit.Assume.assumeTrue(clip.isFile)
        // a GIF of 0:12-0:15 made before (in the test copy's data): the editor opens there, not at 0:20
        val spots = File(com.yoruc.mangacracker.platform.Desktop.ctx.filesDir, "data/gif_spots.properties")
        spots.parentFile.mkdirs()
        val before = spots.takeIf { it.isFile }?.readBytes()
        spots.writeText("test|clip=12.000 15.000\n")
        val scene = ImageComposeScene(1280, 560, Density(1f)) {
            GifEditor(GifSource(listOf("-i", clip.absolutePath), 640, 360, 24.0, 30.0, "test", "test|clip"), 20.0) {}
        }
        var t = 0L
        // reading the frames takes a few seconds
        repeat(200) { scene.render(t); t += 50_000_000L; Thread.sleep(50) }
        // the mouse over the strip, a third of the way along
        scene.sendPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move, androidx.compose.ui.geometry.Offset(700f, 370f))
        repeat(5) { scene.render(t); t += 50_000_000L; Thread.sleep(30) }
        File("build/shots").mkdirs()
        File("build/shots/gif_editor_ruler.png").writeBytes(scene.render(t).encodeToData(EncodedImageFormat.PNG)!!.bytes)
        scene.close()
        if (before != null) spots.writeBytes(before) else spots.delete()
        println("[shot] gif_editor_ruler")
    }
}
