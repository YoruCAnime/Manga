package androidx.compose.ui.platform

import android.content.Context
import android.content.res.Configuration
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.compositionLocalOf
import com.yoruc.mangacracker.platform.Desktop

/** Windows stand-ins for Android's composition locals, so the shared screens read them unchanged. */
val LocalContext = staticCompositionLocalOf<Context> { Desktop.ctx }

/** The window size in dp (the app window provides it). */
val LocalConfiguration = compositionLocalOf { Configuration(1280, 800) }
