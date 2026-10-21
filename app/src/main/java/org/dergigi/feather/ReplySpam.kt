package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.Nip19Parser

/** Local thread projection only. Input is the bounded, verified conversation cache, on IO.
 * Keep the events: detection must never evict content, publish mutes, or trigger relay requests.
 * Include author and all tags so unrelated authors and different reply targets cannot implicate
 * each other. Recompute from cached evidence when reopening a thread, not from relay deliveries.
 */
internal fun suspectedReplySpam(events: Collection<Event>, trustedAuthors: Set<String>): Set<String> {
    data class Fingerprint(val author: String, val content: String, val tags: List<List<String>>)
    return events.asSequence().take(320).distinctBy { it.id }
        .filter { it.kind in listOf(1, 1111, 1244) && it.pubKey !in trustedAuthors }
        .filter { it.content.length >= 60 && ThreadRelations.parent(it) != null }
        .filterNot { it.content.length < 180 && it.content.startsWith("nostr:") &&
            Nip19Parser.uriToRoute(it.content) != null }
        .groupBy { Fingerprint(it.pubKey, it.content, it.tags.map { tag -> tag.toList() }) }
        .values.asSequence().filter { it.size >= 5 }.flatten().map { it.id }.toSet()
}
