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

private val sensitiveHashtags = setOf("nsfw", "porn", "pornography")

/** NIP-36 and explicit author-supplied labels; no content guessing or remote classifier. */
internal fun Event.isSensitive(): Boolean = tags.any { tag ->
    when (tag.firstOrNull()?.lowercase()) {
        "content-warning", "nsfw" -> true
        "t" -> tag.getOrNull(1)?.lowercase() in sensitiveHashtags
        "l" -> tag.getOrNull(2)?.lowercase() == "content-warning" || tag.getOrNull(1)?.lowercase() == "nsfw"
        else -> false
    }
}

internal data class ContentFilter(
    val authors: Set<String> = emptySet(),
    val embedded: Map<String, Event> = emptyMap(),
    val deletedReactions: Set<Pair<String, String>> = emptySet(),
    val showSensitive: Boolean = false,
    val deletedNotes: Set<String> = emptySet(),
) {
    fun hides(reference: NostrContent.Reference): Boolean =
        reference.id in deletedNotes || reference.author in authors || (reference.profile && reference.id in authors) || embedded[reference.id]?.let(::hides) == true

    fun hides(event: Event): Boolean {
        if (event.id in deletedNotes) return true
        if (!showSensitive && event.isSensitive()) return true
        if (event.kind in listOf(6, 7, 16) && (event.pubKey to event.id) in deletedReactions) return true
        if (event.pubKey in authors) return true
        if (event.kind !in listOf(6, 16, 7, 9735, 9321)) return false
        // Tags are already decoded. Avoid URI/NIP-19 parsing during the final render guard.
        val author = event.tags.lastOrNull { it.size >= 2 && it[0] == "p" }?.get(1)
        val id = event.tags.lastOrNull { it.size >= 2 && it[0] == "e" }?.get(1)
        return id in deletedNotes || author in authors || (event.kind == 9735 && event.tagValue("P") in authors) || embedded[id]?.let { it.id in deletedNotes || it.pubKey in authors || (!showSensitive && it.isSensitive()) } == true
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
