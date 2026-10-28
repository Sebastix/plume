package plume.sebastix.dev

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** Horizontal-only recognition leaves vertical scrolling, refresh, taps and long presses alone. */
@Composable
internal fun Modifier.profileTabSwipes(enabled: Boolean, tabs: List<ProfileTab>, selected: ProfileTab, onSelect: (ProfileTab) -> Unit): Modifier {
    if (!enabled || tabs.size < 2) return this
    val currentTabs by rememberUpdatedState(tabs)
    val currentTab by rememberUpdatedState(selected)
    val select by rememberUpdatedState(onSelect)
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    val direction = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    return pointerInput(threshold, direction) {
        var distance = 0f
        detectHorizontalDragGestures(
            onDragStart = { distance = 0f },
            onHorizontalDrag = { change, delta -> change.consume(); distance += delta },
            onDragCancel = { distance = 0f },
            onDragEnd = {
                if (abs(distance) >= threshold) {
                    val step = (if (distance < 0f) 1 else -1) * direction
                    val index = currentTabs.indexOf(currentTab)
                    if (index >= 0) currentTabs.getOrNull(index + step)?.let(select)
                }
                distance = 0f
            },
        )
    }
}
