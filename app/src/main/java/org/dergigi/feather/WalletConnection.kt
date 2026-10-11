package org.dergigi.feather

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.net.URI
import java.net.URLDecoder
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** NWC credentials are separate from the user's identity; never log or save in UI state. */
internal class WalletConnection(val pubkey: String, val relays: List<String>, val secret: ByteArray) {
    companion object {
        fun parse(raw: String): WalletConnection {
            require(raw.length <= 8192) { "Invalid wallet connection" }
            val uri = URI(raw.trim())
            require(uri.scheme in listOf("nostr+walletconnect", "nostrwalletconnect")) { "Paste a Nostr Wallet Connect connection" }
            val pubkey = uri.host?.let(Protocol::publicKey) ?: error("Invalid wallet public key")
            val params = uri.rawQuery.orEmpty().split('&').mapNotNull {
                val parts = it.split('=', limit = 2)
                if (parts.size == 2) parts[0] to URLDecoder.decode(parts[1], "UTF-8") else null
            }
            val keys = params.filter { it.first == "secret" }
            require(keys.size == 1 && Regex("[0-9a-fA-F]{64}").matches(keys[0].second)) { "Invalid wallet secret" }
            val secret = keys[0].second.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val relays = params.filter { it.first == "relay" }.mapNotNull { Protocol.relay(it.second) }.distinct().take(3)
            require(relays.isNotEmpty()) { "Wallet needs a secure relay" }
            return WalletConnection(pubkey, relays, secret)
        }
    }
}

/** Android Keystore wrapping, adapted from Boris. All methods are called on IO. */
internal class WalletStore(context: Context) {
    private val prefs = context.getSharedPreferences("wallet", Context.MODE_PRIVATE)
    val connected = MutableStateFlow(false)
    val confirmedPayments = MutableStateFlow<Map<String, Pair<String, String>>>(emptyMap())
    val recentAmounts = MutableStateFlow<Map<String, List<Long>>>(emptyMap())
    private fun confirmed(value: org.json.JSONObject): Pair<String, String>? {
        if (value.optString("status") != "paid") return null
        val author = Protocol.publicKey(value.optString("author")) ?: return null
        val note = Protocol.publicKey(value.optString("note")) ?: return null
        return author to note
    }
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("feather-wallet", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance("AES", "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("feather-wallet", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        }.generateKey()
    }
    private fun seal(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)
    }
    private fun open(value: String): String {
        val data = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        return cipher.doFinal(data.copyOfRange(12, data.size)).toString(Charsets.UTF_8)
    }
    private var account: String? = null
    @Synchronized fun selectAccount(owner: String?) {
        account = owner
        connected.value = false
        // Legacy connections have no provable owner. Reconnect explicitly rather than assigning them.
        if (owner == null || prefs.getString("owner", null) != owner) disconnect()
        connected.value = runCatching { owner != null && connection(owner)?.also { it.secret.fill(0) } != null }.getOrDefault(false)
    }
    fun load() {
        val saved = prefs.all.keys.filter { it.startsWith("payment:") }.mapNotNull { key ->
            runCatching { payment(key.removePrefix("payment:"))?.let(::confirmed)?.let { key to it } }.getOrNull()
        }.toMap()
        confirmedPayments.update { saved + it }
        val history = prefs.all.keys.filter { it.startsWith("payment:") }.mapNotNull {
            runCatching { payment(it.removePrefix("payment:")) }.getOrNull()
        }.filter { confirmed(it) != null }.sortedByDescending { it.optLong("time") }
        recentAmounts.value = history.groupBy { it.getString("author") }.mapValues { (_, records) ->
            records.map { it.optLong("sats", DEFAULT_ZAP_SATS) }.filter { it in 1..MAX_ZAP_SATS }.distinct().take(8)
        }
    }
    @Synchronized fun connection(owner: String): WalletConnection? {
        check(account == owner) { "Wallet account changed" }
        if (prefs.getString("owner", null) != owner) return null
        return prefs.getString("connection", null)?.let { WalletConnection.parse(open(it)) }
    }
    @Synchronized fun save(raw: String, owner: String) {
        check(account == owner) { "Wallet account changed" }
        check(prefs.edit().putString("connection", seal(raw.trim())).putString("owner", owner).commit()) { "Could not save wallet" }
        connected.value = true
    }
    @Synchronized fun disconnect() {
        // Preserve payment journal, so reconnecting cannot accidentally repeat an uncertain payment.
        check(prefs.edit().remove("connection").remove("owner").commit()) { "Could not remove wallet connection" }
        connected.value = false
    }
    fun forgetPayment(id: String) { check(prefs.edit().remove("payment:$id").commit()) }
    fun payment(id: String): org.json.JSONObject? = prefs.getString("payment:$id", null)?.let { org.json.JSONObject(open(it)) }
    fun savePayment(id: String, value: org.json.JSONObject) {
        // Never discard uncertain payments automatically.
        val entries = prefs.all.keys.filter { it.startsWith("payment:") }
        if (entries.size >= 512 && "payment:$id" !in entries) {
            val settled = entries.firstOrNull { entry -> runCatching {
                org.json.JSONObject(open(prefs.getString(entry, null)!!)).optString("status") == "paid"
            }.getOrDefault(false) }
            if (settled != null) {
                check(prefs.edit().remove(settled).commit())
                confirmedPayments.update { it - settled }
            }
        }
        require("payment:$id" in entries || prefs.all.keys.count { it.startsWith("payment:") } < 512) { "Wallet payment history is full" }
        check(prefs.edit().putString("payment:$id", seal(value.toString())).commit()) { "Could not save payment status" }
        confirmed(value)?.let { paid ->
            confirmedPayments.update { it + ("payment:$id" to paid) }
            val amount = value.optLong("sats", DEFAULT_ZAP_SATS)
            if (amount in 1..MAX_ZAP_SATS) recentAmounts.update {
                it + (paid.first to (listOf(amount) + it[paid.first].orEmpty()).distinct().take(8))
            }
        }
    }
}
