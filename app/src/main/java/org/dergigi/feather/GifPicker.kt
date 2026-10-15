package org.dergigi.feather

import android.animation.ValueAnimator
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Size
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun GifPicker(onDismiss: () -> Unit, onPick: (GifItem) -> Unit) {
    val context = LocalContext.current
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val grid = rememberLazyStaggeredGridState()
    var input by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var results by remember { mutableStateOf<List<GifItem>>(emptyList()) }
    var error by remember { mutableStateOf<GifSearchFailure?>(null) }
    var retryQuery by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    var retryReady by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsStateWithLifecycle()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val resources by (context.applicationContext as FeatherApplication).media.resources.collectAsStateWithLifecycle()
    val normalized = remember(input) { GifSearch.normalize(input) }
    val query = normalized.takeIf(GifSearch::searchable) ?: "gm"
    LaunchedEffect(input, retry) {
        loading = true; error = null
        delay(250)
        try {
            results = GifSearch.search(query, retryQuery == query)
            retryQuery = null
            grid.scrollToItem(0)
        } catch (e: CancellationException) { throw e }
        catch (e: GifSearchFailure) { error = e }
        finally { loading = false }
    }
    LaunchedEffect(error) {
        retryReady = false
        error?.let {
            delay((it.retryAt - System.currentTimeMillis()).coerceAtLeast(0))
            retryReady = it.retryable || it.retryAt > 0
        }
    }
    val visible by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.map { it.index }.toSet() } }
    val animated by remember { derivedStateOf { grid.layoutInfo.visibleItemsInfo.map { it.index }.take(6).toSet() } }
    val motion = ValueAnimator.areAnimatorsEnabled()
    ModalBottomSheet(onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().heightIn(max = (LocalConfiguration.current.screenHeightDp * .8f).dp)
            .imePadding().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = input, onValueChange = { input = it.take(500); loading = true; error = null },
                    modifier = Modifier.weight(1f).focusRequester(focus)
                        .onPreviewKeyEvent { event ->
                            if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                Key.Enter -> { keyboard?.hide(); true }
                                Key.Escape -> { onDismiss(); true }
                                else -> false
                            }
                        }, singleLine = true, shape = RoundedCornerShape(28.dp),
                    placeholder = { Text("Search GIFs") }, leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (input.isNotEmpty()) IconButton(onClick = {
                        input = ""; focus.requestFocus()
                    }) { Icon(Icons.Default.Close, "Clear GIF search") } })
                IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close GIF picker") }
            }
            Box(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(min = 180.dp)) {
                if (error != null) {
                    Column(Modifier.align(Alignment.Center).padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error?.message.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (retryReady) IconButton(onClick = { retryQuery = query; retry++; loading = true }) { Icon(Icons.Default.Refresh, "Retry GIF search") }
                    }
                } else if (!loading && results.isEmpty()) Text("No GIFs found", Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyVerticalStaggeredGrid(columns = StaggeredGridCells.Fixed(2), state = grid,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalItemSpacing = 8.dp,
                    modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = if (loading) .45f else 1f }
                        .semantics { liveRegion = LiveRegionMode.Polite; contentDescription = results.size.toString() + " GIFs" }) {
                    if (results.isEmpty()) items(6) {
                        Box(Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
                    }
                    items(results.size, key = { results[it].url }) { index ->
                        val gif = results[index]
                        GifTile(gif, resources, index in visible && foreground,
                            index in animated && motion && foreground && !loading,
                            Modifier.fillMaxWidth().aspectRatio((gif.width.toFloat() / gif.height).coerceIn(.75f, 2f))
                                .clip(RoundedCornerShape(12.dp)).clickable(enabled = !loading && !picked,
                                    onClickLabel = gif.title.ifBlank { "Add GIF" }) {
                                    if (!picked) { picked = true; onPick(gif) }
                                })
                    }
                }
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center).size(28.dp), strokeWidth = 2.dp)
            }
            Text("GIFs from nostr.build", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 8.dp)
                    .clickable(onClickLabel = "Open nostr.build") {
                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://nostr.build")))
                    }.padding(8.dp))
        }
    }
}

@Composable
private fun GifTile(gif: GifItem, resources: MediaStore.Resources?, visible: Boolean, animate: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    var failed by remember(gif.url) { mutableStateOf(false) }
    var decoded by remember(gif.url) { mutableStateOf<coil3.Image?>(null) }
    var drawn by remember(gif.url) { mutableStateOf(false) }
    val url = if (animate && !failed) gif.animated ?: gif.still else gif.still
    ImageAnimation(decoded, visible && animate)
    val request = remember(url) {
        ImageRequest.Builder(context).data(url).size(240, 320).maxBitmapSize(Size(240, 320))
            .memoryCachePolicy(CachePolicy.DISABLED).build()
    }
    LaunchedEffect(url, visible) { drawn = false; if (!visible) decoded = null }
    Box(modifier.then(if (!drawn) Modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh) else Modifier)) {
        if (visible && resources != null && url != null) AsyncImage(model = request, imageLoader = resources.images,
            contentDescription = gif.title.ifBlank { "GIF" }, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop,
            onSuccess = { drawn = true; decoded = it.result.image },
            onError = { failed = true })
    }
}
