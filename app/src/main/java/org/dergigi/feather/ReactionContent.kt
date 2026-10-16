package org.dergigi.feather

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val shortcode = Regex(":([a-zA-Z0-9_-]+):")

/** NIP-25 reactions reference one NIP-30 emoji by its exact shortcode. */
internal fun customReactionUrl(event: Event): String? {
    if (event.kind != 7) return null
    val name = shortcode.matchEntire(event.content.trim())?.groupValues?.get(1) ?: return null
    return event.tags.firstNotNullOfOrNull { tag ->
        if (tag.getOrNull(0) == "emoji" && tag.getOrNull(1) == name)
            MediaContent.secureUrl(tag.getOrNull(2)) else null
    }
}

@Composable
internal fun ReactionContent(note: Event) {
    val reaction = remember(note.id) { note.content.trim() }
    val url by produceState<String?>(null, note.id) {
        value = withContext(Dispatchers.Default) { customReactionUrl(note) }
    }
    var loaded by remember(note.id, url) { mutableStateOf(false) }
    if (reaction.isEmpty() || reaction == "+") {
        Icon(Icons.Default.Favorite, stringResource(R.string.ui_liked), tint = MaterialTheme.colorScheme.primary)
    } else Box {
        // Preserve the meaning while loading and if the server or decoder fails.
        if (!loaded) Text(if (reaction == "-") "−" else reaction.take(80),
            style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (url != null) RemoteImage(url, Modifier.size(28.dp), reaction, crop = false,
            onImageSize = { _, _ -> loaded = true })
    }
}
