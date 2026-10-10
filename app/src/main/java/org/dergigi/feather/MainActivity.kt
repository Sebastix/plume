package org.dergigi.feather

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Sky = Color(0xFF79C4F2)
private val Ink = Color(0xFF293945)
private val Blue = Color(0xFF2479A9)
private val Mist = Color(0xFFF0F9FF)

class MainActivity : ComponentActivity() {
    private val repo get() = (application as FeatherApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White,
                primaryContainer = Mist, onPrimaryContainer = Ink, background = Color.White,
                surface = Color.White, onSurface = Ink, onBackground = Ink, surfaceContainer = Mist,
                outlineVariant = Color(0xFFEDF1F4))) { FeatherApp() }
        }
    }
    override fun onStart() { super.onStart(); repo.foreground() }
    override fun onStop() { repo.background(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatherApp(vm: FeatherViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val mediaStore = (context.applicationContext as FeatherApplication).media
    var screen by rememberSaveable { mutableStateOf("feed") }
    var profileKey by rememberSaveable { mutableStateOf<String?>(null) }
    var threadId by rememberSaveable { mutableStateOf<String?>(null) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var topBarVisible by remember(screen) { mutableStateOf(true) }
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    fun goHome() { screen = "feed"; profileKey = null; threadId = null; vm.repo.viewProfile(null); vm.repo.viewThread(null) }
    fun openProfile(key: String) { profileKey = key; screen = "profile"; vm.repo.viewProfile(key); vm.repo.viewThread(null) }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data
        if (it.resultCode == RESULT_OK && data != null) {
            val result = ExternalSignerLogin.parseResult(data)
            if (result is SignerResult.RequestAddressed.Successful) {
                vm.login(result.result.pubkey, result.result.packageName); showAccount = false; goHome()
            } else vm.message.value = "Could not connect the signer. Please try again."
        }
    }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(screen, profileKey, threadId) {
        vm.repo.viewProfile(profileKey.takeIf { screen == "profile" })
        vm.repo.viewThread(threadId.takeIf { screen == "thread" })
    }
    BackHandler(screen != "feed") { goHome() }
    GalleryHost {
    MediaHost(mediaStore, reading = screen != "settings") {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AnimatedVisibility(topBarVisible, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(180))) {
            TopAppBar(title = {
                Icon(painterResource(R.drawable.feather), "Feather", Modifier.size(32.dp), tint = Color.Unspecified)
            }, navigationIcon = {
                if (screen != "feed") IconButton(onClick = { goHome() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                if (screen == "feed") {
                    IconButton(onClick = { showAccount = true }) { Icon(Icons.Default.Person, "Account") }
                    IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White, actionIconContentColor = Blue, navigationIconContentColor = Blue))
            }
        },
        containerColor = Color.White,
    ) { padding ->
        when (screen) {
            "settings" -> Settings(s, vm, Modifier.padding(padding))
            else -> {
                val notes = when (screen) {
                    "profile" -> s.notes.filter { it.pubKey == profileKey }
                    "thread" -> s.notes.filter { it.id == threadId || it.tags.any { tag -> tag.size >= 2 && tag[0] == "e" && tag[1] == threadId && (tag.size < 4 || tag[3] != "mention") } }.sortedBy { it.createdAt }
                    else -> s.notes.filter { !Protocol.isReply(it) && (s.publicKey == null || it.pubKey == s.publicKey || it.pubKey in s.following) }
                }
                val activeListState = if (screen == "feed") listState else rememberLazyListState()
                val toolbarScroll = remember(screen, activeListState, scrollThreshold) {
                    object : NestedScrollConnection {
                        private var distance = 0f
                        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                            if (source != NestedScrollSource.UserInput) return Offset.Zero
                            if (!activeListState.canScrollBackward) {
                                topBarVisible = true; distance = 0f
                                return Offset.Zero
                            }
                            val delta = consumed.y
                            if (delta == 0f) return Offset.Zero
                            if (distance * delta < 0f) distance = 0f
                            distance += delta
                            if (distance <= -scrollThreshold) { topBarVisible = false; distance = 0f }
                            else if (distance >= scrollThreshold) { topBarVisible = true; distance = 0f }
                            return Offset.Zero
                        }
                    }
                }
                val playback = LocalMedia.current.playback
                val widthPx = with(LocalDensity.current) { (LocalConfiguration.current.screenWidthDp - 48).dp.roundToPx() }
                PrefetchMedia(notes, s.profiles, activeListState, widthPx)
                var refreshing by remember(screen) { mutableStateOf(false) }
                LaunchedEffect(s.loading) { if (!s.loading) refreshing = false }
                val refresh = {
                    if (!s.loading) { refreshing = true; vm.repo.refresh() }
                }
                PullToRefreshBox(
                    isRefreshing = refreshing && s.loading,
                    onRefresh = refresh,
                    modifier = Modifier.padding(padding).fillMaxSize().nestedScroll(toolbarScroll).semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Refresh") { if (s.loading) false else { refresh(); true } },
                            CustomAccessibilityAction("Show toolbar") { topBarVisible = true; true },
                        )
                    },
                ) {
                    LazyColumn(Modifier.fillMaxSize().onGloballyPositioned { playback?.viewport = it.boundsInWindow() }, state = activeListState, contentPadding = PaddingValues(bottom = 24.dp)) {
                        if (screen == "profile") item("profile-header") {
                            val key = profileKey!!
                            val p = s.profiles[key]
                            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Avatar(p?.name ?: key, 56, p?.picture, openImage = true)
                                Text(p?.name ?: FeatherRepository.shortKey(key), style = MaterialTheme.typography.headlineSmall)
                                if (!p?.about.isNullOrBlank()) Text(p.about)
                                SelectionContainer { Text(Protocol.npub(key), style = MaterialTheme.typography.bodySmall, color = Color(0xFF647582)) }

                            }
                        }
                        if (s.loading && !refreshing) item("progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Sky) }
                        s.error?.let { error -> item("error") { Text(error, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                        if (notes.isEmpty() && !s.loading) item("empty") {
                            Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Icon(painterResource(R.drawable.feather), "No notes yet", Modifier.size(64.dp), tint = Color.Unspecified)
                            }
                        }
                        items(notes, key = { it.id }) { note ->
                            NoteRow(note, s.profiles[note.pubKey], onProfile = { openProfile(note.pubKey) }, onOpen = { threadId = note.id; screen = "thread" })
                            HorizontalDivider(color = Color(0xFFEDF1F4), modifier = Modifier.padding(horizontal = 20.dp))
                        }
                        if (notes.isNotEmpty() && screen != "thread") item("older") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { IconButton(onClick = { vm.repo.older(profileKey.takeIf { screen == "profile" }) }, enabled = !s.loading) { Icon(Icons.Default.KeyboardArrowDown, "Load older notes") } } }
                    }
                }
            }
        }
    }
    if (showAccount) {
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { showAccount = false }, icon = { Icon(Icons.Default.Person, "Account") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.publicKey != null) {
                    Text(s.profiles[s.publicKey]?.name ?: FeatherRepository.shortKey(s.publicKey!!))
                    SelectionContainer { Text(Protocol.npub(s.publicKey!!), style = MaterialTheme.typography.bodySmall) }
                }
                FilledTonalIconButton(onClick = {
                    try { loginLauncher.launch(ExternalSignerLogin.createIntent(emptyList())) }
                    catch (_: Exception) { error = "Install an Android signer such as Amber first." }
                }) { Icon(Icons.Default.Person, "Connect Android signer") }
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zapstore.dev/apps/com.greenart7c3.nostrsigner"))) }) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Get Amber") }
                }
                if (s.publicKey != null) IconButton(onClick = { vm.logout(); showAccount = false; goHome() }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Disconnect account") }
            }
        }, confirmButton = { IconButton(onClick = { showAccount = false }) { Icon(Icons.Default.Close, "Close account") } })
    }
    }
    }
}

@Composable
private fun Avatar(name: String, size: Int = 36, picture: String? = null, openImage: Boolean = false) {
    val openGallery = LocalOpenGallery.current
    Box(Modifier.size(size.dp).background(Mist, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Blue, fontWeight = FontWeight.Medium)
        RemoteImage(picture, Modifier.fillMaxSize().clip(CircleShape).then(if (openImage && picture != null) Modifier.clickable(onClickLabel = "Open profile picture") { openGallery(listOf(picture), 0) } else Modifier), avatar = true)
    }
}

@Composable
private fun NoteRow(note: Event, profile: Profile?, onProfile: () -> Unit, onOpen: () -> Unit) {
    val context = LocalContext.current
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var truncated by remember(note.id) { mutableStateOf(false) }
    val name = profile?.name ?: FeatherRepository.shortKey(note.pubKey)
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onProfile), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, picture = profile?.picture); Spacer(Modifier.width(10.dp)); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            Text(remember(note.createdAt) { DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(note.createdAt)) }, style = MaterialTheme.typography.labelSmall, color = Color(0xFF647582))
        }
        Spacer(Modifier.height(12.dp))
        val attachments = remember(note.id) { MediaContent.attachments(note) }
        val body = remember(note.id) { MediaContent.text(note, attachments) }
        val content = remember(note.id) {
            buildAnnotatedString {
                var offset = 0
                Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(body).take(30).forEach { match ->
                    val url = match.value.trimEnd('.', ',', ')', ']', '!', '?')
                    append(body.substring(offset, match.range.first))
                    withLink(LinkAnnotation.Url(url, TextLinkStyles(style = SpanStyle(color = Blue)))) { append(url) }
                    offset = match.range.first + url.length
                }
                append(body.substring(offset))
            }
        }
        if (body.isNotBlank()) SelectionContainer { Text(content, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp), maxLines = if (expanded) Int.MAX_VALUE else 12, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow }) }
        if (expanded || truncated) IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Show less" else "Read more") }
        if (attachments.isNotEmpty()) { Spacer(Modifier.height(12.dp)); NoteMedia(note, attachments) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpen) { Icon(painterResource(R.drawable.conversation), "Read conversation", Modifier.size(20.dp), tint = Color(0xFF647582)) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://njump.to/${com.vitorpamplona.quartz.nip19Bech32.entities.NNote.create(note.id)}") }, "Share note")) }) { Icon(Icons.Default.Share, "Share note", Modifier.size(18.dp), tint = Color(0xFF647582)) }
        }
    }
}

@Composable
private fun Settings(state: FeedState, vm: FeatherViewModel, modifier: Modifier) {
    var relays by remember(state.relays) { mutableStateOf(state.relays.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val mediaStore = (context.applicationContext as FeatherApplication).media
    val budget by mediaStore.budgetMb.collectAsStateWithLifecycle()
    val mediaError by mediaStore.error.collectAsStateWithLifecycle()
    val resources by mediaStore.resources.collectAsStateWithLifecycle()
    var selectedBudget by remember(budget) { mutableIntStateOf(MediaContent.cacheOptionsMb.indexOf(budget)) }
    val scope = rememberCoroutineScope()
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.storage), "Media cache storage", tint = Blue)
                Spacer(Modifier.width(16.dp))
                val mb = MediaContent.cacheOptionsMb[selectedBudget]
                Text(if (mb >= 1024) "${mb / 1024} GB" else "$mb MB", style = MaterialTheme.typography.titleMedium)
                if (resources == null) { Spacer(Modifier.width(16.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
            }
            Slider(value = selectedBudget.toFloat(), onValueChange = { selectedBudget = it.toInt() }, valueRange = 0f..4f, steps = 3,
                modifier = Modifier.semantics { contentDescription = "Media cache storage limit"; stateDescription = "${MediaContent.cacheOptionsMb[selectedBudget]} megabytes" },
                onValueChangeFinished = { scope.launch { mediaStore.setBudget(MediaContent.cacheOptionsMb[selectedBudget]) } })
            mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            OutlinedTextField(relays, { relays = it }, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Discovery relays, up to four secure WebSocket addresses, one per line" }, minLines = 4, isError = error != null)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                FilledTonalIconButton(onClick = { try { vm.repo.setRelays(relays); error = null } catch (e: IllegalArgumentException) { error = e.message } }) { Icon(Icons.Default.Check, "Save relays") }
                IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dergigi/feather"))) }) { Icon(Icons.Default.Info, "About Feather ${BuildConfig.VERSION_NAME}, source code and feedback") }
            }
        }
    }
}
