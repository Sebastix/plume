package org.dergigi.feather

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import com.vitorpamplona.quartz.nip57Zaps.validate.LnurlForm
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

internal const val DEFAULT_ZAP_SATS = 21L
internal fun sha256(value: ByteArray) = MessageDigest.getInstance("SHA-256").digest(value).joinToString("") { "%02x".format(it) }

/** Decode the BOLT11 hash tags after checksum/amount validation. Wallet verifies the invoice signature. */
internal fun invoiceHash(invoice: String, field: Char): String {
    val alphabet = "qpzry9x8gf2tvdw0s3jn54khce6mua7l"
    require(invoice == invoice.lowercase() || invoice == invoice.uppercase()) { "Invalid invoice" }
    val text = invoice.lowercase()
    val split = text.lastIndexOf('1')
    require(split > 0 && text.startsWith("lnbc")) { "Expected a Bitcoin Lightning invoice" }
    val words = text.substring(split + 1).map { alphabet.indexOf(it).also { n -> require(n >= 0) } }
    val hrp = text.substring(0, split)
    var checksum = 1
    val generators = intArrayOf(0x3b6a57b2, 0x26508e6d, 0x1ea119fa, 0x3d4233dd, 0x2a1462b3)
    for (value in hrp.map { it.code shr 5 } + listOf(0) + hrp.map { it.code and 31 } + words) {
        val top = checksum ushr 25
        checksum = ((checksum and 0x1ffffff) shl 5) xor value
        for (i in 0..4) if (((top ushr i) and 1) != 0) checksum = checksum xor generators[i]
    }
    require(checksum == 1 && words.size >= 117) { "Invalid invoice checksum" }
    var position = 7
    val end = words.size - 6 - 104
    var result: String? = null
    while (position < end) {
        require(position + 3 <= end) { "Invalid invoice tags" }
        val tag = alphabet[words[position]]
        val length = words[position + 1] * 32 + words[position + 2]
        position += 3
        require(position + length <= end) { "Invalid invoice tags" }
        if (tag == field) {
            require(length == 52 && result == null) { "Invalid invoice hash" }
            var bits = 0; var buffer = 0
            val bytes = ArrayList<Byte>()
            for (word in words.subList(position, position + length)) {
                buffer = (buffer shl 5) or word; bits += 5
                if (bits >= 8) { bits -= 8; bytes.add((buffer ushr bits).toByte()) }
            }
            require(bits == 4 && (buffer and 15) == 0 && bytes.size == 32)
            result = bytes.joinToString("") { "%02x".format(it) }
        }
        position += length
    }
    return result ?: error("Missing invoice hash")
}

internal class ZapWallet(context: Context) {
    val store = WalletStore(context)
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private fun get(url: String): JSONObject {
        require(MediaContent.secureUrl(url) != null) { "A secure Lightning endpoint is required" }
        return http.newCall(Request.Builder().url(url).header("Accept", "application/json").build()).execute().use { response ->
            require(response.isSuccessful) { "Lightning endpoint unavailable" }
            val bytes = response.body.source().apply { request(131073) }.buffer.clone().readByteArray()
            require(bytes.size <= 131072) { "Lightning response too large" }
            JSONObject(bytes.toString(Charsets.UTF_8)).also { require(it.optString("status") != "ERROR") { "Lightning endpoint rejected the zap" } }
        }
    }
    suspend fun connect(raw: String) = withContext(Dispatchers.IO) {
        val connection = WalletConnection.parse(raw)
        try {
            val info = NwcWallet.request(connection, "get_info", JSONObject())
            val methods = info.optJSONArray("methods")
            require(methods != null && (0 until methods.length()).any { methods.optString(it) == "pay_invoice" }) { "Wallet does not allow payments" }
            store.save(raw)
        } finally { connection.secret.fill(0) }
    }
    suspend fun zap(note: Event, lightning: String?, relays: List<String>, signer: NostrSignerExternal): Unit = withContext(Dispatchers.IO) {
        val connection = store.connection() ?: error("Connect a wallet in settings")
        try {
            val paymentId = sha256("${connection.pubkey}:${signer.pubKey}:${note.id}".toByteArray())
            val existing = store.payment(paymentId)
            if (existing?.optString("status") == "paid") {
                store.savePayment(paymentId, existing.put("author", signer.pubKey).put("note", note.id))
                return@withContext
            }
            if (existing != null) {
                // Unknown outcomes must be checked, never replaced with a fresh payable invoice.
                val status = NwcWallet.request(connection, "lookup_invoice", JSONObject().put("invoice", existing.getString("invoice")))
                if (status.optString("state") == "settled" || status.optString("preimage").length == 64) {
                    verifyPreimage(status, existing.getString("hash"))
                    store.savePayment(paymentId, existing.put("status", "paid").put("author", signer.pubKey).put("note", note.id))
                    return@withContext
                }
                throw WalletFailure("Payment not confirmed. Check your wallet; no new payment was sent.")
            }
            val endpoint = lightning?.let(LnurlForm::toUrl) ?: error("This author has no Lightning address")
            val params = get(endpoint)
            require(params.optString("tag") == "payRequest" && params.optBoolean("allowsNostr") && Protocol.publicKey(params.optString("nostrPubkey")) != null) { "This address does not support Nostr zaps" }
            val msats = DEFAULT_ZAP_SATS * 1000
            require(msats >= params.getLong("minSendable") && msats <= params.getLong("maxSendable")) { "This address does not accept a 21-sat zap" }
            val callback = params.getString("callback")
            require(MediaContent.secureUrl(callback) != null) { "Invalid Lightning callback" }
            val tags = buildList {
                add(arrayOf("p", note.pubKey)); add(arrayOf("e", note.id)); add(arrayOf("k", note.kind.toString()))
                add(arrayOf("amount", msats.toString()))
                add((listOf("relays") + relays.mapNotNull(Protocol::relay).distinct().take(6)).toTypedArray())
                if (note.kind in 30000..39999) note.tagValue("d")?.let { add(arrayOf("a", "${note.kind}:${note.pubKey}:$it")) }
            }.toTypedArray()
            val now = System.currentTimeMillis() / 1000
            val request = withTimeout(180_000) { signer.sign<Event>(now, 9734, tags, "") }
            require(request.pubKey == signer.pubKey && request.kind == 9734 && request.createdAt == now && request.content.isEmpty() &&
                request.tags.contentDeepEquals(tags) && request.verify()) { "Invalid signed zap request" }
            val json = request.toJson()
            val url = callback.toHttpUrl().newBuilder().setQueryParameter("amount", msats.toString()).setQueryParameter("nostr", json).build()
            val invoice = get(url.toString()).getString("pr")
            require(LnInvoiceUtil.getAmountInSats(invoice).compareTo(BigDecimal.valueOf(DEFAULT_ZAP_SATS)) == 0) { "Invoice amount does not match 21 sats" }
            require(invoiceHash(invoice, 'h') == sha256(json.toByteArray())) { "Invoice does not match this zap request" }
            val hash = invoiceHash(invoice, 'p')
            require((LnInvoiceUtil.parseDetails(invoice)?.expiresAt() ?: 0L) > System.currentTimeMillis() / 1000) { "Invoice expired" }
            val record = JSONObject().put("invoice", invoice).put("hash", hash).put("status", "pending").put("author", signer.pubKey).put("note", note.id)
            store.savePayment(paymentId, record)
            // Persist before sending: process death or timeout must never cause a duplicate payment.
            val paid = try { NwcWallet.request(connection, "pay_invoice", JSONObject().put("invoice", invoice)) }
            catch (failure: WalletFailure) {
                if (failure.code in setOf("UNAUTHORIZED", "RESTRICTED", "QUOTA_EXCEEDED", "INSUFFICIENT_BALANCE", "NOT_IMPLEMENTED", "RATE_LIMITED", "UNSUPPORTED_ENCRYPTION"))
                    store.forgetPayment(paymentId)
                throw failure
            }
            verifyPreimage(paid, hash)
            store.savePayment(paymentId, record.put("status", "paid"))

        } finally { connection.secret.fill(0) }
    }
    private fun verifyPreimage(result: JSONObject, hash: String) {
        val value = result.optString("preimage")
        require(Regex("[a-fA-F0-9]{64}").matches(value)) { "Wallet did not confirm payment" }
        require(sha256(value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()) == hash) { "Invalid payment confirmation" }
    }
}
