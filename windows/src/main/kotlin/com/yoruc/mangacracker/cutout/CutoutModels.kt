package com.yoruc.mangacracker.cutout

import android.content.Context
import com.yoruc.mangacracker.core.Http
import com.yoruc.mangacracker.core.SourceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * The cutout's model files, downloaded once (they are too big for the app itself) from their authors' pages,
 * pinned to one version and checked by SHA-256.
 *  - anime-seg isnetis by SkyTNT (Apache-2.0): https://huggingface.co/skytnt/anime-seg
 *  - MobileSAM (Apache-2.0), ONNX export by Acly: https://huggingface.co/Acly/MobileSAM
 */
object CutoutModels {
    const val SEG = "isnetis.onnx"
    const val SAM_ENC = "mobile_sam_image_encoder.onnx"
    const val SAM_DEC = "sam_mask_decoder_multi.onnx"

    class ModelFile(val name: String, val url: String, val size: Long, val sha256: String)

    val files = listOf(
        ModelFile(SEG, "https://huggingface.co/skytnt/anime-seg/resolve/493cb60893f47441b26ec4fb9a306bce9e342982/isnetis.onnx",
            176_069_933, "f15622d853e8260172812b657053460e20806f04b9e05147d49af7bed31a6e99"),
        ModelFile(SAM_ENC, "https://huggingface.co/Acly/MobileSAM/resolve/0d3b403339b4674a82493d5e97964dd78089ddc8/mobile_sam_image_encoder.onnx",
            28_157_093, "580f5fb648ea1062c0aabc26217aed56921985f03f0cbbd852bba81d760cc749"),
        ModelFile(SAM_DEC, "https://huggingface.co/Acly/MobileSAM/resolve/0d3b403339b4674a82493d5e97964dd78089ddc8/sam_mask_decoder_multi.onnx",
            16_496_559, "8976b90a87ba50a6a72217a5ff994f7d25ce16f2229fcc1ed259e1294c622ffe"),
    )
    val totalBytes get() = files.sumOf { it.size }

    /** tests point this at a folder that already holds the models */
    var dirOverride: File? = null
    fun dir(ctx: Context) = dirOverride ?: File(ctx.filesDir, "cutout")

    fun ready(ctx: Context) = files.all { File(dir(ctx), it.name).length() == it.size }

    /** Downloads what is missing; [progress] gets (bytes done, total). Resumes a part file after a break. */
    suspend fun download(ctx: Context, progress: (Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val d = dir(ctx).apply { mkdirs() }
        var done = files.filter { File(d, it.name).length() == it.size }.sumOf { it.size }
        for (f in files) {
            val out = File(d, f.name)
            if (out.length() == f.size) continue
            val part = File(d, f.name + ".part")
            var have = part.length().takeIf { it < f.size } ?: 0L.also { part.delete() }
            val req = Request.Builder().url(f.url).apply { if (have > 0) header("Range", "bytes=$have-") }.build()
            Http.imageClient.newCall(req).execute().use { r ->
                if (r.code == 200) { have = 0; part.delete() } else if (r.code != 206) throw SourceException("Download failed (HTTP ${r.code})")
                java.io.FileOutputStream(part, have > 0).use { os ->
                    val inp = r.body!!.byteStream()
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf); if (n < 0) break
                        os.write(buf, 0, n); have += n
                        progress(done + have, totalBytes)
                    }
                }
            }
            if (part.length() != f.size || sha256(part) != f.sha256) { part.delete(); throw SourceException("The download of ${f.name} was damaged. Try again.") }
            if (!part.renameTo(out)) throw SourceException("Couldn't store ${f.name}")
            done += f.size
        }
    }

    fun delete(ctx: Context) { dir(ctx).deleteRecursively() }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
