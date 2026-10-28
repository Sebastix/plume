package plume.sebastix.dev

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONObject

/** Public, signed NIP-09 requests only. Accessed on IO; retained tombstones prevent stale relay resurrection. */
internal class ReactionDeletions(context: Context) {
    private val prefs = context.getSharedPreferences("reaction-deletions", Context.MODE_PRIVATE)
    data class Record(val event: Event, val accepted: Boolean)
    private var cached: List<Record>? = null
    @Synchronized fun records(): List<Record> = cached ?: prefs.all.values.mapNotNull { raw ->
        runCatching {
            val json = JSONObject(raw as String)
            Record(Event.fromJson(json.getString("event")), json.optBoolean("accepted"))
        }.getOrNull()
    }.also { cached = it }
    fun targets(event: Event) = event.tags.filter { it.size > 1 && it[0] == "e" }.map { it[1] }.toSet()
    fun deleted(): Set<Pair<String, String>> = records().filter { it.accepted }.flatMap { record ->
        targets(record.event).map { record.event.pubKey to it }
    }.toSet()
    fun pending(author: String, ids: Set<String>): Event? = records().firstOrNull {
        !it.accepted && it.event.pubKey == author && targets(it.event) == ids
    }?.event
    @Synchronized fun put(event: Event, accepted: Boolean) {
        val existing = records()
        require(event.kind == 5 && targets(event).size <= 256)
        require(existing.any { it.event.id == event.id } || existing.size < 512 || existing.any { it.accepted }) { "Reaction deletion history is full" }
        val editor = prefs.edit().putString(event.id, JSONObject().put("event", event.toJson()).put("accepted", accepted).toString())
        val excess = (existing.count { it.event.id != event.id } + 1 - 512).coerceAtLeast(0)
        existing.filter { it.event.id != event.id && it.accepted }.sortedBy { it.event.createdAt }.take(excess)
            .forEach { editor.remove(it.event.id) }
        check(editor.commit()) { "Could not save reaction deletion" }
        cached = null
    }
}
