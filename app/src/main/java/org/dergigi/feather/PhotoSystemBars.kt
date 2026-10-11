package org.dergigi.feather

import android.view.Window
import android.view.WindowManager
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** Scope immersive photo viewing to its own window, including gallery dialogs. */
@Composable
internal fun HidePhotoStatusBar(window: Window, hideNavigationBar: Boolean = false) {
    DisposableEffect(window, hideNavigationBar) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val bars = if (hideNavigationBar) WindowInsetsCompat.Type.systemBars() else WindowInsetsCompat.Type.statusBars()
        val originalInsets = ViewCompat.getRootWindowInsets(window.decorView)
        val statusWasVisible = originalInsets?.isVisible(WindowInsetsCompat.Type.statusBars()) ?: true
        val navigationWasVisible = originalInsets?.isVisible(WindowInsetsCompat.Type.navigationBars()) ?: true
        val previousBehavior = controller.systemBarsBehavior
        val previousCutout = if (Build.VERSION.SDK_INT >= 28) window.attributes.layoutInDisplayCutoutMode else 0
        if (hideNavigationBar && Build.VERSION.SDK_INT >= 28) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(bars)
        onDispose {
            controller.systemBarsBehavior = previousBehavior
            if (statusWasVisible) controller.show(WindowInsetsCompat.Type.statusBars())
            if (hideNavigationBar && navigationWasVisible) controller.show(WindowInsetsCompat.Type.navigationBars())
            if (hideNavigationBar && Build.VERSION.SDK_INT >= 28) {
                window.attributes = window.attributes.apply { layoutInDisplayCutoutMode = previousCutout }
            }
        }
    }
}
