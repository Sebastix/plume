package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter

internal val podcastTypes = listOf("podcast:item:guid", "podcast:guid")
internal val podcastKinds = listOf(1, 1111, 17)
internal data class PodcastReference(
    val id: String, val episode: Boolean, val url: String?,
    val parentId: String? = null, val feedUrl: String? = null,
)

/** NIP-73 external identities, including NIP-22's uppercase root scope. */
internal fun podcastReferences(event: Event): List<PodcastReference> {
    if (event.kind !in podcastKinds) return emptyList()
    val tagged = event.tags.asSequence().filter { it.firstOrNull() in listOf("i", "I") }.mapNotNull { tag ->
        val id = tag.getOrNull(1)?.takeIf { it.length <= 4096 } ?: return@mapNotNull null
        val type = podcastTypes.firstOrNull { id.startsWith("$it:") && id.length > it.length + 1 } ?: return@mapNotNull null
        if (event.kind == 17 && event.tags.none { it.firstOrNull() == "k" && it.getOrNull(1) == type }) return@mapNotNull null
        val hint = MediaContent.secureUrl(tag.getOrNull(2))
            ?: MediaContent.secureUrl(id.removePrefix("$type:"))
        PodcastReference(id, type == podcastTypes.first(), hint?.let { FountainLinks.canonical(it) ?: it })
    }.distinctBy { it.id }.take(8).toList()
    // An episode often carries its parent show's ID too; render the episode once.
    // Retain the parent context even though its separate card is suppressed.
    // A bare episode GUID is not sufficient to identify its feed unambiguously.
    val parent = tagged.filterNot { it.episode }.singleOrNull()
    val primary = tagged.filter { reference -> reference.episode || tagged.none { it.episode } }
        .map { reference -> if (reference.episode) reference.copy(parentId = parent?.id, feedUrl = parent?.url) else reference }
    val links = FountainLinks.parse(event.content).map { PodcastReference(it, "/episode/" in it, it) }
    return (primary + links).distinctBy { it.url ?: it.id }.take(2)
}

internal fun isPodcastActivity(event: Event): Boolean =
    (event.kind != 17 || event.content.trim() != "-") && podcastReferences(event).isNotEmpty()

/** Separate tag filters keep sparse podcast activity from losing to ordinary recent notes. */
internal fun Filter.podcastTagFilters(): List<Filter> = buildList {
    for (kind in kinds.orEmpty().filter { it in podcastKinds }) {
        val tagsToRead = if (kind == 1111) listOf("K", "k") else listOf("k")
        for (tag in tagsToRead) add(Filter(authors = authors, kinds = listOf(kind),
            tags = mapOf(tag to podcastTypes), limit = limit, until = until, since = since))
    }
}
