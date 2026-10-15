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
import com.vitorpamplona.quartz.nip01Core.store.IEventStore
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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
    internal val audio by lazy { AudioPlayback(this) }
    internal val timestamps by lazy { TimestampPreference(this) }
}

data class Profile(val name: String, val about: String = "", val picture: String? = null, val username: String? = null, val banner: String? = null, val lightning: String? = null, val aboutTokens: List<NostrContent.Token> = emptyList(), val nip05: String? = null, val website: String? = null)
data class FeedState(
    val notes: List<Event> = emptyList(),
    val notifications: List<Event> = emptyList(),
    val notificationsFor: String? = null,
    val notificationsLoading: Boolean = false,
    val feedTab: ProfileTab = ProfileTab.POSTS,
    val feedTabs: List<ProfileTab> = listOf(ProfileTab.POSTS, ProfileTab.REPLIES),
    val feedPreviews: Map<ProfileTab, List<Event>> = emptyMap(),
    val threadId: String? = null,
    val threadNotes: List<Event> = emptyList(),
    val conversation: Conversation? = null,
    val threadContextLoading: Boolean = false,
    val threadLoadingParent: String? = null,
    val deletedNotes: Set<String> = emptySet(),
    val profileEvents: List<Event> = emptyList(),
    val profileTabsFor: String? = null,
    val profileTabs: List<ProfileTab> = emptyList(),
    val hashtagNotes: List<Event> = emptyList(),
    val profiles: Map<String, Profile> = emptyMap(),
    val embedded: Map<String, Event> = emptyMap(),
    val engagement: Map<String, Engagement> = emptyMap(),
    val following: Set<String> = emptySet(),
    val sentReposts: Set<String> = emptySet(),
    val sentReactions: Map<String, List<ReactionChoice>> = emptyMap(),
    val deletedReactions: Set<Pair<String, String>> = emptySet(),
    val reactionChoices: List<ReactionChoice> = defaultReactions,
    val recentEmojis: List<String> = emptyList(),
    val customEmojis: List<ReactionChoice> = emptyList(),
    val mutedAuthors: Set<String> = emptySet(),
    val showSensitive: Boolean = false,
    val proofOfWork: Boolean = true,
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
    private val threadMutex = Mutex()
    private val recentThreads = RecentThreads(context, scope)
    private val threadChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val threadGeneration = java.util.concurrent.atomic.AtomicLong()
    private var threadRestoreJob: Job? = null
    private val reactionMutex = Mutex()
    private val repostStore = RepostStore(context)
    private val reactionStore = ReactionStore(context)
    private val reactionDeletions = ReactionDeletions(context)
    @Volatile private var deletedReactions: Set<Pair<String, String>> = emptySet()
    private fun engagementDeleted(event: Event) = event.kind in listOf(6, 7, 16) && (event.pubKey to event.id) in deletedReactions
    private val muteCache = MuteCache(context)
    private val foregroundActive = MutableStateFlow(false)
    @Volatile private var publishingAuthor: String? = null
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val zapVerification = ZapVerification(scope) { changed.tryEmit(Unit) }
    @Volatile private var engagementIds: List<String> = emptyList()
    @Volatile private var engagementApps: List<String> = emptyList()
    private val _state = MutableStateFlow(FeedState(
        showSensitive = prefs.getBoolean("show-sensitive", false),
        proofOfWork = prefs.getBoolean("proof-of-work", true),
        publicKey = prefs.getString("pubkey", null)?.takeIf { !prefs.getString("signer", null).isNullOrBlank() }, signerPackage = prefs.getString("signer", null)?.takeIf { it.isNotBlank() },
    ))
    suspend fun showSensitive(show: Boolean) = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("show-sensitive", show).commit()) { "Could not save content setting" }
        _state.update { it.copy(showSensitive = show) }
        feedDiscoveryAt = 0L
        threadChanges.tryEmit(Unit)
        changed.tryEmit(Unit)
    }
    suspend fun proofOfWork(enabled: Boolean) = withContext(Dispatchers.IO) {
        check(prefs.edit().putBoolean("proof-of-work", enabled).commit()) { "Could not save proof-of-work setting" }
        _state.update { it.copy(proofOfWork = enabled) }
    }
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private val threadProgress = ThreadProgress(scope) { target, parent, loading ->
        _state.update { if (it.threadId == target) it.copy(threadContextLoading = loading, threadLoadingParent = parent.takeIf { loading }) else it }
    }
    private val client = NostrClient(RelayTransport(), scope)
    private val ready = CompletableDeferred<Unit>()
    private val verified = object : LinkedHashMap<String, Boolean>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2048
    }
    // Full verified events, not a record of IDs that might since have been evicted.
    // Accessed under mutex, alongside the disk cache.
    private val exactEventCache = object : LinkedHashMap<String, Event>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Event>?) = size > 128
    }
    private val eventLookupChanges = Channel<Unit>(Channel.CONFLATED)
    // Repository monitor owns network state; all readers share this one subscription.
    private var pendingEventLookups: Map<String, Set<String>> = emptyMap()
    private val eventIdPattern = Regex("[0-9a-f]{64}")
    @Volatile private var active = false
    private var notificationTimeout: Job? = null
    @Volatile private var viewingNotifications = false
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
    @Volatile private var threadContext: Conversation? = null
    @Volatile private var threadParents: List<String> = emptyList()
    @Volatile private var threadExpanded: Set<String> = emptySet()
    @Volatile private var threadAncestorLimit = INITIAL_THREAD_ANCESTORS
    @Volatile private var threadRequestedParent: NostrContent.Reference? = null
    private var threadParentJob: Job? = null
    private val profileQueries = ProfileRelayQuery()
    private var profileSweep: Job? = null
    private var profileSweepKey: Triple<String, ProfileTab, List<String>>? = null
    @Synchronized
    private fun stopProfileSweep() { profileSweep?.cancel(); profileSweep = null; profileSweepKey = null }
    private fun profileReadRelays(author: String, tab: ProfileTab): List<String> =
        (relayLists[author]?.write.orEmpty().ifEmpty { state.value.relays } +
            listOfNotNull(ZAPSTORE_RELAY.takeIf { tab == ProfileTab.APPS }) +
            if (tab == ProfileTab.ISSUES) IssueReports.RELAYS else emptyList()).distinct()

    private var historyJob: Job? = null
    private var refreshTimeout: Job? = null
    @Volatile private var routingPool: Set<String> = emptySet()
    @Volatile private var relayLists: Map<String, Outbox.RelayList> = emptyMap()
    @Volatile private var visibleIds: List<String> = emptyList()
    @Volatile private var galleryNote: Event? = null
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
        if (viewedThread != null) threadChanges.tryEmit(Unit)
        changed.tryEmit(Unit)
    }
    fun openReference(reference: NostrContent.Reference?) { openedReference = reference }
    fun readingGallery(note: Event?) {
        if (galleryNote?.id == note?.id) return
        galleryNote = note
        changed.tryEmit(Unit)
    }

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
            threadProgress.completed(relay.url, forFilters)
            unavailableUntil.remove(Protocol.relay(relay.url) ?: relay.url)
            if (viewingNotifications && forFilters?.any { it.authors == null && it.tags?.get("p") == listOf(state.value.publicKey) && it.kinds?.any { kind -> kind in notificationKinds } == true } == true)
                _state.update { it.copy(notificationsLoading = false) }
            if (forFilters?.any { it.kinds?.any { kind -> kind in profileKinds } == true } == true) _state.update { it.copy(loading = false) }
        }
        override fun onClosed(message: String, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            threadProgress.completed(relay.url, forFilters)
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
            try { recentThreads.load(); trimCache(); ready.complete(Unit); snapshot() }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = "Could not open the local cache.") }; ready.completeExceptionally(e) }
        }
        scope.launch { ready.await(); changed.sample(250).collect { snapshot() } }
        scope.launch { ready.await(); threadChanges.sample(150).collect { refreshConversation() } }
        scope.launch {
            ready.await()
            for (ignored in eventLookupChanges) {
                try { reconcileEventLookups() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    // A failed cache read must not turn into a redundant network request.
                    _state.update { it.copy(error = "Could not read cached event: ${e.message ?: e.javaClass.simpleName}") }
                }
            }
        }
    }
    fun foreground() {
        active = true
        foregroundActive.value = true
        threadChanges.tryEmit(Unit)
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
    @Synchronized
    fun background() { active = false; stopProfileSweep(); foregroundActive.value = false; blocktime.stop(); zapVerification.stop(); discoveryJob?.cancel(); historyJob?.cancel(); threadParentJob?.cancel(); refreshTimeout?.cancel(); pendingEventLookups = emptyMap(); client.unsubscribe("event-ids"); client.disconnect(); _state.update { it.copy(connected = emptySet(), loading = false) } }
    fun refresh() {
        historyJob?.cancel()
        if (viewedThread != null) {
            _state.update { it.copy(loading = true, error = null) }
            scope.launch {
                ready.await()
                threadChanges.tryEmit(Unit)
                if (active) {
                    client.unsubscribe("thread")
                    client.unsubscribe("thread-deletions")
                    changed.tryEmit(Unit); subscribeFeed()
                }
            }
            return
        }
        val profile = viewedProfile.takeIf { viewedThread == null && viewedHashtag == null }
        if (profile != null) {
            profileUntil = null; profileLimit = 100
            _state.update { it.copy(loading = true, error = null) }
            scope.launch {
                ready.await()
                if (!active || viewedProfile != profile) return@launch
                // Explicit refresh must send a new REQ even when the filters are unchanged.
                relayLists[profile]?.write.orEmpty().forEach { unavailableUntil.remove(it) }
                updateRoutingPool()
                stopProfileSweep()
                client.unsubscribe("profile")
                if (profileTab == ProfileTab.APPS) client.unsubscribe("profile-apps")
                changed.tryEmit(Unit)
                subscribeProfile(profile)
                client.connect()
                refreshTimeout?.cancel()
                refreshTimeout = scope.launch {
                    delay(12_000)
                    _state.update { it.copy(loading = false) }
                }
            }
            return
        }
        feedUntil = null; feedCeilings.remove(selectedFeed); feedDiscoveryAt = 0; profileUntil = null; hashtagUntil = null
        profileLimit = 100; hashtagLimit = 200
        _state.update { it.copy(loading = true, error = null) }
        scope.launch { ready.await(); if (active) { changed.tryEmit(Unit); subscribeFeed(); client.connect() } }
    }

    /** Keep a small live subscription; finite profile sweeps cover every advertised write relay. */
    private fun profileFilters(author: String, kinds: List<Int>, limit: Int, until: Long? = null): Map<NormalizedRelayUrl, List<Filter>> {
        val writes = relayLists[author]?.write.orEmpty()
        val routes = (if (writes.isNotEmpty()) writes else state.value.relays).filter { it in routingPool && healthy(it) }.take(2)
        val filters = kinds.flatMap { kind -> Filter(authors = listOf(author), kinds = listOf(kind), limit = limit, until = until).activityFilters() }
        val result = routes.associate { it.normalizeRelayUrl() to filters }
        val catalog = if (32267 in kinds) result + (ZAPSTORE_RELAY.normalizeRelayUrl() to
            listOf(Filter(authors = listOf(author), kinds = listOf(32267), limit = limit, until = until))) else result
        return withIssueRoutes(catalog, listOf(author), kinds, limit, until)
    }
    private fun authorFilters(authors: List<String>?, kinds: List<Int>, limit: Int, until: Long? = null): Map<NormalizedRelayUrl, List<Filter>> {
        val routes = (if (authors == null) routingPool.filter { it != "wss://purplepag.es" }
            .associate { it.normalizeRelayUrl() to listOf(Filter(kinds = kinds, limit = limit, until = until)) }
        else Outbox.readRoutes(authors, relayLists, state.value.relays, allowed = routingPool).mapKeys { it.key.normalizeRelayUrl() }
            .mapValues { (_, keys) -> listOf(Filter(authors = keys, kinds = kinds, limit = limit, until = until)) }).mapValues { (_, filters) -> filters.flatMap { it.activityFilters() } }
        val catalog = if (32267 in kinds) routes + (ZAPSTORE_RELAY.normalizeRelayUrl() to
            listOf(Filter(authors = authors, kinds = listOf(32267), limit = limit, until = until))) else routes
        return withIssueRoutes(catalog, authors, kinds, limit, until)
    }
    private fun withIssueRoutes(routes: Map<NormalizedRelayUrl, List<Filter>>, authors: List<String>?, kinds: List<Int>, limit: Int, until: Long?): Map<NormalizedRelayUrl, List<Filter>> {
        if (1621 !in kinds) return routes
        val result = routes.toMutableMap()
        IssueReports.RELAYS.forEach { url ->
            val relay = url.normalizeRelayUrl()
            result[relay] = result[relay].orEmpty() + Filter(authors = authors, kinds = listOf(1621), limit = limit, until = until)
        }
        return result
    }
    private fun discoverRelays() {
        val s = state.value
        val threadAuthor = s.threadNotes.find { it.matchesThread(viewedThread) }?.pubKey
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
        val threadAuthor = s.threadNotes.find { it.matchesThread(viewedThread) }?.pubKey
        val fallback = if (embeddedIds.any { it !in s.embedded })
            (RelayDefaults.seeds.drop(embeddedSeedOffset) + RelayDefaults.seeds.take(embeddedSeedOffset)).filter(::healthy).take(2)
            else emptyList()
        val publishRoutes = publishingAuthor?.let { author ->
            own?.write.orEmpty().filter(::healthy).take(2) + relayLists[author]?.read.orEmpty().filter(::healthy).take(2)
        }.orEmpty()
        val priority = (relayLists[viewedProfile]?.write.orEmpty().take(2) + publishRoutes + listOf("wss://purplepag.es") + openedReference?.relays.orEmpty() +
            references.flatMap { it.relays }.distinct().take(2) + emojiPackRequests.mapNotNull { it.relay }.distinct().take(2) +
            emojiPackRequests.flatMap { relayLists[it.author]?.write.orEmpty() }.distinct().take(2) + fallback +
            relayLists[threadAuthor]?.read.orEmpty().take(2) + relayLists[threadAuthor]?.write.orEmpty().take(2) +
            threadBranchAuthors().flatMap { relayLists[it]?.read.orEmpty().take(1) + relayLists[it]?.write.orEmpty().take(1) }.distinct().take(3) +
            own?.read.orEmpty() + own?.write.orEmpty() +
            profileAuthors.take(12).flatMap { relayLists[it]?.write.orEmpty().take(1) }).distinct().filter(::healthy)
        val authors = (listOfNotNull(s.publicKey, viewedProfile, threadAuthor) + profileAuthors.take(64) + s.following.take(999) + s.notes.take(40).map { it.pubKey }.takeIf { s.publicKey == null }.orEmpty()).distinct()
        val usableLists = relayLists.mapValues { (_, list) -> Outbox.RelayList(list.read.filter(::healthy), list.write.filter(::healthy)) }
        val popular = Outbox.readRoutes(authors, usableLists, s.relays).keys
        val next = (s.relays + (priority + popular).mapNotNull(Protocol::relay).distinct().filter { it !in s.relays }.take(8)).toSet()
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
                    Filter(authors = listOf(key), kinds = listOf(10063), limit = 1) +
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
        subscribeNotifications()
        if (refreshTimeout?.isActive == true) return
        refreshTimeout = scope.launch {
            delay(12_000)
            _state.update { it.copy(loading = false, error = if (it.connected.isEmpty()) "No relay connection. Cached notes are still available." else it.error) }
        }
    }
    /** Notifications use only the active account’s bounded inbox subscription. */
    fun viewNotifications(show: Boolean) {
        if (viewingNotifications == show) return
        viewingNotifications = show
        if (show) refreshNotifications()
        else { notificationTimeout?.cancel(); client.unsubscribe("notifications"); _state.update { it.copy(notificationsLoading = false) } }
        scope.launch { ready.await(); changed.tryEmit(Unit); if (active) subscribeFeed() }
    }
    fun refreshNotifications() {
        if (!viewingNotifications || state.value.publicKey == null) return
        notificationTimeout?.cancel()
        _state.update { it.copy(notificationsLoading = true) }
        notificationTimeout = scope.launch {
            ready.await()
            client.unsubscribe("notifications")
            if (active) { updateRoutingPool(); subscribeNotifications() }
            changed.tryEmit(Unit)
            delay(8_000)
            _state.update { it.copy(notificationsLoading = false) }
        }
    }
    private fun subscribeNotifications() {
        val owner = state.value.publicKey
        if (!viewingNotifications || owner == null) { client.unsubscribe("notifications"); return }
        val inbox = relayLists[owner]?.read.orEmpty().filter { it in routingPool && healthy(it) }
            .ifEmpty { state.value.relays }.take(4)
        client.subscribe("notifications", inbox.associate { relay -> relay.normalizeRelayUrl() to
            notificationKinds.map { kind -> Filter(kinds = listOf(kind), tags = mapOf("p" to listOf(owner)), limit = 100) }
        }, listener)
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
        else client.subscribe("engagement", (routingPool.filter { it != "wss://purplepag.es" } + if (engagementApps.isNotEmpty() || state.value.threadNotes.any { it.isAppDiscussion() }) listOf(ZAPSTORE_RELAY) else emptyList()).distinct().associate { url ->
            url.normalizeRelayUrl() to (engagementIds.chunked(6).flatMap { ids ->
                listOf(1, 1111, 1244, 7, 9735).map { kind -> Filter(kinds = listOf(kind), tags = mapOf("e" to ids), limit = 300) }
            } + engagementApps.map { Filter(kinds = listOf(1111), tags = mapOf("A" to listOf(it)), limit = 200) })
        }, listener)
        val owner = state.value.publicKey
        if (owner == null || engagementIds.isEmpty()) client.unsubscribe("own-reactions")
        else {
            // Dedicated author filters cannot be crowded out by other people's reactions.
            val routes = (relayLists[owner]?.write.orEmpty() + state.value.relays)
                .distinct().filter { it in routingPool && healthy(it) }.take(4)
            client.subscribe("own-reactions", routes.associate { relay -> relay.normalizeRelayUrl() to
                engagementIds.chunked(6).map { targets ->
                    Filter(authors = listOf(owner), kinds = listOf(7), tags = mapOf("e" to targets), limit = 128)
                }
            }, listener)
        }
        // Keep address filters live while visible so replacement article versions arrive.
        val addressFilters = (references.filter { it.id in embeddedIds } + threadContext?.takeIf { it.target == viewedThread }?.missing.orEmpty())
            .distinctBy { it.id }.mapNotNull { it.addressFilter() }.take(24)
        if (addressFilters.isEmpty()) client.unsubscribe("embedded")
        else {
            val routes = routingPool.filter { it != "wss://purplepag.es" }.associate { it.normalizeRelayUrl() to addressFilters }
            routes.forEach { (relay, filters) -> threadProgress.sent(relay.url, filters) }
            client.subscribe("embedded", routes, listener)
        }
        eventLookupChanges.trySend(Unit)
    }

    /** Exact IDs are immutable. Always resolve complete memory/disk copies before relay I/O. */
    private suspend fun cachedEventsLocked(ids: List<String>): Map<String, Event> {
        val wanted = ids.filter(eventIdPattern::matches).distinct()
        if (wanted.isEmpty()) return emptyMap()
        val wantedIds = wanted.toHashSet()
        val s = state.value
        val memory = sequenceOf(
            exactEventCache.values.asSequence(), s.embedded.values.asSequence(), s.threadNotes.asSequence(),
            s.notes.asSequence(), s.profileEvents.asSequence(), s.hashtagNotes.asSequence(),
            s.notifications.asSequence(), s.feedPreviews.values.asSequence().flatten(),
            olderEvents.asSequence(), listOfNotNull(galleryNote).asSequence(),
            recentThreads.events(wanted).asSequence(),
        ).flatten().filter { it.id in wantedIds }.associateBy { it.id }
        val missing = wanted.filterNot(memory::containsKey)
        val disk = if (missing.isEmpty()) emptyList() else db.query<Event>(Filter(ids = missing, limit = missing.size))
        return (memory + disk.associateBy { it.id }).also { found ->
            found.forEach { (id, event) -> exactEventCache[id] = event }
        }
    }

    /** One thread root, eight missing parents and 24 visible embeds; one shared request path. */
    private fun wantedEventLookups(): Map<String, Set<String>> {
        val result = linkedMapOf<String, Set<String>>()
        viewedThread?.takeIf(eventIdPattern::matches)?.let { id -> result[id] = threadRelays(id).toSet() }
        val thread = viewedThread
        threadContext?.takeIf { it.target == thread }?.missing.orEmpty().take(8).forEach { ref ->
            if (eventIdPattern.matches(ref.id) && (ref.kind == null || ref.kind in readableKinds) && ref.author !in state.value.mutedAuthors) {
                result[ref.id] = (thread?.let(::threadRelays).orEmpty() + ref.relays + relayLists[ref.author]?.write.orEmpty())
                    .filter { it in routingPool }.toSet()
            }
        }
        val embeddedRelays = routingPool.filter { it != "wss://purplepag.es" }.toSet()
        embeddedIds.asSequence().filter(eventIdPattern::matches).take(24).forEach { id ->
            result[id] = result[id].orEmpty() + embeddedRelays
        }
        return result.filterKeys { it !in state.value.deletedNotes }
    }

    private suspend fun reconcileEventLookups(retryId: String? = null) = mutex.withLock {
        if (!active) return@withLock
        val wanted = wantedEventLookups()
        val cached = cachedEventsLocked(wanted.keys.toList())
        synchronized(this) {
            if (!active) return@synchronized
            // Navigation/routing may have changed while SQLite was reading. Reconcile the latest intent.
            if (wanted != wantedEventLookups()) { eventLookupChanges.trySend(Unit); return@synchronized }
            pendingEventLookups = wanted.filterKeys { it !in cached }
            // Explicit retry of an exhausted lookup only, after checking the cache again.
            // Restart the shared subscription so all consumers still share one request per ID.
            if (retryId != null && retryId in pendingEventLookups) client.unsubscribe("event-ids")
            sendEventLookups()
        }
        // Let every consumer render the same cached copy, even if it came from another screen.
        val readable = cached.filterValues { it.kind in readableKinds }
        if (readable.any { (id, event) -> state.value.embedded[id]?.id != event.id }) {
            _state.update { s -> s.copy(embedded = (s.embedded + readable).entries.toList().takeLast(128).associate { it.toPair() }) }
            changed.tryEmit(Unit)
        }
    }

    /** Called under the cache mutex after validation, including deliveries on discovery subscriptions. */
    private fun completeEventLookup(event: Event) = synchronized(this) {
        if (event.id !in pendingEventLookups) return@synchronized
        exactEventCache[event.id] = event
        pendingEventLookups = pendingEventLookups - event.id
        sendEventLookups()
        changed.tryEmit(Unit)
    }

    @Synchronized
    private fun sendEventLookups() {
        val routes = pendingEventLookups.values.flatten().distinct().sorted().associate { relay ->
            val ids = pendingEventLookups.filterValues { relay in it }.keys.sorted()
            relay.normalizeRelayUrl() to listOf(Filter(ids = ids, kinds = readableKinds, limit = ids.size))
        }
        if (!active || routes.isEmpty()) client.unsubscribe("event-ids")
        else {
            routes.forEach { (relay, filters) -> threadProgress.sent(relay.url, filters) }
            client.subscribe("event-ids", routes, listener)
        }
    }

    internal suspend fun cacheSearchProfile(event: Event) = withContext(Dispatchers.IO) {
        if (event.kind == 0) accept(event)
    }

    internal suspend fun cacheIssue(event: Event) = withContext(Dispatchers.IO) {
        ready.await()
        if (event.kind == 1621) { accept(event); feedDiscoveryAt = 0; changed.tryEmit(Unit) }
    }

    private suspend fun accept(event: Event, history: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        acceptOnIo(event, history)
    }

    private suspend fun acceptOnIo(event: Event, history: Boolean): Boolean {
        if (event.kind == 5 && event.tags.count { it.firstOrNull() == "e" } > 256) return false
        if (event.kind !in (profileKinds + listOf(0, 3, 5, 6, 9735, 10000, 10002, 10030, 10063, 30030)) || event.content.length > (if (event.kind in listOf(1621, 30023, 10000) || (event.kind == 30000 && event.tagValue("d") == "mute")) 262_144 else 32_768) || event.tags.size > 5000 || event.createdAt > System.currentTimeMillis() / 1000 + 600) return false
        mutex.withLock {
            val cached = verified[event.id]
            if (cached == false) return false
            val requested = synchronized(this) { event.id in pendingEventLookups }
            // A repeated claimed ID is not proof that this payload is the verified copy.
            // Discard duplicates; never promote their unchecked contents into the lookup cache.
            if (cached == true && !history && !requested) return true
            if (!event.verify()) return false
            // An explicit miss can outlive the stored copy (cache eviction). Re-cache it even
            // when its ID remains in the small verification memo.
            if (cached == null || requested) {
                // Quartz rolls back rejected rows and distinguishes normal NIP-01
                // replacement/duplicate outcomes from actual storage failures.
                val outcome = try { db.batchInsert(listOf(event)).single() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    _state.update { it.copy(error = "Could not cache event: ${e.message ?: e.javaClass.simpleName}") }
                    return false
                }
                when (outcome) {
                    IEventStore.InsertOutcome.Accepted -> verified[event.id] = true
                    is IEventStore.InsertOutcome.Rejected -> {
                        val duplicate = outcome.reason.startsWith("duplicate:")
                        verified[event.id] = duplicate
                        if (!duplicate) {
                            if (outcome.reason.startsWith("replaced:")) {
                                // An exact link can intentionally name an older article version.
                                // Retain that verified copy for the lookup without replacing the
                                // newer addressable record used by discovery.
                                verified.remove(event.id)
                                completeEventLookup(event)
                            }
                            return false
                        }
                    }
                    is IEventStore.InsertOutcome.Failed -> {
                        // Do not mark failed writes as cached: a later relay delivery can retry.
                        _state.update { it.copy(error = "Could not cache event: ${outcome.reason}") }
                        return false
                    }
                }
                // Protect an older reply as soon as it arrives, before any cache pruning.
                retainThreadEvent(event)
                if (outcome == IEventStore.InsertOutcome.Accepted && ++inserts % 200 == 0) trimCacheLocked()
            }
            completeEventLookup(event)
            if (history) olderEvents = (olderEvents + event).distinctBy { it.id }
                .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).takeLast(600)
        }
        if (event.kind == 5 && event.pubKey == state.value.publicKey) {
            reactionDeletions.put(event, accepted = true)
            deletedReactions = reactionDeletions.deleted()
        }
        if (event.kind in listOf(6, 16) && event.content.isNotBlank()) {
            val target = repostTarget(event)
            val original = runCatching { Event.fromJson(event.content) }.getOrNull()
                ?.takeIf { it.kind in readableKinds && (target?.kind == null || it.kind == target.kind) && it.id == target?.id && (target.author == null || it.pubKey == target.author) }
            // Independently validate the enclosed original on IO before storing/displaying it.
            if (original != null) accept(original)
        }
        changed.tryEmit(Unit)
        if (viewedThread != null && event.kind in (readableKinds + 5)) threadChanges.tryEmit(Unit)
        return true
    }
    private suspend fun timeline(filter: Filter, previous: List<Event>, limit: Int): List<Event> = mutex.withLock {
        val filters = filter.activityFilters()
        val matching = (db.query<Event>(filters) + previous + olderEvents).filter { event -> filters.any { it.match(event) } }
        // Sparse posts/replies tabs can have visible items far ahead of their raw-event tail.
        // Keep these anchors when advancing the bounded window.
        val pinned = if (filter.until != null) previous.filter { it.id in visibleIds } else emptyList()
        (matching + pinned).asSequence().filterNot(::engagementDeleted).distinctBy { it.id }
            .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).take(limit).toList()
    }
    private suspend fun snapshot() = snapshotMutex.withLock { snapshotContents() }

    private data class ThreadRequest(val id: String, val generation: Long, val account: String?, val sensitive: Boolean, val muted: Set<String>,
        val ancestors: Int, val expanded: Set<String>, val requestedParent: NostrContent.Reference?,
        val reference: NostrContent.Reference?, val parents: List<String>)

    @Synchronized
    private fun threadRequest(): ThreadRequest? = viewedThread?.let {
        ThreadRequest(it, threadGeneration.get(), state.value.publicKey, state.value.showSensitive, state.value.mutedAuthors,
            threadAncestorLimit, threadExpanded, threadRequestedParent, openedReference?.takeIf { ref -> ref.id == it }, threadParents)
    }

    /** A dedicated cache-only path. Feed, profile and engagement work never gates this display. */
    private suspend fun refreshConversation() {
        try { updateConversation() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) {
            // Keep the existing view and the update collector alive after a transient cache error.
            android.util.Log.w("FeatherRepository", "Could not restore the cached conversation", e)
        }
    }

    private suspend fun updateConversation() = threadMutex.withLock {
        val request = threadRequest() ?: return@withLock
        val muteList = request.account?.let { author -> mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(10000), authors = listOf(author), limit = 1)).firstOrNull()
                ?: db.query<Event>(Filter(kinds = listOf(30000), authors = listOf(author), tags = mapOf("d" to listOf("mute")), limit = 1)).firstOrNull()
        } }
        val muted = request.muted + mutedPublicKeys(muteList) + muteList?.takeIf { it.content.isNotBlank() }?.let { muteCache.read(it, exact = false) }.orEmpty()
        val (notes, view, deletions) = loadConversation(request, muted)
        currentCoroutineContext().ensureActive()
        synchronized(this) {
            if (threadRequest() != request) return@withLock
            recentThreads.remember(request.id, notes, deletions)
            threadContext = view
            if (request.requestedParent != null && view?.rows.orEmpty().none {
                    it.gap == ThreadGap.MISSING && it.reference?.id == request.requestedParent.id
                }) threadRequestedParent = null
            val replyRows = view?.rows.orEmpty().filter { !it.ancestor }.mapNotNull { it.event }
            threadParents = (threadExpanded.toList().takeLast(4) + replyRows.filter { it.id in visibleIds }.map { it.id } +
                replyRows.take(6).map { it.id }).distinct().take(11)
            _state.update { it.copy(threadNotes = notes, conversation = view,
                loading = if (view != null) false else it.loading,
                deletedNotes = (it.deletedNotes + view?.deleted.orEmpty()).toList().takeLast(512).toSet()) }
            if (view != null) threadProgress.expect(request.id,
                (threadRequestedParent ?: view.rows.firstOrNull { it.ancestor && it.gap == ThreadGap.MISSING }?.reference)
                    ?.takeIf { it.kind == null || it.kind in readableKinds })
        }
        changed.tryEmit(Unit) // Author pictures and engagement can fill in independently.
        if (active && viewedThread == request.id) { subscribeThread(request.id); subscribeReadingData() }
    }

    /** Retain only events connected to the opened thread, never the concurrent general feed. */
    private fun retainThreadEvent(event: Event) {
        val id = viewedThread ?: return
        val current = state.value.threadNotes.takeIf { state.value.threadId == id }.orEmpty()
        val known = (recentThreads.ids(id) + current.flatMap { listOfNotNull(it.id, ThreadRelations.address(it)) } + id).toSet()
        val missing = threadContext?.takeIf { it.target == id }?.missing.orEmpty().map { it.id }
        if (event.kind == 5) {
            if (event.tags.any { it.firstOrNull() in listOf("e", "a") && it.getOrNull(1) in known })
                recentThreads.remember(id, emptyList(), listOf(event))
        } else if (event.kind in readableKinds && (event.id in known || event.id in missing || event.matchesThread(id) ||
                ThreadRelations.parent(event)?.id in known || ThreadRelations.root(event)?.id in known)) {
            recentThreads.remember(id, listOf(event))
        }
    }

    /** Resolve ancestry locally first, then project a bounded graph around the selected event. */
    private suspend fun loadConversation(request: ThreadRequest, mutedAuthors: Set<String>): Triple<List<Event>, Conversation?, List<Event>> {
        val id = request.id
        val ancestorLimit = request.ancestors
        val requestedParent = request.requestedParent
        val addressFilter = request.reference?.addressFilter()
        val remembered = mutex.withLock { cachedEventsLocked(recentThreads.ids(id)).values.toList() }
        val focus = mutex.withLock {
            if (addressFilter != null) db.query<Event>(addressFilter).firstOrNull()
            else cachedEventsLocked(listOf(id))[id]?.takeIf { it.kind in readableKinds }
        } ?: (state.value.threadNotes + remembered).find { it.matchesThread(id) } ?: return Triple(emptyList(), null, emptyList())
        val filter = ContentFilter(mutedAuthors, state.value.embedded, showSensitive = request.sensitive, deletedNotes = state.value.deletedNotes)
        val ancestors = linkedMapOf(focus.id to focus)
        var cursor = focus
        for (depth in 0..ancestorLimit) {
            val ref = ThreadRelations.parent(cursor) ?: break
            if (ref.id in ancestors || ref.author in mutedAuthors || filter.hides(cursor) || (ref.kind != null && ref.kind !in readableKinds)) break
            val parent = mutex.withLock {
                ref.addressFilter()?.let { db.query<Event>(it).firstOrNull() } ?: cachedEventsLocked(listOf(ref.id))[ref.id]
            } ?: break
            if (filter.hides(parent)) { ancestors[parent.id] = parent; break }
            ancestors[parent.id] = parent; cursor = parent
        }
        val cached = mutex.withLock { db.query<Event>(ThreadRelations.discovery(focus, id, request.parents)) }
        val retained = state.value.threadNotes.takeIf { state.value.threadId == id }.orEmpty()
        val pool = (ancestors.values + retained.filter { it.id in visibleIds } + remembered + cached + retained).distinctBy { it.id }.take(320).associateByTo(linkedMapOf()) { it.id }
        val missingParents = (listOfNotNull(requestedParent) + threadContext?.takeIf { it.target == id }?.missing.orEmpty() + pool.values.mapNotNull(ThreadRelations::parent))
            .filter { it.id !in pool && it.author !in mutedAuthors && (it.kind == null || it.kind in readableKinds) }.distinctBy { it.id }.take(32)
        val resolvedParents = mutex.withLock {
            cachedEventsLocked(missingParents.map { it.id }).values.toList() + missingParents.mapNotNull { ref ->
                ref.addressFilter()?.let { db.query<Event>(it).firstOrNull() }
            }
        }
        val withParents = (ancestors.values + resolvedParents + pool.values).distinctBy { it.id }.take(320)
        pool.clear(); withParents.forEach { pool[it.id] = it }
        // Root-tagged replies arrive together. Parent-only legacy threads need a few local joins.
        var parents = listOf(focus)
        repeat(3) {
            val ids = parents.filterNot(filter::hides).map { it.id }.distinct().take(32)
            if (ids.isNotEmpty()) {
                val children = mutex.withLock { db.query<Event>(Filter(kinds = listOf(1, 1111, 1244), tags = mapOf("e" to ids), limit = 200)) }
                children.forEach { if (pool.size < 320) pool.putIfAbsent(it.id, it) }
                val parentKeys = parents.flatMap { listOfNotNull(it.id, ThreadRelations.address(it)) }.toSet()
                parents = pool.values.filter { ThreadRelations.parent(it)?.id in parentKeys }
            }
        }
        val addresses = pool.values.mapNotNull(ThreadRelations::address).distinct()
        val deletions = mutex.withLock {
            (cachedEventsLocked(recentThreads.deletionIds(id)).values +
                db.query<Event>(listOf(Filter(kinds = listOf(5), tags = mapOf("e" to pool.keys.toList()), limit = 256)) +
                    if (addresses.isEmpty()) emptyList() else listOf(Filter(kinds = listOf(5), tags = mapOf("a" to addresses), limit = 256))))
                .filter { it.kind == 5 }.distinctBy { it.id }
        }
        val deletedIds = mutableSetOf<Pair<String, String>>()
        val deletedAddresses = mutableMapOf<Pair<String, String>, Long>()
        deletions.forEach { deletion -> deletion.tags.forEach { tag ->
            val value = tag.getOrNull(1)
            if (value != null) when {
                tag[0] == "e" && value in pool -> deletedIds += deletion.pubKey to value
                tag[0] == "a" && value in addresses -> {
                    val key = deletion.pubKey to value
                    deletedAddresses[key] = maxOf(deletedAddresses[key] ?: Long.MIN_VALUE, deletion.createdAt)
                }
            }
        } }
        val deleted = pool.values.filter { event ->
            (event.pubKey to event.id) in deletedIds || ThreadRelations.address(event)?.let { address ->
                deletedAddresses[event.pubKey to address]?.let { event.createdAt <= it }
            } == true
        }.map { it.id }.toSet()
        var view = conversation(id, focus, pool.values.toList(), filter, deleted + state.value.deletedNotes, request.expanded, ancestorLimit)
        // A tapped branch gap gets a slot in the existing eight-parent lookup budget.
        if (requestedParent != null && view.rows.any { it.gap == ThreadGap.MISSING && it.reference?.id == requestedParent.id }) {
            view = view.copy(missing = (listOf(requestedParent) + view.missing).distinctBy { it.id }.take(8))
        }
        return Triple(pool.values.toList(), view, deletions)
    }

    private suspend fun snapshotContents() {
        deletedReactions = reactionDeletions.deleted()
        val key = _state.value.publicKey
        val thread = viewedThread
        val ancestorLimit = threadAncestorLimit
        val requestedParent = threadRequestedParent
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
            val feedFilter = ContentFilter(mutedAuthors, state.value.embedded, showSensitive = state.value.showSensitive, deletedNotes = state.value.deletedNotes)
            val discoveryEvents = latestProfileEvents(discoveredFeed).filterNot(::engagementDeleted).filterNot(feedFilter::hides)
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
        val threadState = state.value.takeIf { it.threadId == thread }
        val threadNotes = threadState?.threadNotes.orEmpty()
        val conversation = threadState?.conversation
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
        val contentFilter = ContentFilter(mutedAuthors, state.value.embedded, showSensitive = state.value.showSensitive, deletedNotes = state.value.deletedNotes)
        val notifications = if (viewingNotifications && key != null) mutex.withLock {
            db.query<Event>(notificationKinds.map { kind -> Filter(kinds = listOf(kind), tags = mapOf("p" to listOf(key)), limit = 100) })
        }.distinctBy { it.id }.filterNot { it.pubKey == key || engagementDeleted(it) || contentFilter.hides(it) }
            .filter { event -> event.kind !in listOf(9735, 9321) || zapActivity(event)?.let { it.sender != key && it.sender !in mutedAuthors } == true }
            .sortedByDescending { it.createdAt }.take(400)
        else state.value.notifications.takeIf { key != null && state.value.notificationsFor == key }.orEmpty()
        val readingPool = (threadNotes + hashtagNotes + profileEvents + (if (viewingNotifications) notifications else all)).filterNot(contentFilter::hides).distinctBy { it.id }
        val visible = (galleryNote?.let { listOf(it) } ?: visibleIds.mapNotNull { id -> readingPool.find { it.id == id } }.ifEmpty {
            if (viewingNotifications) notifications.take(12) else if (thread != null) threadNotes.take(12) else if (hashtag != null) hashtagNotes.take(12) else if (profile != null) profileEvents.filter(tab::matches).take(12) else all.filter(feed::matchesFeed).take(12)
        }).filterNot(contentFilter::hides)
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
            cachedEventsLocked(repostIds).values.filter { it.kind in readableKinds }
        }
        val readingContent = visible + reposts.filterNot(contentFilter::hides)
        val readingTab = if (profile != null) tab else feed
        val collections = if (profile != null) profileEvents else all
        val bookmarkRefs = if (readingTab == ProfileTab.BOOKMARKS)
            collections.flatMap(::bookmarkReferences).filter { it.id in visibleIds }.take(24) else emptyList()
        val peopleRefs = if (readingTab == ProfileTab.PEOPLE)
            collections.flatMap(::peopleReferences).filter { it.id in visibleIds }.distinctBy { it.id }.take(32) else emptyList()
        val refs = (conversation?.missing.orEmpty() + peopleRefs + bookmarkRefs + bioRefs + readingContent.filter { it.kind !in listOf(3, 30000) }.flatMap(NostrContent::references)).distinctBy { it.id }.take(64)
        val notificationRefs = if (viewingNotifications) visible.mapNotNull(::notificationTarget) else emptyList()
        val quoteIds = (notificationRefs.filter { !it.profile } + bookmarkRefs.filter { it.external == null } + bioQuotes + readingContent.flatMap { listOfNotNull(NostrContent.replyReference(it)) + NostrContent.quotes(it) }).map { it.id }.filter { ':' !in it }.distinct().take(24)
        val addressRefs = (bookmarkRefs + refs).distinctBy { it.id }.filter { it.addressFilter() != null }.take((24 - quoteIds.size).coerceAtLeast(0))
        val embedded = mutex.withLock {
            val byId = cachedEventsLocked(quoteIds).filterValues { it.kind in readableKinds }
            val byAddress = addressRefs.mapNotNull { ref ->
                db.query<Event>(ref.addressFilter()!!).sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
                    .firstOrNull()?.let { ref.id to it }
            }.toMap()
            byId + byAddress
        }
        val countTargets = (visible.filter { it.kind in readableKinds } + embedded.values.filterNot(contentFilter::hides)).distinctBy { it.id }.take(24)
        engagementIds = countTargets.map { it.id }
        engagementApps = countTargets.mapNotNull { it.appAddress() }
        val activity = if (engagementIds.isEmpty()) emptyList() else mutex.withLock {
            db.query<Event>(listOf(Filter(kinds = listOf(1, 1111, 1244, 7, 9735), tags = mapOf("e" to engagementIds), limit = 3000)) +
                engagementApps.map { Filter(kinds = listOf(1111), tags = mapOf("A" to listOf(it)), limit = 200) }).distinctBy { it.id }
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
                (data?.opt("nip05") as? String)?.trim()?.takeIf { it.isNotEmpty() }?.take(512),
                profileWebsite(data?.opt("website") as? String),
            )
        }
        val counts = countTargets.associate { target ->
            var zappedByAccount: String? = null
            val replies = activity.count { !contentFilter.hides(it) && ThreadRelations.repliesTo(it, target) }
            val reactions = activity.filter { it.kind == 7 && !engagementDeleted(it) && it.pubKey !in mutedAuthors && reactionTarget(it)?.id == target.id }.distinctBy { it.pubKey to it.content }.size
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
        val sentReposts = key?.let { author ->
            val known = mutex.withLock { db.query<Event>(Filter(kinds = listOf(6, 16), authors = listOf(author), limit = 256)) }
            (known + repostStore.records(author).filter { it.accepted }.map { it.event }).filterNot(::engagementDeleted).mapNotNull { repostTarget(it)?.id }.toSet()
        }.orEmpty()
        val ownReactions = key?.let { author ->
            val recent = mutex.withLock { db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), limit = 256)) }
            val visibleReactions = if (engagementIds.isEmpty()) emptyList() else mutex.withLock {
                db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), tags = mapOf("e" to engagementIds), limit = 512))
            }
            (reactionStore.records(author).filter { it.accepted }.map { it.event } + recent + visibleReactions + activity.filter { it.kind == 7 && it.pubKey == author })
                .filterNot(::engagementDeleted).distinctBy { it.id }.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        }.orEmpty()
        val sentReactions = ownReactions.mapNotNull { event ->
            val target = reactionTarget(event)?.id ?: return@mapNotNull null
            reactionChoice(event)?.let { target to it }
        }.groupBy({ it.first }, { it.second }).mapValues { (_, choices) -> choices.distinctBy { it.key } }
        val reactionChoices = reactionSuggestions(ownReactions)
        val recentEmojis = ownReactions.asSequence().mapNotNull(::reactionChoice)
            .filter { it.image == null && validReactionChoice(it) }.map { it.content }.distinct().take(60).toList()
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
        if (key != _state.value.publicKey || profile != viewedProfile || tab != profileTab || hashtag != viewedHashtag || thread != viewedThread || ancestorLimit != threadAncestorLimit || requestedParent != threadRequestedParent || feed != selectedFeed || feedCeiling != feedUntil || profileCeiling != profileUntil || profileCount != profileLimit || hashtagCeiling != hashtagUntil || hashtagCount != hashtagLimit) { changed.tryEmit(Unit); return }
        emojiPackRequests = packs
        val lists = mutex.withLock { db.query<Event>(Filter(kinds = listOf(10002), limit = 1100)) }
            .associate { it.pubKey to Outbox.parse(it) }
        val routesChanged = lists != relayLists
        relayLists = lists
        val previous = _state.value.following
        val mutesChanged = _state.value.mutedAuthors != mutedAuthors
        val previousThreadAuthor = _state.value.threadNotes.find { it.matchesThread(viewedThread) }?.pubKey
        _state.update { it.copy(notes = all, notifications = notifications, notificationsFor = key, feedTab = feed, feedTabs = feedTabs, feedPreviews = feedPreviews, hashtagNotes = hashtagNotes, profileEvents = profileEvents, profileTabsFor = profile, profileTabs = availableTabs, embedded = (it.embedded + embedded).entries.toList().takeLast(128).associate { entry -> entry.toPair() }, engagement = (it.engagement + counts).entries.toList().takeLast(800).associate { entry -> entry.toPair() }, profiles = (it.profiles - profiles.keys + profiles).entries.toList().takeLast(1000).associate { entry -> entry.toPair() }, following = following, sentReposts = sentReposts, sentReactions = sentReactions, deletedReactions = deletedReactions, reactionChoices = reactionChoices, recentEmojis = recentEmojis, customEmojis = customEmojis, mutedAuthors = mutedAuthors, pendingMuteList = pendingMuteList) }
        if (mutesChanged) threadChanges.tryEmit(Unit)
        if (active && (readingChanged || following != previous || routesChanged || previousThreadAuthor != threadNotes.find { it.matchesThread(viewedThread) }?.pubKey)) subscribeFeed()
        else if (active) { viewedThread?.let(::subscribeThread); subscribeReadingData() }
    }

    private suspend fun trimCache() = mutex.withLock { trimCacheLocked() }
    private suspend fun trimCacheLocked() {
        val protected = recentThreads.protectedIds()
        for ((kind, max) in listOf(1621 to 300, 1222 to 300, 1244 to 300, 30023 to 300, 30000 to 300, 30617 to 300, 32267 to 300, 20 to 600, 21 to 300, 22 to 300, 34235 to 300, 34236 to 300, 10003 to 200, 30003 to 300, 1 to 2000, 1111 to 2000, 6 to 1000, 16 to 1000, 7 to 2000, 5 to 1000, 9735 to 2000, 9321 to 1000, 9802 to 600, 0 to 1000, 3 to 300, 10000 to 300, 10002 to 1100, 10030 to 8, 10063 to 8, 30030 to 64)) {
            var unprotected = 0
            val evict = mutableListOf<String>()
            // Raw rows avoid decoding every event's tags/content merely to evict it.
            // Quartz only adds its newest-first ordering when a limit is supplied.
            db.rawQuery(listOf(Filter(kinds = listOf(kind), limit = Int.MAX_VALUE))) { row ->
                if (row.id !in protected && ++unprotected > max) evict += row.id
            }
            evict.chunked(128).forEach { ids ->
                db.delete(Filter(ids = ids))
                ids.forEach { verified.remove(it) }
            }
        }
        db.deleteExpiredEvents()
    }
    /** Explicit user action only. Signing and relay I/O stay off the UI thread. */
    private fun recordReaction(author: String, target: String, choice: ReactionChoice) {
        _state.update { if (it.publicKey == author) it.copy(
            sentReactions = it.sentReactions + (target to (listOf(choice) + it.sentReactions[target].orEmpty()).distinctBy { item -> item.key }),
            reactionChoices = (listOf(choice) + it.reactionChoices).distinctBy { item -> item.key }.take(6),
            recentEmojis = if (choice.image == null && validReactionChoice(choice))
                (listOf(choice.content) + it.recentEmojis).distinct().take(60) else it.recentEmojis,
        ) else it }
    }

    internal suspend fun repost(target: Event, signer: NostrSignerExternal, signerRequests: SignerRequests) = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            ready.await()
            val author = state.value.publicKey ?: error("Connect an Android signer first")
            require(signer.pubKey == author && target.kind in readableKinds)
            val previous = repostStore.records(author).firstOrNull { repostTarget(it.event)?.id == target.id }
            val saved = previous?.takeUnless { engagementDeleted(it.event) }
            val cached = mutex.withLock { db.query<Event>(Filter(kinds = listOf(6, 16), authors = listOf(author), tags = mapOf("e" to listOf(target.id)), limit = 256)) }
            val known = cached.firstOrNull { !engagementDeleted(it) }
            if (saved?.accepted == true || known != null) {
                _state.update { if (it.publicKey == author) it.copy(sentReposts = it.sentReposts + target.id) else it }
                return@withLock
            }
            val kind = if (target.kind == 1) 6 else 16
            val hint = relayLists[target.pubKey]?.write?.firstOrNull(::healthy)
                ?: state.value.relays.firstOrNull(::healthy) ?: error("No relay available.")
            val tags = buildList {
                add(arrayOf("e", target.id, hint)); add(arrayOf("p", target.pubKey))
                if (kind == 16) add(arrayOf("k", target.kind.toString()))
                if (target.kind in 30000..39999) target.tagValue("d")?.let { add(arrayOf("a", "${target.kind}:${target.pubKey}:$it")) }
                add(arrayOf("client", "Feather"))
            }.toTypedArray()
            val content = if (target.tags.any { it.firstOrNull() == "-" }) "" else target.toJson().takeIf { it.length <= 64_000 }.orEmpty()
            // A quick undo/repost must not recreate the just-deleted event ID.
            val now = maxOf(System.currentTimeMillis() / 1000,
                maxOf(previous?.event?.createdAt ?: 0, cached.maxOfOrNull { it.createdAt } ?: 0) + 1)
            val signed = saved?.event ?: signerRequests.run {
                check(state.value.publicKey == author)
                withTimeout(180_000) { signer.sign<Event>(now, kind, tags, content) }.also {
                    require(it.createdAt == now && it.tags.contentDeepEquals(tags)) { "Signer changed the repost" }
                }
            }
            require(signed.pubKey == author && signed.kind == kind && signed.content == content &&
                repostTarget(signed)?.id == target.id && signed.tagValue("p") == target.pubKey && signed.verify()) { "Invalid signed repost" }
            check(state.value.publicKey == author)
            repostStore.put(target.id, signed, accepted = false)
            // The external signer may still cover our Activity as its result arrives.
            withTimeout(15_000) { foregroundActive.first { it } }
            check(state.value.publicKey == author) { "Account changed." }
            publishingAuthor = target.pubKey
            try {
                check(publishPostEvent(signed, target)) {
                    "No relay confirmed the repost after 3 attempts. Tap again to retry the same event."
                }
                repostStore.put(target.id, signed, accepted = true)
                accept(signed)
                _state.update { if (it.publicKey == author) it.copy(sentReposts = it.sentReposts + target.id) else it }
            } finally {
                publishingAuthor = null
                if (active) updateRoutingPool()
            }
        }
    }

    /** A retry republishes the same signed ID, so a lost acknowledgement cannot create duplicates. */
    private suspend fun publishReactionEvent(event: Event, author: String, recipient: String, appDiscussion: Boolean = false): Boolean {
        repeat(3) { attempt ->
            if (attempt > 0) delay(if (attempt == 1) 1_000 else 3_000)
            currentCoroutineContext().ensureActive()
            check(state.value.publicKey == author) { "Account changed" }
            updateRoutingPool()
            client.connect()
            val routes = (relayLists[author]?.write.orEmpty().filter { it in routingPool && healthy(it) }.take(2) +
                relayLists[recipient]?.read.orEmpty().filter { it in routingPool && healthy(it) }.take(2) +
                state.value.relays.filter(::healthy)).distinct().take(4) + if (appDiscussion) listOf(ZAPSTORE_RELAY) else emptyList()
            val accepted = try {
                routes.isNotEmpty() && client.publishAndConfirm(event, routes.map { it.normalizeRelayUrl() }.toSet(),
                    timeoutInSeconds = 15, untilFirstAccept = true)
            } catch (_: TimeoutCancellationException) { false }
            catch (_: java.io.IOException) { false }
            if (accepted) return true
        }
        return false
    }

    internal suspend fun react(target: Event, choice: ReactionChoice, signer: NostrSignerExternal, signerRequests: SignerRequests) = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            ready.await()
            val author = state.value.publicKey ?: error("Connect an Android signer first.")
            require(signer.pubKey == author && target.kind in readableKinds)
            require(validReactionChoice(choice))
            val known = mutex.withLock {
                db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), tags = mapOf("e" to listOf(target.id)), limit = 100))
                    .firstOrNull { !engagementDeleted(it) && reactionChoice(it) == choice && reactionTarget(it)?.id == target.id }
            }
            val previous = reactionStore.get(author, target.id, choice)
            val saved = previous?.takeUnless { engagementDeleted(it.event) }
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
            val signed = saved?.event ?: run {
                val powEnabled = state.value.proofOfWork
                val mined = ProofOfWork.prepare(author, createdAt, 7, tags, choice.content, ProofOfWork.REACTION_BITS, powEnabled)
                signerRequests.run {
                    check(state.value.publicKey == author) { "Account changed" }
                    withTimeout(180_000) { signer.sign<Event>(mined.createdAt, 7, mined.tags, choice.content) }
                }.also {
                    require(it.createdAt == mined.createdAt && it.tags.contentDeepEquals(mined.tags)) { "Signer changed the reaction." }
                    if (powEnabled) ProofOfWork.verifySigned(it, ProofOfWork.REACTION_BITS)
                }
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
                check(publishReactionEvent(signed, author, target.pubKey, target.isAppDiscussion())) { "Reaction not confirmed" }
                reactionStore.put(target.id, signed, accepted = true)
                accept(signed)
                recordReaction(author, target.id, choice)
            } finally {
                publishingAuthor = null
                if (active) updateRoutingPool()
            }
        }
    }

    internal suspend fun blossomServers(owner: String): List<String> = withContext(Dispatchers.IO) {
        ready.await()
        val filter = Filter(authors = listOf(owner), kinds = listOf(10063), limit = 1)
        suspend fun cached() = mutex.withLock { db.query<Event>(filter).maxByOrNull { it.createdAt } }
        var list = cached()
        if (list == null) {
            val relays = (relayLists[owner]?.write.orEmpty() + state.value.relays).distinct()
            profileQueries.fetch(relays, listOf(filter.toJson())) { event ->
                event.kind == 10063 && event.pubKey == owner && accept(event)
            }
            list = cached()
        }
        check(state.value.publicKey == owner) { "Account changed" }
        val servers = list?.tags.orEmpty().filter { it.firstOrNull() == "server" }
            .mapNotNull { it.getOrNull(1)?.let(::blossomServer) }.distinct()
        require(servers.size <= 16) { "Blossom server list exceeds 16 servers" }
        require(servers.isNotEmpty()) { "No HTTPS Blossom servers found on your profile (kind 10063)." }
        servers
    }

    internal suspend fun reply(draft: ReplyDraft, signer: NostrSignerExternal, signerRequests: SignerRequests, store: ReplyStore, onMining: (Boolean) -> Unit = {}) = withContext(Dispatchers.IO) {
        ready.await()
        val author = draft.owner
        val target = draft.target
        require(state.value.publicKey == author && signer.pubKey == author) { "Account changed" }
        require((target == null || target.kind in readableKinds) && draft.text.isNotBlank() && draft.text.length <= 8000)
        val saved = store.signed(draft)
        val signed = saved ?: run {
            val hint = target?.let { relayLists[it.pubKey]?.write?.firstOrNull(::healthy) }.orEmpty()
            val (kind, threadTags) = target?.let { replyTags(it, hint) } ?: (1 to emptyArray<Array<String>>())
            val participants = threadTags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1) }.toSet()
            val mentions = replyMentions(draft.text).filter { it !in participants }.map { arrayOf("p", it) }
            val quotes = Regex("nostr:(?:nevent|note)1[023456789acdefghjklmnpqrstuvwxyz]+", RegexOption.IGNORE_CASE)
                .findAll(draft.text).take(16).mapNotNull { NostrContent.decode(it.value) }.distinctBy { it.id }.toList()
            val quoteTags = quotes.map { arrayOf("q", it.id, it.relays.firstOrNull().orEmpty(), it.author.orEmpty()) } +
                quotes.mapNotNull { it.author }.distinct().filter { it !in participants && mentions.none { tag -> tag[1] == it } }.map { arrayOf("p", it) }
            val hashtagTags = NostrContent.hashtags(draft.text).map { arrayOf("t", it) }.toTypedArray()
            val gifTags = draft.gifs.filter { it.url in draft.text }.distinctBy { it.url }.take(32).map { it.imeta() }.toTypedArray()
            val tags = threadTags + mentions.toTypedArray() + quoteTags.toTypedArray() + hashtagTags + gifTags + arrayOf(arrayOf("client", "Feather"))
            val now = System.currentTimeMillis() / 1000
            val powEnabled = state.value.proofOfWork
            val mined = ProofOfWork.prepare(author, now, kind, tags, draft.text, ProofOfWork.POST_BITS, powEnabled, onMining)
            signerRequests.run {
                check(state.value.publicKey == author) { "Account changed" }
                withTimeout(180_000) { signer.sign<Event>(mined.createdAt, kind, mined.tags, draft.text) }
            }.also {
                require(it.createdAt == mined.createdAt && it.kind == kind && it.tags.contentDeepEquals(mined.tags)) { "Signer changed the post" }
                if (powEnabled) ProofOfWork.verifySigned(it, ProofOfWork.POST_BITS)
            }
        }
        require(signed.pubKey == author && signed.content == draft.text && signed.kind == (if (target == null || target.kind == 1) 1 else 1111) &&
            replyTarget(signed) == target?.id && signed.verify()) { "Invalid signed post" }
        check(state.value.publicKey == author) { "Account changed" }
        store.saveSigned(draft, signed)
        withTimeout(15_000) { foregroundActive.first { it } }
        check(state.value.publicKey == author) { "Account changed" }
        publishingAuthor = target?.pubKey ?: author
        try {
            check(publishPostEvent(signed, target)) {
                "No relay confirmed the post after 3 attempts. Draft saved; send again to retry the same event."
            }
            accept(signed)
            _state.update { if (target != null && it.threadId == target.id) it.copy(threadNotes = (it.threadNotes + signed).distinctBy { note -> note.id }) else it }
        } finally {
            publishingAuthor = null
            if (active) updateRoutingPool()
        }
    }

    /** Sign and persist once; only relay delivery is retried, never signing or image uploads. */
    private suspend fun publishPostEvent(event: Event, target: Event?): Boolean {
        repeat(3) { attempt ->
            if (attempt > 0) delay(if (attempt == 1) 1_000 else 3_000)
            currentCoroutineContext().ensureActive()
            check(state.value.publicKey == event.pubKey) { "Account changed" }
            // The signer may have backgrounded Feather. Do not reconnect while it is still hidden.
            withTimeout(15_000) { foregroundActive.first { it } }
            check(state.value.publicKey == event.pubKey) { "Account changed" }
            updateRoutingPool()
            client.connect()
            val routes = (relayLists[event.pubKey]?.write.orEmpty().filter { it in routingPool && healthy(it) }.take(2) +
                target?.let { relayLists[it.pubKey]?.read }.orEmpty().filter { it in routingPool && healthy(it) }.take(2) +
                state.value.relays.filter(::healthy)).distinct().take(4) +
                (if (target?.let { it.kind == 1621 || it.tagValue("K") == "1621" } == true) IssueReports.RELAYS else emptyList()) +
                if (target?.isAppDiscussion() == true) listOf(ZAPSTORE_RELAY) else emptyList()
            val accepted = try {
                routes.isNotEmpty() && client.publishAndConfirm(event, routes.map { it.normalizeRelayUrl() }.toSet(),
                    timeoutInSeconds = 15, untilFirstAccept = true)
            } catch (_: TimeoutCancellationException) {
                currentCoroutineContext().ensureActive()
                false
            } catch (_: java.io.IOException) { false }
            if (accepted) return true
        }
        return false
    }

    internal suspend fun removeReaction(target: Event, choice: ReactionChoice, signer: NostrSignerExternal, signerRequests: SignerRequests) = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            ready.await()
            val author = state.value.publicKey ?: error("Connect a signer first")
            require(author == signer.pubKey)
            val cached = mutex.withLock { db.query<Event>(Filter(kinds = listOf(7), authors = listOf(author), tags = mapOf("e" to listOf(target.id)), limit = 256)) }
            val reactions = (cached + reactionStore.records(author).map { it.event }).filter {
                !engagementDeleted(it) && it.pubKey == author && it.kind == 7 && reactionTarget(it)?.id == target.id && reactionChoice(it) == choice
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
                check(publishReactionEvent(deletion, author, target.pubKey, target.isAppDiscussion())) { "Deletion not confirmed" }
                reactionDeletions.put(deletion, accepted = true)
                deletedReactions = reactionDeletions.deleted()
                _state.update { current -> current.copy(deletedReactions = deletedReactions,
                    sentReactions = if (current.publicKey == author) current.sentReactions + (target.id to current.sentReactions[target.id].orEmpty().filter { it != choice }) else current.sentReactions) }
                accept(deletion)
                changed.tryEmit(Unit)
            } finally { publishingAuthor = null; if (active) updateRoutingPool() }
        }
    }

    internal suspend fun removeRepost(target: Event, signer: NostrSignerExternal, signerRequests: SignerRequests) = withContext(Dispatchers.IO) {
        reactionMutex.withLock {
            ready.await()
            val author = state.value.publicKey ?: error("Connect a signer first")
            require(author == signer.pubKey)
            val cached = mutex.withLock { db.query<Event>(Filter(kinds = listOf(6, 16), authors = listOf(author), tags = mapOf("e" to listOf(target.id)), limit = 256)) }
            val reposts = (cached + repostStore.records(author).map { it.event }).filter {
                !engagementDeleted(it) && it.pubKey == author && it.kind in listOf(6, 16) && repostTarget(it)?.id == target.id
            }.distinctBy { it.id }
            if (reposts.isEmpty()) { changed.tryEmit(Unit); return@withLock }
            val ids = reposts.map { it.id }.toSet()
            val tags = (ids.sorted().map { arrayOf("e", it) } + reposts.map { it.kind }.distinct().sorted().map { arrayOf("k", it.toString()) }).toTypedArray()
            val now = System.currentTimeMillis() / 1000
            val deletion = reactionDeletions.pending(author, ids) ?: signerRequests.run {
                check(state.value.publicKey == author) { "Account changed" }
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
                check(publishPostEvent(deletion, target)) { "Deletion not confirmed" }
                reactionDeletions.put(deletion, accepted = true)
                deletedReactions = reactionDeletions.deleted()
                _state.update { current -> current.copy(deletedReactions = deletedReactions,
                    sentReposts = if (current.publicKey == author) current.sentReposts - target.id else current.sentReposts) }
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
        _state.update { it.copy(publicKey = pubkey, signerPackage = signer, following = emptySet(), notes = emptyList(), notifications = emptyList(), notificationsFor = null, notificationsLoading = false, feedTab = ProfileTab.POSTS, feedTabs = listOf(ProfileTab.POSTS, ProfileTab.REPLIES), feedPreviews = emptyMap(), sentReposts = emptySet(), sentReactions = emptyMap(), reactionChoices = defaultReactions, recentEmojis = emptyList(), customEmojis = emptyList(), mutedAuthors = emptySet(), pendingMuteList = null, loading = true) }
        ready.await(); threadChanges.tryEmit(Unit); snapshot(); if (active) subscribeFeed()
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        prefs.edit().remove("pubkey").remove("signer").remove("draft").remove("draftReply").remove("pending").apply()
        emojiPackRequests = emptyList()
        client.unsubscribe("emoji-packs")
        feedUntil = null; feedCeilings.clear(); selectedFeed = ProfileTab.POSTS
        _state.update { it.copy(publicKey = null, signerPackage = null, following = emptySet(), notes = emptyList(), notifications = emptyList(), notificationsFor = null, notificationsLoading = false, feedTab = ProfileTab.POSTS, feedTabs = listOf(ProfileTab.POSTS, ProfileTab.REPLIES), feedPreviews = emptyMap(), sentReposts = emptySet(), sentReactions = emptyMap(), reactionChoices = defaultReactions, recentEmojis = emptyList(), customEmojis = emptyList(), mutedAuthors = emptySet(), pendingMuteList = null) }
        threadChanges.tryEmit(Unit); snapshot(); if (active) subscribeFeed()
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

    fun viewProfile(key: String?) { if (viewedProfile == key) return; stopProfileSweep(); historyJob?.cancel(); profileLimit = 100; profileUntil = null; profileTab = ProfileTab.POSTS; viewedProfile = key; changed.tryEmit(Unit); if (key == null) { client.unsubscribe("profile"); client.unsubscribe("profile-activity"); client.unsubscribe("profile-apps"); client.unsubscribe("profile-issues") }; if (active) subscribeFeed() }
    fun profileTab(tab: ProfileTab) {
        if (profileTab == tab) return
        historyJob?.cancel()
        profileTab = tab
        profileLimit = 100
        profileUntil = null
        changed.tryEmit(Unit)
        if (active) viewedProfile?.let(::subscribeProfile)
    }
    @Synchronized
    private fun subscribeProfile(key: String) {
        if (!active || viewedProfile != key) return
        discoverRelays()
        // Apps are commonly published only to the catalog relay, not personal write relays.
        client.subscribe("profile-apps", mapOf(ZAPSTORE_RELAY.normalizeRelayUrl() to
            listOf(Filter(kinds = listOf(32267), authors = listOf(key), limit = if (profileTab == ProfileTab.APPS) 80 else 8))), listener)
        client.subscribe("profile-issues", IssueReports.RELAYS.associate { it.normalizeRelayUrl() to
            listOf(Filter(kinds = listOf(1621), authors = listOf(key), limit = if (profileTab == ProfileTab.ISSUES) 80 else 8)) }, listener)
        val tab = profileTab
        val allRelays = profileReadRelays(key, tab)
        val sweepKey = Triple(key, tab, allRelays)
        if (profileSweepKey != sweepKey) {
            stopProfileSweep()
            profileSweepKey = sweepKey
            profileSweep = scope.launch {
                ready.await()
                val filters = profileKinds.flatMap { kind ->
                    Filter(authors = listOf(key), kinds = listOf(kind), limit = if (kind in tab.kinds) 80 else 1).activityFilters()
                }
                profileQueries.fetch(allRelays, filters.map { it.toJson() }) { event ->
                    active && viewedProfile == key && profileTab == tab && filters.any { it.match(event) } && accept(event)
                }
            }
        }
        val routes = profileFilters(key, tab.kinds, 80)
        // Separate kind filters keep ordinary notes from starving dedicated media posts.
        client.subscribe("profile", routes, listener)
        // Small independent existence probes keep rare kinds from being crowded out by notes.
        client.subscribe("profile-activity", profileFilters(key, listOf(1), 80).keys.associateWith {
            profileKinds.filter { it !in profileTab.kinds }.flatMap { kind ->
                Filter(kinds = listOf(kind), authors = listOf(key), limit = if (kind == 1) 80 else if (kind in listOf(10003, 30000, 30003)) 8 else 1).activityFilters()
            }
        }, listener)
    }
    @Synchronized
    fun viewThread(id: String?, clicked: Event? = null, retained: List<Event> = emptyList(), expanded: List<String> = emptyList(),
        ancestorLimit: Int = INITIAL_THREAD_ANCESTORS) {
        if (viewedThread == id) return
        val generation = threadGeneration.incrementAndGet()
        threadRestoreJob?.cancel()
        threadParentJob?.cancel()
        val cached = state.value
        val root = clicked?.takeIf { it.matchesThread(id) && it.kind in readableKinds }
            ?: cached.threadNotes.find { it.matchesThread(id) }
            ?: cached.embedded[id]
            ?: cached.profileEvents.find { it.id == id }
            ?: cached.hashtagNotes.find { it.id == id }
            ?: cached.notes.find { it.id == id }
        historyJob?.cancel()
        viewedThread = id
        threadContext = null; threadParents = emptyList()
        threadExpanded = expanded.takeLast(64).toSet()
        threadAncestorLimit = ancestorLimit.coerceIn(INITIAL_THREAD_ANCESTORS, MAX_THREAD_ANCESTORS)
        threadRequestedParent = null
        // Hand off the already rendered event synchronously; no disk or relay round trip.
        _state.update { it.copy(threadId = id, conversation = null, threadNotes = (listOfNotNull(root) + retained).distinctBy { it.id }.take(320).takeIf { id != null }.orEmpty(),
            loading = id != null && root == null) }
        threadProgress.begin(id)
        changed.tryEmit(Unit)
        if (id != null) threadRestoreJob = scope.launch {
            ready.await()
            if (viewedThread != id || threadGeneration.get() != generation) return@launch
            recentThreads.touch(id)
            recentThreads.remember(id, state.value.threadNotes)
            refreshConversation()
        }
        scope.launch {
            if (viewedThread == null) { client.unsubscribe("thread"); client.unsubscribe("thread-deletions") }
            if (active) subscribeFeed()
        }
    }
    fun expandThread(parent: String) {
        if (viewedThread == null || parent !in state.value.threadNotes.map { it.id }) return
        threadExpanded = (threadExpanded + parent).toList().takeLast(64).toSet()
        threadChanges.tryEmit(Unit)
        changed.tryEmit(Unit)
    }

    /** Extend this visit, never change its focused event or create a navigation entry. */
    fun loadThreadParent(row: ConversationRow): Int? {
        val id = viewedThread ?: return null
        val gap = threadContext?.takeIf { it.target == id }?.rows?.find { it.key == row.key } ?: return null
        val ref = gap.reference ?: return null
        if (gap.gap !in listOf(ThreadGap.MISSING, ThreadGap.LIMIT) ||
            (ref.kind != null && ref.kind !in readableKinds) || ref.author in state.value.mutedAuthors) return null
        if (gap.gap == ThreadGap.LIMIT && threadAncestorLimit >= MAX_THREAD_ANCESTORS) return null
        if (!threadProgress.retry(id, ref)) return null
        val retryExisting = synchronized(this) { ref.id in pendingEventLookups }
        if (gap.gap == ThreadGap.LIMIT) threadAncestorLimit = (threadAncestorLimit + 32).coerceAtMost(MAX_THREAD_ANCESTORS)
        threadRequestedParent = ref
        threadParentJob?.cancel()
        threadParentJob = scope.launch {
            try {
                ready.await()
                if (viewedThread != id || threadRequestedParent != ref) return@launch
                refreshConversation()
                if (active && viewedThread == id && threadRequestedParent == ref) {
                    // A cache miss may already have reached EOSE. Retry it only on this tap.
                    if (ref.addressFilter() != null) {
                        client.unsubscribe("embedded")
                        subscribeReadingData()
                    } else reconcileEventLookups(ref.id.takeIf { retryExisting })
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                threadProgress.failed(id, ref)
                if (viewedThread == id) _state.update { it.copy(error = "Could not load earlier conversation: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
        return threadAncestorLimit
    }
    private fun threadBranchAuthors(): List<String> = threadContext?.takeIf { it.target == viewedThread }?.rows.orEmpty()
        .mapNotNull { it.event?.takeIf { e -> e.id in threadParents }?.pubKey }.distinct().take(4)

    private fun threadRelays(id: String): List<String> {
        val root = state.value.threadNotes.find { it.matchesThread(id) }
        val author = root?.pubKey
        val issue = state.value.threadNotes.find { it.id == id }?.let { it.kind == 1621 || it.tagValue("K") == "1621" } == true
        val branchRelays = threadBranchAuthors().flatMap { relayLists[it]?.read.orEmpty() + relayLists[it]?.write.orEmpty() }
        return ((state.value.relays + openedReference?.takeIf { it.id == id }?.relays.orEmpty() + relayLists[author]?.read.orEmpty() + relayLists[author]?.write.orEmpty() + branchRelays).distinct().filter { it in routingPool } +
            if (issue) IssueReports.RELAYS else emptyList()) +
            if (root?.isAppDiscussion() == true || openedReference?.kind == 32267) listOf(ZAPSTORE_RELAY) else emptyList()
    }
    private fun subscribeThread(id: String) {
        discoverRelays()
        val root = state.value.threadNotes.find { it.matchesThread(id) }
        // An address may have a newer version. Replies are new events. Neither is an ID lookup.
        val filters = listOfNotNull(openedReference?.takeIf { it.id == id }?.addressFilter()) + ThreadRelations.discovery(root, id, threadParents)
        if (filters.isEmpty()) client.unsubscribe("thread")
        else client.subscribe("thread", threadRelays(id).associate { it.normalizeRelayUrl() to filters }, listener)
        val known = state.value.threadNotes.takeIf { state.value.threadId == id }.orEmpty()
        if (known.isEmpty()) client.unsubscribe("thread-deletions")
        else {
            val addresses = known.mapNotNull(ThreadRelations::address).distinct().sorted()
            val deletions = listOf(Filter(kinds = listOf(5), tags = mapOf("e" to known.map { it.id }.distinct().take(320).sorted()), limit = 256)) +
                if (addresses.isEmpty()) emptyList() else listOf(Filter(kinds = listOf(5), tags = mapOf("a" to addresses), limit = 256))
            client.subscribe("thread-deletions", threadRelays(id).associate { it.normalizeRelayUrl() to deletions }, listener)
        }
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
                if (author != null) {
                    // Local history is not evidence that every write relay was queried.
                    val filters = kinds.flatMap { kind -> Filter(kinds = listOf(kind), authors = listOf(author), limit = 80, until = oldest - 1).activityFilters() }
                    val received = profileQueries.fetch(profileReadRelays(author, tab), filters.map { it.toJson() }) { event ->
                        sameScreen() && historySequence.get() == sequence && filters.any { it.match(event) } && accept(event, history = true)
                    }
                    found = found || received
                } else if (!found) {
                    val routes = if (tag != null) hashtagFilters(tag, oldest - 1) else if (author != null) profileFilters(author, kinds, 80, oldest - 1) else authorFilters(authors, kinds, 80, oldest - 1)
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
