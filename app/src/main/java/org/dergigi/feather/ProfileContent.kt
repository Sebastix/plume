package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress

// Tabs describe existing public activity, never actions that publish events.
enum class ProfileTab(val kind: Int, val label: String) {
    POSTS(1, "Posts"), REPLIES(1, "Replies"), REACTIONS(7, "Reactions"), HIGHLIGHTS(9802, "Highlights");
    fun matches(event: Event): Boolean = event.kind == kind && when (this) {
        POSTS -> !Protocol.isReply(event)
        REPLIES -> Protocol.isReply(event)
        else -> true
    }
}

internal fun Event.tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

internal fun reactionTarget(event: Event): NostrContent.Reference? {
    if (event.kind != 7) return null
    val tag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" } ?: return null
    val id = Protocol.publicKey(tag[1]) ?: return null
    val kind = event.tagValue("k")?.toIntOrNull()
    return NostrContent.Reference(id, relays = listOfNotNull(tag.getOrNull(2)?.let(Protocol::relay)),
        author = event.tags.lastOrNull { it.size >= 2 && it[0] == "p" }?.get(1)?.let(Protocol::publicKey),
        kind = kind, external = if (kind != null && kind !in listOf(1, 9802)) com.vitorpamplona.quartz.nip19Bech32.entities.NNote.create(id) else null)
}

internal fun highlightAuthor(event: Event): String? = if (event.kind != 9802) null else (
    event.tags.firstOrNull { it.firstOrNull() == "p" && it.getOrNull(3) == "author" }?.getOrNull(1)
        ?: event.tags.firstOrNull { it.firstOrNull() == "p" && it.getOrNull(3) !in listOf("mention", "editor") }?.getOrNull(1)
        ?: event.tagValue("a")?.split(':', limit = 3)?.getOrNull(1)
    )?.let(Protocol::publicKey)

internal data class HighlightSource(val label: String, val url: String? = null, val reference: NostrContent.Reference? = null)
internal fun highlightSource(event: Event): HighlightSource? {
    event.tags.firstOrNull { it.firstOrNull() == "r" && it.getOrNull(2) != "mention" }?.getOrNull(1)?.let { raw ->
        val uri = runCatching { java.net.URI(raw) }.getOrNull()
        if (uri?.scheme in listOf("https", "http") && !uri?.host.isNullOrBlank()) return HighlightSource(uri!!.host.removePrefix("www."), url = raw)
    }
    event.tagValue("e")?.let(Protocol::publicKey)?.let { return HighlightSource("nostr post", reference = NostrContent.Reference(it)) }
    return runCatching {
        val parts = event.tagValue("a")?.split(':', limit = 3) ?: return null
        val kind = parts[0].toInt()
        val author = Protocol.publicKey(parts[1]) ?: return null
        val pointer = NAddress.create(kind, author, parts[2], relay = null)
        HighlightSource(if (kind == 30023) "blog post" else "nostr post", reference = NostrContent.Reference(author, external = pointer))
    }.getOrNull()
}

internal fun activityReferences(event: Event): List<NostrContent.Reference> = when (event.kind) {
    7 -> listOfNotNull(reactionTarget(event))
    9802 -> listOfNotNull(highlightAuthor(event)?.let { NostrContent.Reference(it, profile = true) }) +
        listOf("context", "comment").flatMap { NostrContent.tokens(event.tagValue(it).orEmpty(), event).mapNotNull { token -> token.reference } }
    else -> emptyList()
}
