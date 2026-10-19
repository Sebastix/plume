package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONArray
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Finite profile reads: four sockets at a time, no permanent expansion of the feed pool. */
internal class ProfileRelayQuery {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private val slots = Semaphore(4)

    suspend fun fetch(relays: List<String>, filters: List<String>, accept: suspend (Event) -> Boolean): Boolean {
        var found = false
        for (batch in relays.distinct().chunked(4)) {
            currentCoroutineContext().ensureActive()
            val received = coroutineScope { batch.map { url -> async { slots.withPermit { fetchOne(url, filters, accept = accept) } } }.awaitAll() }
            found = found || received.any { it.found }
        }
        return found
    }

    /** EOSE is distinct from a disconnect, timeout or CLOSED response. */
    suspend fun completed(relays: List<String>, filters: List<String>, accept: suspend (String, Event) -> Boolean): Set<String> {
        // Keep four sockets busy without waiting for the slowest member of each batch.
        // Every candidate is still queried; do not stop at the first quorum and miss a newer list.
        return coroutineScope {
            relays.distinct().map { url -> async {
                slots.withPermit {
                    url to fetchOne(url, filters, timeoutMs = 5_000) { event -> accept(url, event) }
                }
            } }.awaitAll().filter { it.second.complete }.mapTo(mutableSetOf()) { it.first }
        }
    }

    private data class Read(val found: Boolean, val complete: Boolean)
    private suspend fun fetchOne(url: String, filters: List<String>, timeoutMs: Long = 10_000, accept: suspend (Event) -> Boolean): Read {
        val done = CompletableDeferred<Unit>()
        val stopped = AtomicBoolean(false)
        val found = AtomicBoolean(false)
        val complete = AtomicBoolean(false)
        var count = 0
        val socket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (stopped.get()) { webSocket.cancel(); return }
                webSocket.send("[\"REQ\",\"profile-page\",${filters.joinToString(",")}]")
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (stopped.get() || done.isCompleted) return
                if (text.length > 524_288 || ++count > 1024) { done.complete(Unit); webSocket.cancel(); return }
                try {
                    val frame = JSONArray(text)
                    if (frame.optString(1) != "profile-page") return
                    when (frame.optString(0)) {
                        "EVENT" -> {
                            val event = Event.fromJson(frame.getJSONObject(2).toString())
                            // Backpressure on this socket's reader; never enqueue an unbounded event stream.
                            runBlocking { if (!stopped.get() && accept(event)) found.set(true) }
                        }
                        "EOSE" -> { complete.set(true); done.complete(Unit) }
                        "CLOSED" -> done.complete(Unit)
                    }
                } catch (_: Exception) { done.complete(Unit) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.complete(Unit) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { done.complete(Unit) }
        })
        try { withTimeoutOrNull(timeoutMs) { done.await() } }
        finally { stopped.set(true); socket.cancel() }
        return Read(found.get(), complete.get())
    }
}
