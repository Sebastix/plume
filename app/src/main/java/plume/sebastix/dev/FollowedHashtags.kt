package plume.sebastix.dev

import android.content.Context
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal fun normalizeFollowedTag(value: String): String? = value.removePrefix("#")
    .takeIf { it.isNotBlank() && it.length <= 128 && it.none(Char::isWhitespace) }
    ?.lowercase(Locale.ROOT)

internal fun followedTags(event: Event?): Set<String> = event?.tags.orEmpty().asSequence()
    .filter { it.size >= 2 && it[0] == "t" }.mapNotNull { normalizeFollowedTag(it[1]) }.take(256).toSet()

internal fun Event.matchesHashtags(tags: Set<String>): Boolean = kind == 1 && this.tags.any {
    it.size >= 2 && it[0] == "t" && normalizeFollowedTag(it[1]) in tags
}

/** Only the connected account's hashtag tags; no other private list fields are retained. IO callers. */
internal class HashtagCache(context: Context) {
    private val prefs = context.getSharedPreferences("private-hashtags", Context.MODE_PRIVATE)
    fun read(event: Event): Set<String>? = runCatching {
        val data = JSONObject(prefs.getString(event.pubKey, null) ?: return null)
        if (data.getString("id") != event.id) return null
        val tags = data.getJSONArray("tags")
        (0 until tags.length()).mapNotNull { normalizeFollowedTag(tags.getString(it)) }.take(256).toSet()
    }.getOrNull()

    fun write(event: Event, plaintext: String) {
        require(plaintext.length <= 262_144)
        val array = JSONArray(plaintext)
        require(array.length() <= 5000)
        val tags = (0 until array.length()).mapNotNull { index ->
            array.optJSONArray(index)?.takeIf { it.optString(0) == "t" }?.optString(1)?.let(::normalizeFollowedTag)
        }.distinct().take(256)
        val edit = prefs.edit().putString(event.pubKey, JSONObject().put("id", event.id).put("tags", JSONArray(tags)).toString())
        prefs.all.keys.filter { it != event.pubKey }.drop(7).forEach(edit::remove)
        check(edit.commit())
    }
}

/** Distinct posts per tag, ranked locally; never present these samples as network-wide counts. */
internal fun rankedHashtags(events: List<Event>, since: Long? = null): List<String> {
    val counts = mutableMapOf<String, Int>()
    val newest = mutableMapOf<String, Long>()
    events.distinctBy { it.id }.filter { it.kind == 1 && (since == null || it.createdAt >= since) }.forEach { event ->
        event.tags.asSequence().filter { it.size >= 2 && it[0] == "t" }
            .mapNotNull { normalizeFollowedTag(it[1]) }.distinct().take(64).forEach tag@{ tag ->
                if (tag !in counts && counts.size >= 8192) return@tag
                counts[tag] = (counts[tag] ?: 0) + 1
                newest[tag] = maxOf(newest[tag] ?: 0, event.createdAt)
            }
    }
    return counts.keys.sortedWith(compareByDescending<String> { counts[it] ?: 0 }
        .thenByDescending { newest[it] ?: 0 }.thenBy { it }).take(300)
}
