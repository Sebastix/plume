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
    val feedPages = mutableMapOf<ProfileTab, ReaderEntry>()
    fun feedPage(category: ProfileTab): ReaderEntry = feedPages.getOrPut(category) { ReaderEntry("feed", tab = category) }
    var searchQuery by mutableStateOf("")
    var tab by mutableStateOf(tab)
    var toolbar by mutableStateOf(toolbar)
    var reference: NostrContent.Reference? = null
    // Event objects are shared with the repository, never serialized into saved instance state.
    val notes = mutableMapOf<ProfileTab, List<Event>>()
    var conversation: Conversation? = null
    var threadPositioned = index != 0 || offset != 0
    var threadRestoreAnchor: Pair<String, Int>? = null
    var tabs: List<ProfileTab> = emptyList()
    var bookmarks: List<Pair<Event, NostrContent.Reference>> = emptyList()
    var people: List<Pair<Event, NostrContent.Reference>> = emptyList()
    var photos = ProfilePhotos()
}

internal class ReaderNavigation(entries: List<ReaderEntry> = listOf(ReaderEntry("feed"))) {
    val entries = mutableStateListOf<ReaderEntry>().apply { addAll(entries) }
    val current get() = entries.last()
    fun push(entry: ReaderEntry) {
        if (entry.screen == current.screen && entry.target == current.target) return
        entries.add(entry)
        // Bound retained event references; older destinations still restore from the repository.
        entries.dropLast(8).forEach { it.notes.clear(); it.conversation = null; it.bookmarks = emptyList(); it.people = emptyList(); it.photos = ProfilePhotos() }
    }
    fun back() { if (entries.size > 1) entries.removeAt(entries.lastIndex) }
    fun home() { entries.clear(); entries.add(ReaderEntry("feed")) }

    companion object {
        val saver = Saver<ReaderNavigation, ArrayList<Bundle>>(
            save = { navigation -> ArrayList(navigation.entries.map { entry -> Bundle().apply {
                putString("screen", entry.screen); putString("target", entry.target); putString("key", entry.key)
                putInt("index", entry.list.firstVisibleItemIndex); putInt("offset", entry.list.firstVisibleItemScrollOffset)
                putString("tab", entry.tab.name); putBoolean("toolbar", entry.toolbar)
                putBoolean("threadPositioned", entry.threadPositioned)
                if (entry.screen == "thread") {
                    val anchor = entry.list.layoutInfo.visibleItemsInfo.firstOrNull()?.key as? String
                    putString("threadAnchor", anchor ?: entry.threadRestoreAnchor?.first)
                    putInt("threadAnchorOffset", entry.threadRestoreAnchor?.second ?: entry.list.firstVisibleItemScrollOffset)
                }
                putStringArrayList("feedTabs", ArrayList(entry.tabs.map { it.name }))
                entry.feedPages.forEach { (category, page) -> putBundle("feed:${category.name}", Bundle().apply {
                    putString("key", page.key); putInt("index", page.list.firstVisibleItemIndex)
                    putInt("offset", page.list.firstVisibleItemScrollOffset); putBoolean("toolbar", page.toolbar)
                }) }
                putStringArrayList("relays", ArrayList(entry.reference?.relays.orEmpty()))
                putString("author", entry.reference?.author)
                entry.reference?.kind?.let { putInt("kind", it) }
            } }) },
            restore = { saved -> ReaderNavigation(saved.map { bundle ->
                ReaderEntry(bundle.getString("screen")!!, bundle.getString("target"), bundle.getString("key")!!,
                    bundle.getInt("index"), bundle.getInt("offset"), ProfileTab.valueOf(bundle.getString("tab")!!),
                    bundle.getBoolean("toolbar")).also { entry ->
                    entry.threadPositioned = bundle.getBoolean("threadPositioned")
                    entry.threadRestoreAnchor = bundle.getString("threadAnchor")?.let { it to bundle.getInt("threadAnchorOffset") }
                    entry.tabs = bundle.getStringArrayList("feedTabs").orEmpty().mapNotNull { name -> ProfileTab.entries.find { it.name == name } }
                    if (entry.screen == "feed") ProfileTab.entries.forEach { category ->
                        bundle.getBundle("feed:${category.name}")?.let { page ->
                            entry.feedPages[category] = ReaderEntry("feed", key = page.getString("key") ?: UUID.randomUUID().toString(),
                                index = page.getInt("index"), offset = page.getInt("offset"), tab = category, toolbar = page.getBoolean("toolbar"))
                        }
                    }
                    if (entry.screen == "profile" || entry.screen == "thread") entry.reference =
                        NostrContent.Reference(entry.target!!, profile = entry.screen == "profile", relays = bundle.getStringArrayList("relays").orEmpty(),
                            author = bundle.getString("author"), kind = if (bundle.containsKey("kind")) bundle.getInt("kind") else null)
                }
            }) },
        )
    }
}
