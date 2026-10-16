package org.dergigi.feather

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.json.JSONArray

internal const val SETTINGS_D = "org.dergigi.feather/settings"

// Stable wire names; local masks retain the existing notification category bits.
internal object NotificationFilterSettings {
    val names = listOf("replies", "reactions", "zaps", "reposts", "mentions")
    const val MASK = 62
    fun encode(mask: Int) = JSONArray(names.filterIndexed { index, _ -> mask and (1 shl (index + 1)) != 0 })
    fun decode(value: JSONArray): Int {
        require(value.length() <= 32) { "Too many notification filters" }
        var mask = 0
        for (index in 0 until value.length()) {
            val bit = names.indexOf(value.getString(index))
            if (bit >= 0) mask = mask or (1 shl (bit + 1))
        }
        return mask
    }
}

/** NIP-78, NIP-44 encrypted to self. Only explicit settings changes publish. */
internal class AccountSettings(
    context: Context,
    scope: CoroutineScope,
    private val repo: FeatherRepository,
    private val signer: () -> NostrSignerExternal?,
    private val requests: SignerRequests,
    private val readFilters: suspend (String) -> Int,
    private val applyFilters: suspend (String, Int) -> Unit,
    private val report: (String) -> Unit,
) {
    private val prefs = context.getSharedPreferences("account-settings", Context.MODE_PRIVATE)
    private val lock = Mutex()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var foreground = false
    @Volatile private var attempted: String? = null
    @Volatile private var attemptedPending: String? = null

    init {
        scope.launch(Dispatchers.IO) {
            for (ignored in wake) {
                if (!foreground) continue
                // Logout may cancel the current signer operation; keep the queue alive for the next account.
                supervisorScope {
                    launch {
                        try { sync() }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { report("Settings sync: ${e.message ?: e.javaClass.simpleName}. Local preferences kept; reopen settings to retry.") }
                    }.join()
                }
            }
        }
    }
    fun refresh(active: Boolean, retry: Boolean = false) {
        foreground = active
        if (retry) { attempted = null; attemptedPending = null }
        if (active) wake.trySend(Unit)
    }
    private fun read(owner: String) = JSONObject(prefs.getString(owner, "{}") ?: "{}")
    private fun write(owner: String, value: JSONObject) {
        val edit = prefs.edit().putString(owner, value.toString())
        prefs.all.keys.filter { it != owner }.drop(31).forEach(edit::remove)
        check(edit.commit()) { "Could not save preferences" }
    }
    suspend fun change(sensitive: Boolean? = null, pow: Boolean? = null, filters: Int? = null, expectedOwner: String? = null) = withContext(Dispatchers.IO) {
        val identity = signer() ?: return@withContext
        if (expectedOwner != null && identity.pubKey != expectedOwner) return@withContext
        lock.withLock {
            val state = repo.state.value
            if (state.publicKey != identity.pubKey) return@withLock
            val local = read(identity.pubKey)
            val revision = maxOf(System.currentTimeMillis() / 1000, local.optLong("time") + 1,
                (state.settingsEvent?.createdAt ?: 0) + 1)
            val nextSensitive = sensitive ?: state.showSensitive
            val nextPow = pow ?: state.proofOfWork
            val nextFilters = (filters ?: readFilters(identity.pubKey)) and NotificationFilterSettings.MASK
            local.put("notificationFilters", nextFilters)
            local.put("sensitive", nextSensitive).put("pow", nextPow).put("time", revision)
                .put("dirty", true).remove("event")
            write(identity.pubKey, local)
            repo.applyAccountSettings(identity.pubKey, nextSensitive, nextPow)
            applyFilters(identity.pubKey, nextFilters)
        }
        wake.trySend(Unit)
    }
    private suspend fun sync() {
        val identity = signer() ?: return
        val owner = identity.pubKey
        fun current() = signer() === identity && repo.state.value.publicKey == owner
        if (!current()) return
        val local = lock.withLock {
            read(owner).also { local ->
                if (local.has("sensitive")) repo.applyAccountSettings(owner, local.getBoolean("sensitive"), local.getBoolean("pow"))
                if (local.has("notificationFilters")) applyFilters(owner, local.getInt("notificationFilters") and NotificationFilterSettings.MASK)
            }
        }
        val remote = repo.state.value.settingsEvent?.takeIf { it.pubKey == owner }
        if (local.optBoolean("dirty")) {
            val revision = local.getLong("time")
            val attempt = "$owner:$revision"
            if (attemptedPending == attempt) return
            attemptedPending = attempt
            val plain = JSONObject().put("version", 1).put("showSensitive", local.getBoolean("sensitive"))
                .put("proofOfWork", local.getBoolean("pow"))
                .put("notificationFilters", NotificationFilterSettings.encode(local.optInt("notificationFilters", readFilters(owner)))).toString()
            val signed = local.optString("event").takeIf { it.isNotBlank() }?.let { Event.fromJson(it) } ?: requests.run {
                check(current()) { "Account changed" }
                val encrypted = withTimeout(180_000) { identity.nip44Encrypt(plain, owner) }
                check(current()) { "Account changed" }
                val tags = arrayOf(arrayOf("d", SETTINGS_D))
                withTimeout(180_000) { identity.sign<Event>(revision, 30078, tags, encrypted) }.also {
                    require(it.pubKey == owner && it.kind == 30078 && it.createdAt == revision &&
                        it.tags.contentDeepEquals(tags) && it.content == encrypted && it.verify()) { "Invalid signed settings" }
                }
            }
            if (!current()) return
            lock.withLock {
                val latest = read(owner)
                if (latest.optLong("time") == revision) { latest.put("event", signed.toJson()); write(owner, latest) }
            }
            // Do not publish an obsolete edit if another change arrived during signer interaction.
            if (lock.withLock { read(owner).optLong("time") } != revision) { wake.trySend(Unit); return }
            check(repo.publishSettings(signed)) { "No relay confirmed the settings" }
            lock.withLock {
                val latest = read(owner)
                if (latest.optLong("time") == revision) {
                    latest.put("dirty", false).put("id", signed.id).remove("event")
                    write(owner, latest)
                } else wake.trySend(Unit)
            }
            // Reconcile a newer remote edit that may have arrived while this one was offline.
            wake.trySend(Unit)
            return
        }
        if (remote == null || remote.id == local.optString("id") || remote.createdAt < local.optLong("time") ||
            (remote.createdAt == local.optLong("time") && local.optString("id").isNotEmpty() && remote.id >= local.getString("id")) ||
            attempted == remote.id) return
        attempted = remote.id
        val plain = requests.run {
            check(current()) { "Account changed" }
            withTimeout(180_000) { identity.nip44Decrypt(remote.content, owner) }
        }
        require(plain.length <= 4096) { "Settings payload too large" }
        val decoded = JSONObject(plain)
        require(decoded.getInt("version") == 1) { "Unsupported settings version" }
        val sensitive = decoded.getBoolean("showSensitive")
        val pow = decoded.getBoolean("proofOfWork")
        val filters = if (decoded.has("notificationFilters")) NotificationFilterSettings.decode(decoded.getJSONArray("notificationFilters")) else null
        lock.withLock {
            if (!current() || read(owner).optBoolean("dirty")) return@withLock
            // Older settings events omit this optional field: retain the device selection.
            val nextFilters = filters ?: readFilters(owner)
            write(owner, JSONObject().put("notificationFilters", nextFilters).put("sensitive", sensitive).put("pow", pow)
                .put("time", remote.createdAt).put("id", remote.id))
            repo.applyAccountSettings(owner, sensitive, pow)
            applyFilters(owner, nextFilters)
        }
    }
}
