package org.dergigi.feather

import android.content.Context
import android.util.AtomicFile
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A small access-ordered membership index. Signed events stay in the existing event database. */
internal class RecentThreads(context: Context, scope: CoroutineScope) {
    private data class Entry(val notes: List<String>, val deletions: List<String>)
    private val file = AtomicFile(File(context.filesDir, "recent-threads.json"))
    private val entries = linkedMapOf<String, Entry>()
    private val memory = LinkedHashMap<String, Event>(128, .75f, true)
    private var memoryBytes = 0L
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val hex = Regex("[0-9a-f]{64}")

    init {
        // Always called on the repository IO scope. Coalesce writes without delaying display.
        scope.launch {
            for (ignored in writes) {
                delay(250)
                val json = synchronized(this@RecentThreads) {
                    JSONArray().apply { entries.forEach { (target, entry) ->
                        put(JSONObject().put("target", target).put("notes", JSONArray(entry.notes))
                            .put("deletions", JSONArray(entry.deletions)))
                    } }.toString()
                }
                var stream: java.io.FileOutputStream? = null
                try {
                    stream = file.startWrite()
                    stream.write(json.toByteArray(Charsets.UTF_8))
                    file.finishWrite(stream)
                } catch (e: Exception) {
                    file.failWrite(stream)
                    android.util.Log.w("RecentThreads", "Could not save thread history", e)
                }
            }
        }
    }

    /** Run before startup pruning and before accepting network events. Never called on main. */
    @Synchronized
    fun load() {
        runCatching {
            val bytes = file.openRead().use { require(it.channel.size() <= 1_048_576); it.readBytes() }
            val array = JSONArray(bytes.toString(Charsets.UTF_8))
            for (index in (array.length() - 8).coerceAtLeast(0) until array.length()) {
                val row = array.optJSONObject(index) ?: continue
                val target = row.optString("target").takeIf { it.length <= 1024 && (hex.matches(it) || it.split(':', limit = 3).let { parts ->
                    parts.size == 3 && parts[0].toIntOrNull() in 30000..39999 && hex.matches(parts[1])
                }) } ?: continue
                fun ids(key: String, limit: Int): List<String> {
                    val values = row.optJSONArray(key) ?: return emptyList()
                    return (0 until minOf(values.length(), limit)).map { values.optString(it) }.filter(hex::matches).distinct()
                }
                entries[target] = Entry(ids("notes", 320), ids("deletions", 256))
            }
        }
    }

    @Synchronized
    fun touch(target: String) {
        entries[target] = entries.remove(target) ?: Entry(emptyList(), emptyList())
        while (entries.size > 8) entries.remove(entries.keys.first())
        writes.trySend(Unit)
    }

    @Synchronized
    fun ids(target: String): List<String> = entries[target]?.notes.orEmpty()

    @Synchronized
    fun protectedIds(): Set<String> = entries.values.flatMap { it.notes + it.deletions }.toSet()

    @Synchronized
    fun events(ids: Collection<String>): List<Event> = ids.mapNotNull { memory[it] }.filterNot(::expired)

    @Synchronized
    fun remember(target: String, notes: List<Event>, deletions: List<Event> = emptyList()) {
        val previous = entries[target] ?: return // Only explicitly opened threads enter history.
        val next = Entry((notes.filterNot(::expired).map { it.id } + previous.notes).distinct().take(320),
            (deletions.map { it.id } + previous.deletions).distinct().take(256))
        entries[target] = next
        (notes + deletions).filterNot(::expired).forEach { event ->
            memory.remove(event.id)?.let { memoryBytes -= weight(it) }
            memory[event.id] = event; memoryBytes += weight(event)
        }
        while (memory.size > 512 || memoryBytes > 4 * 1024 * 1024) {
            memory.remove(memory.keys.first())?.let { memoryBytes -= weight(it) }
        }
        if (next != previous) writes.trySend(Unit)
    }

    @Synchronized
    fun deletionIds(target: String): List<String> = entries[target]?.deletions.orEmpty()

    private fun weight(event: Event): Long = 1024L + event.content.length * 2L + event.tags.sumOf { tag -> tag.sumOf { it.length * 2L + 32 } }
    private fun expired(event: Event): Boolean = event.tagValue("expiration")?.toLongOrNull()?.let { it <= System.currentTimeMillis() / 1000 } == true
}
