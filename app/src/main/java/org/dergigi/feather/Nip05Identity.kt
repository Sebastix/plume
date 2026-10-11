package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

private enum class IdentityStatus { PENDING, VALID, INVALID, UNAVAILABLE }
private data class IdentityResult(val status: IdentityStatus, val detail: String)
private data class InternetIdentity(val name: String, val domain: String) {
    val root get() = name == "_"
    val label get() = if (root) domain else "$name@$domain"
    val endpoint get() = HttpUrl.Builder().scheme("https").host(domain)
        .addPathSegments(".well-known/nostr.json").addQueryParameter("name", name).build()
}

private fun internetIdentity(raw: String): InternetIdentity? = runCatching {
    require(raw.length <= 320)
    val value = raw.trim().lowercase(Locale.ROOT)
    val parts = if ('@' in value) value.split('@') else listOf("_", value)
    require(parts.size == 2 && parts[0].matches(Regex("[a-z0-9._-]+")))
    require('.' in parts[1] && parts[1].none { it in "/:@?#\\" || it.isWhitespace() })
    val domain = HttpUrl.Builder().scheme("https").host(parts[1]).build().host
    InternetIdentity(parts[0], domain)
}.getOrNull()

/** Visible profile identities only. Bounded, expiring results never alter relay routing. */
private object IdentityLookup {
    private data class Cached(val result: IdentityResult, val expires: Long)
    private val cache = object : LinkedHashMap<String, Cached>(256, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > 256
    }
    private val locks = Array(16) { Mutex() }
    private val requests = Semaphore(2)
    private fun publicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress || address.isSiteLocalAddress || address.isMulticastAddress) return false
        val bytes = address.address
        val first = bytes[0].toInt() and 255
        if (bytes.size == 16 && first and 254 == 252) return false
        if (bytes.size == 4 && (first == 0 || first >= 224 || (first == 100 && (bytes[1].toInt() and 255) in 64..127))) return false
        return true
    }
    private val http = OkHttpClient.Builder().dns { host ->
        Dns.SYSTEM.lookup(host).also { addresses ->
            if (addresses.isEmpty() || addresses.any { !publicAddress(it) }) throw UnknownHostException("Non-public identity host")
        }
    }.followRedirects(false).followSslRedirects(false).callTimeout(8, TimeUnit.SECONDS)
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(5, TimeUnit.SECONDS).build()

    suspend fun verify(identity: InternetIdentity, pubkey: String): IdentityResult = withContext(Dispatchers.IO) {
        val key = "${identity.name}@${identity.domain}:$pubkey"
        locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
            synchronized(cache) { cache[key]?.takeIf { it.expires > System.currentTimeMillis() } }?.let { return@withLock it.result }
            val result = requests.withPermit {
                try {
                    http.newCall(Request.Builder().url(identity.endpoint).build()).awaitIdentity().use { response ->
                        when {
                            response.code >= 500 || response.code == 429 -> IdentityResult(IdentityStatus.UNAVAILABLE, "Identity server returned HTTP ${response.code}.")
                            !response.isSuccessful -> IdentityResult(IdentityStatus.INVALID, "Identity endpoint returned HTTP ${response.code}; redirects are not allowed.")
                            else -> {
                                val source = response.body.source()
                                source.request(262_145)
                                require(source.buffer.size <= 262_144) { "Identity response exceeds 256 KiB." }
                                val names = JSONObject(source.readUtf8()).optJSONObject("names")
                                val mapped = names?.opt(identity.name) as? String
                                if (mapped == pubkey && mapped.matches(Regex("[0-9a-f]{64}")))
                                    IdentityResult(IdentityStatus.VALID, if (identity.root) "The root domain identity matches this profile's public key." else "The domain record matches this profile's public key.")
                                else IdentityResult(IdentityStatus.INVALID, "The domain record does not map this name to this profile's public key.")
                            }
                        }
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: IOException) { IdentityResult(IdentityStatus.UNAVAILABLE, "Could not reach the identity endpoint. Verification is unavailable.") }
                catch (_: Exception) { IdentityResult(IdentityStatus.INVALID, "The identity endpoint returned an invalid or oversized record.") }
            }
            val ttl = when (result.status) { IdentityStatus.VALID -> 21_600_000L; IdentityStatus.INVALID -> 900_000L; else -> 60_000L }
            synchronized(cache) { cache[key] = Cached(result, System.currentTimeMillis() + ttl) }
            result
        }
    }
}

private suspend fun Call.awaitIdentity(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}

@Composable
internal fun Nip05Identity(raw: String, pubkey: String) {
    val identity = remember(raw) { internetIdentity(raw) }
    val view = LocalView.current
    var visible by remember(raw, pubkey) { mutableStateOf(false) }
    var details by remember(raw, pubkey) { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val result by produceState(IdentityResult(IdentityStatus.PENDING, "Checking the domain record."), raw, pubkey, active) {
        value = IdentityResult(IdentityStatus.PENDING, "Checking the domain record.")
        if (identity == null) value = IdentityResult(IdentityStatus.INVALID, "Malformed NIP-05 address.")
        else if (active) value = IdentityLookup.verify(identity, pubkey)
    }
    val label = identity?.label ?: raw
    val tint = when (result.status) {
        IdentityStatus.VALID -> Color(0xFF36A866)
        IdentityStatus.INVALID -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(Modifier.fillMaxWidth().onGloballyPositioned {
        val bounds = it.boundsInWindow()
        visible = bounds.bottom > 0 && bounds.top < view.height && bounds.right > 0 && bounds.left < view.width
    }.clickable(onClickLabel = "NIP-05 verification details") { details = true }
        .semantics(mergeDescendants = true) { contentDescription = "$label. ${result.detail}" },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        when (result.status) {
            IdentityStatus.PENDING -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.dp)
            IdentityStatus.VALID -> if (identity?.root == true)
                Icon(painterResource(R.drawable.double_check), null, Modifier.size(18.dp), tint = tint)
            else Icon(Icons.Default.Check, null, Modifier.size(16.dp), tint = tint)
            IdentityStatus.INVALID -> Icon(Icons.Default.Warning, null, Modifier.size(16.dp), tint = tint)
            IdentityStatus.UNAVAILABLE -> Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = tint)
        }
    }
    if (details) StatusDetails("NIP-05", "$raw\n\n${result.detail}\n\n${identity?.endpoint ?: ""}\n\n$pubkey") { details = false }
}
