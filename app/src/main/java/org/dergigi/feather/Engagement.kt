package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip57Zaps.ZapReceiptEvent
import com.vitorpamplona.quartz.nip57Zaps.validate.LnurlForm
import com.vitorpamplona.quartz.nip57Zaps.validate.ZapReceiptValidator
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Counts of locally discovered, verified events, not an authoritative global total. */
data class Engagement(val replies: Int = 0, val reactions: Int = 0, val zaps: Int = 0)

internal fun replyTarget(event: Event): String? {
    if (event.kind != 1) return null
    val tags = event.tags.filter { it.size >= 2 && it[0] == "e" }
    val marked = tags.firstOrNull { it.getOrNull(3) == "reply" }
        ?: tags.firstOrNull { it.getOrNull(3) == "root" }
    return (marked ?: tags.lastOrNull { it.size < 4 || it[3].isEmpty() })?.get(1)
}

/** Only resolve providers for receipts we actually have; never block the feed snapshot on HTTP. */
internal class ZapVerification(private val scope: CoroutineScope, private val changed: () -> Unit) {
    private data class Provider(val key: String?, val expires: Long)
    private val providers = ConcurrentHashMap<String, Provider>()
    private val running = ConcurrentHashMap<String, Job>()
    private val verified = object : LinkedHashMap<String, Boolean>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>?) = size > 1024
    }
    private data class Validation(val receipt: ZapReceiptEvent, val target: Event, val recipient: String, val lnurl: String, val provider: String, val cacheKey: String)
    private val validationQueue = Channel<Validation>(capacity = 64)
    private val pending = mutableSetOf<String>()
    @Volatile private var enabled = false
    private val validator = scope.launch {
        for (work in validationQueue) {
            if (enabled) {
                val result = runCatching {
                    val request = work.receipt.zapRequest ?: return@runCatching false
                    request.verify() && request.kind == 9734 &&
                        request.tags.count { it.firstOrNull() == "p" } == 1 && request.tags.count { it.firstOrNull() == "e" } == 1 &&
                        request.tagValue("e") == work.target.id && request.tagValue("p") == work.recipient &&
                        work.receipt.tagValue("e") == work.target.id &&
                        ZapReceiptValidator.validate(work.receipt, work.provider, work.lnurl) == ZapReceiptValidator.Result.Valid
                }.getOrDefault(false)
                synchronized(this@ZapVerification) { verified[work.cacheKey] = result }
            }
            synchronized(this@ZapVerification) { pending.remove(work.cacheKey) }
            if (enabled) changed()
        }
    }
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followSslRedirects(false).build()
    fun stop() { enabled = false; running.values.forEach { it.cancel() }; running.clear(); http.dispatcher.cancelAll() }

    @Synchronized
    fun valid(event: Event, target: Event, profile: Profile?, active: Boolean): Boolean {
        enabled = active
        val receipt = event as? ZapReceiptEvent ?: return false
        val recipient = receipt.tagValue("p") ?: return false
        val splitRecipients = target.tags.filter { it.size > 1 && it[0] == "zap" }.map { it[1] }
        if (recipient != target.pubKey && recipient !in splitRecipients) return false
        val lnurl = profile?.lightning ?: return false
        val url = runCatching { LnurlForm.toUrl(lnurl)?.let(MediaContent::secureUrl) }.getOrNull() ?: return false
        val now = android.os.SystemClock.elapsedRealtime()
        val provider = providers[url]?.takeIf { it.expires > now }
        if (provider == null) {
            if (active && running.size < 2 && !running.containsKey(url)) {
                val job = scope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val key = runCatching {
                            http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                                if (!response.isSuccessful) return@use null
                                val body = response.body
                                val bytes = body.source().apply { request(65_537) }.buffer.clone().readByteArray()
                                if (bytes.size > 65_536) return@use null
                                val json = JSONObject(bytes.toString(Charsets.UTF_8))
                                if (json.optBoolean("allowsNostr")) Protocol.publicKey(json.optString("nostrPubkey")) else null
                            }
                        }.getOrNull()
                        ensureActive()
                        if (providers.size >= 128) providers.keys.firstOrNull()?.let(providers::remove)
                        providers[url] = Provider(key, android.os.SystemClock.elapsedRealtime() + if (key == null) 60_000 else 3_600_000)
                        changed()
                    } finally { running.remove(url, currentCoroutineContext().job) }
                }
                running[url] = job
                job.start()
            }
            return false
        }
        val providerKey = provider.key ?: return false
        val cacheKey = "${event.id}:$url:$providerKey"
        verified[cacheKey]?.let { return it }
        if (active && pending.add(cacheKey)) {
            if (validationQueue.trySend(Validation(receipt, target, recipient, lnurl, providerKey, cacheKey)).isFailure) pending.remove(cacheKey)
        }
        return false
    }
}
