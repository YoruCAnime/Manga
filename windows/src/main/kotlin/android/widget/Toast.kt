package android.widget

import android.content.Context
import com.yoruc.mangacracker.platform.Desktop

/** Windows stand-in: Android's short messages show as a toast at the bottom of the window (no pop-up windows). */
class Toast private constructor(private val text: String) {
    fun show() = Desktop.toast(text)

    companion object {
        const val LENGTH_SHORT = 0
        const val LENGTH_LONG = 1
        @Suppress("UNUSED_PARAMETER")
        fun makeText(ctx: Context?, text: CharSequence, length: Int) = Toast(text.toString())
    }
}
