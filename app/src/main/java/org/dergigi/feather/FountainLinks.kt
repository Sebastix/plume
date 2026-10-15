package org.dergigi.feather

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.jsoup.nodes.Document

/** Episode shares are ordinary notes. Do not subscribe to the kind-54 format. */
internal object FountainLinks {
    private val links = Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE)
    private val episodeId = Regex("[A-Za-z0-9_-]{1,128}")
    fun canonical(raw: String): String? {
        val url = raw.toHttpUrlOrNull() ?: return null
        if (url.host !in setOf("fountain.fm", "www.fountain.fm", "play.fountain.fm") ||
            url.username.isNotEmpty() || url.password.isNotEmpty() || url.port !in setOf(80, 443)) return null
        val path = url.pathSegments.filter(String::isNotEmpty)
        if (path.size != 2 || path[0] !in setOf("episode", "show") || !episodeId.matches(path[1])) return null
        return "https://fountain.fm/${path[0]}/${path[1]}"
    }

    fun parse(content: String): List<String> = links.findAll(content).mapNotNull {
        canonical(it.value.trimEnd('.', ',', ')', ']', '!', '?'))
    }.distinct().take(2).toList()

    data class Episode(val audio: String, val title: String?, val show: String?, val image: String?, val duration: Long)

    /** Read public page data only; never execute scripts or pick paid product enclosures. */
    fun episode(document: Document, canonical: String): Episode? {
        if ("/episode/" !in canonical) return null
        val id = canonical.substringAfterLast('/')
        val chunks = StringBuilder()
        for (script in document.select("script")) {
            val source = script.data().trim()
            val prefix = "self.__next_f.push("
            if (!source.startsWith(prefix)) continue
            val args = runCatching { JSONTokener(source.substring(prefix.length)).nextValue() as? JSONArray }.getOrNull() ?: continue
            if (args.optInt(0) == 1 && args.opt(1) is String) chunks.append(args.getString(1))
        }
        // React's streamed page includes a JSON episode object; other records may be opaque text.
        val text = chunks.toString()
        for (match in Regex("\"episode\"\\s*:").findAll(text)) {
            val event = runCatching { JSONTokener(text.substring(match.range.last + 1)).nextValue() as? JSONObject }.getOrNull() ?: continue
            if (event.optString("_id") != id || event.optString("_type") != "EPISODE") continue
            val info = event.optJSONObject("info") ?: continue
            val audio = MediaContent.secureUrl(info.optString("audio")) ?: continue
            fun field(name: String, length: Int) = info.optString(name).takeIf { it.isNotBlank() && it != "null" }?.take(length)
            val seconds = info.optDouble("duration", 0.0).takeIf { it.isFinite() && it in 0.0..604800.0 } ?: 0.0
            return Episode(audio, field("title", 300), field("subtitle", 300) ?: field("publisher", 300),
                MediaContent.secureUrl(field("image", 4096)), (seconds * 1000).toLong())
        }
        return null
    }
}
