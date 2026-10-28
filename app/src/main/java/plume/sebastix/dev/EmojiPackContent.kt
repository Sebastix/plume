package plume.sebastix.dev

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun EmojiPackContent(event: Event) {
    val emojis by produceState<List<ReactionChoice>>(emptyList(), event.id) {
        value = withContext(Dispatchers.Default) { collectionEmojis(event) }
    }
    val gallery = LocalOpenGallery.current
    Text(event.tagValue("title")?.takeIf { it.isNotBlank() } ?: event.tagValue("d").orEmpty(), style = MaterialTheme.typography.titleMedium)
    LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(vertical = 8.dp)) {
        itemsIndexed(emojis, key = { _, item -> item.key }) { index, item ->
            IconButton(onClick = { gallery(emojis.mapNotNull { it.image }, index) }, modifier = Modifier.size(48.dp)) {
                RemoteImage(item.image, Modifier.size(32.dp), item.content, crop = false)
            }
        }
    }
}
