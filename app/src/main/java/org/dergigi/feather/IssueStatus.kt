package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter

internal val issueStatusKinds = listOf(1630, 1631, 1632, 1633)

internal fun issueRepositories(issue: Event): List<String> = issue.tags
    .filter { it.firstOrNull() == "a" }
    .mapNotNull { it.getOrNull(1) }
    .filter { issueRepositoryFilter(it) != null }.distinct().take(4)

internal fun issueRepositoryFilter(address: String): Filter? {
    val parts = address.split(':', limit = 3)
    if (parts.size != 3 || parts[0] != "30617" || Protocol.publicKey(parts[1]) == null || parts[2].isBlank()) return null
    return Filter(kinds = listOf(30617), authors = listOf(parts[1]), tags = mapOf("d" to listOf(parts[2])), limit = 1)
}

internal fun issueStatusAuthors(issue: Event, repositories: List<Event>): List<String> =
    (listOf(issue.pubKey) + issueRepositories(issue).map { it.split(':', limit = 3)[1] } +
        repositories.flatMap { repo -> repo.tags.filter { it.firstOrNull() == "maintainers" }
            .flatMap { it.drop(1) }.mapNotNull(Protocol::publicKey) }).distinct().take(64)

/** Only the root issue's author and its repositories' maintainers can change its status. */
internal fun latestIssueStatus(issue: Event, repositories: List<Event>, events: List<Event>): Int {
    val authors = issueStatusAuthors(issue, repositories).toSet()
    return events.asSequence().filter { status ->
        status.kind in issueStatusKinds && status.pubKey in authors && status.createdAt >= issue.createdAt &&
            status.tags.any { it.firstOrNull() == "e" && it.getOrNull(1) == issue.id && it.getOrNull(3) == "root" }
    }.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).firstOrNull()?.kind ?: 1630
}
