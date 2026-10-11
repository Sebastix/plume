package org.dergigi.feather

import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Scope immersive photo viewing to its own window, including gallery dialogs. */
@Composable
internal fun HidePhotoStatusBar(window: Window) {
    DisposableEffect(window) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val statusBars = WindowInsetsCompat.Type.statusBars()
        val wasVisible = ViewCompat.getRootWindowInsets(window.decorView)?.isVisible(statusBars) ?: true
        val previousBehavior = controller.systemBarsBehavior
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(statusBars)
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            if (wasVisible) controller.show(statusBars)
        }
    }
}
