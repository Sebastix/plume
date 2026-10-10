package org.dergigi.feather

import com.vitorpamplona.quartz.nip01Core.core.Event
import java.net.URI

object MediaContent {
    enum class Kind { IMAGE, VIDEO }
    data class Attachment(val url: String, val kind: Kind, val ratio: Float = 16f / 9f, val preview: String? = null, val alt: String? = null)
    val cacheOptionsMb = listOf(128, 256, 512, 1024, 2048)
    fun imageBudget(mb: Int): Long = mb * 1024L * 1024 / 4
    fun videoBudget(mb: Int): Long = mb * 1024L * 1024 - imageBudget(mb)
    private val urls = Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE)
    fun secureUrl(value: String?): String? = value?.let { runCatching {
        val uri = URI(it)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() && uri.userInfo == null)
        it
    }.getOrNull() }

    fun attachments(event: Event, limit: Int = 4): List<Attachment> {
        val metadata = event.tags.filter { it.firstOrNull() == "imeta" }.take(32).mapNotNull { tag ->
            val fields = tag.drop(1).mapNotNull { entry ->
                val split = entry.indexOf(' ')
                if (split <= 0) null else entry.take(split) to entry.substring(split + 1)
            }.toMap()
            fields["url"]?.let { it to fields }
        }.toMap()
        return urls.findAll(event.content).map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }.distinct().mapNotNull { url ->
            if (secureUrl(url) == null) return@mapNotNull null
            val fields = metadata[url].orEmpty()
            val extension = runCatching { URI(url).path.substringAfterLast('.').lowercase() }.getOrDefault("")
            val kind = when {
                fields["m"]?.startsWith("image/") == true -> Kind.IMAGE
                fields["m"]?.startsWith("video/") == true -> Kind.VIDEO
                extension in setOf("jpg", "jpeg", "png", "webp", "gif", "avif") -> Kind.IMAGE
                extension in setOf("mp4", "m4v", "mov", "webm") -> Kind.VIDEO
                else -> return@mapNotNull null
            }
            val dimensions = fields["dim"]?.split('x')?.mapNotNull(String::toFloatOrNull)
            val ratio = dimensions?.takeIf { it.size == 2 && it.all { dim -> dim.isFinite() && dim > 0 } }?.let { it[0] / it[1] }?.coerceIn(.6f, 2f) ?: (16f / 9f)
            Attachment(url, kind, ratio, secureUrl(fields["image"] ?: fields["thumb"]), fields["alt"]?.take(500))
        }.take(limit.coerceIn(1, 32)).toList()
    }
    fun text(event: Event, attachments: List<Attachment>): String {
        val mediaUrls = attachments.map { it.url }.toSet()
        return urls.replace(event.content) { match ->
            val url = match.value.trimEnd('.', ',', ')', ']', '!', '?')
            if (url in mediaUrls) match.value.removePrefix(url) else match.value
        }.trim().replace(Regex("\n{3,}"), "\n\n")
    }

    /** Play only a mostly visible video, favoring the one closest to the viewport center. */
    fun visibleScore(top: Float, bottom: Float, height: Float, viewportTop: Float, viewportBottom: Float): Float? {
        if (height <= 0 || viewportBottom <= viewportTop) return null
        val visible = (minOf(bottom, viewportBottom) - maxOf(top, viewportTop)).coerceAtLeast(0f)
        if (visible / height < .6f) return null
        return kotlin.math.abs((top + bottom) / 2 - (viewportTop + viewportBottom) / 2)
    }
}
