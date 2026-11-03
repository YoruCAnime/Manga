package android.content

import java.io.File

/**
 * Windows stand-in for the one part of Android's Context the shared code uses: where the app keeps its files.
 * Everything lives under %LOCALAPPDATA%\MangaCracker (files = data, downloads; cache = pictures, http).
 */
open class Context(root: File) {
    val filesDir: File = File(root, "files").apply { mkdirs() }
    val cacheDir: File = File(root, "cache").apply { mkdirs() }
    val noBackupFilesDir: File get() = filesDir
    val applicationContext: Context get() = this
}
