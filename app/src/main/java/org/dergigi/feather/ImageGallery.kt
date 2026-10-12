package org.dergigi.feather

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.CachePolicy
import coil3.request.maxBitmapSize
import coil3.size.Size
import kotlinx.coroutines.*
import kotlin.math.abs
import com.vitorpamplona.quartz.nip01Core.core.Event

internal val LocalGalleryVisible = staticCompositionLocalOf { false }
internal class GalleryOpener(private val open: (List<String>, Int, Event?, Boolean) -> Unit) {
    operator fun invoke(images: List<String>, start: Int, note: Event? = null, expandAlbum: Boolean = true) = open(images, start, note, expandAlbum)
}
internal val LocalOpenGallery = staticCompositionLocalOf { GalleryOpener { _, _, _, _ -> } }

@Composable
internal fun GalleryHost(state: FeedState, walletConnected: Boolean, covered: Boolean = false, album: () -> ProfilePhotos = { ProfilePhotos() }, onOpenNote: (String, Event?) -> Unit, onOpenProfile: (String) -> Unit, content: @Composable () -> Unit) {
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
    CompositionLocalProvider(LocalGalleryVisible provides urls.isNotEmpty(), LocalOpenGallery provides GalleryOpener { images, start, note, expandAlbum ->
        if (images.isNotEmpty() && (note == null || !filter.hides(note))) {
            val selected = start.coerceIn(images.indices)
            val photos = currentAlbum()
            val collection = photos.urls
            // Preserve exact indices (including duplicate URLs) for full-grid calls.
            // Single-note/inline callers in Photos must still open the whole album.
            val albumIndex = if (!expandAlbum) -1 else if (images == collection) selected else collection.indexOf(images[selected])
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
        if (urls.isNotEmpty() && !hiddenSource && !covered) ImageGallery(urls, index, noteIds, state, walletConnected,
            sourceNote = { page ->
                val id = noteIds.getOrNull(page)
                sourceNotes.getOrNull(page)?.takeIf { it.id == id }
                    ?: currentAlbum().sources.find { it.id == id }
                    ?: state.embedded[id]
                    ?: state.notes.find { it.id == id }
                    ?: state.profileEvents.find { it.id == id }
                    ?: state.threadNotes.find { it.id == id }
            },
            onOpenProfile = { pubkey ->
                urls = arrayListOf(); noteIds = arrayListOf(); sourceNotes = emptyList()
                onOpenProfile(pubkey)
            },
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
private fun ImageGallery(urls: List<String>, initialIndex: Int, noteIds: List<String>, state: FeedState, walletConnected: Boolean, sourceNote: (Int) -> Event?, onPage: (Int) -> Unit, onDismiss: () -> Unit, onOpenNote: (Int) -> Unit, onOpenProfile: (String) -> Unit) {
    val pager = rememberPagerState(initialPage = initialIndex.coerceIn(urls.indices), pageCount = { urls.size })
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var zoomed by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var controlsVisible by remember { mutableStateOf(false) }
    var interaction by remember { mutableIntStateOf(0) }
    val accessibility = LocalAccessibilityManager.current
    var busy by remember { mutableStateOf(false) }
    var reactionPicker by remember { mutableStateOf(false) }
    var zapPicker by remember { mutableStateOf(false) }
    val currentNote = sourceNote(pager.currentPage)
    val app = context.applicationContext as FeatherApplication
    val resources by app.media.resources.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    DisposableEffect(currentNote?.id) {
        app.repository.readingGallery(currentNote)
        onDispose { app.repository.readingGallery(null) }
    }
    val reactionActions = LocalReactionActions.current
    val zapActions = LocalZapActions.current
    val actionPending = currentNote?.id?.let { it in reactionActions.pending || it in zapActions.pending } == true
    var background by rememberSaveable { mutableIntStateOf(0) }
    var pendingSave by remember { mutableStateOf<List<String>?>(null) }
    val colors = listOf(Color.Black, Color.White, Color(0xFF666666))
    val foreground = if (background == 1) Color.Black else Color.White
    fun toast(message: String) { AppMessages.show(message) }
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
    LaunchedEffect(pager.isScrollInProgress) {
        if (pager.isScrollInProgress) controlsVisible = false
    }
    LaunchedEffect(controlsVisible, interaction, menu, busy, reactionPicker, zapPicker, actionPending) {
        if (controlsVisible && !menu && !busy && !reactionPicker && !zapPicker && !actionPending) {
            delay(accessibility?.calculateRecommendedTimeoutMillis(3_000L,
                containsIcons = true, containsText = true, containsControls = true) ?: 3_000L)
            controlsVisible = false
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        CopyableMessageHost()
        HidePhotoStatusBar((LocalView.current.parent as DialogWindowProvider).window, hideNavigationBar = true)
        CompositionLocalProvider(LocalMedia provides MediaEnvironment(resources, lifecycle.isAtLeast(Lifecycle.State.STARTED), null)) {
        Box(Modifier.fillMaxSize().background(colors[background]).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.changes.any { it.pressed && !it.previousPressed }) interaction++
                }
            }
        }) {
            HorizontalPager(state = pager, userScrollEnabled = !zoomed, modifier = Modifier.fillMaxSize()) { page ->
                ZoomableGalleryImage(urls[page], page == pager.currentPage,
                    onToggleControls = { controlsVisible = !controlsVisible },
                    onZoomed = { if (page == pager.currentPage) { zoomed = it; if (it) controlsVisible = false } })
            }
            AnimatedVisibility(controlsVisible, modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { -it },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it }) {
            Row(Modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 4.dp)
                .background(colors[background].copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(28.dp)), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Close gallery", tint = foreground) }
                currentNote?.let { note ->
                    val author = state.profiles[note.pubKey]
                    val name = author?.name ?: FeatherRepository.shortKey(note.pubKey)
                    IconButton(onClick = { onOpenProfile(note.pubKey) },
                        modifier = Modifier.semantics { contentDescription = "Open $name’s profile" }) {
                        Avatar(name, size = 36, picture = author?.picture)
                    }
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
            }
            AnimatedVisibility(controlsVisible && !noteIds.getOrNull(pager.currentPage).isNullOrBlank(),
                modifier = Modifier.align(Alignment.BottomEnd),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it }) {
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(end = 16.dp, bottom = if (urls.size > 1) 72.dp else 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    currentNote?.let { note -> key(note.id) {
                        val counts = state.engagement[note.id] ?: Engagement()
                        val canReact = state.publicKey != null
                        val canZap = canReact && walletConnected
                        Surface(modifier = Modifier.graphicsLayer { alpha = if (canZap) 1f else 0.38f },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 6.dp) {
                            NoteZap(note, counts, state.publicKey,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer, iconOnly = true, enabled = canZap,
                                onPickerVisibilityChanged = { zapPicker = it })
                        }
                        Surface(modifier = Modifier.graphicsLayer { alpha = if (canReact) 1f else 0.38f },
                            shape = androidx.compose.foundation.shape.CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer, shadowElevation = 6.dp) {
                            OrangeHeart(note, counts.reactions, state.sentReactions[note.id].orEmpty(),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer, iconOnly = true, enabled = canReact,
                                onPickerVisibilityChanged = { reactionPicker = it })
                        }
                    } }
                    FloatingActionButton(onClick = { onOpenNote(pager.currentPage) },
                        shape = androidx.compose.foundation.shape.CircleShape) {
                        Icon(painterResource(R.drawable.conversation), "Open image's note")
                    }
                }
            }
            AnimatedVisibility(controlsVisible && urls.size > 1, modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(tween(180)) + slideInVertically(tween(180)) { it },
                exit = fadeOut(tween(180)) + slideOutVertically(tween(180)) { it }) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(bottom = 8.dp)
                .background(colors[background].copy(alpha = 0.55f), androidx.compose.foundation.shape.RoundedCornerShape(28.dp)),
                horizontalAlignment = Alignment.CenterHorizontally) {
                if (urls.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = pager.currentPage > 0, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage - 1) } }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowLeft, "Previous image", tint = foreground.copy(alpha = if (pager.currentPage > 0) 1f else 0.3f)) }
                Text("${pager.currentPage + 1} / ${urls.size}", color = foreground, modifier = Modifier.padding(horizontal = 16.dp))
                IconButton(enabled = pager.currentPage < urls.lastIndex, onClick = { scope.launch { pager.animateScrollToPage(pager.currentPage + 1) } }) { Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, "Next image", tint = foreground.copy(alpha = if (pager.currentPage < urls.lastIndex) 1f else 0.3f)) }
                }
            }
            }
        }
        }
    }
}

@Composable
private fun ZoomableGalleryImage(url: String, isCurrent: Boolean, onToggleControls: () -> Unit, onZoomed: (Boolean) -> Unit) {
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
    val gestureScope = rememberCoroutineScope()
    var singleTap by remember(url) { mutableStateOf<Job?>(null) }
    val currentToggleControls by rememberUpdatedState(onToggleControls)
    DisposableEffect(url, isCurrent) { onDispose { singleTap?.cancel() } }
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
            AsyncImage(model = request, imageLoader = resources!!.images, contentDescription = "Gallery image; tap for controls, pinch or double-tap to zoom", contentScale = ContentScale.Fit,
                onSuccess = { loaded = true; decoded = it.result.image }, onError = { failed = true },
                modifier = Modifier.fillMaxSize().semantics {
                    customActions = listOf(CustomAccessibilityAction("Toggle gallery controls") { currentToggleControls(); true }, CustomAccessibilityAction("Zoom in") { applyScale(2.5f); true }, CustomAccessibilityAction("Reset zoom") { applyScale(1f); true })
                }.pointerInput(url) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = down.uptimeMillis
                        // Cancel the pending single tap as soon as the second touch arrives.
                        if (lastTap > 0 && start - lastTap < 300) singleTap?.cancel()
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
                                singleTap?.cancel()
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
                            else {
                                lastTap = start
                                singleTap?.cancel()
                                singleTap = gestureScope.launch { delay(300); currentToggleControls() }
                            }
                        }
                    }
                }.graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y })
            if (!loaded) CircularProgressIndicator(Modifier.size(28.dp))
        }
    }
}
