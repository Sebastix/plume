package org.dergigi.feather

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.emoji2.emojipicker.EmojiPickerView
import androidx.emoji2.emojipicker.RecentEmojiProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale

private data class SearchEmoji(val emoji: String, val name: String, val words: List<String>, val image: String? = null) {
    val choice get() = ReactionChoice(emoji, image)
}
private val wordBreaks = Regex("[^\\p{L}\\p{N}]+")
private fun words(value: String) = value.lowercase(Locale.ROOT).split(wordBreaks).filter { it.isNotEmpty() }

/** A single, lazily loaded catalog. No network requests or per-query retained cache. */
private object EmojiSearchIndex {
    private val mutex = Mutex()
    private var entries: List<SearchEmoji>? = null

    suspend fun search(context: Context, query: String): List<SearchEmoji> {
        val catalog = withContext(Dispatchers.IO) {
            mutex.withLock {
                entries ?: context.assets.open("emoji-search.tsv").bufferedReader().useLines { lines ->
                    lines.map { line ->
                        val fields = line.split('\t', limit = 3)
                        SearchEmoji(fields[0], fields[1], words(fields[1] + " " + fields.getOrElse(2) { "" }).distinct())
                    }.toList()
                }.also { entries = it }
            }
        }
        return withContext(Dispatchers.Default) {
            val tokens = words(query)
            val exact = query.trim().replace("\uFE0F", "")
            catalog.asSequence().filter { item ->
                item.emoji.replace("\uFE0F", "") == exact ||
                    (tokens.isNotEmpty() && tokens.all { token -> item.words.any { it.startsWith(token) } })
            }.sortedBy { item ->
                when {
                    item.emoji.replace("\uFE0F", "") == exact -> 0
                    item.name.equals(query.trim(), ignoreCase = true) -> 1
                    "skin tone" in item.name -> 3
                    else -> 2
                }
            }.take(200).toList()
        }
    }
}

private data class EmojiSearchResult(val query: String, val items: List<SearchEmoji>?, val failed: Boolean = false)

@Composable
internal fun SearchableEmojiPicker(recent: List<String>, custom: List<ReactionChoice>, modifier: Modifier = Modifier, onPick: (ReactionChoice) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val context = LocalContext.current.applicationContext
    val latestPick by rememberUpdatedState(onPick)
    val result by produceState(EmojiSearchResult("", null), query, custom) {
        if (query.isBlank()) {
            value = EmojiSearchResult(query, null)
        } else {
            delay(100)
            value = try {
                val matches = withContext(Dispatchers.Default) {
                    val tokens = words(query)
                    custom.filter { item -> tokens.isNotEmpty() && tokens.all { token ->
                        words(item.content).any { it.startsWith(token) }
                    } }.take(200).map { SearchEmoji(it.content, it.content.trim(':'), emptyList(), it.image) }
                }
                EmojiSearchResult(query, (matches + EmojiSearchIndex.search(context, query)).take(200))
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                EmojiSearchResult(query, null, failed = true)
            }
        }
    }
    Column(modifier) {
        OutlinedTextField(value = query, onValueChange = { query = it.take(100) },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 8.dp),
            placeholder = { Text("Search emojis") }, singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, "Search emojis") },
            trailingIcon = {
                if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Clear search") }
            }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        if (query.isBlank() && custom.isNotEmpty()) {
            LazyRow(Modifier.fillMaxWidth(), contentPadding = PaddingValues(horizontal = 8.dp)) {
                items(custom, key = { it.key }) { choice ->
                    IconButton(onClick = { latestPick(choice) }, modifier = Modifier.size(48.dp)) {
                        RemoteImage(choice.image!!, Modifier.size(26.dp), "React with ${choice.content}", crop = false)
                    }
                }
            }
        }
        Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
            if (query.isBlank()) {
                AndroidView(modifier = Modifier.fillMaxSize(), factory = { viewContext ->
                    EmojiPickerView(viewContext).apply {
                        // Match search's adaptive spacing instead of stretching seven columns.
                        addOnLayoutChangeListener { _, left, _, right, _, _, _, _, _ ->
                            val columns = ((right - left) / (48 * resources.displayMetrics.density)).toInt().coerceAtLeast(1)
                            if (emojiGridColumns != columns) emojiGridColumns = columns
                        }
                        // Only signed reactions populate recents; browsing is not a selection history.
                        setRecentEmojiProvider(object : RecentEmojiProvider {
                            override suspend fun getRecentEmojiList() = recent
                            override fun recordSelection(emoji: String) = Unit
                        })
                        setOnEmojiPickedListener { latestPick(ReactionChoice(it.emoji)) }
                    }
                })
            } else if (result.query != query || (result.items == null && !result.failed)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else if (result.failed) {
                Text("Emoji search unavailable", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (result.items.isNullOrEmpty()) {
                Text("No emojis found", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                // Reset only search results, never the feed or the note behind the sheet.
                key(result.query) {
                    LazyVerticalGrid(columns = GridCells.Adaptive(48.dp), modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                        items(result.items.orEmpty(), key = { it.choice.key }) { item ->
                            IconButton(onClick = { latestPick(item.choice) }, modifier = Modifier.size(48.dp)
                                .semantics { contentDescription = "React with ${item.name}" }) {
                                if (item.image == null) Text(item.emoji, fontSize = 26.sp)
                                else RemoteImage(item.image, Modifier.size(26.dp), item.name, crop = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
