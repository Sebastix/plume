package plume.sebastix.dev

import com.vitorpamplona.quartz.nip01Core.core.Event

/** NIP-65 routing. Unknown, invalid, or unselected routes fall back to automatically selected base relays. */
object Outbox {
    data class RelayList(val read: List<String>, val write: List<String>)

    fun parse(event: Event): RelayList {
        fun urls(marker: String) = event.tags.asSequence()
            .filter { it.size >= 2 && it[0] == "r" && (it.size == 2 || it[2] == marker) }
            .mapNotNull { Protocol.relay(it[1]) }.distinct().toList()
        return RelayList(urls("read").take(4), urls("write"))
    }

    fun readRoutes(authors: List<String>, lists: Map<String, RelayList>, fallback: List<String>, maxDiscovered: Int = 8, allowed: Set<String>? = null): Map<String, List<String>> {
        val keys = authors.distinct()
        val candidates = keys.flatMap { lists[it]?.write.orEmpty() }.distinct().filter { it !in fallback && (allowed == null || it in allowed) }.toMutableSet()
        val selected = fallback.toMutableSet()
        // Prefer relays covering the most authors; stable tie-breaking avoids connection churn.
        val coverage = keys.associateWith { key -> lists[key]?.write.orEmpty().count { it in selected } }.toMutableMap()
        repeat(maxDiscovered) {
            val best = candidates.sorted().maxByOrNull { url -> keys.count { (coverage[it] ?: 0) < 2 && url in lists[it]?.write.orEmpty() } }
            if (best != null && keys.any { (coverage[it] ?: 0) < 2 && best in lists[it]?.write.orEmpty() }) {
                selected += best
                candidates -= best
                keys.filter { best in lists[it]?.write.orEmpty() }.forEach { coverage[it] = (coverage[it] ?: 0) + 1 }
            }
        }
        val result = linkedMapOf<String, MutableList<String>>()
        keys.forEach { key ->
            val routes = lists[key]?.write.orEmpty().filter { it in selected }.take(2).ifEmpty { fallback }
            routes.forEach { result.getOrPut(it) { mutableListOf() }.add(key) }
        }
        return result
    }

}
