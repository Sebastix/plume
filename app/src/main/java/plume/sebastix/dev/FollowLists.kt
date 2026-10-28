package plume.sebastix.dev

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class MissingFollowList : IllegalStateException("No existing follow list found")

/** Apply one explicit delta to a freshly discovered, complete follow/interests list. */
internal class FollowLists(private val repo: PlumeRepository, private val requests: SignerRequests) {
    private val edits = Mutex()
    private val queries = ProfileRelayQuery()
    private data class Discovery(val event: Event?, val completed: Set<String>, val witnessed: Boolean)
    private fun diagnostic(routes: List<String>, completed: Set<String>): String =
        "Completed ${completed.size}/${routes.size}: ${completed.joinToString().ifEmpty { "none" }}; " +
            "incomplete: ${(routes - completed).joinToString().ifEmpty { "none" }}"
    private val newest = compareByDescending<Event> { it.createdAt }.thenBy { it.id }

    suspend fun change(identity: NostrSignerExternal, target: String, follow: Boolean, allowCreate: Boolean = false) =
        changeList(identity, target, follow, allowCreate, 3)

    suspend fun changeHashtag(identity: NostrSignerExternal, target: String, follow: Boolean, allowCreate: Boolean = false) =
        changeList(identity, target, follow, allowCreate, 10015)

    suspend fun changeMutedWord(identity: NostrSignerExternal, word: String, add: Boolean) =
        changeList(identity, word, add, true, 10000)

    suspend fun changeAccountMute(identity: NostrSignerExternal, pubkey: String, mute: Boolean) =
        changeList(identity, pubkey, mute, true, 10000, "p")

    private suspend fun changeList(identity: NostrSignerExternal, target: String, follow: Boolean, allowCreate: Boolean, listKind: Int, muteTag: String = "word") =
        withContext(Dispatchers.IO) {
            edits.withLock {
                val owner = identity.pubKey
                fun current() = check(repo.state.value.publicKey == owner) { "Account changed" }
                current()
                if (listKind == 3) require(Protocol.publicKey(target) == target && target != owner)
                else if (listKind == 10000 && muteTag == "p") require(Protocol.publicKey(target) == target && target != owner)
                else if (listKind == 10000) require(target.isNotBlank() && target.length <= 256 && normalizeMutedWord(target) == target)
                else require(normalizeFollowedTag(target) == target)
                suspend fun discover(kind: Int, routes: List<String>): Discovery {
                    repo.awaitListEditingForeground()
                    current()
                    var latest = repo.followEvent(owner, kind)
                    val lock = Mutex()
                    val received = mutableMapOf<String, MutableSet<String>>()
                    val filter = Filter(kinds = listOf(kind), authors = listOf(owner), tags = if (kind == 30000) mapOf("d" to listOf("mute")) else null, limit = 1)
                    val completed = withTimeout(45_000) {
                        queries.completed(routes, listOf(filter.toJson())) { relay, event ->
                            require(event.kind == kind && (kind != 30000 || event.tagValue("d") == "mute") && event.pubKey == owner && event.verify() &&
                                event.tags.size <= 5000 && event.content.length <= (if (kind in listOf(10015, 10000, 30000)) 262_144 else 32_768) &&
                                event.createdAt <= System.currentTimeMillis() / 1000 + 600) { "Invalid follow-list response" }
                            current()
                            lock.withLock {
                                received.getOrPut(relay) { mutableSetOf() }.add(event.id)
                                latest = listOfNotNull(latest, event).sortedWith(newest).firstOrNull()
                            }
                            repo.cacheFollowEvent(event, relay)
                            true
                        }
                    }
                    current()
                    val cached = repo.followEvent(owner, kind)
                    return lock.withLock {
                        latest = listOfNotNull(latest, cached).sortedWith(newest).firstOrNull()
                        Discovery(latest, completed, completed.any { latest?.id in received[it].orEmpty() })
                    }
                }
                val knownRelays = repo.followEvent(owner, 10002)?.let(Outbox::parse)
                val seeds = RelayDefaults.seeds.take(3)
                val discoveryRoutes = (knownRelays?.write.orEmpty() + knownRelays?.read.orEmpty() + seeds).distinct()
                require(discoveryRoutes.size <= 32) { "Too many relays to safely refresh the follow list" }
                val relayResult = discover(10002, discoveryRoutes)
                check(relayResult.completed.size >= 2) {
                    "Could not refresh account relays. ${diagnostic(discoveryRoutes, relayResult.completed)}"
                }
                val relayList = relayResult.event?.let(Outbox::parse)
                val writes = relayList?.write.orEmpty()
                val publishRoutes = (writes + seeds).distinct()
                val readRoutes = (publishRoutes + relayList?.read.orEmpty()).distinct()
                require(readRoutes.size <= 32) { "Too many relays to safely refresh the follow list" }

                suspend fun fresh(): Event? {
                    val primary = discover(listKind, readRoutes)
                    if (listKind == 10000 && primary.event == null) check(primary.completed.size == readRoutes.size) {
                        "Could not confirm the current mute list. No update sent."
                    }
                    val result = if (listKind == 10000 && primary.event == null) discover(30000, readRoutes) else primary
                    val event = result.event
                    val completed = result.completed
                    check(completed.size >= 2) {
                        "Could not refresh the follow list. ${diagnostic(readRoutes, completed)}"
                    }
                    check(event == null || result.witnessed) {
                        "No completed relay returned the latest known follow list (${event?.id?.take(12)}). " +
                            "No further update sent. ${diagnostic(readRoutes, completed)}"
                    }
                    if (event == null) {
                        check(completed.size == readRoutes.size) { "Could not confirm an empty follow list. No further update sent." }
                        if (!allowCreate) throw MissingFollowList()
                    }
                    return event
                }

                suspend fun currentList() = repo.followEvent(owner, listKind)
                    ?: if (listKind == 10000) repo.followEvent(owner, 30000) else null
                suspend fun cachePrivate(event: Event, plaintext: String) {
                    if (listKind == 10000) repo.cacheMutePlaintext(event, plaintext)
                    else repo.cacheHashtagPlaintext(event, plaintext)
                }
                val base = fresh()
                val edit = when (listKind) {
                    10015 -> editHashtagList(base, target, follow, identity, requests)
                    10000 -> editMuteList(base, target, follow, identity, requests, muteTag)
                    else -> null
                }
                if (listKind != 3 && edit == null) return@withLock
                val wasFollowing = target in Protocol.follows(base)
                if (listKind == 3 && wasFollowing == follow) return@withLock
                val tags = if (edit != null) edit.tags.toMutableList() else base?.tags.orEmpty().filterNot {
                    !follow && it.firstOrNull() == "p" && it.getOrNull(1) == target
                }.toMutableList().also { if (follow) it.add(arrayOf("p", target)) }
                require(tags.size <= 5000) { "Follow list is too large" }
                val content = edit?.content ?: base?.content.orEmpty()
                require(content.length <= (if (listKind in listOf(10015, 10000)) 262_144 else 32_768)) { "Follow list is too large" }
                val timestamp = maxOf(System.currentTimeMillis() / 1000, (base?.createdAt ?: 0) + 1)
                check(timestamp <= System.currentTimeMillis() / 1000 + 60) {
                    "Follow list is dated in the future. Check your device clock."
                }
                val expectedTags = tags.toTypedArray()
                val signed = requests.run {
                    current()
                    check(currentList()?.id == base?.id) { "Follow list changed. Tap again to retry safely." }
                    withTimeout(180_000) { identity.sign<Event>(timestamp, listKind, expectedTags, content) }
                }
                require(signed.pubKey == owner && signed.kind == listKind && signed.createdAt == timestamp &&
                    signed.content == content && signed.tags.contentDeepEquals(expectedTags) && signed.verify()) {
                    "Invalid signed follow list"
                }
                repeat(3) { attempt ->
                    if (attempt > 0) delay(1_000L * attempt)
                    // The signer can stay open while another app edits the list. Refresh after
                    // signing and before every retransmission; never re-sign an obsolete snapshot.
                    val latest = fresh()
                    if (latest?.id == signed.id) {
                        edit?.privatePlaintext?.let { cachePrivate(signed, it) }
                        return@withLock
                    }
                    check(latest?.id == base?.id) { "Follow list changed in another client. Tap again to retry safely." }
                    current()
                    check(currentList()?.id == base?.id) { "Follow list changed. Tap again to retry safely." }
                    if (repo.publishFollowEvent(signed, publishRoutes, base?.takeIf { it.kind == listKind }?.id, relayResult.event?.id)) {
                        edit?.privatePlaintext?.let { cachePrivate(signed, it) }
                        return@withLock
                    }
                }
                error("No relay confirmed the follow change. Tap again to check and retry safely.")
            }
        }
}
