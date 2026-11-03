package com.yoruc.mangacracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.yoruc.mangacracker.ui.theme.Mc
import com.yoruc.mangacracker.video.SeriesCovers
import com.yoruc.mangacracker.video.VideoRef

/**
 * A video's cover that never stays empty: the site's picture, then its other addresses ([VideoRef.altCovers]),
 * then a cover of the same series that loaded from any source ([SeriesCovers]), then a plain placeholder.
 */
@Composable
fun VideoCover(v: VideoRef, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val own = remember(v.key, v.cover) { (listOf(v.cover) + v.altCovers).filter { it.isNotBlank() }.distinct() }
    var i by remember(v.key, v.cover) { mutableIntStateOf(0) }
    val url = when {
        i < own.size -> own[i]
        i == own.size -> SeriesCovers.get(v.series)?.takeIf { it !in own }
        else -> null
    }
    if (url == null) {
        Box(modifier.background(Mc.Card2), contentAlignment = Alignment.Center) { Icon(Icons.Rounded.SmartDisplay, null, tint = Mc.Text3) }
        return
    }
    SubcomposeAsyncImage(
        model = ImageRequest.Builder(coil3.PlatformContext.INSTANCE).data(url).crossfade(220).build(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = { Shimmer(Modifier.fillMaxSize()) },
        error = { Box(Modifier.fillMaxSize().background(Mc.Card2)) },
        onSuccess = { if (i < own.size) SeriesCovers.put(v.series, url) },
        onError = { i++ },
    )
}
