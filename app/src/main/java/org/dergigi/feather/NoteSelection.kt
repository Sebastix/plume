package org.dergigi.feather

import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import com.vitorpamplona.quartz.nip01Core.core.Event

internal val LocalSelectionReply = staticCompositionLocalOf<(Event, String) -> Unit> { { _, _ -> } }
private data object ReplySelectionAction

/** Keep Android's normal selection actions and add a reply scoped to this exact event. */
@Composable
internal fun NoteSelection(note: Event, content: @Composable () -> Unit) {
    key(note.id) {
        val selection = rememberSelectionState()
        val reply = LocalSelectionReply.current
        SelectionContainer(state = selection, modifier = Modifier.appendTextContextMenuComponents {
            if (selection.selectedTexts.any { it.text.isNotBlank() }) {
                item(ReplySelectionAction, "Reply", R.drawable.reply) {
                    // Bound the copy before joining potentially large article selections on main.
                    val parts = selection.selectedTexts
                    val length = parts.sumOf { it.length.toLong() } + (parts.size - 1).coerceAtLeast(0)
                    if (length > 8000) {
                        AppMessages.show("Select a shorter passage to quote in your reply.")
                        return@item
                    }
                    val text = parts.joinToString("\n") { it.text }
                    if (text.isNotBlank()) {
                        close()
                        selection.clear()
                        reply(note, text)
                    }
                }
            }
        }, content = content)
    }
}
