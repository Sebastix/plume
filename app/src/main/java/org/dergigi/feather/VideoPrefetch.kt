@file:androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)

package org.dergigi.feather

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only dimensions are retained. Network/cache reads and metadata extraction happen on IO. */
internal object VideoRatios {
    private val values = MutableStateFlow<Map<String, Float>>(emptyMap())
    val ratios = values.asStateFlow()
    @Synchronized fun put(url: String, ratio: Float) {
        if (!ratio.isFinite() || ratio <= 0f || values.value[url] == ratio) return
        values.value = (values.value - url + (url to ratio)).entries.toList().takeLast(256).associate { it.toPair() }
    }
}

/** Two bounded warm-up jobs share playback's disk cache, without creating extra players/decoders. */
internal class VideoPrefetcher(private val scope: CoroutineScope, private val resources: MediaStore.Resources) {
    private val running = mutableMapOf<String, Job>()
    private val finished = linkedMapOf<String, Long>()
    private var wanted = emptyList<String>()

    fun update(urls: List<String>, playing: String?) {
        wanted = urls.filter { it != playing }.distinct().take(3)
        // Release cache spans promptly when playback takes over, or a fast fling leaves a clip behind.
        running.filterKeys { it !in wanted }.values.toList().forEach { it.cancel() }
        pump()
    }

    private fun pump() {
        if (!scope.isActive) return
        val now = SystemClock.elapsedRealtime()
        wanted.filter { it !in running && (finished[it] ?: 0L) <= now }.take((2 - running.size).coerceAtLeast(0)).forEach { url ->
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val owner = currentCoroutineContext()
                    runInterruptible(Dispatchers.IO) { warm(url) { owner.ensureActive() } }
                    finished[url] = SystemClock.elapsedRealtime() + 300_000
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { finished[url] = SystemClock.elapsedRealtime() + 30_000 }
                finally {
                    while (finished.size > 128) finished.remove(finished.keys.first())
                    running.remove(url)
                    pump()
                }
            }
            running[url] = job
            job.start()
        }
    }

    private fun warm(url: String, checkActive: () -> Unit) {
        val source = resources.prefetchVideoSource.createDataSource()
        try {
            checkActive()
            source.open(DataSpec(Uri.parse(url)))
            val buffer = ByteArray(64 * 1024)
            var remaining = 4L * 1024 * 1024
            while (remaining > 0) {
                checkActive()
                val count = source.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (count == C.RESULT_END_OF_INPUT) break
                remaining -= count
            }
        } finally { source.close() }
        checkActive()
        // Parse only an already-cached prefix. Never let optional sizing trigger extra HTTP reads.
        if (VideoRatios.ratios.value[url] == null) runCatching {
            val span = resources.videos.getCachedSpans(url).firstOrNull { it.position == 0L } ?: return@runCatching
            val file = span.file ?: return@runCatching
            val metadata = MediaMetadataRetriever()
            try {
                metadata.setDataSource(file.absolutePath)
                val width = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toFloatOrNull() ?: return@runCatching
                val height = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toFloatOrNull() ?: return@runCatching
                val rotation = metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                if (width > 0 && height > 0) VideoRatios.put(url, if (rotation % 180 != 0) height / width else width / height)
            } finally { metadata.release() }
        }
    }
}
