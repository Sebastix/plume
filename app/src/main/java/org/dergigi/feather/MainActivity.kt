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
import androidx.compose.ui.platform.LocalLayoutDirection
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
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Sky = Color(0xFF79C4F2)

class MainActivity : ComponentActivity() {
    private val repo get() = (application as FeatherApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeatherTheme { FeatherApp() }
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
    var showSettings by rememberSaveable { mutableStateOf(false) }
    val listState = rememberLazyListState()
    var topBarVisible by remember(screen) { mutableStateOf(true) }
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val profileListState = key(screen, profileKey, threadId) { rememberLazyListState() }
    val activeListState = if (screen == "feed") listState else profileListState
    val hasProfileBanner = screen == "profile" && s.profiles[profileKey]?.banner != null
    val bannerBelowToolbarPx = with(LocalDensity.current) { 112.dp.toPx() }
    val toolbarOverBanner by remember(hasProfileBanner, activeListState, bannerBelowToolbarPx) {
        derivedStateOf {
            hasProfileBanner && activeListState.firstVisibleItemIndex == 0 &&
                activeListState.firstVisibleItemScrollOffset < bannerBelowToolbarPx
        }
    }
    val toolbarButtonSurface = if (toolbarOverBanner) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape) else Modifier
    fun goHome() { vm.repo.openReference(null); screen = "feed"; profileKey = null; threadId = null; vm.repo.viewProfile(null); vm.repo.viewThread(null) }
    fun openReference(ref: NostrContent.Reference) {
        if (ref.external != null) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://njump.to/${ref.external}"))); return }
        vm.repo.openReference(ref)
        if (ref.profile) { profileKey = ref.id; screen = "profile"; vm.repo.viewProfile(ref.id); vm.repo.viewThread(null) }
        else { threadId = ref.id; screen = "thread"; vm.repo.viewThread(ref.id); vm.repo.viewProfile(null) }
    }
    fun openProfile(key: String) { vm.repo.openReference(NostrContent.Reference(key, profile = true)); profileKey = key; screen = "profile"; vm.repo.viewProfile(key); vm.repo.viewThread(null) }
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
    MediaHost(mediaStore, reading = !showSettings && !showAccount) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            AnimatedVisibility(topBarVisible, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(180))) {
            TopAppBar(title = {
                if (screen == "feed") {
                    Icon(painterResource(R.drawable.feather), "Feather", Modifier.size(32.dp), tint = Color.Unspecified)
                }
            }, navigationIcon = {
                if (screen != "feed") IconButton(onClick = { goHome() }, modifier = toolbarButtonSurface) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                if (screen == "profile") IconButton(onClick = { showSettings = true }, modifier = toolbarButtonSurface) { Icon(Icons.Default.Settings, "Settings") }
                if (screen == "feed") {
                    IconButton(onClick = { s.publicKey?.let { openProfile(it) } ?: run { showAccount = true } }, modifier = Modifier.semantics { contentDescription = "Account" }) {
                        val account = s.profiles[s.publicKey]
                        if (s.publicKey != null) Avatar(account?.name ?: FeatherRepository.shortKey(s.publicKey!!), 28, account?.picture)
                        else Icon(Icons.Default.Person, null, Modifier.size(22.dp))
                    }
                }
            }, expandedHeight = 64.dp, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (toolbarOverBanner) Color.Transparent else MaterialTheme.colorScheme.surface,
                actionIconContentColor = MaterialTheme.colorScheme.primary, navigationIconContentColor = MaterialTheme.colorScheme.primary))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        when (screen) {
            else -> {
                val notes = when (screen) {
                    "profile" -> s.notes.filter { it.pubKey == profileKey }
                    "thread" -> s.notes.filter { it.id == threadId || it.tags.any { tag -> tag.size >= 2 && tag[0] == "e" && tag[1] == threadId && (tag.size < 4 || tag[3] != "mention") } }.sortedBy { it.createdAt }
                    else -> s.notes.filter { !Protocol.isReply(it) && (s.publicKey == null || it.pubKey == s.publicKey || it.pubKey in s.following) }
                }
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
                val currentNotes by rememberUpdatedState(notes)
                LaunchedEffect(activeListState, screen) {
                    snapshotFlow { activeListState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                        .distinctUntilChanged().collect { keys ->
                            val last = currentNotes.indexOfLast { it.id in keys }
                            val first = currentNotes.indexOfFirst { it.id in keys }
                            val nearby = if (last >= 0) currentNotes.drop(last + 1).take(6) + currentNotes.take(first).takeLast(2) else emptyList()
                            vm.repo.readingNotes(keys + nearby.map { it.id })
                        }
                }
                var refreshing by remember(screen) { mutableStateOf(false) }
                LaunchedEffect(s.loading) { if (!s.loading) refreshing = false }
                val refresh = {
                    if (!s.loading) { refreshing = true; vm.repo.refresh() }
                }
                PullToRefreshBox(
                    isRefreshing = refreshing && s.loading,
                    onRefresh = refresh,
                    modifier = Modifier.padding(
                        if (hasProfileBanner) PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                            start = padding.calculateStartPadding(LocalLayoutDirection.current),
                            end = padding.calculateEndPadding(LocalLayoutDirection.current),
                        ) else padding
                    ).fillMaxSize().nestedScroll(toolbarScroll).semantics {
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
                            val openGallery = LocalOpenGallery.current
                            Column(Modifier.fillMaxWidth()) {
                                p?.banner?.let { banner ->
                                    RemoteImage(banner, Modifier.fillMaxWidth().height(176.dp)
                                        .clickable(onClickLabel = "Open profile banner") { openGallery(listOf(banner), 0) }, "Profile banner")
                                }
                                Column(Modifier.padding(horizontal = 24.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                        Avatar(p?.name ?: key, 56, p?.picture, openImage = true)
                                        Spacer(Modifier.width(14.dp))
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text(p?.name ?: FeatherRepository.shortKey(key), style = MaterialTheme.typography.titleLarge,
                                                fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                            p?.username?.takeIf { it != p.name }?.let {
                                                Text("@$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                        }
                                        IconButton(onClick = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nostr public key", Protocol.npub(key)))
                                        }) { Icon(painterResource(R.drawable.copy), "Copy profile public key", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                                    }
                                    if (!p?.about.isNullOrBlank()) SelectionContainer {
                                        Text(p.about, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 24.dp))
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
                            NoteRow(note, s, onReference = ::openReference, onProfile = { openProfile(note.pubKey) }, onOpen = { threadId = note.id; screen = "thread" })
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
                        }
                        if (notes.isNotEmpty() && screen != "thread") item("older") { Box(Modifier.fillMaxWidth().padding(12.dp), contentAlignment = Alignment.Center) { IconButton(onClick = { vm.repo.older(profileKey.takeIf { screen == "profile" }) }, enabled = !s.loading) { Icon(Icons.Default.KeyboardArrowDown, "Load older notes") } } }
                    }
                }
            }
        }
    }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Settings(s, onLogout = { vm.logout(); showSettings = false; showAccount = false; goHome() },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 28.dp).padding(bottom = 32.dp))
        }
    }
    if (showAccount) {
        var error by remember { mutableStateOf<String?>(null) }
        ModalBottomSheet(onDismissRequest = { showAccount = false }, containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp).padding(bottom = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
                val key = s.publicKey
                val profile = s.profiles[key]
                if (key != null) {
                    Avatar(profile?.name ?: FeatherRepository.shortKey(key), 72, profile?.picture)
                    Text(profile?.name ?: FeatherRepository.shortKey(key), style = MaterialTheme.typography.titleLarge,
                        maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        FilledTonalIconButton(onClick = { showAccount = false; openProfile(key) }) { Icon(Icons.Default.Person, "View your profile") }
                        IconButton(onClick = { vm.logout(); showAccount = false; goHome() }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Disconnect account") }
                    }
                } else {
                    Icon(painterResource(R.drawable.feather), null, Modifier.size(64.dp), tint = Color.Unspecified)
                    FilledTonalIconButton(onClick = {
                        try { loginLauncher.launch(ExternalSignerLogin.createIntent(emptyList())) }
                        catch (_: Exception) { error = "Install an Android signer such as Amber first." }
                    }, modifier = Modifier.size(56.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowForward, "Connect Android signer") }
                    IconButton(onClick = { showAccount = false; showSettings = true }) { Icon(Icons.Default.Settings, "Settings", tint = MaterialTheme.colorScheme.primary) }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zapstore.dev/apps/com.greenart7c3.nostrsigner"))) }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, "Get Amber")
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
private fun Avatar(name: String, size: Int = 36, picture: String? = null, openImage: Boolean = false) {
    val openGallery = LocalOpenGallery.current
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        RemoteImage(picture, Modifier.fillMaxSize().clip(CircleShape).then(if (openImage && picture != null) Modifier.clickable(onClickLabel = "Open profile picture") { openGallery(listOf(picture), 0) } else Modifier), avatar = true)
    }
}

@Composable
private fun NoteRow(note: Event, state: FeedState, onReference: (NostrContent.Reference) -> Unit, onProfile: () -> Unit, onOpen: () -> Unit) {
    val profile = state.profiles[note.pubKey]
    val context = LocalContext.current
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var truncated by remember(note.id) { mutableStateOf(false) }
    val name = profile?.name ?: FeatherRepository.shortKey(note.pubKey)
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Read conversation", onClick = onOpen).padding(horizontal = 24.dp, vertical = 24.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onProfile), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name, picture = profile?.picture); Spacer(Modifier.width(10.dp)); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            Text(remember(note.createdAt) { DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(note.createdAt)) }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        val attachments = remember(note.id) { MediaContent.attachments(note) }
        val body = remember(note.id) { MediaContent.text(note, attachments) }
        val quotes = remember(note.id) { NostrContent.quotes(note) }
        val content = richNoteText(body, note, state.profiles, onReference, quotes.map { it.id }.toSet())
        if (content.isNotBlank()) SelectionContainer { Text(content, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp), maxLines = if (expanded) Int.MAX_VALUE else 12, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow }) }
        quotes.forEach { reference ->
            Spacer(Modifier.height(12.dp))
            EmbeddedNote(reference, state.embedded[reference.id], state.profiles, "${note.id}:${reference.id}", onReference)
        }
        if (expanded || truncated) IconButton(onClick = { expanded = !expanded }) { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Show less" else "Read more") }
        if (attachments.isNotEmpty()) { Spacer(Modifier.height(12.dp)); NoteMedia(note, attachments) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onOpen) { Icon(painterResource(R.drawable.conversation), "Read conversation", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://njump.to/${com.vitorpamplona.quartz.nip19Bech32.entities.NNote.create(note.id)}") }, "Share note")) }) { Icon(Icons.Default.Share, "Share note", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Text and links stay selectable; mention labels update when metadata arrives. */
@Composable
private fun richNoteText(body: String, note: Event, profiles: Map<String, Profile>, onReference: (NostrContent.Reference) -> Unit,
    embeddedIds: Set<String> = emptySet()): androidx.compose.ui.text.AnnotatedString {
    val tokens = remember(body, note.id) { NostrContent.tokens(body, note) }
    val open by rememberUpdatedState(onReference)
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(body, tokens, profiles, embeddedIds, linkColor) {
        buildAnnotatedString {
            var offset = 0
            tokens.forEach { token ->
                append(body.substring(offset, token.start))
                val ref = token.reference
                if (ref != null) {
                    if (ref.id !in embeddedIds || ref.profile) {
                        withLink(LinkAnnotation.Clickable(token.text, TextLinkStyles(style = SpanStyle(color = linkColor))) { open(ref) }) {
                            append(if (ref.profile) "@${profiles[ref.id]?.name ?: FeatherRepository.shortKey(ref.id)}" else "↗ ${FeatherRepository.shortKey(ref.id)}")
                        }
                    }
                } else if (token.url != null) {
                    withLink(LinkAnnotation.Url(token.url, TextLinkStyles(style = SpanStyle(color = linkColor)))) { append(token.text) }
                }
                offset = token.end
            }
            append(body.substring(offset))
        }
    }
}

@Composable
private fun EmbeddedNote(reference: NostrContent.Reference, note: Event?, profiles: Map<String, Profile>, mediaKey: String, onReference: (NostrContent.Reference) -> Unit) {
    Surface(onClick = { onReference(reference) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth(), tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (note == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.conversation), "Open referenced note", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                    Text(FeatherRepository.shortKey(reference.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                }
            } else {
                val profile = profiles[note.pubKey]
                val name = profile?.name ?: FeatherRepository.shortKey(note.pubKey)
                Row(Modifier.fillMaxWidth().clickable { onReference(NostrContent.Reference(note.pubKey, profile = true)) },
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Avatar(name, 28, profile?.picture)
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val attachments = remember(note.id) { MediaContent.attachments(note, limit = 1) }
                val body = remember(note.id) { MediaContent.text(note, attachments) }
                val text = richNoteText(body, note, profiles, onReference)
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp), maxLines = 8, overflow = TextOverflow.Ellipsis)
                // One level only. Deeper references remain links, avoiding recursive network/render work.
                if (attachments.isNotEmpty()) NoteMedia(note, attachments, mediaKey)
            }
        }
    }
}

@Composable
private fun Settings(state: FeedState, onLogout: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val mediaStore = (context.applicationContext as FeatherApplication).media
    val budget by mediaStore.budgetMb.collectAsStateWithLifecycle()
    val mediaError by mediaStore.error.collectAsStateWithLifecycle()
    val resources by mediaStore.resources.collectAsStateWithLifecycle()
    var selectedBudget by remember(budget) { mutableIntStateOf(MediaContent.cacheOptionsMb.indexOf(budget)) }
    val scope = rememberCoroutineScope()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        state.publicKey?.let { key ->
            val profile = state.profiles[key]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(profile?.name ?: FeatherRepository.shortKey(key), 40, profile?.picture)
                Text(profile?.name ?: FeatherRepository.shortKey(key), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                FilledTonalIconButton(onClick = onLogout) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Log out") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.storage), "Media cache storage", tint = MaterialTheme.colorScheme.primary)
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
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dergigi/feather"))) }) {
                Icon(Icons.Default.Info, "About Feather ${BuildConfig.VERSION_NAME}, source code and feedback", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
