package org.dergigi.feather

import android.graphics.drawable.Animatable
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil3.DrawableImage
import coil3.Image

/** Drawable start/stop are UI APIs; Coil handles decoding on its background dispatcher. */
@Composable
internal fun ImageAnimation(image: Image?, visible: Boolean) {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val animation = (image as? DrawableImage)?.drawable as? Animatable
    val running = visible && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    DisposableEffect(animation, running) {
        if (running) animation?.start() else animation?.stop()
        onDispose { animation?.stop() }
    }
}
