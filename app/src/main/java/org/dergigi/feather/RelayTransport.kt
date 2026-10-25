package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.relay.normalizer.NormalizedRelayUrl
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebsocketBuilder
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocket as QuartzSocket
import com.vitorpamplona.quartz.nip01Core.relay.sockets.WebSocketListener as QuartzListener
import kotlinx.coroutines.runBlocking
import okhttp3.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Consume frames serially on OkHttp's reader, without an unbounded message queue. */
class RelayTransport : WebsocketBuilder {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).pingInterval(30, TimeUnit.SECONDS).build()
    override fun build(url: NormalizedRelayUrl, out: QuartzListener): QuartzSocket = object : QuartzSocket {
        @Volatile private var socket: WebSocket? = null
        private val started = AtomicBoolean(false)
        private val ended = AtomicBoolean(false)
        override fun needsReconnect() = ended.get() || !started.get()
        override fun connect() {
            if (ended.get() || !started.compareAndSet(false, true)) return
            val beginning = System.nanoTime()
            val created = try {
                val validated = Protocol.relay(url.url) ?: throw IllegalArgumentException("Invalid relay URL")
                http.newWebSocket(Request.Builder().url(validated).build(), object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: Response) {
                        if (ended.get()) { webSocket.cancel(); return }
                        out.onOpen(((System.nanoTime() - beginning) / 1_000_000).toInt(), false)
                    }
                    override fun onMessage(webSocket: WebSocket, text: String) {
                        if (ended.get()) return
                        if (text.length > 131_072) { webSocket.cancel(); fail(IllegalStateException("Relay message too large")); return }
                        try { runBlocking { if (!ended.get()) out.onMessage(text) } }
                        catch (e: Exception) { webSocket.cancel(); fail(e) }
                    }
                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, reason) }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (ended.compareAndSet(false, true)) out.onClosed(code, reason) }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { fail(t) }
                })
            } catch (e: Exception) {
                fail(e)
                return
            }
            socket = created
            if (ended.get()) created.cancel()
        }
        private fun fail(t: Throwable) { if (ended.compareAndSet(false, true)) out.onFailure(t, null, null) }
        override fun disconnect() {
            if (ended.compareAndSet(false, true)) { socket?.cancel(); out.onClosed(1000, "App paused") }
        }
        override fun send(msg: String): Boolean = !ended.get() && socket?.send(msg) == true
    }
}
