package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MissingFollowList : IllegalStateException("No existing follow list found")

/** Apply one explicit delta to a freshly discovered, complete NIP-02 event. */
internal class FollowLists(private val repo: FeatherRepository, private val requests: SignerRequests) {
    private val edits = Mutex()
    private val queries = ProfileRelayQuery()
    private val newest = compareByDescending<Event> { it.createdAt }.thenBy { it.id }

    suspend fun change(identity: NostrSignerExternal, target: String, follow: Boolean, allowCreate: Boolean = false) =
        withContext(Dispatchers.IO) {
            edits.withLock {
                val owner = identity.pubKey
                fun current() = check(repo.state.value.publicKey == owner) { "Account changed" }
                current()
                require(Protocol.publicKey(target) == target && target != owner)
                suspend fun discover(kind: Int, routes: List<String>): Pair<Event?, Set<String>> {
                    current()
                    var latest = repo.followEvent(owner, kind)
                    val lock = Mutex()
                    val filter = Filter(kinds = listOf(kind), authors = listOf(owner), limit = 1)
                    val completed = withTimeout(45_000) {
                        queries.completed(routes, listOf(filter.toJson())) { event ->
                            require(event.kind == kind && event.pubKey == owner && event.verify() &&
                                event.tags.size <= 5000 && event.content.length <= 32_768 &&
                                event.createdAt <= System.currentTimeMillis() / 1000 + 600) { "Invalid follow-list response" }
                            current()
                            lock.withLock {
                                latest = listOfNotNull(latest, event).sortedWith(newest).firstOrNull()
                            }
                            repo.cacheFollowEvent(event)
                            true
                        }
                    }
                    current()
                    latest = listOfNotNull(latest, repo.followEvent(owner, kind)).sortedWith(newest).firstOrNull()
                    return latest to completed
                }
                val knownRelays = repo.followEvent(owner, 10002)?.let(Outbox::parse)
                val seeds = RelayDefaults.seeds.take(3)
                val discoveryRoutes = (knownRelays?.write.orEmpty() + knownRelays?.read.orEmpty() + seeds).distinct()
                require(discoveryRoutes.size <= 32) { "Too many relays to safely refresh the follow list" }
                val relayResult = discover(10002, discoveryRoutes)
                check(relayResult.second.size >= minOf(2, discoveryRoutes.size) &&
                    relayResult.second.containsAll(knownRelays?.write.orEmpty())) {
                    "Could not refresh account relays. Follow list unchanged."
                }
                val relayList = relayResult.first?.let(Outbox::parse)
                val writes = relayList?.write.orEmpty()
                val routes = (writes + relayList?.read.orEmpty() + seeds).distinct()
                require(routes.size <= 32) { "Too many relays to safely refresh the follow list" }

                suspend fun fresh(): Event? {
                    val (event, completed) = discover(3, routes)
                    check(completed.size >= minOf(2, routes.size) && completed.containsAll(writes)) {
                        "Could not refresh the follow list from all write relays. No further update sent."
                    }
                    if (event == null) {
                        check(completed.size == routes.size) { "Could not confirm an empty follow list. No further update sent." }
                        if (!allowCreate) throw MissingFollowList()
                    }
                    return event
                }

                val base = fresh()
                val wasFollowing = target in Protocol.follows(base)
                if (wasFollowing == follow) return@withLock
                val tags = base?.tags.orEmpty().filterNot {
                    !follow && it.firstOrNull() == "p" && it.getOrNull(1) == target
                }.toMutableList()
                if (follow) tags.add(arrayOf("p", target))
                require(tags.size <= 5000) { "Follow list is too large" }
                val content = base?.content.orEmpty()
                val timestamp = maxOf(System.currentTimeMillis() / 1000, (base?.createdAt ?: 0) + 1)
                check(timestamp <= System.currentTimeMillis() / 1000 + 60) {
                    "Follow list is dated in the future. Check your device clock."
                }
                val expectedTags = tags.toTypedArray()
                val signed = requests.run {
                    current()
                    check(repo.followEvent(owner, 3)?.id == base?.id) { "Follow list changed. Tap again to retry safely." }
                    withTimeout(180_000) { identity.sign<Event>(timestamp, 3, expectedTags, content) }
                }
                require(signed.pubKey == owner && signed.kind == 3 && signed.createdAt == timestamp &&
                    signed.content == content && signed.tags.contentDeepEquals(expectedTags) && signed.verify()) {
                    "Invalid signed follow list"
                }
                repeat(3) { attempt ->
                    if (attempt > 0) delay(1_000L * attempt)
                    // The signer can stay open while another app edits the list. Refresh after
                    // signing and before every retransmission; never re-sign an obsolete snapshot.
                    val latest = fresh()
                    if (latest?.id == signed.id) return@withLock
                    check(latest?.id == base?.id) { "Follow list changed in another client. Tap again to retry safely." }
                    current()
                    check(repo.followEvent(owner, 3)?.id == base?.id) { "Follow list changed. Tap again to retry safely." }
                    if (repo.publishFollowEvent(signed, routes, base?.id, relayResult.first?.id)) return@withLock
                }
                error("No relay confirmed the follow change. Tap again to check and retry safely.")
            }
        }
}
