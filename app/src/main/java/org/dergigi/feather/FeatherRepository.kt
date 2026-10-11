package org.dergigi.feather

import android.app.Application
import android.content.Context
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip01Core.relay.client.accessories.publishAndConfirm
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

class FeatherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        crashes.install()
        com.vitorpamplona.quartz.utils.Log.minLevel = com.vitorpamplona.quartz.utils.LogLevel.ERROR
    }
    internal val crashes by lazy { CrashReports(this) }
    val repository by lazy { FeatherRepository(this) }
    val media by lazy { MediaStore(this) }
}

data class Profile(val name: String, val about: String = "", val picture: String? = null, val username: String? = null, val banner: String? = null, val lightning: String? = null, val aboutTokens: List<NostrContent.Token> = emptyList())
data class FeedState(
    val notes: List<Event> = emptyList(),
    val feedTab: ProfileTab = ProfileTab.POSTS,
    val feedTabs: List<ProfileTab> = listOf(ProfileTab.POSTS, ProfileTab.REPLIES),
    val feedPreviews: Map<ProfileTab, List<Event>> = emptyMap(),
    val threadId: String? = null,
    val threadNotes: List<Event> = emptyList(),
    val profileEvents: List<Event> = emptyList(),
    val profileTabsFor: String? = null,
    val profileTabs: List<ProfileTab> = emptyList(),
    val hashtagNotes: List<Event> = emptyList(),
    val profiles: Map<String, Profile> = emptyMap(),
    val embedded: Map<String, Event> = emptyMap(),
    val engagement: Map<String, Engagement> = emptyMap(),
    val following: Set<String> = emptySet(),
    val sentReactions: Map<String, List<ReactionChoice>> = emptyMap(),
    val deletedReactions: Set<Pair<String, String>> = emptySet(),
    val reactionChoices: List<ReactionChoice> = defaultReactions,
    val customEmojis: List<ReactionChoice> = emptyList(),
    val mutedAuthors: Set<String> = emptySet(),
    val showSensitive: Boolean = false,
    val pendingMuteList: Event? = null,
    val publicKey: String? = null,
    val signerPackage: String? = null,
    val relays: List<String> = RelayDefaults.seeds.take(4),
    val connected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val loadingOlder: Boolean = false,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class FeatherRepository(context: Context) {
    private val prefs = context.getSharedPreferences("feather", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal val blocktime = Blocktime(context.applicationContext)
    private val dbPath = context.getDatabasePath("events.db").also { it.parentFile?.mkdirs() }.absolutePath
    private val db by lazy { EventStore(dbName = dbPath, numReaders = 1) }
    private val mutex = Mutex()
    private val snapshotMutex = Mutex()
    private val reactionMutex = Mutex()
    private val reactionStore = ReactionStore(context)
    private val reactionDeletions = ReactionDeletions(context)
    @Volatile private var deletedReactions: Set<Pair<String, String>> = emptySet()
    private fun reactionDeleted(event: Event) = event.kind == 7 && (event.pubKey to event.id) in deletedReactions
    private val muteCache = MuteCache(context)
    private val foregroundActive = MutableStateFlow(false)
    @Volatile private var publishingAuthor: String? = null
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val zapVerification = ZapVerification(scope) { changed.tryEmit(Unit) }
    @Volatile private var engagementIds: List<String> = emptyList()
    private val _state = MutableStateFlow(FeedState(
        showSensitive = prefs.getBoolean("show-sensitive", false),
        publicKey = prefs.getString("pubkey", null)?.takeIf { !prefs.getString("signer", null).isNullOrBlank() }, signerPackage = prefs.getString("signer", null)?.takeIf { it.isNotBlank() },
    ))
    suspend fun showSensitive(show: Boolean) = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("show-sensitive", show).commit()) { "Could not save content setting" }
        _state.update { it.copy(showSensitive = show) }
        feedDiscoveryAt = 0L
        changed.tryEmit(Unit)
    }
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private val client = NostrClient(RelayTransport(), scope)
    private val ready = CompletableDeferred<Unit>()
    private val verified = object : LinkedHashMap<String, Boolean>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2048
    }
    @Volatile private var active = false
    @Volatile private var viewedProfile: String? = null
    @Volatile private var profileTab = ProfileTab.POSTS
    @Volatile private var feedUntil: Long? = null
    @Volatile private var selectedFeed = ProfileTab.POSTS
    private val feedCeilings = java.util.concurrent.ConcurrentHashMap<ProfileTab, Long>()
    // Snapshot mutex owns discovery caches. Avoid re-parsing every category on each relay event.
    private var feedDiscoveryKey: Pair<List<String>?, Set<String>>? = null
    @Volatile private var feedDiscoveryAt = 0L
    private var feedDiscoveryPages: Map<ProfileTab, List<Event>> = emptyMap()
    @Volatile private var hashtagLimit = 200
    // Retain a bounded tail even if disk-cache pruning removes historical events.
    @Volatile private var olderEvents: List<Event> = emptyList()
    private val historySequence = java.util.concurrent.atomic.AtomicLong()
    @Volatile private var profileLimit = 100
    @Volatile private var profileUntil: Long? = null
    @Volatile private var viewedHashtag: String? = null
    @Volatile private var hashtagUntil: Long? = null
    @Volatile private var viewedThread: String? = null
    private var historyJob: Job? = null
    private var refreshTimeout: Job? = null
    @Volatile private var routingPool: Set<String> = emptySet()
    @Volatile private var relayLists: Map<String, Outbox.RelayList> = emptyMap()
    @Volatile private var visibleIds: List<String> = emptyList()
    @Volatile private var emojiPackRequests: List<EmojiPack> = emptyList()
    @Volatile private var references: List<NostrContent.Reference> = emptyList()
    @Volatile private var embeddedIds: List<String> = emptyList()
    @Volatile private var profileAuthors: List<String> = emptyList()
    @Volatile private var openedReference: NostrContent.Reference? = null
    private var discoveryJob: Job? = null
    fun readingNotes(ids: List<String>) {
        val bounded = ids.distinct().take(32)
        if (bounded == visibleIds) return
        visibleIds = bounded
        changed.tryEmit(Unit)
    }
    fun openReference(reference: NostrContent.Reference?) { openedReference = reference }

    private var seedOffset = 0
    private var embeddedSeedOffset = 6
    private val unavailableUntil = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private fun healthy(url: String) = (unavailableUntil[Protocol.relay(url) ?: url] ?: 0) <= android.os.SystemClock.elapsedRealtime()
    private fun unavailable(url: String) {
        if (active) unavailableUntil[Protocol.relay(url) ?: url] = android.os.SystemClock.elapsedRealtime() + 120_000
    }
    private var inserts = 0
    private val listener = object : SubscriptionListener {
        override suspend fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            if (forFilters?.any { it.match(event) } == true) accept(event)
        }
        override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            unavailableUntil.remove(Protocol.relay(relay.url) ?: relay.url)
            if (forFilters?.any { it.kinds?.any { kind -> kind in profileKinds } == true } == true) _state.update { it.copy(loading = false) }
        }
        override fun onClosed(message: String, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            unavailable(relay.url)
        }
    }
    init {
        client.disconnect()
        prefs.edit().remove("relays").remove("draft").remove("draftReply").remove("pending").apply()
        if (_state.value.publicKey == null) prefs.edit().remove("pubkey").remove("signer").apply()
        client.addConnectionListener(object : RelayConnectionListener {
            override fun onConnected(relay: IRelayClient, pingMillis: Int, compressed: Boolean) {
                unavailableUntil.remove(Protocol.relay(relay.url.url) ?: relay.url.url)
                _state.update { it.copy(connected = it.connected + relay.url.url, error = null) }
            }
            override fun onDisconnected(relay: IRelayClient) { _state.update { it.copy(connected = it.connected - relay.url.url) } }
            override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
                unavailable(relay.url.url)
                _state.update { it.copy(connected = it.connected - relay.url.url) }
            }
        })
        scope.launch {
            try { trimCache(); snapshot(); ready.complete(Unit) }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = "Could not open the local cache.") }; ready.completeExceptionally(e) }
        }
        scope.launch { changed.sample(250).collect { snapshot() } }
    }
    fun foreground() {
        active = true
        foregroundActive.value = true
        scope.launch { ready.await(); if (active) { changed.tryEmit(Unit); subscribeFeed(); client.connect() } }
        discoveryJob?.cancel()
        discoveryJob = scope.launch {
            ready.await()
            var ticks = 0
            while (isActive && active) {
                delay(10_000)
                if (!active) break
                val now = android.os.SystemClock.elapsedRealtime()
                unavailableUntil.entries.removeIf { it.value <= now }
                // Try another seed batch while an identity's relay list remains undiscovered.
                if (++ticks % 3 == 0 && (state.value.connected.isEmpty() || (state.value.publicKey != null && relayLists[state.value.publicKey] == null))) {
                    seedOffset = (seedOffset + 4) % RelayDefaults.seeds.size
                }
                if (ticks % 3 == 0 && embeddedIds.any { it !in state.value.embedded }) embeddedSeedOffset = (embeddedSeedOffset + 2) % RelayDefaults.seeds.size
                changed.tryEmit(Unit) // Retry deferred optional work without waiting for a new relay event.
                subscribeFeed()
            }
        }
    }
    fun background() { active = false; foregroundActive.value = false; blocktime.stop(); zapVerification.stop(); discoveryJob?.cancel(); historyJob?.cancel(); refreshTimeout?.cancel(); client.disconnect(); _state.update { it.copy(connected = emptySet(), loading = false) } }
    fun refresh() {
        historyJob?.cancel()
        feedUntil = null; feedCeilings.remove(selectedFeed); feedDiscoveryAt = 0; profileUntil = null; hashtagUntil = null
        profileLimit = 100; hashtagLimit = 200
        _state.update { it.copy(loading = true, error = null) }
        scope.launch { ready.await(); if (active) { changed.tryEmit(Unit); subscribeFeed(); client.connect() } }
    }
    private fun authorFilters(authors: List<String>?, kinds: List<Int>, limit: Int, until: Long? = null): Map<NormalizedRelayUrl, List<Filter>> {
        val routes = (if (authors == null) routingPool.filter { it != "wss://purplepag.es" }
            .associate { it.normalizeRelayUrl() to listOf(Filter(kinds = kinds, limit = limit, until = until)) }
        else Outbox.readRoutes(authors, relayLists, state.value.relays, allowed = routingPool).mapKeys { it.key.normalizeRelayUrl() }
            .mapValues { (_, keys) -> listOf(Filter(authors = keys, kinds = kinds, limit = limit, until = until)) }).mapValues { (_, filters) -> filters.flatMap { it.activityFilters() } }
        return if (32267 in kinds) routes + (ZAPSTORE_RELAY.normalizeRelayUrl() to
            listOf(Filter(authors = authors, kinds = listOf(32267), limit = limit, until = until))) else routes
    }
    private fun discoverRelays() {
        val s = state.value
        val threadAuthor = s.threadNotes.find { it.id == viewedThread }?.pubKey
        val authors = (listOfNotNull(s.publicKey, viewedProfile, threadAuthor) + emojiPackRequests.map { it.author } + profileAuthors.take(64) + s.following.take(999) + s.notes.take(40).map { it.pubKey }.takeIf { s.publicKey == null }.orEmpty()).distinct()
        if (authors.isEmpty()) client.unsubscribe("relay-lists")
        else client.subscribe("relay-lists", routingPool.associate { it.normalizeRelayUrl() to listOf(Filter(authors = authors, kinds = listOf(10002), limit = authors.size)) }, listener)
    }
    private fun updateRoutingPool() {
        val own = relayLists[state.value.publicKey]
        val personal = (own?.read.orEmpty() + own?.write.orEmpty()).distinct().filter(::healthy).take(4)
        val seeds = (RelayDefaults.seeds.drop(seedOffset) + RelayDefaults.seeds.take(seedOffset)).filter(::healthy)
            .ifEmpty { RelayDefaults.seeds }
        // Keep a bootstrap route for finding relay-list updates beyond the account's own relays.
        val base = (personal.take(3) + seeds.take(4 - personal.take(3).size)).distinct()
        _state.update { it.copy(relays = base) }
        val s = state.value
        val threadAuthor = s.threadNotes.find { it.id == viewedThread }?.pubKey
        val fallback = if (embeddedIds.any { it !in s.embedded })
            (RelayDefaults.seeds.drop(embeddedSeedOffset) + RelayDefaults.seeds.take(embeddedSeedOffset)).filter(::healthy).take(2)
            else emptyList()
        val publishRoutes = publishingAuthor?.let { author ->
            own?.write.orEmpty().filter(::healthy).take(2) + relayLists[author]?.read.orEmpty().filter(::healthy).take(2)
        }.orEmpty()
        val priority = (publishRoutes + listOf("wss://purplepag.es") + openedReference?.relays.orEmpty() +
            references.flatMap { it.relays }.distinct().take(2) + emojiPackRequests.mapNotNull { it.relay }.distinct().take(2) +
            emojiPackRequests.flatMap { relayLists[it.author]?.write.orEmpty() }.distinct().take(2) + fallback + relayLists[viewedProfile]?.write.orEmpty().take(2) +
            relayLists[threadAuthor]?.read.orEmpty().take(2) + relayLists[threadAuthor]?.write.orEmpty().take(2) +
            own?.read.orEmpty() + own?.write.orEmpty() +
            profileAuthors.take(12).flatMap { relayLists[it]?.write.orEmpty().take(1) }).distinct().filter(::healthy)
        val authors = (listOfNotNull(s.publicKey, viewedProfile, threadAuthor) + profileAuthors.take(64) + s.following.take(999) + s.notes.take(40).map { it.pubKey }.takeIf { s.publicKey == null }.orEmpty()).distinct()
        val usableLists = relayLists.mapValues { (_, list) -> Outbox.RelayList(list.read.filter(::healthy), list.write.filter(::healthy)) }
        val popular = Outbox.readRoutes(authors, usableLists, s.relays).keys
        val next = (s.relays + (priority + popular).distinct().filter { it !in s.relays }.take(8)).toSet()
        if (next != routingPool) {
            routingPool = next
            historyJob?.cancel()
            client.unsubscribe("profiles")
        }
    }
    @Synchronized
    private fun subscribeFeed() {
        if (!active) return
        updateRoutingPool()
        val s = _state.value
        discoverRelays()
        s.publicKey?.let { key ->
            val accountRoutes = authorFilters(listOf(key), listOf(0), 1)
            // Following is essential to the home feed. Request it independently from metadata,
            // including existing bootstrap relays that may have it when write relays do not.
            val contactRelays = (accountRoutes.keys + s.relays.map { it.normalizeRelayUrl() } +
                routingPool.filter { it == "wss://purplepag.es" }.map { it.normalizeRelayUrl() }).distinct()
            client.subscribe("contacts", contactRelays.associateWith {
                listOf(Filter(authors = listOf(key), kinds = listOf(3), limit = 1))
            }, listener)
            val routes = accountRoutes.mapValues { (_, filters) ->
                filters + Filter(authors = listOf(key), kinds = listOf(7), limit = 200) +
                    Filter(authors = listOf(key), kinds = listOf(5), limit = 256) +
                    Filter(authors = listOf(key), kinds = listOf(10000), limit = 1) +
                    Filter(authors = listOf(key), kinds = listOf(10030), limit = 1) +
                    Filter(authors = listOf(key), kinds = listOf(30000), tags = mapOf("d" to listOf("mute")), limit = 1)
            }
            client.subscribe("account", routes, listener)
        }
            ?: run { client.unsubscribe("account"); client.unsubscribe("contacts") }
        val authors = s.publicKey?.let { (listOf(it) + s.following).take(1000) }
        client.subscribe("feed", authorFilters(authors, selectedFeed.feedKinds(), 80), listener)
        val discovery = authorFilters(authors, profileKinds, 8).mapValues { (_, filters) ->
            filters.flatMap { filter -> filter.kinds.orEmpty().map { kind ->
                Filter(authors = filter.authors, kinds = listOf(kind), tags = filter.tags, limit = 8)
            } }
        }
        client.subscribe("feed-discovery", discovery, listener)
        viewedProfile?.let { subscribeProfile(it) }
        viewedThread?.let { subscribeThread(it) }
        viewedHashtag?.let { client.subscribe("hashtag", hashtagFilters(it), listener) }
        subscribeReadingData()
        if (refreshTimeout?.isActive == true) return
        refreshTimeout = scope.launch {
            delay(12_000)
            _state.update { it.copy(loading = false, error = if (it.connected.isEmpty()) "No relay connection. Cached notes are still available." else it.error) }
        }
    }
    /** Small batches keep metadata filters within common relay request limits. */
    private fun subscribeReadingData() {
        if (!active) return
        val packs = emojiPackRequests.takeIf { state.value.publicKey != null }.orEmpty()
        if (packs.isEmpty()) client.unsubscribe("emoji-packs")
        else client.subscribe("emoji-packs", routingPool.filter { it != "wss://purplepag.es" }.associate {
            it.normalizeRelayUrl() to packs.map { pack -> pack.filter() }
        }, listener)

        val targets = (listOfNotNull(state.value.publicKey, viewedProfile) + profileAuthors).distinct().take(160)
        val metadata = routingPool.associate { url ->
            url.normalizeRelayUrl() to targets.chunked(16).map { Filter(authors = it, kinds = listOf(0), limit = it.size) }
        }.filterValues { it.isNotEmpty() }
        if (metadata.isEmpty()) client.unsubscribe("profiles")
        else client.subscribe("profiles", metadata, listener)
        if (engagementIds.isEmpty()) client.unsubscribe("engagement")
        else client.subscribe("engagement", routingPool.filter { it != "wss://purplepag.es" }.associate { url ->
            url.normalizeRelayUrl() to engagementIds.chunked(6).flatMap { ids ->
                listOf(1, 7, 9735).map { kind -> Filter(kinds = listOf(kind), tags = mapOf("e" to ids), limit = 300) }
            }
        }, listener)
        val ids = embeddedIds.filter { ':' !in it && it !in state.value.embedded }
        // Keep address filters live while visible so replacement article versions arrive.
        val addressFilters = references.filter { it.id in embeddedIds }.mapNotNull { it.addressFilter() }.take(24)
        val filters = (if (ids.isEmpty()) emptyList() else listOf(Filter(ids = ids, kinds = readableKinds, limit = ids.size))) + addressFilters
        if (filters.isEmpty()) client.unsubscribe("embedded")
        else client.subscribe("embedded", routingPool.filter { it != "wss://purplepag.es" }.associate {
            it.normalizeRelayUrl() to filters
        }, listener)
    }

    private suspend fun accept(event: Event, history: Boolean = false): Boolean {
        if (event.kind == 5 && event.tags.count { it.firstOrNull() == "e" } > 256) return false
        if (event.kind !in (profileKinds + listOf(0, 3, 5, 6, 9735, 10000, 10002, 10030, 30030)) || event.content.length > (if (event.kind in listOf(30023, 10000) || (event.kind == 30000 && event.tagValue("d") == "mute")) 262_144 else 32_768) || event.tags.size > 5000 || event.createdAt > System.currentTimeMillis() / 1000 + 600) return false
        mutex.withLock {
            if (verified.containsKey(event.id) && !history) return true
            if (!verified.containsKey(event.id)) {
                if (!event.verify()) return false
                verified[event.id] = true
                db.insert(event)
                if (++inserts % 200 == 0) trimCacheLocked()
            }
            if (history) olderEvents = (olderEvents + event).distinctBy { it.id }
                .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).takeLast(600)
        }
        if (event.kind == 5 && event.pubKey == state.value.publicKey) {
            reactionDeletions.put(event, accepted = true)
            deletedReactions = reactionDeletions.deleted()
        }
        if (event.kind == 6 && event.content.isNotBlank()) {
            val target = repostTarget(event)
            val original = runCatching { Event.fromJson(event.content) }.getOrNull()
                ?.takeIf { it.kind == 1 && it.id == target?.id && (target.author == null || it.pubKey == target.author) }
            // Independently validate the enclosed original on IO before storing/displaying it.
            if (original != null) accept(original)
        }
        changed.tryEmit(Unit)
        return true
    }
    private suspend fun timeline(filter: Filter, previous: List<Event>, limit: Int): List<Event> = mutex.withLock {
        val filters = filter.activityFilters()
        val matching = (db.query<Event>(filters) + previous + olderEvents).filter { event -> filters.any { it.match(event) } }
        // Sparse posts/replies tabs can have visible items far ahead of their raw-event tail.
        // Keep these anchors when advancing the bounded window.
        val pinned = if (filter.until != null) previous.filter { it.id in visibleIds } else emptyList()
        (matching + pinned).asSequence().filterNot(::reactionDeleted).distinctBy { it.id }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).take(limit).toList()
    }
    private suspend fun snapshot() = snapshotMutex.withLock { snapshotContents() }
    private suspend fun snapshotContents() {
        deletedReactions = reactionDeletions.deleted()
        val key = _state.value.publicKey
        val thread = viewedThread
        val contact = key?.let { mutex.withLock { db.query<Event>(Filter(kinds = listOf(3), authors = listOf(it), limit = 1)).firstOrNull() } }
        val muteList = key?.let { author -> mutex.withLock {
            // A current list (including an empty one) supersedes the deprecated mute set.
            db.query<Event>(Filter(kinds = listOf(10000), authors = listOf(author), limit = 1)).firstOrNull()
                ?: db.query<Event>(Filter(kinds = listOf(30000), authors = listOf(author), tags = mapOf("d" to listOf("mute")), limit = 1)).firstOrNull()
        } }
        val privateMutes = muteList?.takeIf { it.content.isNotBlank() }?.let(muteCache::read)
        // Preserve already-known private blocks while a replacement awaits signer approval.
        val cachedPrivateMutes = if (muteList?.content?.isNotBlank() == true) muteCache.read(muteList, exact = false).orEmpty() else emptySet()
        val mutedAuthors = mutedPublicKeys(muteList) + (privateMutes ?: cachedPrivateMutes)
        val pendingMuteList = muteList?.takeIf { it.content.isNotBlank() && privateMutes == null }
        val following = Protocol.follows(contact) - mutedAuthors
        val feedCeiling = feedUntil
        val feed = selectedFeed
        val feedAuthors = key?.let { (listOf(it) + following).take(1000) }
        val previousFeed = state.value.notes.takeIf { state.value.feedTab == feed }.orEmpty()
        val all = latestProfileEvents(timeline(Filter(kinds = feed.feedKinds(), authors = feedAuthors, limit = 600, until = feedCeiling), previousFeed, 600))
        val discoveryKey = feedAuthors to mutedAuthors
        val discoveryNow = android.os.SystemClock.elapsedRealtime()
        if (feedDiscoveryKey != discoveryKey || discoveryNow - feedDiscoveryAt >= 5_000) {
            val discoveredFeed = mutex.withLock {
                db.query<Event>(profileKinds.flatMap { kind -> Filter(kinds = listOf(kind), authors = feedAuthors, limit = 80).activityFilters() })
            }
            val feedFilter = ContentFilter(mutedAuthors, state.value.embedded, showSensitive = state.value.showSensitive)
            val discoveryEvents = latestProfileEvents(discoveredFeed).filterNot(::reactionDeleted).filterNot(feedFilter::hides)
            feedDiscoveryPages = ProfileTab.entries.associateWith { candidate -> discoveryEvents.filter(candidate::matchesFeed) }
            feedDiscoveryKey = discoveryKey
            feedDiscoveryAt = discoveryNow
        }
        val previews = feedDiscoveryPages
        val feedTabs = (listOf(ProfileTab.POSTS, ProfileTab.REPLIES) + ProfileTab.entries.filter {
            it !in listOf(ProfileTab.POSTS, ProfileTab.REPLIES) && previews[it].orEmpty().isNotEmpty()
        }).sortedWith(compareBy<ProfileTab> { it.orderPriority }
            .thenByDescending { previews[it].orEmpty().size }.thenBy { it.ordinal })
        val feedPreviews = previews.mapValues { it.value.take(40) }
        val threadNotes = thread?.let { id ->
            val cached = mutex.withLock {
                db.query<Event>(listOf(Filter(kinds = readableKinds, ids = listOf(id)),
                    Filter(kinds = listOf(1), tags = mapOf("e" to listOf(id)), limit = 100)))
            }
            val root = cached.find { it.id == id } ?: state.value.threadNotes.find { it.id == id }
            listOfNotNull(root) + cached.filter { it.id != id && it.tags.any { tag ->
                tag.size >= 2 && tag[0] == "e" && tag[1] == id && (tag.size < 4 || tag[3] != "mention")
            } }.distinctBy { it.id }.sortedWith(compareBy<Event> { it.createdAt }.thenBy { it.id })
        }.orEmpty()
        val profile = viewedProfile
        val tab = profileTab
        val profileCeiling = profileUntil
        val profileCount = profileLimit
        val profileEvents = profile?.let { author ->
            latestProfileEvents(timeline(Filter(kinds = tab.kinds, authors = listOf(author), limit = profileCount, until = profileCeiling), state.value.profileEvents, profileCount))
        }.orEmpty()
        val availableTabs = profile?.let { author ->
            val discovered = mutex.withLock {
                db.query<Event>(profileKinds.flatMap { kind ->
                    Filter(kinds = listOf(kind), authors = listOf(author), limit = 600).activityFilters()
                })
            }
            val events = latestProfileEvents(discovered + profileEvents + olderEvents.filter { it.involves(author) })
            val frequencies = ProfileTab.entries.associateWith { candidate -> events.count(candidate::matches) }
            val found = frequencies.filterValues { it > 0 }.keys
            (found + state.value.profileTabs.takeIf { state.value.profileTabsFor == author }.orEmpty())
                .sortedWith(compareBy<ProfileTab> { it.orderPriority }
                    .thenByDescending { frequencies[it] ?: 0 }.thenBy { it.ordinal })
        }.orEmpty()
        val hashtag = viewedHashtag
        val hashtagCeiling = hashtagUntil
        val hashtagCount = hashtagLimit
        val hashtagNotes = hashtag?.let { tag ->
            timeline(Filter(kinds = listOf(1), tags = mapOf("t" to listOf(tag)), limit = hashtagCount, until = hashtagCeiling), state.value.hashtagNotes, hashtagCount)
        }.orEmpty()
        val contentFilter = ContentFilter(mutedAuthors, state.value.embedded, showSensitive = state.value.showSensitive)
        val readingPool = (threadNotes + hashtagNotes + profileEvents + all).filterNot(contentFilter::hides).distinctBy { it.id }
        val visible = visibleIds.mapNotNull { id -> readingPool.find { it.id == id } }.ifEmpty {
            if (thread != null) threadNotes.take(12) else if (hashtag != null) hashtagNotes.take(12) else if (profile != null) profileEvents.filter(tab::matches).take(12) else all.filter(feed::matchesFeed).take(12)
        }.filterNot(contentFilter::hides)
        val biography = profile?.let { author -> mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(0), authors = listOf(author), limit = 1)).firstOrNull()
        } }
        val bioTokens = biography?.let { event ->
            val about = runCatching { JSONObject(event.content).optString("about") }.getOrDefault("").take(2000).withoutObjectMarkers()
            NostrContent.tokens(about, event)
        }.orEmpty()
        val bioRefs = bioTokens.mapNotNull { it.reference }.filter { it.hashtag == null }.distinctBy { it.id }
        val bioQuotes = bioRefs.filter { !it.profile && it.external == null }.take(3)
        val repostIds = visible.mapNotNull(::repostTarget).map { it.id }.distinct().take(24)
        val reposts = if (repostIds.isEmpty()) emptyList() else mutex.withLock {
            db.query<Event>(Filter(ids = repostIds, kinds = listOf(1), limit = 24))
        }
        val readingContent = visible + reposts.filterNot(contentFilter::hides)
        val readingTab = if (profile != null) tab else feed
        val collections = if (profile != null) profileEvents else all
        val bookmarkRefs = if (readingTab == ProfileTab.BOOKMARKS)
            collections.flatMap(::bookmarkReferences).filter { it.id in visibleIds }.take(24) else emptyList()
        val peopleRefs = if (readingTab == ProfileTab.PEOPLE)
            collections.flatMap(::peopleReferences).filter { it.id in visibleIds }.distinctBy { it.id }.take(32) else emptyList()
        val refs = (peopleRefs + bookmarkRefs + bioRefs + readingContent.filter { it.kind !in listOf(3, 30000) }.flatMap(NostrContent::references)).distinctBy { it.id }.take(64)
        val quoteIds = (bookmarkRefs.filter { it.external == null } + bioQuotes + readingContent.flatMap { listOfNotNull(NostrContent.replyReference(it)) + NostrContent.quotes(it) }).map { it.id }.filter { ':' !in it }.distinct().take(24)
        val addressRefs = (bookmarkRefs + refs).distinctBy { it.id }.filter { it.addressFilter() != null }.take((24 - quoteIds.size).coerceAtLeast(0))
        val embedded = mutex.withLock {
            val byId = if (quoteIds.isEmpty()) emptyMap() else
                db.query<Event>(Filter(ids = quoteIds, kinds = readableKinds, limit = 24)).associateBy { it.id }
            val byAddress = addressRefs.mapNotNull { ref ->
                db.query<Event>(ref.addressFilter()!!).sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
                    .firstOrNull()?.let { ref.id to it }
            }.toMap()
            byId + byAddress
        }
        val countTargets = (visible.filter { it.kind in readableKinds && it.kind != 30023 } + embedded.values.filterNot(contentFilter::hides)).distinctBy { it.id }.take(24)
        engagementIds = countTargets.map { it.id }
        val activity = if (engagementIds.isEmpty()) emptyList() else mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(1, 7, 9735), tags = mapOf("e" to engagementIds), limit = 3000))
        }
        val authors = (activity.filter { it.kind == 9735 }.mapNotNull { it.tagValue("p") }.take(24) + listOfNotNull(key, viewedProfile) + visible.map { it.pubKey } +
            refs.filter { it.profile }.map { it.id } + refs.mapNotNull { it.author } + embedded.values.map { it.pubKey } +
            embedded.values.flatMap(NostrContent::references).filter { it.profile }.map { it.id }).distinct().take(160)
        val profiles = if (authors.isEmpty()) emptyMap() else mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(0), authors = authors, limit = authors.size))
        }.associate { event ->
            val data = runCatching { JSONObject(event.content) }.getOrNull()
            val about = data?.optString("about").orEmpty().take(2000).withoutObjectMarkers()
            event.pubKey to Profile(
                data?.optString("display_name")?.takeIf { it.isNotBlank() } ?: data?.optString("name")?.takeIf { it.isNotBlank() } ?: shortKey(event.pubKey),
                about,
                MediaContent.secureUrl(data?.optString("picture")?.trim()),
                data?.optString("name")?.trim()?.takeIf { it.isNotBlank() },
                listOf("banner", "cover", "header").firstNotNullOfOrNull { field -> MediaContent.secureUrl(data?.optString(field)?.trim()) },
                data?.optString("lud16")?.takeIf { it.isNotBlank() } ?: data?.optString("lud06")?.takeIf { it.isNotBlank() },
                NostrContent.tokens(about, event),
            )
        }
        val counts = countTargets.associate { target ->
            var zappedByAccount: String? = null
            val replies = activity.count { it.pubKey !in mutedAuthors && replyTarget(it) == target.id }
            val reactions = activity.filter { it.kind == 7 && !reactionDeleted(it) && it.pubKey !in mutedAuthors && reactionTarget(it)?.id == target.id }.distinctBy { it.pubKey to it.content }.size
            val zapSats = activity.asSequence().filter { it.kind == 9735 && it.tagValue("e") == target.id }
                .mapNotNull { receipt ->
                    val amount = zapVerification.amountInSats(receipt, target, profiles[receipt.tagValue("p")] ?: state.value.profiles[receipt.tagValue("p")], active)
                    if (amount.signum() > 0 && key != null && (receipt as? ZapReceiptEvent)?.zapRequest?.pubKey == key) {
                        zappedByAccount = key
                    }
                    amount.takeIf { it.signum() > 0 }?.let { receipt.tagValue("bolt11")?.lowercase(java.util.Locale.ROOT) to it }
                }.distinctBy { it.first }.fold(java.math.BigDecimal.ZERO) { total, (_, amount) -> total + amount }
            target.id to Engagement(replies, reactions, zapSats, zappedByAccount)
        }
        val ownReactions = key?.let { author ->
            val recent = mutex.withLock { db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), limit = 256)) }
            (reactionStore.records(author).filter { it.accepted }.map { it.event } + recent + activity.filter { it.kind == 7 && it.pubKey == author })
                .filterNot(::reactionDeleted).distinctBy { it.id }.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        }.orEmpty()
        val sentReactions = ownReactions.mapNotNull { event ->
            val target = reactionTarget(event)?.id ?: return@mapNotNull null
            reactionChoice(event)?.let { target to it }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, choices) -> choices.distinctBy { it.key } }
        val reactionChoices = reactionSuggestions(ownReactions)
        val emojiList = key?.let { author -> mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(10030), authors = listOf(author), limit = 1)).firstOrNull()
        } }
        val packs = emojiPacks(emojiList)
        val packEvents = mutex.withLock {
            packs.mapNotNull { pack -> db.query<Event>(pack.filter()).firstOrNull() }
        }
        val collection = (collectionEmojis(emojiList) + packEvents.flatMap(::collectionEmojis))
            .distinctBy { it.key }.take(1024)
        val customEmojis = (ownReactions.mapNotNull(::reactionChoice).filter { it.image != null } + collection)
            .distinctBy { it.key }.take(1024)

        val packsChanged = emojiPackRequests != packs
        val readingChanged = references != refs || profileAuthors != authors || packsChanged
        references = refs
        embeddedIds = quoteIds + addressRefs.map { it.id }
        profileAuthors = authors
        if (key != _state.value.publicKey || profile != viewedProfile || tab != profileTab || hashtag != viewedHashtag || thread != viewedThread || feed != selectedFeed || feedCeiling != feedUntil || profileCeiling != profileUntil || profileCount != profileLimit || hashtagCeiling != hashtagUntil || hashtagCount != hashtagLimit) { changed.tryEmit(Unit); return }
        emojiPackRequests = packs
        val lists = mutex.withLock { db.query<Event>(Filter(kinds = listOf(10002), limit = 1100)) }
            .associate { it.pubKey to Outbox.parse(it) }
        val routesChanged = lists != relayLists
        relayLists = lists
        val previous = _state.value.following
        val previousThreadAuthor = _state.value.threadNotes.find { it.id == viewedThread }?.pubKey
        _state.update { it.copy(notes = all, feedTab = feed, feedTabs = feedTabs, feedPreviews = feedPreviews, threadNotes = if (viewedThread == thread && it.threadId == thread) threadNotes else it.threadNotes, hashtagNotes = hashtagNotes, profileEvents = profileEvents, profileTabsFor = profile, profileTabs = availableTabs, embedded = (it.embedded + embedded).entries.toList().takeLast(128).associate { entry -> entry.toPair() }, engagement = (it.engagement + counts).entries.toList().takeLast(800).associate { entry -> entry.toPair() }, profiles = (it.profiles - profiles.keys + profiles).entries.toList().takeLast(1000).associate { entry -> entry.toPair() }, following = following, sentReactions = sentReactions, deletedReactions = deletedReactions, reactionChoices = reactionChoices, customEmojis = customEmojis, mutedAuthors = mutedAuthors, pendingMuteList = pendingMuteList) }
        if (active && (readingChanged || following != previous || routesChanged || previousThreadAuthor != threadNotes.find { it.id == viewedThread }?.pubKey)) subscribeFeed()
        else if (active) subscribeReadingData()
    }

    private suspend fun trimCache() = mutex.withLock { trimCacheLocked() }
    private suspend fun trimCacheLocked() {
        for ((kind, max) in listOf(30023 to 300, 30000 to 300, 30617 to 300, 32267 to 300, 20 to 600, 21 to 300, 22 to 300, 34235 to 300, 34236 to 300, 10003 to 200, 30003 to 300, 1 to 2000, 6 to 1000, 7 to 2000, 5 to 1000, 9735 to 2000, 9321 to 1000, 9802 to 600, 0 to 1000, 3 to 300, 10000 to 300, 10002 to 1100, 10030 to 8, 30030 to 64)) {
            val rows = db.query<Event>(Filter(kinds = listOf(kind), limit = max + 1))
            if (rows.size > max) db.delete(Filter(kinds = listOf(kind), until = rows[max].createdAt))
        }
        db.deleteExpiredEvents()
    }
    /** Explicit user action only. Signing and relay I/O stay off the UI thread. */
    private fun recordReaction(author: String, target: String, choice: ReactionChoice) {
        _state.update { if (it.publicKey == author) it.copy(
            sentReactions = it.sentReactions + (target to (listOf(choice) + it.sentReactions[target].orEmpty()).distinctBy { item -> item.key }),
            reactionChoices = (listOf(choice) + it.reactionChoices).distinctBy { item -> item.key }.take(6),
        ) else it }
    }

    internal suspend fun react(target: Event, choice: ReactionChoice, signer: NostrSignerExternal, signerRequests: SignerRequests) = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            ready.await()
            val author = state.value.publicKey ?: error("Connect an Android signer first.")
            require(signer.pubKey == author && target.kind in readableKinds)
            require(validReactionChoice(choice))
            val known = mutex.withLock {
                db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), tags = mapOf("e" to listOf(target.id)), limit = 100))
                    .firstOrNull { !reactionDeleted(it) && reactionChoice(it) == choice && reactionTarget(it)?.id == target.id }
            }
            val previous = reactionStore.get(author, target.id, choice)
            val saved = previous?.takeUnless { reactionDeleted(it.event) }
            if (known != null || saved?.accepted == true) {
                recordReaction(author, target.id, choice)
                return@withLock
            }
            val hint = relayLists[target.pubKey]?.write?.firstOrNull(::healthy)
            val tags = buildList {
                add(arrayOf("e", target.id, hint.orEmpty(), target.pubKey))
                add(arrayOf("p", target.pubKey))
                add(arrayOf("k", target.kind.toString()))
                choice.image?.let { add(arrayOf("emoji", choice.content.removeSurrounding(":"), it)) }
                if (target.kind in 30000..39999) target.tagValue("d")?.let { add(arrayOf("a", "${target.kind}:${target.pubKey}:$it")) }
            }.toTypedArray()
            val createdAt = maxOf(System.currentTimeMillis() / 1000, (previous?.event?.createdAt ?: 0) + 1)
            val signed = saved?.event ?: signerRequests.run { withTimeout(180_000) { signer.sign<Event>(createdAt, 7, tags, choice.content) } }.also {
                require(it.createdAt == createdAt && it.tags.contentDeepEquals(tags)) { "Signer changed the reaction." }
            }
            require(signed.pubKey == author && signed.kind == 7 && signed.content == choice.content && reactionChoice(signed) == choice &&
                reactionTarget(signed)?.id == target.id && signed.tagValue("p") == target.pubKey && signed.verify()) { "Invalid signed reaction." }
            check(state.value.publicKey == author) { "Account changed." }
            reactionStore.put(target.id, signed, accepted = false)
            // The external signer may still cover our Activity as its result arrives.
            withTimeout(15_000) { foregroundActive.first { it } }
            check(state.value.publicKey == author) { "Account changed." }
            publishingAuthor = target.pubKey
            try {
                updateRoutingPool()
                client.connect()
                val own = relayLists[author]?.write.orEmpty()
                val inbox = relayLists[target.pubKey]?.read.orEmpty()
                val routes = (own.filter { it in routingPool && healthy(it) }.take(2) +
                    inbox.filter { it in routingPool && healthy(it) }.take(2) + state.value.relays.filter(::healthy)).distinct().take(4)
                check(routes.isNotEmpty()) { "No relay available." }
                check(client.publishAndConfirm(signed, routes.map { it.normalizeRelayUrl() }.toSet(), timeoutInSeconds = 15, untilFirstAccept = true)) {
                    "No relay confirmed the reaction. Choose the same reaction to retry."
                }
                reactionStore.put(target.id, signed, accepted = true)
                accept(signed)
                recordReaction(author, target.id, choice)
            } finally {
                publishingAuthor = null
                if (active) updateRoutingPool()
            }
        }
    }

    internal suspend fun removeReaction(target: Event, choice: ReactionChoice, signer: NostrSignerExternal, signerRequests: SignerRequests) = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            ready.await()
            val author = state.value.publicKey ?: error("Connect a signer first")
            require(author == signer.pubKey)
            val cached = mutex.withLock { db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), tags = mapOf("e" to listOf(target.id)), limit = 256)) }
            val reactions = (cached + reactionStore.records(author).map { it.event }).filter {
                !reactionDeleted(it) && it.pubKey == author && it.kind == 7 && reactionTarget(it)?.id == target.id && reactionChoice(it) == choice
            }.distinctBy { it.id }
            if (reactions.isEmpty()) { changed.tryEmit(Unit); return@withLock }
            val ids = reactions.map { it.id }.toSet()
            val tags = (ids.sorted().map { arrayOf("e", it) } + listOf(arrayOf("k", "7"))).toTypedArray()
            val now = System.currentTimeMillis() / 1000
            val deletion = reactionDeletions.pending(author, ids) ?: signerRequests.run {
                withTimeout(180_000) { signer.sign<Event>(now, 5, tags, "") }.also {
                    require(it.createdAt == now) { "Signer changed deletion timestamp" }
                }
            }
            require(deletion.pubKey == author && deletion.kind == 5 && deletion.content.isEmpty() && deletion.tags.contentDeepEquals(tags) && deletion.verify())
            check(state.value.publicKey == author)
            reactionDeletions.put(deletion, accepted = false)
            withTimeout(15_000) { foregroundActive.first { it } }
            check(state.value.publicKey == author)
            publishingAuthor = target.pubKey
            try {
                updateRoutingPool(); client.connect()
                val routes = (relayLists[author]?.write.orEmpty().filter { it in routingPool && healthy(it) }.take(2) +
                    relayLists[target.pubKey]?.read.orEmpty().filter { it in routingPool && healthy(it) }.take(2) + state.value.relays.filter(::healthy)).distinct().take(4)
                check(routes.isNotEmpty())
                check(client.publishAndConfirm(deletion, routes.map { it.normalizeRelayUrl() }.toSet(), timeoutInSeconds = 15, untilFirstAccept = true)) { "Deletion not confirmed" }
                reactionDeletions.put(deletion, accepted = true)
                deletedReactions = reactionDeletions.deleted()
                _state.update { current -> current.copy(deletedReactions = deletedReactions,
                    sentReactions = if (current.publicKey == author) current.sentReactions + (target.id to current.sentReactions[target.id].orEmpty().filter { it != choice }) else current.sentReactions) }
                accept(deletion)
                changed.tryEmit(Unit)
            } finally { publishingAuthor = null; if (active) updateRoutingPool() }
        }
    }

    suspend fun decryptMuteList(event: Event, signer: NostrSignerExternal) = withContext(Dispatchers.IO) {
        require(event.pubKey == state.value.publicKey && signer.pubKey == event.pubKey)
        require(event.id == state.value.pendingMuteList?.id)
        val plaintext = withTimeout(180_000) {
            if ("?iv=" in event.content) signer.nip04Decrypt(event.content, event.pubKey)
            else signer.nip44Decrypt(event.content, event.pubKey)
        }
        ensureActive()
        if (event.pubKey != state.value.publicKey || event.id != state.value.pendingMuteList?.id) return@withContext
        muteCache.write(event, plaintext)
        snapshot()
    }

    suspend fun account(pubkey: String, signer: String) = withContext(Dispatchers.IO) {
        require(Protocol.publicKey(pubkey) == pubkey && signer.isNotBlank())
        prefs.edit().putString("pubkey", pubkey).putString("signer", signer).remove("draft").remove("draftReply").remove("pending").apply()
        emojiPackRequests = emptyList()
        client.unsubscribe("emoji-packs")
        feedUntil = null; feedCeilings.clear(); selectedFeed = ProfileTab.POSTS
        _state.update { it.copy(publicKey = pubkey, signerPackage = signer, following = emptySet(), notes = emptyList(), feedTab = ProfileTab.POSTS, feedTabs = listOf(ProfileTab.POSTS, ProfileTab.REPLIES), feedPreviews = emptyMap(), sentReactions = emptyMap(), reactionChoices = defaultReactions, customEmojis = emptyList(), mutedAuthors = emptySet(), pendingMuteList = null, loading = true) }
        ready.await(); snapshot(); if (active) subscribeFeed()
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        prefs.edit().remove("pubkey").remove("signer").remove("draft").remove("draftReply").remove("pending").apply()
        emojiPackRequests = emptyList()
        client.unsubscribe("emoji-packs")
        feedUntil = null; feedCeilings.clear(); selectedFeed = ProfileTab.POSTS
        _state.update { it.copy(publicKey = null, signerPackage = null, following = emptySet(), notes = emptyList(), feedTab = ProfileTab.POSTS, feedTabs = listOf(ProfileTab.POSTS, ProfileTab.REPLIES), feedPreviews = emptyMap(), sentReactions = emptyMap(), reactionChoices = defaultReactions, customEmojis = emptyList(), mutedAuthors = emptySet(), pendingMuteList = null) }
        snapshot(); if (active) subscribeFeed()
    }
    fun feedTab(tab: ProfileTab) {
        if (selectedFeed == tab) return
        feedUntil?.let { feedCeilings[selectedFeed] = it }
        selectedFeed = tab
        feedUntil = feedCeilings[tab]
        historyJob?.cancel()
        visibleIds = emptyList()
        _state.update { it.copy(loading = true, error = null) }
        changed.tryEmit(Unit)
        scope.launch { ready.await(); if (active) subscribeFeed() }
    }

    fun viewProfile(key: String?) { if (viewedProfile == key) return; historyJob?.cancel(); profileLimit = 100; profileUntil = null; profileTab = ProfileTab.POSTS; viewedProfile = key; changed.tryEmit(Unit); if (key == null) { client.unsubscribe("profile"); client.unsubscribe("profile-activity"); client.unsubscribe("profile-apps") }; if (active) subscribeFeed() }
    fun profileTab(tab: ProfileTab) {
        if (profileTab == tab) return
        historyJob?.cancel()
        profileTab = tab
        profileLimit = 100
        profileUntil = null
        changed.tryEmit(Unit)
        if (active) viewedProfile?.let(::subscribeProfile)
    }
    private fun subscribeProfile(key: String) {
        discoverRelays()
        // Apps are commonly published only to the catalog relay, not personal write relays.
        client.subscribe("profile-apps", mapOf(ZAPSTORE_RELAY.normalizeRelayUrl() to
            listOf(Filter(kinds = listOf(32267), authors = listOf(key), limit = if (profileTab == ProfileTab.APPS) 80 else 8))), listener)
        val routes = authorFilters(listOf(key), listOf(1), 80).keys
        // Separate kind filters keep ordinary notes from starving dedicated media posts.
        client.subscribe("profile", routes.associateWith {
            profileTab.kinds.flatMap { kind -> Filter(kinds = listOf(kind), authors = listOf(key), limit = 80).activityFilters() }
        }, listener)
        // Small independent existence probes keep rare kinds from being crowded out by notes.
        client.subscribe("profile-activity", routes.associateWith {
            profileKinds.filter { it !in profileTab.kinds }.flatMap { kind ->
                Filter(kinds = listOf(kind), authors = listOf(key), limit = if (kind == 1) 80 else if (kind in listOf(10003, 30000, 30003)) 8 else 1).activityFilters()
            }
        }, listener)
    }
    fun viewThread(id: String?, clicked: Event? = null) {
        if (viewedThread == id) return
        val cached = state.value
        val root = clicked?.takeIf { it.id == id && it.kind in readableKinds }
            ?: cached.threadNotes.find { it.id == id }
            ?: cached.embedded[id]
            ?: cached.profileEvents.find { it.id == id }
            ?: cached.hashtagNotes.find { it.id == id }
            ?: cached.notes.find { it.id == id }
        historyJob?.cancel()
        viewedThread = id
        // Hand off the already rendered event synchronously; no disk or relay round trip.
        _state.update { it.copy(threadId = id, threadNotes = listOfNotNull(root).takeIf { id != null }.orEmpty(),
            loading = id != null && root == null) }
        changed.tryEmit(Unit)
        scope.launch {
            if (viewedThread == null) client.unsubscribe("thread")
            if (active) subscribeFeed()
        }
    }
    private fun subscribeThread(id: String) {
        discoverRelays()
        val author = state.value.threadNotes.find { it.id == id }?.pubKey
        val routes = (state.value.relays + openedReference?.takeIf { it.id == id }?.relays.orEmpty() + relayLists[author]?.read.orEmpty() + relayLists[author]?.write.orEmpty()).distinct().filter { it in routingPool }
        val filters = listOf(Filter(ids = listOf(id), kinds = readableKinds), Filter(kinds = listOf(1), tags = mapOf("e" to listOf(id)), limit = 80))
        client.subscribe("thread", routes.associate { it.normalizeRelayUrl() to filters }, listener)
    }
    fun viewHashtag(tag: String?) {
        if (viewedHashtag == tag) return
        historyJob?.cancel()
        viewedHashtag = tag
        hashtagUntil = null
        hashtagLimit = 200
        changed.tryEmit(Unit)
        scope.launch {
            if (viewedHashtag == null) client.unsubscribe("hashtag")
            if (active) subscribeFeed()
        }
    }
    private fun hashtagFilters(tag: String, until: Long? = null) = state.value.relays.associate {
        it.normalizeRelayUrl() to listOf(Filter(kinds = listOf(1), tags = mapOf("t" to listOf(tag)), limit = 80, until = until))
    }
    @Synchronized
    fun older(author: String? = null): Boolean {
        if (!active || viewedThread != null || state.value.loadingOlder) return false
        val sequence = historySequence.incrementAndGet()
        val subscription = "older-$sequence"
        val profile = viewedProfile
        val tab = profileTab
        val feed = selectedFeed
        val tag = viewedHashtag
        val account = state.value.publicKey
        fun sameScreen() = active && profile == viewedProfile && tab == profileTab && feed == selectedFeed && tag == viewedHashtag && viewedThread == null && account == state.value.publicKey
        _state.update { it.copy(loadingOlder = true) }
        historyJob = scope.launch(start = CoroutineStart.LAZY) {
            try {
                ready.await()
                if (!sameScreen()) return@launch
                val current = state.value
                val authors = if (tag != null) null else author?.let(::listOf) ?: account?.let { (listOf(it) + current.following).take(1000) }
                val kinds = if (author != null) tab.kinds else if (tag != null) listOf(1) else feed.feedKinds()
                val events = (if (tag != null) current.hashtagNotes else if (author != null) current.profileEvents else current.notes)
                    .filter { it.kind in kinds && (authors == null || it.pubKey in authors || (it.kind == 9321 && it.tagValue("p") in authors) || (it.kind == 9735 && (it.tagValue("p") in authors || it.tagValue("P") in authors))) }
                val oldest = events.minOfOrNull { it.createdAt } ?: return@launch
                if (oldest <= 0) return@launch
                val filter = Filter(kinds = kinds, authors = authors, tags = tag?.let { mapOf("t" to listOf(it)) }, limit = 80, until = oldest - 1)
                val cached = mutex.withLock { db.query<Event>(filter.activityFilters()) }
                var found = cached.isNotEmpty()
                if (!found) {
                    val routes = if (tag != null) hashtagFilters(tag, oldest - 1) else authorFilters(authors, kinds, 80, oldest - 1)
                    if (routes.isEmpty()) return@launch
                    val complete = CompletableDeferred<Unit>()
                    val ended = java.util.concurrent.ConcurrentHashMap.newKeySet<NormalizedRelayUrl>()
                    val received = java.util.concurrent.atomic.AtomicBoolean()
                    val pageListener = object : SubscriptionListener {
                        override suspend fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                            if (sameScreen() && historySequence.get() == sequence && forFilters?.any { it.match(event) } == true && accept(event, history = true)) received.set(true)
                        }
                        override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                            ended.add(relay)
                            if (ended.containsAll(routes.keys)) complete.complete(Unit)
                        }
                        override fun onClosed(message: String, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                            ended.add(relay)
                            if (ended.containsAll(routes.keys)) complete.complete(Unit)
                        }
                    }
                    client.subscribe(subscription, routes, pageListener)
                    withTimeoutOrNull(12_000) { complete.await() }
                    found = received.get()
                }
                ensureActive()
                if (!sameScreen() || !found) return@launch
                // Keep the visible tail. Once capped, discard only one page's worth of
                // off-screen leading items so the next page fits, without replacing the list.
                val ceiling = events.getOrNull(80)?.createdAt
                if (tag != null) {
                    if (events.size >= 600) hashtagUntil = ceiling else hashtagLimit = (hashtagLimit + 80).coerceAtMost(600)
                } else if (author != null) {
                    if (events.size >= 600) profileUntil = ceiling else profileLimit = (profileLimit + 80).coerceAtMost(600)
                } else if (events.size >= 600) feedUntil = ceiling
                snapshot()
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (sameScreen()) _state.update { it.copy(error = "Couldn't load older notes. Pull to refresh.") }
            } finally {
                client.unsubscribe(subscription)
                if (historySequence.get() == sequence) _state.update { it.copy(loadingOlder = false) }
            }
        }
        historyJob?.start()
        return true
    }
    companion object { fun shortKey(key: String): String = "${key.take(8)}…${key.takeLast(4)}" }
}
