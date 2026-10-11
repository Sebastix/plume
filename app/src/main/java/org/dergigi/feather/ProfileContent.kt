package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl

// Tabs describe existing public activity, never actions that publish events.
internal val videoKinds = listOf(21, 22, 34235, 34236)
internal val readableKinds = listOf(1, 20, 9802, 30023) + videoKinds
internal val profileKinds = listOf(1, 7, 9802, 20, 10003, 30003, 32267, 30023, 3, 30000, 30617) + videoKinds

enum class ProfileTab(val kinds: List<Int>, val label: String) {
    POSTS(listOf(1), "Posts"), REPLIES(listOf(1), "Replies"),
    REACTIONS(listOf(7), "Reactions"), HIGHLIGHTS(listOf(9802), "Highlights"),
    PHOTOS(listOf(1, 20), "Photos"), VIDEOS(listOf(1, 21, 22, 34235, 34236), "Videos"),
    BOOKMARKS(listOf(10003, 30003), "Bookmarks"), APPS(listOf(32267), "Apps"),
    ARTICLES(listOf(30023), "Articles"), PEOPLE(listOf(3, 30000), "People"), PROJECTS(listOf(30617), "Projects");
    fun matches(event: Event): Boolean = event.kind in kinds && when (this) {
        POSTS -> !Protocol.isReply(event)
        REPLIES -> Protocol.isReply(event)
        PHOTOS -> MediaContent.attachments(event, 32).any { it.kind == MediaContent.Kind.IMAGE }
        VIDEOS -> MediaContent.attachments(event, 32).any { it.kind == MediaContent.Kind.VIDEO }
        BOOKMARKS -> bookmarkReferences(event).isNotEmpty()
        PEOPLE -> peopleReferences(event).isNotEmpty()
        APPS, PROJECTS -> !event.tagValue("d").isNullOrBlank()
        else -> true
    }
}

/** Only public tags are inspected. Encrypted list content is never parsed or requested. */
internal fun bookmarkReferences(event: Event): List<NostrContent.Reference> {
    if (event.kind !in listOf(10003, 30003)) return emptyList()
    return event.tags.mapNotNull { tag ->
        val value = tag.getOrNull(1) ?: return@mapNotNull null
        val relay = tag.getOrNull(2)?.let(Protocol::relay)
        when (tag[0]) {
            "e" -> Protocol.publicKey(value)?.let { NostrContent.Reference(it, relays = listOfNotNull(relay)) }
            "a" -> runCatching {
                val parts = value.split(':', limit = 3)
                require(parts.size == 3)
                val kind = parts[0].toInt()
                val author = Protocol.publicKey(parts[1]) ?: return@mapNotNull null
                NostrContent.Reference(value, author = author, kind = kind, relays = listOfNotNull(relay),
                    external = NAddress.create(kind, author, parts[2], relay = relay?.normalizeRelayUrl()))
            }.getOrNull()
            else -> null
        }
    }.distinctBy { it.id }
}

/** Old versions retained by paging must never resurrect removed public bookmarks. */
internal fun latestProfileEvents(events: List<Event>): List<Event> = events
    .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
    .distinctBy { event -> when {
        event.kind in listOf(3, 10003) -> "${event.kind}:${event.pubKey}"
        event.kind in listOf(30000, 30003, 30023, 30617, 34235, 34236, 32267) -> "${event.kind}:${event.pubKey}:${event.tagValue("d").orEmpty()}"
        else -> event.id
    } }

internal fun Event.tagValue(name: String): String? = tags.firstOrNull { it.size >= 2 && it[0] == name }?.get(1)

/** NIP-18 text-note reposts resolve via their e/p tags; content may be empty. */
internal fun repostTarget(event: Event): NostrContent.Reference? {
    if (event.kind != 6) return null
    val tag = event.tags.firstOrNull { it.size >= 2 && it[0] == "e" } ?: return null
    val id = Protocol.publicKey(tag[1]) ?: return null
    return NostrContent.Reference(id, relays = listOfNotNull(tag.getOrNull(2)?.let(Protocol::relay)),
        author = event.tagValue("p")?.let(Protocol::publicKey), kind = 1)
}

internal fun reactionTarget(event: Event): NostrContent.Reference? {
    if (event.kind != 7) return null
    val tag = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" } ?: return null
    val id = Protocol.publicKey(tag[1]) ?: return null
    val kind = event.tagValue("k")?.toIntOrNull()
    return NostrContent.Reference(id, relays = listOfNotNull(tag.getOrNull(2)?.let(Protocol::relay)),
        author = event.tags.lastOrNull { it.size >= 2 && it[0] == "p" }?.get(1)?.let(Protocol::publicKey),
        kind = kind, external = if (kind != null && kind !in readableKinds) com.vitorpamplona.quartz.nip19Bech32.entities.NNote.create(id) else null)
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
    3, 30000 -> peopleReferences(event)
    10003, 30003 -> bookmarkReferences(event)
    6 -> listOfNotNull(repostTarget(event))
    7 -> listOfNotNull(reactionTarget(event))
    9802 -> listOfNotNull(highlightAuthor(event)?.let { NostrContent.Reference(it, profile = true) }) +
        listOf("context", "comment").flatMap { NostrContent.tokens(event.tagValue(it).orEmpty(), event).mapNotNull { token -> token.reference } }
    else -> emptyList()
}

internal data class ProfilePhotos(
    val rows: List<List<Pair<Event, MediaContent.Attachment>>> = emptyList(),
    val urls: List<String> = emptyList(),
    val sources: List<Event> = emptyList(),
)

/** Public follow/list members only; encrypted content stays opaque. */
internal fun peopleReferences(event: Event): List<NostrContent.Reference> {
    if (event.kind !in listOf(3, 30000) || (event.kind == 30000 && event.tagValue("d") == "mute")) return emptyList()
    return event.tags.asSequence().filter { it.firstOrNull() == "p" }.mapNotNull { tag ->
        tag.getOrNull(1)?.let(Protocol::publicKey)?.let { key ->
            NostrContent.Reference(key, profile = true, relays = listOfNotNull(tag.getOrNull(2)?.let(Protocol::relay)))
        }
    }.distinctBy { it.id }.take(5000).toList()
}

/** Resolve public address tags by kind + author + identifier, never as event IDs. */
internal fun NostrContent.Reference.addressFilter(): com.vitorpamplona.quartz.nip01Core.relay.filters.Filter? {
    val parts = id.split(':', limit = 3)
    if (parts.size != 3) return null
    val addressKind = parts[0].toIntOrNull() ?: return null
    val addressAuthor = Protocol.publicKey(parts[1]) ?: return null
    if (addressKind !in readableKinds || addressKind !in 30000..39999) return null
    return com.vitorpamplona.quartz.nip01Core.relay.filters.Filter(
        kinds = listOf(addressKind), authors = listOf(addressAuthor), tags = mapOf("d" to listOf(parts[2])), limit = 1)
}
