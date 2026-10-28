package plume.sebastix.dev

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged

/** Only visible rows are measured. Enough retained height to keep a short tab scrollable. */
internal class ProfileTabSpace {
    private val heights = mutableMapOf<String, Int>()
    var measured by mutableIntStateOf(0)
        private set

    fun record(key: String, height: Int, viewport: Int) {
        // Once a viewport is covered, later rows cannot increase the required minimum.
        if (key !in heights && ((viewport > 0 && measured >= viewport) || heights.size >= 128)) return
        val previous = heights.put(key, height) ?: 0
        if (height != previous) measured += height - previous
    }
}

@Composable
internal fun ProfileTabRow(space: ProfileTabSpace?, key: String, viewport: Int, content: @Composable () -> Unit) {
    if (space == null) content()
    else Column(Modifier.onSizeChanged { space.record(key, it.height, viewport) }) { content() }
}
