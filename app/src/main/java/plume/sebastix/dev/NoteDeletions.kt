package plume.sebastix.dev

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import org.json.JSONArray

/** Bounded public NIP-09 history, independent of feed snapshots and ordinary event eviction. IO only. */
internal class NoteDeletions(context: Context) {
    private val prefs = context.getSharedPreferences("note-deletions", Context.MODE_PRIVATE)
    private var requests = emptyList<Event>()
    private var ids = emptySet<Pair<String, String>>()
    private var addresses = emptyMap<Pair<String, String>, Long>()

    @Synchronized fun load(cached: List<Event>) {
        val saved = runCatching {
            val json = JSONArray(prefs.getString("requests", "[]"))
            (0 until minOf(json.length(), 512)).mapNotNull { i ->
                runCatching { Event.fromJson(json.getString(i)) }.getOrNull()
            }
        }.getOrDefault(emptyList())
        replace((saved + cached).filter { it.kind == 5 && runCatching { it.verify() }.getOrDefault(false) })
    }

    /** Caller has verified the signature. Do not infer authorization from the request's target tags. */
    @Synchronized fun put(request: Event) {
        if (request.kind == 5 && requests.none { it.id == request.id }) replace(requests + request)
    }

    @Synchronized fun hides(event: Event): Boolean = event.kind != 5 &&
        ((event.pubKey to event.id) in ids || ThreadRelations.address(event)?.let { address ->
            addresses[event.pubKey to address]?.let { event.createdAt <= it }
        } == true)

    private fun replace(events: List<Event>) {
        var bytes = 0
        requests = events.distinctBy { it.id }.sortedByDescending { it.createdAt }.take(512).filter {
            bytes += it.toJson().toByteArray(Charsets.UTF_8).size
            bytes <= 1_048_576
        }
        ids = requests.flatMap { request -> request.tags.filter { it.size > 1 && it[0] == "e" }
            .map { request.pubKey to it[1] } }.toSet()
        addresses = buildMap<Pair<String, String>, Long> {
            requests.forEach { request -> request.tags.filter { it.size > 1 && it[0] == "a" }.forEach {
                val key = request.pubKey to it[1]
                put(key, maxOf(get(key) ?: Long.MIN_VALUE, request.createdAt))
            } }
        }
        // Keep the in-memory protection even if Android cannot currently write storage.
        if (!prefs.edit().putString("requests", JSONArray(requests.map { it.toJson() }).toString()).commit())
            android.util.Log.w("NoteDeletions", "Could not persist deletion history")
    }
}
