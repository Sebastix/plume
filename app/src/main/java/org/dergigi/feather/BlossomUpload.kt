package org.dergigi.feather

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.nip55AndroidSigner.client.NostrSignerExternal
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

internal fun blossomServer(raw: String): String? = raw.toHttpUrlOrNull()?.takeIf {
    it.isHttps && it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
}?.toString()?.trimEnd('/')

/** One bounded image at a time; only explicitly selected account servers receive bytes. */
internal class BlossomUpload(private val context: Context) {
    private val http = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false).build()
    data class Result(val url: String, val failures: List<String>)

    suspend fun upload(uri: Uri, servers: List<String>, signer: NostrSignerExternal, requests: SignerRequests, onUploaded: suspend (String) -> Unit): Result = withContext(Dispatchers.IO) {
        require(servers.isNotEmpty()) { "No Blossom servers found on your profile (kind 10063)." }
        context.cacheDir.listFiles()?.filter { it.name.startsWith("blossom-") && it.name.endsWith(".image") }?.forEach { it.delete() }
        val file = File.createTempFile("blossom-", ".image", context.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            context.contentResolver.openInputStream(uri)?.use { input ->
                file.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        size += read
                        require(size <= 20L * 1024 * 1024) { "Image exceeds 20 MB" }
                        digest.update(buffer, 0, read); output.write(buffer, 0, read)
                    }
                }
            } ?: error("Could not read selected image")
            require(size > 0) { "Selected image is empty" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            require(bounds.outWidth > 0 && bounds.outHeight > 0 && bounds.outMimeType?.startsWith("image/") == true) { "Unsupported image format" }
            val mime = bounds.outMimeType!!
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val now = System.currentTimeMillis() / 1000 - 1
            val tags = (listOf(arrayOf("t", "upload"), arrayOf("x", hash), arrayOf("expiration", (now + 1800).toString())) +
                servers.map { arrayOf("server", it.toHttpUrlOrNull()!!.host) }.distinctBy { it[1] }).toTypedArray()
            val content = "Upload image to your Blossom servers"
            val auth = requests.run { withTimeout(180_000) { signer.sign<Event>(now, 24242, tags, content) } }
            require(auth.pubKey == signer.pubKey && auth.kind == 24242 && auth.createdAt == now &&
                auth.content == content && auth.tags.contentDeepEquals(tags) && auth.verify()) { "Invalid Blossom upload authorization" }
            // Deployed Blossom servers decode standard padded Base64 (without line breaks).
            val token = "Nostr " + Base64.encodeToString(auth.toJson().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            var first: String? = null
            val failures = mutableListOf<String>()
            for (server in servers) {
                currentCoroutineContext().ensureActive()
                val url = try {
                    val source = first
                    if (source == null) put(server, "upload", file.asRequestBody(mime.toMediaType()), token, hash, size)
                    else try {
                        put(server, "mirror", JSONObject().put("url", source).toString().toRequestBody("application/json".toMediaType()), token, hash, size)
                    } catch (e: CancellationException) { throw e }
                    catch (e: BlossomHttpFailure) {
                        if (e.code !in listOf(404, 405, 501)) throw e
                        put(server, "upload", file.asRequestBody(mime.toMediaType()), token, hash, size)
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    failures += "${server.toHttpUrlOrNull()!!.host}: ${walletErrorReason(e)}"
                    continue
                }
                if (first == null) { first = url; onUploaded(url) }
            }
            Result(first ?: error("Image upload failed: ${failures.joinToString("; ")}"), failures)
        } finally { file.delete() }
    }

    private suspend fun put(server: String, path: String, body: RequestBody, token: String, hash: String, size: Long): String {
        val request = Request.Builder().url("$server/$path").header("Authorization", token)
            .header("X-SHA-256", hash).put(body).build()
        val call = http.newCall(request)
        call.timeout().timeout(if (path == "mirror") 15 else 90, TimeUnit.SECONDS)
        return call.awaitUpload().use { response ->
            if (!response.isSuccessful) {
                val reason = response.header("X-Reason")?.takeIf { it.isNotBlank() }
                    ?: response.peekBody(4096).string().let { raw ->
                        val json = runCatching { JSONObject(raw) }.getOrNull()
                        json?.optString("message")?.takeIf { it.isNotBlank() }
                            ?: json?.optString("error")?.takeIf { it.isNotBlank() }
                            ?: raw.takeUnless { it.trimStart().startsWith("<") }
                    }
                val detail = reason?.let(::walletErrorText)?.takeIf { it.isNotBlank() }
                throw BlossomHttpFailure(response.code, "HTTP ${response.code}${detail?.let { ": $it" }.orEmpty()}")
            }
            val source = response.body.source()
            source.request(32769)
            require(source.buffer.size <= 32768) { "Upload response too large" }
            val descriptor = JSONObject(source.readUtf8())
            require(descriptor.getString("sha256") == hash && descriptor.getLong("size") == size) { "Upload descriptor does not match image" }
            val url = descriptor.getString("url").toHttpUrlOrNull()
            require(url != null && url.isHttps && url.username.isEmpty() && url.password.isEmpty() &&
                url.toString().length <= 2048 && url.pathSegments.last().startsWith(hash)) { "Invalid Blossom image URL" }
            url.toString()
        }
    }
}

private class BlossomHttpFailure(val code: Int, message: String) : IOException(message)

private suspend fun Call.awaitUpload(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (!continuation.isCancelled) continuation.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response, onCancellation = { _, value, _ -> value.close() })
        }
    })
}
