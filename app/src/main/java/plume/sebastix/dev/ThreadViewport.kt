package plume.sebastix.dev

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.constrainHeight

/** Keep enough space below the entry anchor to prevent LazyColumn's end-of-list scrollback.
 * Heights are read/written during measurement, not onSizeChanged a frame after the jump.
 * Only measured rows are retained; there is no eager composition of off-screen ancestors.
 */
internal class ThreadViewport {
    private val heights = mutableStateMapOf<String, Int>()
    private var tail by mutableStateOf(emptyList<String>())
    private var viewport by mutableIntStateOf(0)
    private var floor: String? = null
    private var floorOffset by mutableIntStateOf(0)
    private var previousKeys = emptySet<String>()
    private var pendingAnchor: Pair<String, Int>? = null
    private var beforeRequest: LazyListLayoutInfo? = null

    fun clear() {
        heights.clear(); tail = emptyList(); floor = null; floorOffset = 0
        previousKeys = emptySet(); pendingAnchor = null; beforeRequest = null
    }

    suspend fun observe(entry: ReaderEntry) {
        snapshotFlow { entry.list.layoutInfo }.collect { measured ->
            if (pendingAnchor != null && measured !== beforeRequest) {
                pendingAnchor = null
                beforeRequest = null
                entry.threadPositioned = true
            }
        }
    }

    fun update(entry: ReaderEntry, rows: List<ConversationRow>, graphReady: Boolean) {
        val keys = rows.map { it.key }.toSet()
        val state = entry.list
        val layout = state.layoutInfo
        val focused = rows.indexOfFirst { it.focused }
        val restore = entry.threadRestoreAnchor
        val anchor = layout.visibleItemsInfo.firstOrNull { it.index == state.firstVisibleItemIndex }
        // A request may not have been measured before the next cache snapshot arrives.
        // Keep its key, not an index from the older projection.
        when {
            restore != null && graphReady && focused >= 0 -> {
                val restored = rows.indexOfFirst { it.key == restore.first }
                val index = restored.takeIf { it >= 0 } ?: focused
                pendingAnchor = rows[index].key to if (restored >= 0) restore.second else 0
                floor = rows[index].key
                floorOffset = if (restored >= 0) restore.second.coerceAtLeast(0) else 0
                entry.threadRestoreAnchor = null
            }
            pendingAnchor == null && !entry.threadPositioned && focused >= 0 -> {
                // No suspending scroll: apply to the same data set in the next measure.
                // Until a real row has been measured, a new graph may move this index again.
                if (anchor?.key == rows[focused].key) entry.threadPositioned = true
                else if (!state.isScrollInProgress) pendingAnchor = rows[focused].key to 0
                else entry.threadPositioned = true
            }
            anchor?.key in previousKeys && anchor?.key !in keys && !state.isScrollInProgress -> {
                // A loading gap disappeared. Keep the next surviving visible note at its
                // actual pixel offset, rather than anchoring to the gap's obsolete index.
                layout.visibleItemsInfo.firstOrNull { it.key in keys }?.let { visible ->
                    pendingAnchor = visible.key.toString() to -visible.offset
                }
            }
        }
        pendingAnchor?.let { requested ->
            val index = rows.indexOfFirst { it.key == requested.first }
            if (index >= 0 && !state.isScrollInProgress) {
                beforeRequest = state.layoutInfo
                state.requestScrollToItem(index, requested.second)
            }
            else { pendingAnchor = null; entry.threadPositioned = true }
        }
        if (floor !in keys) { floor = rows.getOrNull(focused)?.key; floorOffset = 0 }
        tail = rows.dropWhile { it.key != floor }.map { it.key }
        heights.keys.retainAll(keys)
        previousKeys = keys
    }

    fun viewportModifier() = Modifier.layout { measurable, constraints ->
        viewport = constraints.maxHeight
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    fun rowModifier(key: String) = Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        heights[key] = placeable.height
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    }

    @Composable
    fun EndSpace() {
        Spacer(Modifier.fillMaxWidth().layout { _, constraints ->
            // If an earlier tail row was never measured, we are already below the entry
            // anchor. Do not manufacture extra space based on unknown off-screen heights.
            val extra = if (tail.isNotEmpty() && tail.all { it in heights })
                (viewport + floorOffset - tail.sumOf { heights[it] ?: 0 }).coerceAtLeast(0) else 0
            layout(constraints.maxWidth, constraints.constrainHeight(extra)) {}
        })
    }
}
