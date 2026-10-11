package org.dergigi.feather

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONArray
import org.json.JSONObject

/** The account's NIP-51 p tags only; never infer blocks from someone else's lists. */
internal fun mutedPublicKeys(event: Event?): Set<String> = event?.tags.orEmpty().asSequence()
    .filter { it.size >= 2 && it[0] == "p" }
    .mapNotNull { Protocol.publicKey(it[1]) }.toSet()

internal data class ContentFilter(
    val authors: Set<String> = emptySet(),
    val embedded: Map<String, Event> = emptyMap(),
) {
    fun hides(reference: NostrContent.Reference): Boolean =
        reference.author in authors || (reference.profile && reference.id in authors) || embedded[reference.id]?.pubKey in authors

    fun hides(event: Event): Boolean {
        if (event.pubKey in authors) return true
        if (authors.isEmpty() || event.kind !in listOf(6, 7)) return false
        // Tags are already decoded. Avoid URI/NIP-19 parsing during the final render guard.
        val author = event.tags.lastOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
        val id = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        return author in authors || embedded[id]?.pubKey in authors
    }
}
internal val LocalContentFilter = staticCompositionLocalOf { ContentFilter() }

/** One decrypted list per account, keyed by signed event ID; private app storage only. IO callers. */
internal class MuteCache(context: Context) {
    private val prefs = context.getSharedPreferences("private-mutes", Context.MODE_PRIVATE)
    fun read(event: Event, exact: Boolean = true): Set<String>? = runCatching {
        val data = JSONObject(prefs.getString(event.pubKey, null) ?: return null)
        if (exact && data.getString("id") != event.id) return null
        keys(data.getJSONArray("tags"))
    }.getOrNull()

    fun write(event: Event, plaintext: String) {
        require(plaintext.length <= 262_144)
        val keys = keys(JSONArray(plaintext))
        val tags = JSONArray().apply { keys.forEach { put(JSONArray().put("p").put(it)) } }
        val edit = prefs.edit().putString(event.pubKey, JSONObject().put("id", event.id).put("tags", tags).toString())
        prefs.all.keys.filter { it != event.pubKey }.drop(7).forEach(edit::remove)
        check(edit.commit())
    }

    private fun keys(tags: JSONArray): Set<String> {
        require(tags.length() <= 5000)
        return buildSet {
            for (i in 0 until tags.length()) {
                val tag = tags.optJSONArray(i) ?: continue
                if (tag.optString(0) == "p") Protocol.publicKey(tag.optString(1))?.let(::add)
            }
        }
    }
}
