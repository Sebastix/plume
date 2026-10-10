package org.dergigi.feather

import android.os.Bundle
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.util.UUID

/** Each visit owns its UI state; opening another reader never overwrites it. */
internal class ReaderEntry(
    val screen: String,
    val target: String? = null,
    val key: String = UUID.randomUUID().toString(),
    index: Int = 0,
    offset: Int = 0,
    tab: ProfileTab = ProfileTab.POSTS,
    toolbar: Boolean = true,
) {
    val list = LazyListState(index, offset)
    var tab by mutableStateOf(tab)
    var toolbar by mutableStateOf(toolbar)
    var reference: NostrContent.Reference? = null
    // Event objects are shared with the repository, never serialized into saved instance state.
    val notes = mutableMapOf<ProfileTab, List<Event>>()
    var tabs: List<ProfileTab> = emptyList()
    var bookmarks: List<Pair<Event, NostrContent.Reference>> = emptyList()
    var photos = ProfilePhotos()
}

internal class ReaderNavigation(entries: List<ReaderEntry> = listOf(ReaderEntry("feed"))) {
    val entries = mutableStateListOf<ReaderEntry>().apply { addAll(entries) }
    val current get() = entries.last()
    fun push(entry: ReaderEntry) {
        if (entry.screen == current.screen && entry.target == current.target) return
        entries.add(entry)
        // Bound retained event references; older destinations still restore from the repository.
        entries.dropLast(8).forEach { it.notes.clear(); it.bookmarks = emptyList(); it.photos = ProfilePhotos() }
    }
    fun back() { if (entries.size > 1) entries.removeAt(entries.lastIndex) }
    fun home() { entries.clear(); entries.add(ReaderEntry("feed")) }

    companion object {
        val saver = Saver<ReaderNavigation, ArrayList<Bundle>>(
            save = { navigation -> ArrayList(navigation.entries.map { entry -> Bundle().apply {
                putString("screen", entry.screen); putString("target", entry.target); putString("key", entry.key)
                putInt("index", entry.list.firstVisibleItemIndex); putInt("offset", entry.list.firstVisibleItemScrollOffset)
                putString("tab", entry.tab.name); putBoolean("toolbar", entry.toolbar)
                putStringArrayList("relays", ArrayList(entry.reference?.relays.orEmpty()))
                putString("author", entry.reference?.author)
                entry.reference?.kind?.let { putInt("kind", it) }
            } }) },
            restore = { saved -> ReaderNavigation(saved.map { bundle ->
                ReaderEntry(bundle.getString("screen")!!, bundle.getString("target"), bundle.getString("key")!!,
                    bundle.getInt("index"), bundle.getInt("offset"), ProfileTab.valueOf(bundle.getString("tab")!!),
                    bundle.getBoolean("toolbar")).also { entry ->
                    if (entry.screen == "profile" || entry.screen == "thread") entry.reference =
                        NostrContent.Reference(entry.target!!, profile = entry.screen == "profile", relays = bundle.getStringArrayList("relays").orEmpty(),
                            author = bundle.getString("author"), kind = if (bundle.containsKey("kind")) bundle.getInt("kind") else null)
                }
            }) },
        )
    }
}
