package org.dergigi.feather

import android.content.Context
import android.util.AtomicFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** Resolve only explicitly tapped timestamps. One lookup at a time; all disk/JSON work stays on IO. */
internal class Blocktime(private val context: Context, scope: CoroutineScope) {
    private data class Entry(val height: Int?, val expires: Long)
    private val entries = object : LinkedHashMap<Long, Entry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) = size > 512
    }
    private val mutex = Mutex()
    private var loaded = false
    private val writes = Channel<String>(Channel.CONFLATED)
    init {
        scope.launch(Dispatchers.IO) {
            for (snapshot in writes) runCatching {
                val file = AtomicFile(File(context.cacheDir, "blocktime.json"))
                val output = file.startWrite()
                try { output.write(snapshot.toByteArray()); file.finishWrite(output) }
                catch (e: Exception) { file.failWrite(output); throw e }
            }
        }
    }
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followSslRedirects(false).build()
    fun stop() { http.dispatcher.cancelAll() }

    suspend fun height(timestamp: Long): Int? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis() / 1_000
            if (timestamp !in 1231006505L..now) return@withLock null
            val file = AtomicFile(File(context.cacheDir, "blocktime.json"))
            if (!loaded) {
                loaded = true
                runCatching {
                    file.openRead().use { input ->
                        val bytes = ByteArray(65_537)
                        var size = 0
                        while (size < bytes.size) {
                            val read = input.read(bytes, size, bytes.size - size)
                            if (read < 0) break
                            size += read
                        }
                        require(size <= 65_536)
                        val json = JSONArray(String(bytes, 0, size, Charsets.UTF_8))
                        for (index in 0 until minOf(json.length(), 512)) {
                            val row = json.getJSONObject(index)
                            val height = row.getInt("height")
                            val expires = row.getLong("expires")
                            if (height >= 0 && expires > now) entries[row.getLong("time")] = Entry(height, expires)
                        }
                    }
                }
            }
            entries[timestamp]?.takeIf { it.expires > now }?.let { return@withLock it.height }
            val height = try {
                val request = Request.Builder().url("https://mempool.space/api/v1/mining/blocks/timestamp/$timestamp").build()
                http.newCall(request).await().use { response ->
                    if (!response.isSuccessful) throw IOException("Block lookup failed")
                    val source = response.body.source()
                    source.request(4_097)
                    val bytes = source.buffer.clone().readByteArray()
                    require(bytes.size <= 4_096)
                    val json = JSONObject(bytes.toString(Charsets.UTF_8))
                    require(Instant.parse(json.getString("timestamp")).epochSecond <= timestamp)
                    json.getInt("height").also { require(it >= 0) }
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { null }
            val ttl = if (height == null) 60L else if (now - timestamp < 86_400) 300L else 30L * 86_400
            entries[timestamp] = Entry(height, now + ttl)
            if (height != null) runCatching {
                val json = JSONArray()
                entries.forEach { (time, entry) ->
                    if (entry.height != null && entry.expires > now) json.put(JSONObject().put("time", time).put("height", entry.height).put("expires", entry.expires))
                }
                writes.trySend(json.toString())
            }
            height
        }
    }
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (!continuation.isCancelled) continuation.resumeWithException(e)
        }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
