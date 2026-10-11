package org.dergigi.feather

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.painterResource
import android.net.Uri
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.maxBitmapSize
import coil3.size.Size
import kotlinx.coroutines.*
import kotlin.math.abs
import com.vitorpamplona.quartz.nip01Core.core.Event

internal val LocalGalleryVisible = staticCompositionLocalOf { false }
internal class GalleryOpener(private val open: (List<String>, Int, Event?) -> Unit) {
    operator fun invoke(images: List<String>, start: Int, note: Event? = null) = open(images, start, note)
}
internal val LocalOpenGallery = staticCompositionLocalOf { GalleryOpener { _, _, _ -> } }

@Composable
internal fun GalleryHost(album: () -> ProfilePhotos = { ProfilePhotos() }, onOpenNote: (String, Event?) -> Unit, content: @Composable () -> Unit) {
    var urls by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var index by rememberSaveable { mutableIntStateOf(0) }
    var noteIds by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var sourceNotes by remember { mutableStateOf<List<Event?>>(emptyList()) }
    val filter = LocalContentFilter.current
    val hiddenSource = sourceNotes.any { it != null && filter.hides(it) } || noteIds.any { id -> filter.embedded[id]?.let(filter::hides) == true }
    LaunchedEffect(hiddenSource) {
        if (hiddenSource) { urls = arrayListOf(); noteIds = arrayListOf(); sourceNotes = emptyList() }
    }
    val currentAlbum by rememberUpdatedState(album)
    CompositionLocalProvider(LocalGalleryVisible provides urls.isNotEmpty(), LocalOpenGallery provides GalleryOpener { images, start, note ->
        if (images.isNotEmpty() && (note == null || !filter.hides(note))) {
            val selected = start.coerceIn(images.indices)
            val photos = currentAlbum()
            val collection = photos.urls
            // Preserve exact indices (including duplicate URLs) for full-grid calls.
            // Single-note/inline callers in Photos must still open the whole album.
            val albumIndex = if (images == collection) selected else collection.indexOf(images[selected])
            if (albumIndex >= 0) {
                urls = ArrayList(collection); index = albumIndex
                sourceNotes = photos.sources
                noteIds = ArrayList(photos.sources.map { it.id })
            } else {
                urls = ArrayList(images); index = selected
                sourceNotes = List(images.size) { note }
                noteIds = ArrayList(List(images.size) { note?.id.orEmpty() })
            }
        }
    }) {
        content()
        if (urls.isNotEmpty() && !hiddenSource) ImageGallery(urls, index, noteIds,
            onPage = { index = it },
            onDismiss = { urls = arrayListOf(); noteIds = arrayListOf(); sourceNotes = emptyList() },
            onOpenNote = { page ->
                val id = noteIds.getOrNull(page)?.takeIf { it.isNotBlank() }
                val note = sourceNotes.getOrNull(page)?.takeIf { it.id == id }
                if (id != null) {
                    urls = arrayListOf(); noteIds = arrayListOf(); sourceNotes = emptyList()
                    onOpenNote(id, note)
                }
            })
    }
}

// Swipe, double-tap/pinch zoom, background cycling and actions follow Boris.
@Composable
private fun ImageGallery(urls: List<String>, initialIndex: Int, noteIds: List<String>, onPage: (Int) -> Unit, onDismiss: () -> Unit, onOpenNote: (Int) -> Unit) {
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(urls.indices), pageCount = { urls.size })
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var background by rememberSaveable { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<List<String>?>(null) }
    val colors = listOf(Color.Black, Color.White, Color(0xFF666666))
    val foreground = if (background == 1) Color.Black else Color.White
    fun toast(message: String) { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
    fun save(images: List<String>) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val count = withContext(Dispatchers.IO) {
                    var saved = 0
                    for (url in images) { ensureActive(); try { ImageStore.save(context, url); saved++ } catch (e: CancellationException) { throw e } catch (_: Exception) { } }
                    saved
                }
                toast(if (count == 0) "Couldn't save images. Check your connection and try again." else "Saved $count of ${images.size} images to Pictures${if (Build.VERSION.SDK_INT >= 29) "/Feather" else ""}.")
            } finally { busy = false }
        }
    }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val images = pendingSave; pendingSave = null
        if (granted && images != null) save(images) else toast("Storage permission is needed to save images on this Android version.")
    }
    fun download(images: List<String>) {
        if (Build.VERSION.SDK_INT >= 29 || ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED) save(images)
        else { pendingSave = images; permission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    }
    fun share(url: String) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                val intent = withContext(Dispatchers.IO) { ImageStore.share(context, url) }
                context.startActivity(Intent.createChooser(intent, "Share image"))
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { toast("Couldn't share this image. Please try again.") }
            finally { busy = false }
        }
    }
    LaunchedEffect(pager.currentPage) { zoomed = false; onPage(pager.currentPage) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Box(Modifier.fillMaxSize().background(colors[background])) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(vertical = 56.dp)) { page ->
                ZoomableGalleryImage(urls[page], page == pager.currentPage) { if (page == pager.currentPage) zoomed = it }
            }
            Row(Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close gallery", tint = foreground) }
                if (!noteIds.getOrNull(pager.currentPage).isNullOrBlank()) IconButton(onClick = { onOpenNote(pager.currentPage) }) {
                    Icon(painterResource(R.drawable.conversation), "Open image's note", tint = foreground)
                }
                Spacer(Modifier.weight(1f))
                if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = foreground, strokeWidth = 2.dp)
                IconButton(onClick = { background = (background + 1) % colors.size }) { Icon(painterResource(R.drawable.contrast), "Change gallery background", tint = foreground) }
                IconButton(onClick = { download(listOf(urls[pager.currentPage])) }, enabled = !busy) { Icon(painterResource(R.drawable.download), "Save image to Pictures", tint = foreground) }
                IconButton(onClick = { share(urls[pager.currentPage]) }, enabled = !busy) { Icon(Icons.Outlined.Share, "Share image", tint = foreground) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, "More image actions", tint = foreground) }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        Row {
                            IconButton(enabled = !busy, onClick = { menu = false; download(urls.toList()) }) {
                                Icon(painterResource(R.drawable.download_all), "Save all images")
                            }
                            IconButton(onClick = { menu = false; clipboard.setText(AnnotatedString(urls[pager.currentPage])) }) {
                                Icon(painterResource(R.drawable.copy), "Copy image URL")
                            }
                            IconButton(onClick = { menu = false; runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(urls[pager.currentPage]))) } }) {
                                Icon(painterResource(R.drawable.open_external), "Open image in browser")
                            }
                        }
                    }
                }
            }
            if (urls.size > 1) Row(Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous image", tint = foreground.copy(alpha = if (pager.currentPage > 0) 1f else 0.3f)) }
                Text("${pager.currentPage + 1} / ${urls.size}", color = foreground, modifier = Modifier.padding(horizontal = 16.dp))
                IconButton(enabled = pager.currentPage < urls.lastIndex, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next image", tint = foreground.copy(alpha = if (pager.currentPage < urls.lastIndex) 1f else 0.3f)) }
            }
        }
    }
}

@Composable
private fun ZoomableGalleryImage(url: String, isCurrent: Boolean, onZoomed: (Boolean) -> Unit) {
    val context = LocalContext.current
    val media = (context.applicationContext as FeatherApplication).media
    val resources by media.resources.collectAsState()
    val request = remember(context, url) {
        ImageRequest.Builder(context).data(url).size(2048, 2048).maxBitmapSize(Size(2048, 2048))
            .memoryCachePolicy(CachePolicy.DISABLED).build()
    }
    var decoded by remember(url) { mutableStateOf<coil3.Image?>(null) }
    ImageAnimation(decoded, isCurrent)
    var scale by remember(url) { mutableFloatStateOf(1f) }
    var offset by remember(url) { mutableStateOf(Offset.Zero) }
    var lastTap by remember(url) { mutableLongStateOf(0L) }
    var failed by remember(url) { mutableStateOf(false) }
    var loaded by remember(url) { mutableStateOf(false) }
    var retry by remember(url) { mutableIntStateOf(0) }
    val currentOnZoomed by rememberUpdatedState(onZoomed)
    fun applyScale(next: Float, pan: Offset = offset) {
        scale = next.coerceIn(1f, 5f)
        offset = if (scale < 1.02f) { scale = 1f; Offset.Zero } else pan
        currentOnZoomed(scale > 1f)
    }
    LaunchedEffect(isCurrent) { if (!isCurrent) { scale = 1f; offset = Offset.Zero } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (failed) {
            IconButton(onClick = { failed = false; loaded = false; retry++ }) { Icon(Icons.Outlined.Refresh, "Image failed to load. Retry.") }
        } else if (resources != null) key(url, retry) {
            AsyncImage(model = request, imageLoader = resources!!.images, contentDescription = "Gallery image; pinch or double-tap to zoom", contentScale = ContentScale.Fit,
                onSuccess = { loaded = true; decoded = it.result.image }, onError = { failed = true },
                modifier = Modifier.fillMaxSize().semantics {
                    customActions = listOf(CustomAccessibilityAction("Zoom in") { applyScale(2.5f); true }, CustomAccessibilityAction("Reset zoom") { applyScale(1f); true })
                }.pointerInput(url) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.uptimeMillis
                        var endedAt = start
                        var pinching = false
                        var dragged = false
                        do {
                            val event = awaitPointerEvent()
                            endedAt = event.changes.maxOf { it.uptimeMillis }
                            val pan = event.calculatePan()
                            if (event.changes.count { it.pressed } >= 2) pinching = true
                            if (abs(pan.x) > 1f || abs(pan.y) > 1f) dragged = true
                            if (pinching || scale > 1f) {
                                val next = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                                val maxX = size.width * (next - 1) / 2
                                val maxY = size.height * (next - 1) / 2
                                applyScale(next, Offset((offset.x + pan.x).coerceIn(-maxX, maxX), (offset.y + pan.y).coerceIn(-maxY, maxY)))
                                event.changes.forEach { if (it.positionChanged()) it.consume() }
                            }
                        } while (event.changes.any { it.pressed })
                        if (!pinching && !dragged && endedAt - start < 300) {
                            if (lastTap > 0 && start - lastTap < 300) {
                                if (scale > 1f) applyScale(1f)
                                else {
                                    val next = 2.5f
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    // Counter the center-based layer transform so the tapped
                                    // image point stays beneath the finger as it magnifies.
                                    applyScale(next, (down.position - center) * (1f - next))
                                }
                                lastTap = 0L
                            }
                            else lastTap = start
                        }
                    }
                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            if (!loaded) CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}
