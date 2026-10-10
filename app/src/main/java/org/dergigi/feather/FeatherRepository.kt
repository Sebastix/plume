package org.dergigi.feather

import android.app.Application
import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
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
        com.vitorpamplona.quartz.utils.Log.minLevel = com.vitorpamplona.quartz.utils.LogLevel.ERROR
    }
    val repository by lazy { FeatherRepository(this) }
    val media by lazy { MediaStore(this) }
}

data class Profile(val name: String, val about: String = "", val picture: String? = null, val username: String? = null, val banner: String? = null, val lightning: String? = null)
data class FeedState(
    val notes: List<Event> = emptyList(),
    val threadId: String? = null,
    val threadNotes: List<Event> = emptyList(),
    val profileEvents: List<Event> = emptyList(),
    val hashtagNotes: List<Event> = emptyList(),
    val profiles: Map<String, Profile> = emptyMap(),
    val embedded: Map<String, Event> = emptyMap(),
    val engagement: Map<String, Engagement> = emptyMap(),
    val following: Set<String> = emptySet(),
    val publicKey: String? = null,
    val signerPackage: String? = null,
    val relays: List<String> = RelayDefaults.seeds.take(4),
    val connected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
)

@OptIn(FlowPreview::class)
class FeatherRepository(context: Context) {
    private val prefs = context.getSharedPreferences("feather", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dbPath = context.getDatabasePath("events.db").also { it.parentFile?.mkdirs() }.absolutePath
    private val db by lazy { EventStore(dbName = dbPath, numReaders = 1) }
    private val mutex = Mutex()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val zapVerification = ZapVerification(scope) { changed.tryEmit(Unit) }
    @Volatile private var engagementIds: List<String> = emptyList()
    private val _state = MutableStateFlow(FeedState(
        publicKey = prefs.getString("pubkey", null)?.takeIf { !prefs.getString("signer", null).isNullOrBlank() }, signerPackage = prefs.getString("signer", null)?.takeIf { it.isNotBlank() },
    ))
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private val client = NostrClient(RelayTransport(), scope)
    private val ready = CompletableDeferred<Unit>()
    private val verified = object : LinkedHashMap<String, Boolean>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2048
    }
    @Volatile private var active = false
    @Volatile private var viewedProfile: String? = null
    @Volatile private var profileTab = ProfileTab.POSTS
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
            if (forFilters?.any { it.kinds?.any { kind -> kind in listOf(1, 7, 9802) } == true } == true) _state.update { it.copy(loading = false) }
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
    fun background() { active = false; zapVerification.stop(); discoveryJob?.cancel(); historyJob?.cancel(); refreshTimeout?.cancel(); client.disconnect(); _state.update { it.copy(connected = emptySet(), loading = false) } }
    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch { ready.await(); if (active) { changed.tryEmit(Unit); subscribeFeed(); client.connect() } }
    }
    private fun authorFilters(authors: List<String>?, kinds: List<Int>, limit: Int, until: Long? = null): Map<NormalizedRelayUrl, List<Filter>> {
        if (authors == null) return routingPool.filter { it != "wss://purplepag.es" }.associate { it.normalizeRelayUrl() to listOf(Filter(kinds = kinds, limit = limit, until = until)) }
        return Outbox.readRoutes(authors, relayLists, state.value.relays, allowed = routingPool).mapKeys { it.key.normalizeRelayUrl() }
            .mapValues { (_, keys) -> listOf(Filter(authors = keys, kinds = kinds, limit = limit, until = until)) }
    }
    private fun discoverRelays() {
        val s = state.value
        val threadAuthor = s.threadNotes.find { it.id == viewedThread }?.pubKey
        val authors = (listOfNotNull(s.publicKey, viewedProfile, threadAuthor) + profileAuthors.take(64) + s.following.take(999) + s.notes.take(40).map { it.pubKey }.takeIf { s.publicKey == null }.orEmpty()).distinct()
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
        val priority = (listOf("wss://purplepag.es") + openedReference?.relays.orEmpty() +
            references.flatMap { it.relays }.distinct().take(2) + fallback + relayLists[viewedProfile]?.write.orEmpty().take(2) +
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
            client.unsubscribe("older")
            client.unsubscribe("profiles")
        }
    }
    @Synchronized
    private fun subscribeFeed() {
        if (!active) return
        updateRoutingPool()
        val s = _state.value
        discoverRelays()
        s.publicKey?.let { client.subscribe("account", authorFilters(listOf(it), listOf(0, 3), 2), listener) }
            ?: client.unsubscribe("account")
        val authors = s.publicKey?.let { (listOf(it) + s.following).take(1000) }
        client.subscribe("feed", authorFilters(authors, listOf(1), 80), listener)
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
        val ids = embeddedIds.filter { it !in state.value.embedded }
        if (ids.isEmpty()) client.unsubscribe("embedded")
        else client.subscribe("embedded", routingPool.filter { it != "wss://purplepag.es" }.associate {
            it.normalizeRelayUrl() to listOf(Filter(ids = ids, kinds = listOf(1, 9802), limit = ids.size))
        }, listener)
    }

    private suspend fun accept(event: Event) {
        if (event.kind !in setOf(0, 1, 3, 7, 9735, 9802, 10002) || event.content.length > 32_768 || event.tags.size > 5000 || event.createdAt > System.currentTimeMillis() / 1000 + 600) return
        mutex.withLock {
            if (verified.containsKey(event.id)) return
            if (!event.verify()) return
            verified[event.id] = true
            db.insert(event)
            if (++inserts % 200 == 0) trimCacheLocked()
        }
        changed.tryEmit(Unit)
    }
    private suspend fun snapshot() {
        val key = _state.value.publicKey
        val thread = viewedThread
        val contact = key?.let { mutex.withLock { db.query<Event>(Filter(kinds = listOf(3), authors = listOf(it), limit = 1)).firstOrNull() } }
        val following = Protocol.follows(contact)
        val filters = mutableListOf(Filter(kinds = listOf(1), authors = key?.let { (listOf(it) + following).take(1000) }, limit = 600))
        viewedProfile?.let { filters += Filter(kinds = listOf(1), authors = listOf(it), limit = 100) }
        val all = mutex.withLock { db.query<Event>(filters) }.distinctBy { it.id }.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).take(800)
        val threadNotes = thread?.let { id ->
            val cached = mutex.withLock {
                db.query<Event>(listOf(Filter(kinds = listOf(1, 9802), ids = listOf(id)),
                    Filter(kinds = listOf(1), tags = mapOf("e" to listOf(id)), limit = 100)))
            }
            val root = cached.find { it.id == id } ?: state.value.threadNotes.find { it.id == id }
            listOfNotNull(root) + cached.filter { it.id != id && it.tags.any { tag ->
                tag.size >= 2 && tag[0] == "e" && tag[1] == id && (tag.size < 4 || tag[3] != "mention")
            } }.distinctBy { it.id }.sortedWith(compareBy<Event> { it.createdAt }.thenBy { it.id })
        }.orEmpty()
        val profile = viewedProfile
        val tab = profileTab
        val profileEvents = profile?.let { author -> mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(tab.kind), authors = listOf(author), limit = profileLimit, until = profileUntil))
        } }.orEmpty().sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        val hashtag = viewedHashtag
        val hashtagNotes = hashtag?.let { tag -> mutex.withLock {
            db.query<Event>(Filter(kinds = listOf(1), tags = mapOf("t" to listOf(tag)), limit = 200, until = hashtagUntil))
        } }.orEmpty().sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id })
        val readingPool = (threadNotes + hashtagNotes + profileEvents + all).distinctBy { it.id }
        val visible = visibleIds.mapNotNull { id -> readingPool.find { it.id == id } }.ifEmpty {
            if (thread != null) threadNotes.take(12) else if (hashtag != null) hashtagNotes.take(12) else if (profile != null) profileEvents.filter(tab::matches).take(12) else all.take(12)
        }
        val refs = visible.flatMap(NostrContent::references).distinctBy { it.id }.take(64)
        val quoteIds = visible.flatMap(NostrContent::quotes).map { it.id }.distinct().take(24)
        val embedded = if (quoteIds.isEmpty()) emptyMap() else mutex.withLock {
            db.query<Event>(Filter(ids = quoteIds, kinds = listOf(1, 9802), limit = 24))
        }.associateBy { it.id }
        val countTargets = (visible.filter { it.kind in listOf(1, 9802) } + embedded.values).distinctBy { it.id }.take(24)
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
            event.pubKey to Profile(
                data?.optString("display_name")?.takeIf { it.isNotBlank() } ?: data?.optString("name")?.takeIf { it.isNotBlank() } ?: shortKey(event.pubKey),
                data?.optString("about").orEmpty().take(2000),
                MediaContent.secureUrl(data?.optString("picture")?.trim()),
                data?.optString("name")?.trim()?.takeIf { it.isNotBlank() },
                listOf("banner", "cover", "header").firstNotNullOfOrNull { field -> MediaContent.secureUrl(data?.optString(field)?.trim()) },
                data?.optString("lud16")?.takeIf { it.isNotBlank() } ?: data?.optString("lud06")?.takeIf { it.isNotBlank() },
            )
        }
        val counts = countTargets.associate { target ->
            val replies = activity.count { replyTarget(it) == target.id }
            val reactions = activity.filter { it.kind == 7 && reactionTarget(it)?.id == target.id }.distinctBy { it.pubKey to it.content }.size
            val zaps = activity.filter { it.kind == 9735 && it.tagValue("e") == target.id &&
                zapVerification.valid(it, target, profiles[it.tagValue("p")] ?: state.value.profiles[it.tagValue("p")], active) }
                .distinctBy { it.tagValue("bolt11") }.size
            target.id to Engagement(replies, reactions, zaps)
        }
        val readingChanged = references != refs || profileAuthors != authors
        references = refs
        embeddedIds = quoteIds
        profileAuthors = authors
        if (key != _state.value.publicKey || profile != viewedProfile || tab != profileTab || hashtag != viewedHashtag || thread != viewedThread) { changed.tryEmit(Unit); return }
        val lists = mutex.withLock { db.query<Event>(Filter(kinds = listOf(10002), limit = 1100)) }
            .associate { it.pubKey to Outbox.parse(it) }
        val routesChanged = lists != relayLists
        relayLists = lists
        val previous = _state.value.following
        val previousThreadAuthor = _state.value.threadNotes.find { it.id == viewedThread }?.pubKey
        _state.update { it.copy(notes = all, threadNotes = if (viewedThread == thread && it.threadId == thread) threadNotes else it.threadNotes, hashtagNotes = hashtagNotes, profileEvents = profileEvents, embedded = (it.embedded + embedded).entries.toList().takeLast(128).associate { entry -> entry.toPair() }, engagement = (it.engagement + counts).entries.toList().takeLast(800).associate { entry -> entry.toPair() }, profiles = (it.profiles - profiles.keys + profiles).entries.toList().takeLast(1000).associate { entry -> entry.toPair() }, following = following) }
        if (active && (readingChanged || following != previous || routesChanged || previousThreadAuthor != threadNotes.find { it.id == viewedThread }?.pubKey)) subscribeFeed()
        else if (active) subscribeReadingData()
    }

    private suspend fun trimCache() = mutex.withLock { trimCacheLocked() }
    private suspend fun trimCacheLocked() {
        for ((kind, max) in listOf(1 to 2000, 7 to 2000, 9735 to 2000, 9802 to 600, 0 to 1000, 3 to 10, 10002 to 1100)) {
            val rows = db.query<Event>(Filter(kinds = listOf(kind), limit = max + 1))
            if (rows.size > max) db.delete(Filter(kinds = listOf(kind), until = rows[max].createdAt))
        }
        db.deleteExpiredEvents()
    }
    suspend fun account(pubkey: String, signer: String) = withContext(Dispatchers.IO) {
        require(Protocol.publicKey(pubkey) == pubkey && signer.isNotBlank())
        prefs.edit().putString("pubkey", pubkey).putString("signer", signer).remove("draft").remove("draftReply").remove("pending").apply()
        _state.update { it.copy(publicKey = pubkey, signerPackage = signer, following = emptySet(), loading = true) }
        ready.await(); snapshot(); if (active) subscribeFeed()
    }
    suspend fun logout() = withContext(Dispatchers.IO) {
        prefs.edit().remove("pubkey").remove("signer").remove("draft").remove("draftReply").remove("pending").apply()
        _state.update { it.copy(publicKey = null, signerPackage = null, following = emptySet()) }
        snapshot(); if (active) subscribeFeed()
    }
    fun viewProfile(key: String?) { if (viewedProfile == key) return; historyJob?.cancel(); profileLimit = 100; profileUntil = null; profileTab = ProfileTab.POSTS; viewedProfile = key; changed.tryEmit(Unit); if (key == null) client.unsubscribe("profile"); if (active) subscribeFeed() }
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
        client.subscribe("profile", authorFilters(listOf(key), listOf(profileTab.kind), 80), listener)
    }
    fun viewThread(id: String?, clicked: Event? = null) {
        if (viewedThread == id) return
        val cached = state.value
        val root = clicked?.takeIf { it.id == id && it.kind in listOf(1, 9802) }
            ?: cached.threadNotes.find { it.id == id }
            ?: cached.embedded[id]
            ?: cached.profileEvents.find { it.id == id }
            ?: cached.hashtagNotes.find { it.id == id }
            ?: cached.notes.find { it.id == id }
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
        val filters = listOf(Filter(ids = listOf(id), kinds = listOf(1, 9802)), Filter(kinds = listOf(1), tags = mapOf("e" to listOf(id)), limit = 80))
        client.subscribe("thread", routes.associate { it.normalizeRelayUrl() to filters }, listener)
    }
    fun viewHashtag(tag: String?) {
        if (viewedHashtag == tag) return
        historyJob?.cancel()
        viewedHashtag = tag
        hashtagUntil = null
        changed.tryEmit(Unit)
        scope.launch {
            if (viewedHashtag == null) client.unsubscribe("hashtag")
            if (active) subscribeFeed()
        }
    }
    private fun hashtagFilters(tag: String, until: Long? = null) = state.value.relays.associate {
        it.normalizeRelayUrl() to listOf(Filter(kinds = listOf(1), tags = mapOf("t" to listOf(tag)), limit = 80, until = until))
    }
    fun older(author: String? = null) {
        historyJob?.cancel()
        historyJob = scope.launch {
            val s = state.value
            val tag = viewedHashtag
            val authors = if (tag != null) null else author?.let(::listOf) ?: s.publicKey?.let { (listOf(it) + s.following).take(1000) }
            val kind = if (author != null) profileTab.kind else 1
            val events = if (tag != null) s.hashtagNotes else if (author != null) s.profileEvents else s.notes
            val oldest = events.filter { it.kind == kind && (authors == null || it.pubKey in authors) }.minOfOrNull { it.createdAt } ?: return@launch
            if (tag != null) { hashtagUntil = oldest - 1; changed.tryEmit(Unit) }
            if (author != null) {
                if (profileLimit >= 600) profileUntil = oldest - 1
                else profileLimit = (profileLimit + 80).coerceAtMost(600)
                changed.tryEmit(Unit)
            }
            _state.update { it.copy(loading = true) }
            try { client.subscribe("older", if (tag != null) hashtagFilters(tag, oldest - 1) else authorFilters(authors, listOf(kind), 80, oldest - 1), listener); delay(12_000) }
            finally { client.unsubscribe("older"); _state.update { it.copy(loading = false) } }
        }
    }
    companion object { fun shortKey(key: String): String = "${key.take(8)}…${key.takeLast(4)}" }
}
