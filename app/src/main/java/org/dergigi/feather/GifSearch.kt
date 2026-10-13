package org.dergigi.feather

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.TimeUnit

internal data class GifItem(val url: String, val width: Int, val height: Int, val format: String,
    val title: String, val bytes: Long?, val mp4: String?, val animated: String?, val still: String?) {
    fun imeta() = buildList {
        add("imeta"); add("url " + url); add("m image/" + format); add("dim " + width + "x" + height)
        bytes?.let { add("size " + it) }
        if (title.isNotBlank()) add("alt " + title)
    }.toTypedArray()
    fun json() = JSONObject().put("url", url).put("width", width).put("height", height).put("format", format)
        .put("title", title).put("bytes", bytes).put("mp4", mp4).put("animated", animated).put("still", still)
    companion object {
        private fun media(raw: String?): String? = raw?.let { value ->
            runCatching { value.toHttpUrl() }.getOrNull()?.takeIf {
                it.isHttps && it.host == "gifs.nostr.build" && it.username.isEmpty() && it.password.isEmpty()
            }?.toString()
        }
        fun parse(json: JSONObject): GifItem? {
            val url = media(json.optString("url")) ?: return null
            val width = json.optInt("width"); val height = json.optInt("height")
            val format = json.optString("format")
            if (width !in 1..32768 || height !in 1..32768 || format !in listOf("gif", "webp")) return null
            val preview = json.optJSONObject("previews")?.optJSONObject("w240")
                ?: json.optJSONObject("previews")?.optJSONObject("small")
            return GifItem(url, width, height, format, json.optString("title").filterNot(Char::isISOControl).take(300),
                json.optLong("bytes", -1).takeIf { it >= 0 }, media(json.optString("mp4")),
                media(preview?.optString("animated") ?: json.optString("animated")),
                media(preview?.optString("still") ?: json.optString("still")))
        }
        fun list(raw: String): List<GifItem> = runCatching {
            val array = JSONArray(raw)
            (0 until minOf(array.length(), 32)).mapNotNull { array.optJSONObject(it)?.let(::parse) }
        }.getOrDefault(emptyList())
    }
}

internal class GifSearchFailure(message: String, val retryAt: Long = 0, val retryable: Boolean = false) : Exception(message)

/** Shared requests survive picker dismissal; queries, concurrency and memory are bounded. */
internal object GifSearch {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val permits = Semaphore(2)
    private val http = OkHttpClient.Builder().callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()
    private data class Entry(val result: Deferred<JSONObject>, val expires: Long)
    private val requests = linkedMapOf<String, Entry>()
    @Volatile private var blockedUntil = 0L
    @Volatile private var denied = false
    fun normalize(raw: String): String {
        val value = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase(Locale.ROOT)
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}\\p{So}\\u200D\\uFE0F'\\s]"), " ")
            .replace(Regex("\\s+"), " ").trim()
        val points = value.codePoints().limit(100).toArray()
        return String(points, 0, points.size)
    }
    fun searchable(q: String): Boolean {
        val points = q.codePoints().toArray()
        if (points.none { Character.isLetterOrDigit(it) || Character.getType(it) == Character.OTHER_SYMBOL.toInt() }) return false
        return points.size > 1 || points.firstOrNull()?.let {
            !Character.isLetter(it) || Character.UnicodeScript.of(it) != Character.UnicodeScript.LATIN
        } == true
    }
    private suspend fun request(route: String, query: String, refresh: Boolean): JSONObject {
        val key = route + ":" + query
        val task = synchronized(requests) {
            val now = System.currentTimeMillis()
            val existing = requests[key]
            if (existing != null && (!existing.result.isCompleted || (!refresh && existing.expires > now))) existing.result
            else {
                if (denied || BuildConfig.GIFS_NOSTR_BUILD_API_KEY.isEmpty()) throw GifSearchFailure("GIF search is not registered.")
                if (blockedUntil > now) throw GifSearchFailure("Too many searches. Try again in a minute.", blockedUntil)
                requests.entries.removeAll { it.value.result.isCompleted && it.value.expires <= now }
                if (requests.size >= 24) {
                    requests.entries.firstOrNull { it.value.result.isCompleted }?.key?.let(requests::remove)
                    if (requests.size >= 24) throw GifSearchFailure("Search is busy. Try again shortly.", retryable = true)
                }
                scope.async {
                    permits.withPermit {
                        if (blockedUntil > System.currentTimeMillis()) throw GifSearchFailure("Too many searches. Try again in a minute.", blockedUntil)
                        val url = ("https://gifs.nostr.build/api/v1/" + route).toHttpUrl().newBuilder()
                            .addQueryParameter("q", query).addQueryParameter("limit", if (route == "search") "200" else "5").build()
                        http.newCall(Request.Builder().url(url).header("Authorization", "Bearer " + BuildConfig.GIFS_NOSTR_BUILD_API_KEY)
                            .header("Accept", "application/json").build()).execute().use { response ->
                            if (!response.isSuccessful) {
                                when (response.code) {
                                    400 -> throw GifSearchFailure("Try another search.")
                                    403 -> { denied = true; throw GifSearchFailure("GIF search is not registered.") }
                                    429 -> {
                                        blockedUntil = System.currentTimeMillis() + (response.header("Retry-After")?.toLongOrNull() ?: 60).coerceIn(1, 3600) * 1000
                                        throw GifSearchFailure("Too many searches. Try again in a minute.", blockedUntil)
                                    }
                                    else -> throw GifSearchFailure("Search is unavailable right now.", System.currentTimeMillis() + 5000, true)
                                }
                            }
                            val source = response.body.source()
                            source.request(2_097_153)
                            require(source.buffer.size <= 2_097_152) { "GIF response too large" }
                            JSONObject(source.readUtf8())
                        }
                    }
                }.also { requests[key] = Entry(it, now + 60_000) }
            }
        }
        return try { task.await() } catch (e: CancellationException) { throw e }
        catch (e: GifSearchFailure) { throw e }
        catch (_: Exception) { throw GifSearchFailure("Search is unavailable right now.", System.currentTimeMillis() + 5000, true) }
    }
    suspend fun search(query: String, refresh: Boolean = false): List<GifItem> {
        val json = request("search", normalize(query), refresh)
        return withContext(Dispatchers.Default) {
            val array = json.optJSONArray("items") ?: JSONArray()
            (0 until minOf(200, array.length())).mapNotNull { array.optJSONObject(it)?.let(GifItem::parse) }.distinctBy { it.url }
        }
    }
    suspend fun suggest(query: String): List<String> {
        val array = request("suggest", normalize(query), false).optJSONArray("terms") ?: return emptyList()
        val terms = (0 until minOf(20, array.length())).mapNotNull(array::optJSONObject)
        val exact = terms.filterNot { it.optBoolean("fuzzy") }
        return (exact.ifEmpty { terms }).map { it.optString("term").take(100) }
            .filter { it.isNotBlank() && normalize(it) != normalize(query) }.distinctBy(::normalize).take(5)
    }
}
