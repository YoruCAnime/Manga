package androidx.activity.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import com.yoruc.mangacracker.platform.Back

/**
 * Windows stand-in for Android's back button handler: Esc, Backspace (outside text fields), the mouse's back button
 * and the window's back arrow go to the newest enabled handler.
 */
@Composable
fun BackHandler(enabled: Boolean = true, onBack: () -> Unit) {
    val cb = rememberUpdatedState(onBack)
    val en = rememberUpdatedState(enabled)
    DisposableEffect(Unit) {
        val h = Back.Handler({ en.value }) { cb.value() }
        Back.add(h)
        onDispose { Back.remove(h) }
    }
}
