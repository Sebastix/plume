package plume.sebastix.dev

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
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Counts and zap amounts from locally discovered, verified events, not an authoritative global total. */
data class Engagement(val replies: Int = 0, val reactions: Int = 0, val zapSats: BigDecimal = BigDecimal.ZERO,
    // Store the identity, not a boolean: cached counts can outlive account switches.
    val zappedByAccount: String? = null, val reposts: Int = 0, val repostedByAccount: String? = null)

internal fun replyTarget(event: Event): String? {
    if (event.kind in listOf(1111, 1244)) event.tagValue("e")?.let(Protocol::publicKey)?.takeUnless { it == event.id }?.let { return it }
    return ThreadRelations.parent(event)?.id
}

/** Only resolve providers for receipts we actually have; never block the feed snapshot on HTTP. */
internal class ZapVerification(private val scope: CoroutineScope, private val changed: () -> Unit) {
    private data class Provider(val key: String?, val expires: Long)
    private val providers = ConcurrentHashMap<String, Provider>()
    private val running = ConcurrentHashMap<String, Job>()
    private val verified = object : LinkedHashMap<String, BigDecimal>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BigDecimal>?) = size > 1024
    }
    // Successful validation is evidence about immutable signed events, not provider freshness.
    private val confirmed = object : LinkedHashMap<String, BigDecimal>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BigDecimal>?) = size > 4096
    }
    private data class Validation(val receipt: ZapReceiptEvent, val target: Event, val recipient: String, val lnurl: String, val provider: String, val cacheKey: String)
    private val validationQueue = Channel<Validation>(capacity = 64)
    private val pending = mutableSetOf<String>()
    @Volatile private var enabled = false
    private val validator = scope.launch {
        for (work in validationQueue) {
            if (enabled) {
                val result = runCatching {
                    val request = work.receipt.zapRequest ?: return@runCatching BigDecimal.ZERO
                    val valid = request.verify() && request.kind == 9734 &&
                        request.tags.count { it.firstOrNull() == "p" } == 1 && request.tags.count { it.firstOrNull() == "e" } == 1 &&
                        request.tagValue("e") == work.target.id && request.tagValue("p") == work.recipient &&
                        work.receipt.tagValue("e") == work.target.id &&
                        ZapReceiptValidator.validate(work.receipt, work.provider, work.lnurl) == ZapReceiptValidator.Result.Valid
                    // Quartz derives this exact satoshi amount from the validated BOLT11 invoice.
                    if (valid) work.receipt.amount?.takeIf { it.signum() > 0 } ?: BigDecimal.ZERO else BigDecimal.ZERO
                }.getOrDefault(BigDecimal.ZERO)
                synchronized(this@ZapVerification) {
                    verified[work.cacheKey] = result
                    if (result.signum() > 0) confirmed["${work.receipt.id}:${work.target.id}"] = result
                }
            }
            synchronized(this@ZapVerification) { pending.remove(work.cacheKey) }
            if (enabled) changed()
        }
    }
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followSslRedirects(false).build()
    fun stop() { enabled = false; running.values.forEach { it.cancel() }; running.clear(); http.dispatcher.cancelAll() }

    @Synchronized
    fun amountInSats(event: Event, target: Event, profile: Profile?, active: Boolean): BigDecimal {
        enabled = active
        val receipt = event as? ZapReceiptEvent ?: return BigDecimal.ZERO
        val recipient = receipt.tagValue("p") ?: return BigDecimal.ZERO
        val splitRecipients = target.tags.filter { it.size > 1 && it[0] == "zap" }.map { it[1] }
        if (recipient != target.pubKey && recipient !in splitRecipients) return BigDecimal.ZERO
        confirmed["${receipt.id}:${target.id}"]?.let { return it }
        val lnurl = profile?.lightning ?: return BigDecimal.ZERO
        val url = runCatching { LnurlForm.toUrl(lnurl)?.let(MediaContent::secureUrl) }.getOrNull() ?: return BigDecimal.ZERO
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
            return BigDecimal.ZERO
        }
        val providerKey = provider.key ?: return BigDecimal.ZERO
        val cacheKey = "${event.id}:$url:$providerKey"
        verified[cacheKey]?.let { return it }
        if (active && pending.add(cacheKey)) {
            if (validationQueue.trySend(Validation(receipt, target, recipient, lnurl, providerKey, cacheKey)).isFailure) pending.remove(cacheKey)
        }
        return BigDecimal.ZERO
    }
}
