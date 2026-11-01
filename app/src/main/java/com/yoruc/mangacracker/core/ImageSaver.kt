package com.yoruc.mangacracker.core

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Saves pictures to Pictures/MangaCracker/<title>/ (shows in the gallery, no permission needed on Android 10+). */
object ImageSaver {
    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r\\t]"), " ").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "untitled" }

    private fun ext(bytes: ByteArray): Pair<String, String> = when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "png" to "image/png"
        bytes.size > 3 && bytes[0] == 'G'.code.toByte() && bytes[1] == 'I'.code.toByte() -> "gif" to "image/gif"
        bytes.size > 11 && String(bytes, 8, 4) == "WEBP" -> "webp" to "image/webp"
        bytes.size > 11 && String(bytes, 4, 8).let { it == "ftypavif" || it == "ftypavis" } -> "avif" to "image/avif"
        else -> "jpg" to "image/jpeg"
    }

    suspend fun save(ctx: Context, url: String, folder: String, name: String): String = withContext(Dispatchers.IO) {
        val bytes = Http.bytes(url)
        val (e, mime) = ext(bytes)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "${clean(name)}.$e")
            put(MediaStore.Images.Media.MIME_TYPE, mime)
            put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/MangaCracker/${clean(folder)}")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val uri = cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: throw SourceException("Could not create the file")
        cr.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
        cr.update(uri, values, null, null)
        "Pictures/MangaCracker/${clean(folder)}"
    }

    suspend fun share(ctx: Context, url: String) {
        val file = withContext(Dispatchers.IO) {
            val bytes = Http.bytes(url)
            val dir = File(ctx.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
            File(dir, "page.${ext(bytes).first}").apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND).setType("image/*").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ctx.startActivity(Intent.createChooser(send, "Share page").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
