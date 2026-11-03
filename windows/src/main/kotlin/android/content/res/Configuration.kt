package android.content.res

/** Windows stand-in: the window's size in dp (set by the app window as it is resized). */
class Configuration(val screenWidthDp: Int, val screenHeightDp: Int) {
    val orientation: Int get() = if (screenWidthDp > screenHeightDp) ORIENTATION_LANDSCAPE else ORIENTATION_PORTRAIT

    companion object {
        const val ORIENTATION_PORTRAIT = 1
        const val ORIENTATION_LANDSCAPE = 2
    }
}
