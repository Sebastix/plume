package org.dergigi.feather

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.ConcurrentHashMap

internal enum class IssueType { BUG, FEATURE, CRASH }
internal data class IssueDraft(val type: IssueType, val title: String, val body: String, val crash: String? = null,
    val build: String = "Feather ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.GIT_COMMIT}")

/** Only explicit Send signs/publishes. Retry the exact saved event, including after process death. */
internal class IssueReports(
    private val context: Context,
    private val scope: CoroutineScope,
    private val signer: () -> NostrSignerExternal?,
    private val requests: SignerRequests,
) {
    val draft = MutableStateFlow<IssueDraft?>(null)
    val visible = MutableStateFlow(false)
    val busy = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)
    val published = MutableStateFlow<String?>(null)
    private val writes = Channel<Unit>(Channel.CONFLATED)
    private val pendingWrites = ConcurrentHashMap<IssueType, IssueDraft>()
    private var job: Job? = null
    private var loading: Job? = null
    private fun prefs() = context.getSharedPreferences("issue-reports", Context.MODE_PRIVATE)

    init {
        scope.launch(Dispatchers.IO) {
            for (signal in writes) {
                for ((type, value) in pendingWrites.entries.toList()) {
                    try {
                        check(prefs().edit().putString(type.name, encode(value)).commit())
                        pendingWrites.remove(type, value)
                    } catch (e: Exception) { error.value = "Could not save issue draft" }
                }
            }
        }
    }

    fun open(type: IssueType, crash: String? = null) {
        if (busy.value) { visible.value = true; return }
        if (draft.value?.let { it.type == type && it.crash == crash } == true && published.value == null) {
            visible.value = true; return
        }
        loading?.cancel()
        visible.value = true; draft.value = null; error.value = null; published.value = null
        loading = scope.launch {
            try {
            draft.value = withContext(Dispatchers.IO) {
                val saved = pendingWrites[type] ?: prefs().getString(type.name, null)?.let { raw -> runCatching {
                    val obj = JSONObject(raw)
                    IssueDraft(type, obj.getString("title").take(160), obj.getString("body").take(40_000),
                        obj.optString("crash").takeIf { it.isNotEmpty() }, obj.getString("build"))
                }.getOrNull() }
                saved?.takeIf { it.crash == crash && (it.title.isNotBlank() || it.body.isNotBlank()) } ?: IssueDraft(type,
                    if (type == IssueType.CRASH) "Crash: " + redactIssueDiagnostics(crash.orEmpty()).lineSequence()
                        .firstOrNull { it.contains("Exception") || it.contains("Error:") }?.take(130).orEmpty() else "",
                    if (crash != null) "```text\n" + redactIssueDiagnostics(crash).take(38_000).replace("```", "'''") + "\n```"
                    else "", crash)
            }
            draft.value?.let(::save)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { error.value = "Could not load issue draft" }
        }
    }

    private fun save(value: IssueDraft) { pendingWrites[value.type] = value; writes.trySend(Unit) }

    fun edit(title: String, body: String) {
        if (busy.value || published.value != null) return
        draft.value?.copy(title = title.take(160), body = body.take(40_000))?.let {
            draft.value = it; save(it)
        }
    }

    fun close() { visible.value = false }
    fun accountChanged() { job?.cancel() }

    fun send() {
        val identity = signer() ?: return
        val current = draft.value ?: return
        if (busy.value || published.value != null || current.title.isBlank() || current.body.isBlank()) return
        busy.value = true; error.value = null
        job = scope.launch {
            try {
                val id = withContext(Dispatchers.IO) {
                    val body = current.body.trimEnd() + if (current.type == IssueType.CRASH) "" else
                        "\n\n---\n${current.build}"
                    require(body.toByteArray(Charsets.UTF_8).size <= 55_000) { "Issue is too long. Please shorten the details." }
                    val tags = arrayOf(arrayOf("a", REPOSITORY, RELAYS.first()), arrayOf("p", MAINTAINER),
                        arrayOf("subject", current.title.trim()), arrayOf("t", if (current.type == IssueType.FEATURE) "enhancement" else "bug"),
                        arrayOf("client", "Feather")) + if (current.type == IssueType.CRASH) arrayOf(arrayOf("t", "crash")) else emptyArray()
                    val key = "signed:${identity.pubKey}:${current.type.name}"
                    check(prefs().edit().putString(current.type.name, encode(current)).commit()) { "Could not save issue draft" }
                    val saved = prefs().getString(key, null)?.let { runCatching { Event.fromJson(it) }.getOrNull() }
                        ?.takeIf { it.pubKey == identity.pubKey && it.kind == 1621 && it.content == body && it.tags.contentDeepEquals(tags) && it.verify() }
                    val event = saved ?: requests.run {
                        check(signer() === identity) { "Account changed" }
                        val now = System.currentTimeMillis() / 1000
                        withTimeout(180_000) { identity.sign<Event>(now, 1621, tags, body) }.also {
                            require(it.pubKey == identity.pubKey && it.createdAt == now && it.kind == 1621 &&
                                it.content == body && it.tags.contentDeepEquals(tags) && it.verify()) { "Invalid signed issue" }
                        }
                    }
                    check(signer() === identity) { "Account changed" }
                    // Persist before network I/O: losing an OK never creates a second issue on retry.
                    val edit = prefs().edit().putString(key, event.toJson())
                    prefs().all.keys.filter { it.startsWith("signed:") && it != key }.drop(15).forEach(edit::remove)
                    check(edit.commit()) { "Could not save signed issue for retry" }
                    val failures = supervisorScope { RELAYS.map { relay -> async {
                        try { publishIssue(relay, event); null }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { "${relay.removePrefix("wss://")}: ${e.message.orEmpty().take(300)}" }
                    } }.awaitAll() }
                    check(failures.any { it == null }) { "Issue not confirmed. Send again to retry the same issue.\n${failures.joinToString("\n")}" }
                    try { (context.applicationContext as FeatherApplication).repository.cacheIssue(event) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { /* A cache failure must not turn an acknowledged issue into a failed submission. */ }
                    com.vitorpamplona.quartz.nip19Bech32.entities.NEvent.create(event.id, event.pubKey, event.kind,
                        relay = RELAYS[failures.indexOfFirst { it == null }].normalizeRelayUrl())
                }
                published.value = id
                current.crash?.let { (context.applicationContext as FeatherApplication).crashes.dismiss(it) }
                save(current.copy(title = "", body = "", crash = null))
            } catch (e: TimeoutCancellationException) { error.value = "Issue submission timed out. Send again to retry." }
            catch (e: CancellationException) { error.value = "Issue submission cancelled. Draft saved."; throw e }
            catch (e: Exception) { error.value = e.message ?: "Could not publish issue. Draft saved." }
            finally { busy.value = false }
        }
    }

    private fun encode(value: IssueDraft) = JSONObject().put("title", value.title).put("body", value.body)
        .put("crash", value.crash.orEmpty()).put("build", value.build).toString()

    companion object {
        const val MAINTAINER = "6e468422dfb74a5738702a8823b9b28168abab8655faacb6853cd0ee15deee93"
        const val REPOSITORY = "30617:$MAINTAINER:feather"
        val RELAYS = listOf("wss://relay.ngit.dev", "wss://gitnostr.com")
    }
}

/** Remove common credentials/URLs from prefilled diagnostics; users can review and edit the report. */
internal fun redactIssueDiagnostics(raw: String): String = raw
    .replace(Regex("(?i)(?:https?|wss?|nostr\\+walletconnect|nostrwalletconnect|bunker|nostrconnect)://[^\\s<>]+"), "[URL removed]")
    .replace(Regex("(?i)(?:nsec|ncryptsec|nbunksec)1[0-9a-z]+"), "[secret removed]")
    .replace(Regex("(?i)(authorization|secret|password|token|preimage|private[_ -]?key)\\s*[:=]\\s*[^\\s,;]+"), "$1=[removed]")
    .replace(Regex("(?i)\\b[0-9a-f]{64}\\b"), "[identifier removed]")
    .replace(Regex("/(?:data|storage|sdcard)/[^\\s]+"), "[local path removed]")

private val issueHttp by lazy { OkHttpClient.Builder().connectTimeout(8, TimeUnit.SECONDS).build() }
private suspend fun publishIssue(relay: String, event: Event) {
    val done = CompletableDeferred<Unit>()
    val socket = issueHttp.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
        private var received = 0
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (!webSocket.send("[\"EVENT\",${event.toJson()}]")) done.completeExceptionally(IllegalStateException("Could not send issue"))
        }
        override fun onMessage(webSocket: WebSocket, text: String) {
            if (done.isCompleted) return
            if (text.length > 16_384 || ++received > 32) { done.completeExceptionally(IllegalStateException("Relay response exceeded limits")); return }
            runCatching {
                val frame = JSONArray(text)
                if (frame.optString(0) == "OK" && frame.optString(1) == event.id) {
                    if (frame.optBoolean(2)) done.complete(Unit)
                    else done.completeExceptionally(IllegalStateException(frame.optString(3).take(300).ifBlank { "Relay rejected issue" }))
                }
                if (frame.optString(0) == "AUTH") done.completeExceptionally(IllegalStateException("Relay requires authentication"))
            }
        }
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.completeExceptionally(t) }
        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            done.completeExceptionally(IllegalStateException("Relay closed before confirming issue ($code)"))
        }
    })
    try { withTimeout(15_000) { done.await() } }
    catch (e: TimeoutCancellationException) { throw IllegalStateException("Relay did not confirm within 15s", e) }
    finally { socket.cancel() }
}
