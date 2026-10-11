package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.KeyPair
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip01Core.signers.NostrSignerInternal
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

internal class WalletFailure(message: String, val code: String? = null) : Exception(message)

/** Dedicated bounded NWC sockets. First connected relay handles the request; no automatic payment retry. */
internal object NwcWallet {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).readTimeout(0, TimeUnit.SECONDS).build()
    suspend fun request(connection: WalletConnection, method: String, params: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val signer = NostrSignerInternal(KeyPair(privKey = connection.secret))
        val inbox = Channel<Event>(64)
        val opened = Channel<WebSocket>(3)
        val infoId = UUID.randomUUID().toString()
        val responseId = UUID.randomUUID().toString()
        val responseSubscription = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val sockets = connection.relays.map { relay ->
            val authSent = java.util.concurrent.atomic.AtomicBoolean(false)
            val authId = java.util.concurrent.atomic.AtomicReference<String?>(null)
            val infoRequest = JSONArray().put("REQ").put(infoId).put(JSONObject().put("kinds", JSONArray().put(13194))
                .put("authors", JSONArray().put(connection.pubkey)).put("limit", 1)).toString()
            http.newWebSocket(Request.Builder().url(relay).build(), object : WebSocketListener() {
                override fun onOpen(socket: WebSocket, response: Response) {
                    socket.send(infoRequest)
                    opened.trySend(socket)
                }
                override fun onMessage(socket: WebSocket, text: String) {
                    if (text.length > 131072) return
                    // Parsing and signature verification run on the wallet IO coroutine below.
                    runCatching {
                        val data = JSONArray(text)
                        if (data.optString(0) == "AUTH" && data.optString(1).length in 1..1024 && authSent.compareAndSet(false, true)) {
                            launch(Dispatchers.IO) {
                                runCatching {
                                    val auth = signer.sign<Event>(System.currentTimeMillis() / 1000, 22242,
                                        arrayOf(arrayOf("relay", relay), arrayOf("challenge", data.getString(1))), "")
                                    authId.set(auth.id)
                                    socket.send(JSONArray().put("AUTH").put(JSONObject(auth.toJson())).toString())
                                }
                            }
                        }
                        if (data.optString(0) == "OK" && data.optString(1) == authId.get() && data.optBoolean(2)) {
                            socket.send(infoRequest)
                            responseSubscription.get()?.let { socket.send(it) }
                        }
                        if (data.optString(0) == "EVENT" && data.optString(1) in listOf(infoId, responseId))
                            inbox.trySend(Event.fromJson(data.getJSONObject(2).toString()))
                    }
                }
            })
        }
        try {
            val socket = withTimeout(12_000) { opened.receive() }
            val info = withTimeoutOrNull(4_000) {
                while (true) {
                    val event = inbox.receive()
                    if (event.kind == 13194 && event.pubKey == connection.pubkey && event.verify()) return@withTimeoutOrNull event
                }
                @Suppress("UNREACHABLE_CODE") null
            }
            val nip44 = info?.tags?.any { it.firstOrNull() == "encryption" && it.drop(1).any { value -> "nip44_v2" in value.split(' ') } } == true
            val plain = JSONObject().put("method", method).put("params", params).toString()
            val encrypted = if (nip44) signer.nip44Encrypt(plain, connection.pubkey) else signer.nip04Encrypt(plain, connection.pubkey)
            val now = System.currentTimeMillis() / 1000
            val event = signer.sign<Event>(now, 23194, buildList {
                add(arrayOf("p", connection.pubkey)); add(arrayOf("expiration", (now + 90).toString()))
                if (nip44) add(arrayOf("encryption", "nip44_v2"))
            }.toTypedArray(), encrypted)
            val subscription = JSONArray().put("REQ").put(responseId).put(JSONObject().put("kinds", JSONArray().put(23195))
                .put("authors", JSONArray().put(connection.pubkey)).put("#e", JSONArray().put(event.id))
                .put("#p", JSONArray().put(signer.pubKey))).toString()
            responseSubscription.set(subscription)
            socket.send(subscription)
            check(socket.send(JSONArray().put("EVENT").put(JSONObject(event.toJson())).toString())) { "Wallet relay disconnected" }
            withTimeout(if (method == "pay_invoice") 90_000 else 20_000) {
                while (true) {
                    val response = inbox.receive()
                    if (response.kind != 23195 || response.pubKey != connection.pubkey || response.tagValue("e") != event.id ||
                        response.tagValue("p") != signer.pubKey || !response.verify()) continue
                    val decoded = runCatching { if (nip44) signer.nip44Decrypt(response.content, connection.pubkey)
                        else signer.nip04Decrypt(response.content, connection.pubkey) }.getOrNull() ?: continue
                    val json = runCatching { JSONObject(decoded) }.getOrNull() ?: continue
                    if (json.optString("result_type") != method) continue
                    json.optJSONObject("error")?.let {
                        val code = it.optString("code").takeIf { value -> Regex("[A-Z_]{1,40}").matches(value) } ?: "OTHER"
                        throw WalletFailure("Wallet: $code", code)
                    }
                    return@withTimeout json.optJSONObject("result") ?: throw WalletFailure("Invalid wallet response")
                }
                @Suppress("UNREACHABLE_CODE") JSONObject()
            }
        } finally {
            sockets.forEach { it.cancel() }; inbox.close(); opened.close()
        }
    }
}
