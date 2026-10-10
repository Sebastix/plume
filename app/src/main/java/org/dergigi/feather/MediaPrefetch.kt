package org.dergigi.feather

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import coil3.size.Dimension
import coil3.size.Size
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest

private data class PrefetchImage(val url: String, val avatar: Boolean)
private data class PrefetchInput(val notes: List<Event>, val profiles: Map<String, Profile>, val visible: List<String>)
private data class PrefetchPlan(val upcoming: List<PrefetchImage>, val visible: Set<PrefetchImage>)

/** Parsing and URL planning run off the UI thread. Only two speculative requests run at once. */
@OptIn(ExperimentalCoroutinesApi::class)
@Composable
fun PrefetchMedia(notes: List<Event>, profiles: Map<String, Profile>, state: LazyListState, widthPx: Int) {
    val media = LocalMedia.current
    val context = LocalContext.current.applicationContext
    val currentNotes by rememberUpdatedState(notes)
    val currentProfiles by rememberUpdatedState(profiles)
    LaunchedEffect(state, media.resources, media.active, widthPx) {
        val resources = media.resources ?: return@LaunchedEffect
        if (!media.active) return@LaunchedEffect
        val prefetch = MediaPrefetcher(this, resources, context, widthPx)
        snapshotFlow { PrefetchInput(currentNotes, currentProfiles, state.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String }) }
            .mapLatest { input -> withContext(Dispatchers.Default) { plan(input) } }
            .distinctUntilChanged()
            .collect { prefetch.update(it) }
    }
}

private fun plan(input: PrefetchInput): PrefetchPlan {
    val visibleIds = input.visible.toSet()
    val first = input.notes.indexOfFirst { it.id in visibleIds }
    val last = input.notes.indexOfLast { it.id in visibleIds }
    fun images(note: Event): List<PrefetchImage> =
        listOfNotNull(input.profiles[note.pubKey]?.picture?.let { PrefetchImage(it, true) }) +
            MediaContent.attachments(note).mapNotNull {
                val url = if (it.kind == MediaContent.Kind.IMAGE) it.url else it.preview
                url?.let { value -> PrefetchImage(value, false) }
            } + YouTubeLinks.parse(note.content).map { PrefetchImage(it.thumbnail, false) }
    val upcoming = input.notes.drop(last + 1).take(6) + input.notes.take(first.coerceAtLeast(0)).takeLast(2).asReversed()
    return PrefetchPlan(upcoming.flatMap(::images).filter { MediaContent.secureUrl(it.url) != null }.distinct().take(40),
        input.notes.filter { it.id in visibleIds }.flatMap(::images).toSet())
}

/** All queue bookkeeping stays on the effect's main dispatcher; fetching/decoding runs on IO. */
private class MediaPrefetcher(
    private val scope: CoroutineScope,
    private val resources: MediaStore.Resources,
    private val context: Context,
    private val widthPx: Int,
) {
    private val running = mutableMapOf<PrefetchImage, Job>()
    private val finished = linkedMapOf<PrefetchImage, Long>()
    private var wanted: List<PrefetchImage> = emptyList()

    fun update(plan: PrefetchPlan) {
        wanted = plan.upcoming
        val useful = (plan.upcoming + plan.visible).toSet()
        // Keep an in-flight fetch when its note enters the viewport or stays ahead.
        running.filterKeys { it !in useful }.values.toList().forEach { it.cancel() }
        pump()
    }

    private fun pump() {
        if (!scope.isActive) return
        val now = SystemClock.elapsedRealtime()
        wanted.filter { it !in running && (finished[it] ?: 0) <= now }.take((2 - running.size).coerceAtLeast(0)).forEach { image ->
            val job = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result = withContext(Dispatchers.IO) {
                        val week = System.currentTimeMillis() / (7 * 24 * 60 * 60 * 1000L)
                        resources.prefetchImages.execute(ImageRequest.Builder(context).data(image.url)
                            .diskCacheKey(if (image.avatar) "${image.url}:avatar:$week" else image.url)
                            .size(if (image.avatar) Size(256, 256) else Size(Dimension(widthPx.coerceAtLeast(1)), Dimension.Undefined)).build())
                    }
                    if (!image.avatar && result is SuccessResult) ImageRatios.put(image.url, result.image.width, result.image.height)
                    finished.remove(image)
                    finished[image] = SystemClock.elapsedRealtime() + if (result is SuccessResult) 300_000 else 30_000
                    while (finished.size > 128) finished.remove(finished.keys.first())
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    finished[image] = SystemClock.elapsedRealtime() + 30_000
                    while (finished.size > 128) finished.remove(finished.keys.first())
                } finally {
                    running.remove(image)
                    pump()
                }
            }
            running[image] = job
            job.start()
        }
    }
}
