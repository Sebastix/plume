package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import kotlinx.coroutines.*
import okhttp3.*
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class SearchProfile(val event: Event, val name: String, val picture: String?, val identity: String?, val sensitive: Boolean)

/** One short-lived, unsigned NIP-50 subscription; never touches the feed's relay pool. */
internal object VertexProfileSearch {
    private val http = OkHttpClient.Builder().connectTimeout(5, TimeUnit.SECONDS).build()
    private val cache = android.util.LruCache<String, Pair<Long, List<SearchProfile>>>(12)

    suspend fun search(query: String): List<SearchProfile> = withContext(Dispatchers.IO) {
        synchronized(cache) { cache.get(query) }?.takeIf { System.currentTimeMillis() - it.first < 300_000 }?.let { return@withContext it.second }
        val done = CompletableDeferred<List<String>>()
        val rows = ArrayList<String>()
        var received = 0
        val socket = http.newWebSocket(Request.Builder().url("https://relay.vertexlab.io").build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONArray().put("REQ").put("profiles").put(JSONObject()
                    .put("kinds", JSONArray().put(0)).put("search", query).put("limit", 30)).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (done.isCompleted) return
                if (text.length > 65_536 || ++received > 100) {
                    done.completeExceptionally(IllegalStateException("Vertex response exceeded search limits")); webSocket.cancel(); return
                }
                try {
                    val frame = JSONArray(text)
                    when (frame.optString(0)) {
                        "EVENT" -> if (frame.optString(1) == "profiles" && rows.size < 30) rows.add(frame.getJSONObject(2).toString())
                        "EOSE" -> if (frame.optString(1) == "profiles") done.complete(rows.toList())
                        "CLOSED" -> if (frame.optString(1) == "profiles") done.completeExceptionally(IllegalStateException("Vertex: ${frame.optString(2).take(300)}"))
                    }
                } catch (e: Exception) { done.completeExceptionally(e) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.completeExceptionally(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (!done.isCompleted) done.completeExceptionally(IllegalStateException("Vertex closed search: $code ${reason.take(200)}"))
            }
        })
        val raw = try { withTimeout(8_000) { done.await() } } finally { socket.cancel() }
        val results = raw.mapNotNull { json ->
            ensureActive()
            runCatching {
                val event = Event.fromJson(json)
                if (event.kind != 0 || event.content.length > 32_768 || event.tags.size > 5000 ||
                    event.createdAt > System.currentTimeMillis() / 1000 + 600 || !event.verify()) return@runCatching null
                val data = JSONObject(event.content)
                SearchProfile(event, (data.optString("display_name").takeIf(String::isNotBlank)
                    ?: data.optString("name").takeIf(String::isNotBlank) ?: FeatherRepository.shortKey(event.pubKey)).take(200),
                    MediaContent.secureUrl(data.optString("picture").trim()),
                    data.optString("nip05").takeIf(String::isNotBlank)?.take(320), event.isSensitive())
            }.getOrNull()
        }.groupBy { it.event.pubKey }.values.map { versions -> versions.maxBy { it.event.createdAt } }
        if (results.isNotEmpty()) synchronized(cache) { cache.put(query, System.currentTimeMillis() to results) }
        results
    }
}

@Composable
internal fun ProfileSearchPage(vm: FeatherViewModel, navigation: ReaderNavigation, home: ReaderEntry, active: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val running = active && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    var results by remember { mutableStateOf<List<SearchProfile>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val query = home.searchQuery.trim()
    val scope = rememberCoroutineScope()
    val focus = LocalFocusManager.current
    LaunchedEffect(active) { if (!active) focus.clearFocus() }
    LaunchedEffect(query, running, retry) {
        error = null
        loading = false
        results = emptyList()
        if (!running || query.length < 2) return@LaunchedEffect
        loading = true
        try {
            delay(400)
            results = VertexProfileSearch.search(query)
        } catch (e: TimeoutCancellationException) { error = "Vertex profile search timed out" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { error = "Vertex profile search: ${e.message ?: e.javaClass.simpleName}" }
        finally { loading = false }
    }
    val media = (LocalContext.current.applicationContext as FeatherApplication).media
    val resources by media.resources.collectAsStateWithLifecycle()
    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalMedia provides MediaEnvironment(resources, running, null)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().imePadding().padding(top = 80.dp)) {
            OutlinedTextField(value = home.searchQuery, onValueChange = { home.searchQuery = it.take(100) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = { if (home.searchQuery.isNotEmpty()) IconButton(onClick = { home.searchQuery = "" }) { Icon(Icons.Default.Close, "Clear search") } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).semantics { contentDescription = "Search profiles" },
                shape = androidx.compose.foundation.shape.RoundedCornerShape(28.dp), singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { focus.clearFocus(); retry++ }))
            if (loading) Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            error?.let { message -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                IconButton(onClick = { AppMessages.show(message); retry++ }) { Icon(Icons.Default.Warning, "Search failed; show error and retry", tint = MaterialTheme.colorScheme.error) }
            } }
            LazyColumn(state = home.feedPage(ProfileTab.SEARCH).list, contentPadding = PaddingValues(top = 8.dp, bottom = 144.dp)) {
                items(results.filter { it.event.pubKey !in state.mutedAuthors && (state.showSensitive || !it.sensitive) }, key = { it.event.pubKey }) { profile ->
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${profile.name}'s profile") {
                        focus.clearFocus()
                        scope.launch {
                            vm.repo.cacheSearchProfile(profile.event)
                            navigation.push(ReaderEntry("profile", profile.event.pubKey))
                        }
                    }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Avatar(profile.name, 40, profile.picture)
                        Column(Modifier.weight(1f)) {
                            Text(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            profile.identity?.let { Text(it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        }
                    }
                }
            }
        }
    }
}
