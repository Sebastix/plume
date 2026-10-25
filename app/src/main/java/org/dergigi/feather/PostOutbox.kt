package org.dergigi.feather

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/** Durable delivery records, separate from drafts and evictable caches. Call only on IO. */
internal class PostOutbox(context: Context) {
    private val prefs by lazy { context.getSharedPreferences("post-outbox", Context.MODE_PRIVATE) }
    data class Record(val event: Event, val target: Event?)
    private var cached: List<Record>? = null
    private val pending = MutableStateFlow<Set<String>>(emptySet())
    val ids = pending.asStateFlow()

    @Synchronized fun records(): List<Record> = cached ?: prefs.all.values.map { raw ->
        val json = JSONObject(raw as String)
        val event = Event.fromJson(json.getString("event"))
        require(supported(event) && event.verify()) { "Invalid outbox event" }
        val target = json.optString("target").takeIf { it.isNotEmpty() }?.let { Event.fromJson(it) }
        require(target == null || target.verify()) { "Invalid outbox reply context" }
        Record(event, target)
    }.sortedWith(compareBy<Record> { it.event.createdAt }.thenBy { it.event.id }).also {
        cached = it
        pending.value = it.map { record -> record.event.id }.toSet()
    }

    @Synchronized fun put(event: Event, target: Event?) {
        require(supported(event) && event.verify()) { "Invalid outbox event" }
        require(target == null || target.verify()) { "Invalid outbox context" }
        val all = records()
        if (all.any { it.event.id == event.id }) return
        // Commit the undo and cancellation of any queued original together. An in-flight
        // original may already have reached a relay, so the deletion must still be delivered.
        val removed = if (event.kind == 5) {
            val ids = event.tags.filter { it.firstOrNull() == "e" }.mapNotNull { it.getOrNull(1) }.toSet()
            all.filter { it.event.pubKey == event.pubKey && it.event.kind in listOf(6, 7, 16) && it.event.id in ids }
                .map { it.event.id }.toSet()
        } else emptySet()
        val existing = all.filterNot { it.event.id in removed }
        require(existing.size < 128) { "Outbox is full. Reconnect to send queued actions first." }
        val raw = JSONObject().put("event", event.toJson()).put("target", target?.toJson().orEmpty()).toString()
        require(raw.toByteArray().size + prefs.all.filterKeys { it !in removed }.values.sumOf { (it as String).toByteArray().size } <= 16 * 1024 * 1024) {
            "Outbox is full. Reconnect to send queued actions first."
        }
        val editor = prefs.edit().putString(event.id, raw)
        removed.forEach { editor.remove(it) }
        check(editor.commit()) { "Could not save action to outbox" }
        cached = existing + Record(event, target)
        pending.value = cached!!.map { it.event.id }.toSet()
    }

    private fun supported(event: Event): Boolean = event.kind in listOf(1, 1111, 6, 7, 16) ||
        (event.kind == 5 && event.tags.any { it.firstOrNull() == "e" } &&
            event.tags.filter { it.firstOrNull() == "k" }.let { kinds ->
                kinds.isNotEmpty() && kinds.all { it.getOrNull(1) in listOf("6", "7", "16") }
            })

    @Synchronized fun remove(id: String) {
        val existing = records()
        if (existing.none { it.event.id == id }) return
        check(prefs.edit().remove(id).commit()) { "Could not update post outbox" }
        cached = existing.filterNot { it.event.id == id }
        pending.value = cached!!.map { it.event.id }.toSet()
    }
}
