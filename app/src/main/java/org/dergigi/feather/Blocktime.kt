package org.dergigi.feather

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.SystemClock
import android.util.AtomicFile
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

/** Successful mappings never expire. Memory stays bounded; SQLite retains every resolved timestamp. */
internal class Blocktime(context: Context) {
    private data class Entry(val height: Int?, val retryAfter: Long = 0)
    private val entries = object : LinkedHashMap<Long, Entry>(128, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Entry>?) = size > 512
    }
    private val mutex = Mutex()
    private val providers = listOf("https://mempool.space", "https://mempool.emzy.de")
    // Shared across timestamps: an outage must not cause one failed call per visible note.
    private val providerRetryAfter = mutableMapOf<String, Long>()
    // SQLiteOpenHelper does not open or read the database until height() accesses it on IO.
    private val store = BlocktimeStore(context.applicationContext)
    private val http = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).followSslRedirects(false).build()
    fun stop() { http.dispatcher.cancelAll() }

    suspend fun height(timestamp: Long): Int? = withContext(Dispatchers.IO) {
        mutex.withLock {
            val now = System.currentTimeMillis() / 1_000
            val elapsed = SystemClock.elapsedRealtime()
            if (timestamp !in 1231006505L..now) return@withLock null
            entries[timestamp]?.takeIf { it.height != null || it.retryAfter > elapsed }?.let { return@withLock it.height }
            val cached = runCatching { store.find(timestamp) }.getOrNull()
            if (cached != null) {
                entries[timestamp] = Entry(cached)
                return@withLock cached
            }
            var height: Int? = null
            for (provider in providers) {
                if ((providerRetryAfter[provider] ?: 0) > SystemClock.elapsedRealtime()) continue
                try {
                    height = lookup(provider, timestamp)
                    providerRetryAfter.remove(provider)
                    break
                } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {
                    providerRetryAfter[provider] = SystemClock.elapsedRealtime() + 60_000
                }
            }
            // Optional persistence must not hide an otherwise usable result.
            height?.let { resolved -> runCatching { store.save(timestamp, resolved) } }
            entries[timestamp] = Entry(height, if (height == null) SystemClock.elapsedRealtime() + 60_000 else 0)
            height
        }
    }

    private suspend fun lookup(provider: String, timestamp: Long): Int {
        val request = Request.Builder().url("$provider/api/v1/mining/blocks/timestamp/$timestamp").build()
        return http.newCall(request).await().use { response ->
            if (!response.isSuccessful) throw IOException("Block lookup failed: HTTP ${response.code}")
            val source = response.body.source()
            source.request(4_097)
            val bytes = source.buffer.clone().readByteArray()
            require(bytes.size <= 4_096)
            val json = JSONObject(bytes.toString(Charsets.UTF_8))
            val blockTimestamp = when (val value = json.get("timestamp")) {
                is Number -> value.toLong()
                is String -> value.toLongOrNull() ?: Instant.parse(value).epochSecond
                else -> error("Invalid block timestamp")
            }
            require(blockTimestamp in 1231006505L..timestamp)
            json.getInt("height").also { require(it >= 0) }
        }
    }

}

/** App data, not Android's reclaimable cache directory. No expiry or pruning of successful lookups. */
private class BlocktimeStore(private val context: Context) : SQLiteOpenHelper(context, "blocktime.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE blocktime (timestamp INTEGER PRIMARY KEY, height INTEGER NOT NULL CHECK (height >= 0))")
        // Keep all valid legacy results, including ones that the old TTL would have expired.
        val rows = runCatching {
            AtomicFile(File(context.cacheDir, "blocktime.json")).openRead().use { input ->
                val bytes = ByteArray(65_537)
                var size = 0
                while (size < bytes.size) {
                    val read = input.read(bytes, size, bytes.size - size)
                    if (read < 0) break
                    size += read
                }
                require(size <= 65_536)
                JSONArray(String(bytes, 0, size, Charsets.UTF_8))
            }
        }.getOrNull() ?: return
        val now = System.currentTimeMillis() / 1000
        for (index in 0 until minOf(rows.length(), 512)) {
            val row = rows.optJSONObject(index) ?: continue
            val timestamp = row.optLong("time", -1)
            val height = row.optInt("height", -1)
            if (timestamp in 1231006505L..now && height >= 0) insert(db, timestamp, height)
        }
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun find(timestamp: Long): Int? = readableDatabase.rawQuery(
        "SELECT height FROM blocktime WHERE timestamp = ?", arrayOf(timestamp.toString()),
    ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else null }

    fun save(timestamp: Long, height: Int) = insert(writableDatabase, timestamp, height)

    private fun insert(db: SQLiteDatabase, timestamp: Long, height: Int) {
        val values = ContentValues(2).apply { put("timestamp", timestamp); put("height", height) }
        db.insertOrThrow("blocktime", null, values)
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
