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
        require(event.kind in listOf(1, 1111) && event.verify()) { "Invalid outbox event" }
        val target = json.optString("target").takeIf { it.isNotEmpty() }?.let { Event.fromJson(it) }
        require(target == null || target.verify()) { "Invalid outbox reply context" }
        Record(event, target)
    }.sortedWith(compareBy<Record> { it.event.createdAt }.thenBy { it.event.id }).also {
        cached = it
        pending.value = it.map { record -> record.event.id }.toSet()
    }

    @Synchronized fun put(event: Event, target: Event?) {
        val existing = records()
        if (existing.any { it.event.id == event.id }) return
        require(existing.size < 128) { "Outbox is full. Your draft is saved; reconnect to send queued posts first." }
        val raw = JSONObject().put("event", event.toJson()).put("target", target?.toJson().orEmpty()).toString()
        require(raw.toByteArray().size + prefs.all.values.sumOf { (it as String).toByteArray().size } <= 16 * 1024 * 1024) {
            "Outbox is full. Your draft is saved."
        }
        check(prefs.edit().putString(event.id, raw).commit()) { "Could not save post to outbox" }
        cached = existing + Record(event, target)
        pending.value = cached!!.map { it.event.id }.toSet()
    }

    @Synchronized fun remove(id: String) {
        val existing = records()
        if (existing.none { it.event.id == id }) return
        check(prefs.edit().remove(id).commit()) { "Could not update post outbox" }
        cached = existing.filterNot { it.event.id == id }
        pending.value = cached!!.map { it.event.id }.toSet()
    }
}
