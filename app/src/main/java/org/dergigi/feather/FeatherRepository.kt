package org.dergigi.feather

import android.app.Application
import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.client.NostrClient
import com.vitorpamplona.quartz.nip01Core.relay.client.listeners.RelayConnectionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.reqs.SubscriptionListener
import com.vitorpamplona.quartz.nip01Core.relay.client.single.IRelayClient
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.Message
import com.vitorpamplona.quartz.nip01Core.relay.commands.toClient.OkMessage
import com.vitorpamplona.quartz.nip01Core.relay.filters.Filter
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip01Core.store.sqlite.EventStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

class FeatherApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.vitorpamplona.quartz.utils.Log.minLevel = com.vitorpamplona.quartz.utils.LogLevel.ERROR
    }
    val repository by lazy { FeatherRepository(this) }
}

data class Profile(val name: String, val about: String = "", val picture: String? = null)
data class FeedState(
    val notes: List<Event> = emptyList(),
    val profiles: Map<String, Profile> = emptyMap(),
    val following: Set<String> = emptySet(),
    val publicKey: String? = null,
    val signerPackage: String? = null,
    val relays: List<String> = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"),
    val connected: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
    val followingLoaded: Boolean = false,
)

@OptIn(FlowPreview::class)
class FeatherRepository(context: Context) {
    private val prefs = context.getSharedPreferences("feather", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val dbPath = context.getDatabasePath("events.db").also { it.parentFile?.mkdirs() }.absolutePath
    private val db by lazy { EventStore(dbName = dbPath, numReaders = 1) }
    private val mutex = Mutex()
    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val initialRelays = prefs.getString("relays", null)?.lines()?.mapNotNull(Protocol::relay)?.distinct()?.take(4)
    private val _state = MutableStateFlow(FeedState(
        publicKey = prefs.getString("pubkey", null), signerPackage = prefs.getString("signer", null),
        relays = initialRelays?.takeIf { it.isNotEmpty() } ?: FeedState().relays,
    ))
    val state: StateFlow<FeedState> = _state.asStateFlow()
    private val client = NostrClient(RelayTransport(), scope)
    private val ready = CompletableDeferred<Unit>()
    private val acknowledgements = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    private val verified = object : LinkedHashMap<String, Boolean>(1024, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 2048
    }
    @Volatile private var active = false
    private var viewedProfile: String? = null
    private var viewedThread: String? = null
    private var metadataJob: Job? = null
    private var historyJob: Job? = null
    private var refreshTimeout: Job? = null
    private var inserts = 0
    private val listener = object : SubscriptionListener {
        override suspend fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            if (forFilters?.any { it.match(event) } == true) accept(event)
        }
        override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            if (forFilters?.any { it.kinds?.contains(1) == true } == true) _state.update { it.copy(loading = false) }
            if (forFilters?.any { it.kinds?.contains(3) == true } == true) _state.update { it.copy(followingLoaded = true) }
        }
        override fun onClosed(message: String, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
            _state.update { it.copy(loading = false, error = "A relay could not serve this feed. Check your relays or try again.") }
        }
    }
    init {
        client.addConnectionListener(object : RelayConnectionListener {
            override fun onConnected(relay: IRelayClient, pingMillis: Int, compressed: Boolean) {
                _state.update { it.copy(connected = it.connected + relay.url.url, error = null) }
            }
            override fun onDisconnected(relay: IRelayClient) { _state.update { it.copy(connected = it.connected - relay.url.url) } }
            override fun onCannotConnect(relay: IRelayClient, errorMessage: String) {
                _state.update { it.copy(connected = it.connected - relay.url.url) }
            }
            override suspend fun onIncomingMessage(relay: IRelayClient, msgStr: String, msg: Message) {
                if (msg is OkMessage && msg.success) acknowledgements[msg.eventId]?.complete(Unit)
            }
        })
        scope.launch {
            try { trimCache(); snapshot(); ready.complete(Unit) }
            catch (e: Exception) { _state.update { it.copy(loading = false, error = "Could not open the local cache.") }; ready.completeExceptionally(e) }
        }
        scope.launch { changed.debounce(250).collect { snapshot() } }
    }
    fun foreground() {
        active = true
        scope.launch { ready.await(); if (active) { client.connect(); subscribeFeed() } }
    }
    fun background() { active = false; client.disconnect(); _state.update { it.copy(connected = emptySet(), loading = false) } }
    fun refresh() {
        _state.update { it.copy(loading = true, error = null) }
        scope.launch { ready.await(); subscribeFeed(); client.connect() }
    }
    private fun relayFilters(filters: List<Filter>) = _state.value.relays.associate { it.normalizeRelayUrl() to filters }
    private fun subscribeFeed() {
        val s = _state.value
        s.publicKey?.let { client.subscribe("account", relayFilters(listOf(Filter(authors = listOf(it), kinds = listOf(0, 3), limit = 2))), listener) }
            ?: client.unsubscribe("account")
        val authors = s.publicKey?.let { (listOf(it) + s.following).take(1000) }
        client.subscribe("feed", relayFilters(listOf(Filter(authors = authors, kinds = listOf(1), limit = 80))), listener)
        viewedProfile?.let { subscribeProfile(it) }
        viewedThread?.let { subscribeThread(it) }
        refreshTimeout?.cancel()
        refreshTimeout = scope.launch {
            delay(12_000)
            _state.update { it.copy(loading = false, error = if (it.connected.isEmpty()) "No relay connection. Cached notes are still available." else it.error) }
        }
    }
    private suspend fun accept(event: Event) {
        if (event.kind !in setOf(0, 1, 3) || event.content.length > 32_768 || event.tags.size > 5000 || event.createdAt > System.currentTimeMillis() / 1000 + 600) return
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
        val contact = key?.let { mutex.withLock { db.query<Event>(Filter(kinds = listOf(3), authors = listOf(it), limit = 1)).firstOrNull() } }
        val following = Protocol.follows(contact)
        val filters = mutableListOf(Filter(kinds = listOf(1), authors = key?.let { (listOf(it) + following).take(1000) }, limit = 600))
        viewedProfile?.let { filters += Filter(kinds = listOf(1), authors = listOf(it), limit = 100) }
        viewedThread?.let { filters += Filter(kinds = listOf(1), ids = listOf(it)); filters += Filter(kinds = listOf(1), tags = mapOf("e" to listOf(it)), limit = 100) }
        val all = mutex.withLock { db.query<Event>(filters) }.distinctBy { it.id }.sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).take(800)
        val profiles = mutex.withLock { db.query<Event>(Filter(kinds = listOf(0), limit = 1000)) }.associate { event ->
            val data = runCatching { JSONObject(event.content) }.getOrNull()
            event.pubKey to Profile(
                data?.optString("display_name")?.takeIf { it.isNotBlank() } ?: data?.optString("name")?.takeIf { it.isNotBlank() } ?: shortKey(event.pubKey),
                data?.optString("about").orEmpty().take(2000),
                data?.optString("picture")?.takeIf { it.startsWith("https://") },
            )
        }
        if (key != _state.value.publicKey) { changed.tryEmit(Unit); return }
        val previous = _state.value.following
        _state.update { it.copy(notes = all, profiles = profiles, following = following) }
        if (following != previous && active) subscribeFeed()
        metadataJob?.cancel()
        metadataJob = scope.launch {
            delay(400)
            if (!active) return@launch
            val missing = (all.take(100).map { it.pubKey } + listOfNotNull(key, viewedProfile)).distinct().filter { it !in profiles }.take(100)
            if (missing.isNotEmpty()) client.subscribe("profiles", relayFilters(listOf(Filter(authors = missing, kinds = listOf(0), limit = 100))), listener)
        }
    }
    private suspend fun trimCache() = mutex.withLock { trimCacheLocked() }
    private suspend fun trimCacheLocked() {
        for ((kind, max) in listOf(1 to 2000, 0 to 1000, 3 to 10)) {
            val rows = db.query<Event>(Filter(kinds = listOf(kind), limit = max + 1))
            if (rows.size > max) db.delete(Filter(kinds = listOf(kind), until = rows[max].createdAt))
        }
        db.deleteExpiredEvents()
    }
    suspend fun account(pubkey: String, signer: String?) {
        require(Protocol.publicKey(pubkey) == pubkey)
        prefs.edit().putString("pubkey", pubkey).putString("signer", signer).remove("draft").remove("draftReply").remove("pending").apply()
        _state.update { it.copy(publicKey = pubkey, signerPackage = signer, following = emptySet(), followingLoaded = false, loading = true) }
        ready.await(); snapshot(); subscribeFeed()
    }
    suspend fun logout() {
        prefs.edit().remove("pubkey").remove("signer").remove("draft").remove("draftReply").remove("pending").apply()
        _state.update { it.copy(publicKey = null, signerPackage = null, following = emptySet(), followingLoaded = false) }
        snapshot(); subscribeFeed()
    }
    fun setRelays(text: String) {
        val lines = text.lines().map { it.trim() }.filter { it.isNotBlank() }
        require(lines.size in 1..4 && lines.all { Protocol.relay(it) != null }) { "Use one to four wss:// relay addresses, one per line." }
        val relays = lines.mapNotNull(Protocol::relay).distinct()
        prefs.edit().putString("relays", relays.joinToString("\n")).apply()
        client.disconnect()
        _state.update { it.copy(relays = relays, connected = emptySet(), loading = true, error = null) }
        refresh()
    }
    fun viewProfile(key: String?) { viewedProfile = key; changed.tryEmit(Unit); if (key == null) client.unsubscribe("profile") else subscribeProfile(key) }
    private fun subscribeProfile(key: String) = client.subscribe("profile", relayFilters(listOf(Filter(authors = listOf(key), kinds = listOf(0, 1), limit = 80))), listener)
    fun viewThread(id: String?) { viewedThread = id; changed.tryEmit(Unit); if (id == null) client.unsubscribe("thread") else subscribeThread(id) }
    private fun subscribeThread(id: String) = client.subscribe("thread", relayFilters(listOf(Filter(ids = listOf(id), kinds = listOf(1)), Filter(kinds = listOf(1), tags = mapOf("e" to listOf(id)), limit = 80))), listener)
    fun older(author: String? = null) {
        historyJob?.cancel()
        historyJob = scope.launch {
            val s = state.value
            val authors = author?.let(::listOf) ?: s.publicKey?.let { (listOf(it) + s.following).take(1000) }
            val oldest = s.notes.filter { authors == null || it.pubKey in authors }.minOfOrNull { it.createdAt } ?: return@launch
            _state.update { it.copy(loading = true) }
            try { client.subscribe("older", relayFilters(listOf(Filter(authors = authors, kinds = listOf(1), until = oldest - 1, limit = 80))), listener); delay(12_000) }
            finally { client.unsubscribe("older"); _state.update { it.copy(loading = false) } }
        }
    }
    suspend fun publish(event: Event) {
        require(event.kind in setOf(1, 3) && event.verify() && event.pubKey == state.value.publicKey) { "Signer returned an invalid event." }
        val ack = CompletableDeferred<Unit>()
        acknowledgements[event.id] = ack
        try {
            client.connect()
            client.publish(event, state.value.relays.map { it.normalizeRelayUrl() }.toSet())
            withTimeout(20_000) { ack.await() }
            accept(event)
        } finally { acknowledgements.remove(event.id) }
    }
    suspend fun latestContacts(): Event? {
        val key = state.value.publicKey ?: error("Connect an account first.")
        val events = ConcurrentHashMap<String, Event>()
        val completed = ConcurrentHashMap.newKeySet<String>()
        val done = CompletableDeferred<Unit>()
        val filter = Filter(authors = listOf(key), kinds = listOf(3), limit = 1)
        val sub = "contacts-edit"
        client.subscribe(sub, relayFilters(listOf(filter)), object : SubscriptionListener {
            override suspend fun onEvent(event: Event, isLive: Boolean, relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                if (filter.match(event) && event.verify()) events[event.id] = event
            }
            override fun onEose(relay: NormalizedRelayUrl, forFilters: List<Filter>?) {
                completed.add(relay.url)
                if (completed.size >= state.value.relays.size) done.complete(Unit)
            }
        })
        try { withTimeoutOrNull(8_000) { done.await() } } finally { client.unsubscribe(sub) }
        check(completed.isNotEmpty()) { "Could not refresh your follow list. Try again when connected." }
        val local = mutex.withLock { db.query<Event>(filter).firstOrNull() }
        return (events.values + listOfNotNull(local)).sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).firstOrNull()
    }
    fun draftReply(): Event? = prefs.getString("draftReply", null)?.let { runCatching { Event.fromJson(it) }.getOrNull() }
    fun saveDraftReply(event: Event?) { prefs.edit().putString("draftReply", event?.toJson()).apply() }
    fun draft(): String = prefs.getString("draft", "").orEmpty()
    fun saveDraft(value: String) { prefs.edit().putString("draft", value).apply() }
    fun pending(): Event? = prefs.getString("pending", null)?.let { runCatching { Event.fromJson(it) }.getOrNull() }
    fun savePending(event: Event?) { prefs.edit().putString("pending", event?.toJson()).apply() }
    companion object { fun shortKey(key: String): String = "${key.take(8)}…${key.takeLast(4)}" }
}
