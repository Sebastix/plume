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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onSizeChanged
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged

private val Sky = Color(0xFF79C4F2)

class MainActivity : ComponentActivity() {
    private val repo get() = (application as FeatherApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FeatherTheme { FeatherApp(window); PreviousCrashDialog() }
        }
    }
    override fun onStart() { super.onStart(); repo.foreground() }
    override fun onStop() { repo.background(); super.onStop() }
}

@Composable
fun FeatherApp(window: android.view.Window, vm: FeatherViewModel = viewModel()) {
    val navigation = rememberSaveable(saver = ReaderNavigation.saver) { ReaderNavigation() }
    val entry = navigation.current
    if (entry.screen in listOf("feed", "profile") && entry.tab == ProfileTab.PHOTOS) HidePhotoStatusBar(window)
    if (entry.screen == "feed") HomeFeedPages(vm, navigation, entry)
    else FeatherReader(vm, navigation, entry)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeatherReader(vm: FeatherViewModel, navigation: ReaderNavigation, entry: ReaderEntry, activePage: Boolean = true, homeToolbar: ReaderEntry? = null,
    accountRequested: Boolean = false, onAccountHandled: () -> Unit = {}) {
    val s by vm.state.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val connectedSigner by vm.signer.collectAsStateWithLifecycle()
    val reacting by vm.reacting.collectAsStateWithLifecycle()
    val zapping by vm.zapping.collectAsStateWithLifecycle()
    val walletConnected by vm.wallet.store.connected.collectAsStateWithLifecycle()
    val confirmedPayments by vm.wallet.store.confirmedPayments.collectAsStateWithLifecycle()
    val confirmedZaps = remember(confirmedPayments, s.publicKey) {
        confirmedPayments.values.filter { it.first == s.publicKey }.map { it.second }.toSet()
    }
    val decryptingMutes by vm.decryptingMutes.collectAsStateWithLifecycle()
    val contentFilter = remember(s.mutedAuthors, s.embedded, s.deletedReactions, s.showSensitive) { ContentFilter(s.mutedAuthors, s.embedded, s.deletedReactions, s.showSensitive) }
    val context = LocalContext.current
    val snack = remember { SnackbarHostState() }
    val scrollScope = rememberCoroutineScope()
    val mediaStore = (context.applicationContext as FeatherApplication).media
    val screen = entry.screen
    val profileKey = entry.target.takeIf { screen == "profile" }
    val hashtag = entry.target.takeIf { screen == "hashtag" }
    val threadId = entry.target.takeIf { screen == "thread" }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var showWallet by remember { mutableStateOf(false) }
    LaunchedEffect(connectedSigner) { showWallet = false }
    LaunchedEffect(accountRequested) {
        if (accountRequested) { showAccount = true; onAccountHandled() }
    }
    var statusReport by rememberSaveable { mutableStateOf<String?>(null) }
    var statusTitle by rememberSaveable { mutableStateOf("") }
    var profileTab by entry::tab
    val visibleProfileTabs = if (s.profileTabsFor == profileKey && profileKey != null) {
        s.profileTabs.toList().also { entry.tabs = it }
    } else entry.tabs
    LaunchedEffect(entry.key, visibleProfileTabs) {
        if (screen == "profile" && visibleProfileTabs.isNotEmpty() && profileTab !in visibleProfileTabs) profileTab = visibleProfileTabs.first()
    }
    var topBarVisible by (homeToolbar ?: entry)::toolbar
    val scrollThreshold = with(LocalDensity.current) { 32.dp.toPx() }
    val activeListState = entry.list
    val savedScreens = rememberSaveableStateHolder()
    val hasProfileBanner = screen == "profile" && s.profiles[profileKey]?.banner != null
    val bannerBelowToolbarPx = with(LocalDensity.current) { 112.dp.toPx() }
    val toolbarOverBanner by remember(hasProfileBanner, activeListState, bannerBelowToolbarPx) {
        derivedStateOf {
            hasProfileBanner && activeListState.firstVisibleItemIndex == 0 &&
                activeListState.firstVisibleItemScrollOffset < bannerBelowToolbarPx
        }
    }
    val transparentToolbar = screen == "feed" || toolbarOverBanner
    val toolbarButtonSurface = Modifier.size(RoundControlSize).then(if (transparentToolbar) Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape) else Modifier)
    fun selectProfileTab(tab: ProfileTab) {
        if (profileTab == tab) return
        profileTab = tab
    }
    fun activate(destination: ReaderEntry, clicked: Event? = null) {
        vm.repo.openReference(destination.reference)
        vm.repo.viewHashtag(destination.target.takeIf { destination.screen == "hashtag" })
        vm.repo.viewProfile(destination.target.takeIf { destination.screen == "profile" })
        if (destination.screen == "profile") vm.repo.profileTab(destination.tab)
        if (destination.screen == "feed") vm.repo.feedTab(destination.tab)
        vm.repo.viewThread(destination.target.takeIf { destination.screen == "thread" },
            clicked ?: destination.notes.values.firstNotNullOfOrNull { notes -> notes.find { it.id == destination.target } })
    }
    fun goHome() {
        navigation.entries.forEach { savedScreens.removeState(it.key) }
        navigation.home(); activate(navigation.current)
    }
    fun goBack() {
        val removed = navigation.current
        navigation.back()
        savedScreens.removeState(removed.key)
        activate(navigation.current)
    }
    fun openReference(ref: NostrContent.Reference) {
        if (contentFilter.hides(ref)) return
        val resolved = s.embedded[ref.id]
        if (resolved != null && resolved.kind in readableKinds) {
            val destination = ReaderEntry("thread", resolved.id)
            destination.reference = NostrContent.Reference(resolved.id, author = resolved.pubKey, kind = resolved.kind, relays = ref.relays)
            destination.notes[ProfileTab.POSTS] = listOf(resolved)
            navigation.push(destination); activate(navigation.current, resolved)
            return
        }
        if (ref.external != null) {
            val url = MediaContent.secureUrl(ref.external) ?: "https://njump.to/${ref.external}"
            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return
        }
        val destination = ReaderEntry(if (ref.hashtag != null) "hashtag" else if (ref.profile) "profile" else "thread", ref.hashtag ?: ref.id)
        destination.reference = ref.takeIf { ref.hashtag == null }
        navigation.push(destination); activate(navigation.current)
    }
    fun openProfile(key: String) = openReference(NostrContent.Reference(key, profile = true))
    fun openNote(note: Event) {
        if (contentFilter.hides(note)) return
        val destination = ReaderEntry("thread", note.id)
        destination.reference = NostrContent.Reference(note.id, profile = false)
        destination.notes[ProfileTab.POSTS] = listOf(note)
        navigation.push(destination); activate(navigation.current, note)
    }
    val reactionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode != android.app.Activity.RESULT_OK || data == null || data.getBooleanExtra("rejected", false))
            vm.cancelSignerRequest("Signer request cancelled.")
        else vm.signerResponse(data)
    }
    DisposableEffect(connectedSigner, reactionLauncher, activePage) {
        val signer = connectedSigner
        val launch: (Intent) -> Unit = { intent ->
            scrollScope.launch {
                if (vm.signer.value === signer && vm.signerRequests.active) {
                    try { reactionLauncher.launch(intent) }
                    catch (_: Exception) { vm.cancelSignerRequest("Could not open your signer. Please try again.") }
                }
            }
        }
        if (activePage) signer?.registerForegroundLauncher(launch)
        onDispose { if (activePage) signer?.unregisterForegroundLauncher(launch) }
    }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data
        if (it.resultCode == RESULT_OK && data != null) {
            val result = ExternalSignerLogin.parseResult(data)
            if (result is SignerResult.RequestAddressed.Successful) {
                vm.login(result.result.pubkey, result.result.packageName); showAccount = false; goHome()
            } else vm.message.value = "Could not connect the signer. Please try again."
        }
    }
    LaunchedEffect(s.pendingMuteList?.id, connectedSigner, reacting, activePage) {
        if (activePage) vm.loadMutes(s.pendingMuteList)
    }
    LaunchedEffect(message, activePage) { if (activePage) message?.let { snack.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(entry.key, activePage) { if (activePage) activate(entry) }
    LaunchedEffect(entry.key, profileTab, activePage) { if (activePage && screen == "profile") vm.repo.profileTab(profileTab) }
    statusReport?.let { StatusDetails(statusTitle, it) { statusReport = null } }
    BackHandler(activePage && navigation.entries.size > 1 && !showAccount && !showSettings) { goBack() }
    CompositionLocalProvider(LocalContentFilter provides contentFilter, LocalZapActions provides ZapActions(zapping, confirmedZaps) { note ->
        if (s.publicKey == null) showAccount = true
        else if (!walletConnected) showWallet = true
        else vm.zap(note)
    }, LocalReactionActions provides ReactionActions(pending = reacting, choices = s.reactionChoices, customEmojis = s.customEmojis, remove = { note, choice -> vm.react(note, choice, remove = true) }) { note, choice ->
        if (s.publicKey == null) showAccount = true else vm.react(note, choice)
    }) {
    GalleryHost(state = s, covered = showAccount || showWallet || showSettings, album = { if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PHOTOS) entry.photos else ProfilePhotos() },
        onOpenNote = { id, note -> if (note != null) openNote(note) else openReference(NostrContent.Reference(id)) }) {
    MediaHost(mediaStore, reading = activePage && !showSettings && !showAccount) {
    savedScreens.SaveableStateProvider(entry.key) {
    Scaffold(
        snackbarHost = { SnackbarHost(snack, Modifier.padding(bottom = if (homeToolbar != null) 144.dp else 0.dp)) },
        topBar = {
            AnimatedVisibility(homeToolbar == null && topBarVisible, enter = expandVertically(tween(180)), exit = shrinkVertically(tween(180))) {
            TopAppBar(title = {
                if (screen == "hashtag") Text("#${hashtag.orEmpty()}", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                if (screen == "feed") {
                    IconButton(onClick = {
                        scrollScope.launch {
                            // Avoid traversing a long feed and loading intermediate media.
                            if (activeListState.firstVisibleItemIndex > 20) activeListState.scrollToItem(0)
                            else activeListState.animateScrollToItem(0)
                            entry.toolbar = true
                        }
                    }, modifier = Modifier.size(48.dp).then(toolbarButtonSurface)) {
                        Icon(painterResource(R.drawable.feather), "Scroll feed to top", Modifier.size(32.dp), tint = Color.Unspecified)
                    }
                }
            }, navigationIcon = {
                if (screen != "feed") IconButton(onClick = { goBack() }, modifier = toolbarButtonSurface) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                if (screen == "profile" && s.publicKey != null && profileKey == s.publicKey) IconButton(onClick = { showSettings = true }, modifier = toolbarButtonSurface) { Icon(Icons.Default.Settings, "Settings") }
                if (screen == "feed") {
                    IconButton(onClick = { s.publicKey?.let { openProfile(it) } ?: run { showAccount = true } }, modifier = (if (s.publicKey != null) Modifier.size(RoundControlSize) else toolbarButtonSurface).semantics { contentDescription = "Account" }) {
                        val account = s.profiles[s.publicKey]
                        if (s.publicKey != null) Avatar(account?.name ?: FeatherRepository.shortKey(s.publicKey!!), ROUND_CONTROL_DP, account?.picture)
                        else Icon(Icons.Default.Person, null, Modifier.size(22.dp))
                    }
                }
            }, expandedHeight = 64.dp, colors = TopAppBarDefaults.topAppBarColors(
                containerColor = if (transparentToolbar) Color.Transparent else MaterialTheme.colorScheme.surface,
                actionIconContentColor = MaterialTheme.colorScheme.primary, navigationIconContentColor = MaterialTheme.colorScheme.primary))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
    ) { padding ->
        when (screen) {
            else -> {
                // A new input token is pending immediately, before the background effect starts.
                val preparation = remember(entry.key, s.notes, s.feedTab, s.feedPreviews, s.profileEvents, s.hashtagNotes, hashtag, screen, profileKey, threadId, profileTab, s.publicKey, s.following, contentFilter) { Any() }
                val preparedNotes by key(profileTab) { produceState<Pair<Any?, List<Event>>>(null to entry.notes[profileTab].orEmpty(), preparation) {
                    val retained = entry.notes[profileTab].orEmpty().filterNot(contentFilter::hides)
                    value = preparation to withContext(Dispatchers.Default) {
                    val filtered = when (screen) {
                    "hashtag" -> s.hashtagNotes.filter { event -> event.tags.any { it.size > 1 && it[0] == "t" && it[1].equals(hashtag, ignoreCase = true) } }
                    "profile" -> latestProfileEvents(s.profileEvents.filter { it.involves(profileKey) } + retained).filter(profileTab::matches).take(600)
                    "thread" -> emptyList()
                    else -> (if (s.feedTab == profileTab) s.notes else latestProfileEvents(s.feedPreviews[profileTab].orEmpty() + retained))
                        .filter { profileTab.matchesFeed(it) && (s.publicKey == null || it.involves(s.publicKey) || it.pubKey in s.following || (it.kind == 9321 && it.tagValue("p") in s.following) || (it.kind == 9735 && (it.tagValue("p") in s.following || it.tagValue("P") in s.following))) }.take(600)
                    }
                    // Returning to a paginated profile must not discard its already displayed pages.
                    if (screen == "hashtag" && filtered.size < retained.size)
                        latestProfileEvents(filtered + retained).filter { screen != "profile" || profileTab.matches(it) }.take(600)
                    else filtered
                    }
                } }
                // The selected note is handed off immediately; background filtering never blanks it.
                val notes = if (screen == "thread") {
                    // StateFlow already holds the handoff even if lifecycle collection resumes next frame.
                    val current = vm.repo.state.value
                    (current.threadNotes.takeIf { current.threadId == threadId && it.size >= entry.notes[profileTab].orEmpty().size } ?: entry.notes[profileTab]).orEmpty()
                } else preparedNotes.second
                // Re-check retained/handoff state synchronously when a list changes. These are
                // bounded in-memory key checks; no parsing, disk, or network work on main.
                val visibleNotes = remember(notes, contentFilter) { notes.filterNot(contentFilter::hides) }
                SideEffect { entry.notes[profileTab] = visibleNotes }
                val preparing = screen != "thread" && preparedNotes.first !== preparation
                val waitingForPage = screen == "feed" && (!activePage || s.feedTab != profileTab)
                val pendingContent = preparing || waitingForPage || s.loading
                // Relay completion can precede the sampled cache snapshot or another relay's
                // response. Brief gaps keep a spinner; existing content is never delayed.
                val emptySettled by produceState(false, entry.key, profileTab, pendingContent, visibleNotes.isEmpty()) {
                    value = false
                    if (!pendingContent && visibleNotes.isEmpty()) {
                        kotlinx.coroutines.delay(1_500)
                        value = true
                    }
                }
                val emptyLoading = visibleNotes.isEmpty() && (pendingContent || !emptySettled)
                val hiddenContent = visibleNotes.isEmpty() && (notes.isNotEmpty() || profileKey in s.mutedAuthors ||
                    entry.reference?.let(contentFilter::hides) == true)
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
                val preparedBookmarks by produceState<List<Pair<Event, NostrContent.Reference>>>(if (profileTab == ProfileTab.PEOPLE) entry.people else entry.bookmarks, visibleNotes, profileTab) {
                    value = withContext(Dispatchers.Default) {
                        if (screen in listOf("profile", "feed") && profileTab in listOf(ProfileTab.BOOKMARKS, ProfileTab.PEOPLE))
                            visibleNotes.asSequence().flatMap { collection -> (if (profileTab == ProfileTab.PEOPLE) peopleReferences(collection) else bookmarkReferences(collection)).asSequence().map { collection to it } }.take(5000).toList()
                        else emptyList()
                    }
                    if (screen in listOf("profile", "feed") && profileTab == ProfileTab.BOOKMARKS) entry.bookmarks = value
                    if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PEOPLE) entry.people = value
                }
                val bookmarkRows = remember(preparedBookmarks, contentFilter) {
                    preparedBookmarks.filterNot { contentFilter.hides(it.first) || contentFilter.hides(it.second) }
                }
                val preparedPhotos by produceState(entry.photos, visibleNotes, profileTab) {
                    value = withContext(Dispatchers.Default) {
                        if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PHOTOS) {
                            val photos = visibleNotes.asSequence().flatMap { note -> MediaContent.attachments(note, 32).filter { it.kind == MediaContent.Kind.IMAGE }.map { note to it } }.take(600).toList()
                            ProfilePhotos(photos.chunked(3), photos.map { it.second.url }, photos.map { it.first })
                        } else ProfilePhotos()
                    }
                    if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PHOTOS) entry.photos = value
                }
                val photoGrid = remember(preparedPhotos, contentFilter) {
                    val photos = preparedPhotos.rows.flatten().filterNot { contentFilter.hides(it.first) }
                    ProfilePhotos(photos.chunked(3), photos.map { it.second.url }, photos.map { it.first })
                }
                SideEffect { if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PHOTOS) entry.photos = photoGrid }
                val photoRows = photoGrid.rows
                val currentPhotos by rememberUpdatedState(photoRows)
                val mediaKeys = remember(photoRows, bookmarkRows, s.embedded) {
                    photoRows.associate { row -> "photo:${row.first().first.id}:${row.first().second.url}" to row.map { it.first.id } } +
                        bookmarkRows.associate { "${it.first.id}:${it.second.id}" to listOf(s.embedded[it.second.id]?.id ?: it.second.id) }
                }
                val mediaNotes = if (screen in listOf("profile", "feed") && profileTab == ProfileTab.BOOKMARKS)
                    bookmarkRows.mapNotNull { s.embedded[it.second.id] }.filterNot(contentFilter::hides).distinctBy { it.id } else visibleNotes
                val mediaEmbeds = remember(s.embedded, contentFilter) { s.embedded.filterValues { !contentFilter.hides(it) } }
                PrefetchMedia(mediaNotes, s.profiles, activeListState, widthPx, mediaEmbeds, mediaKeys)
                val currentBookmarks by rememberUpdatedState(bookmarkRows)
                val currentNotes by rememberUpdatedState(visibleNotes)
                LaunchedEffect(activeListState, screen, activePage) {
                    if (!activePage) return@LaunchedEffect
                    snapshotFlow { activeListState.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
                        .distinctUntilChanged().collect { keys ->
                            val last = currentNotes.indexOfLast { it.id in keys }
                            val first = currentNotes.indexOfFirst { it.id in keys }
                            val nearby = if (last >= 0) currentNotes.drop(last + 1).take(6) + currentNotes.take(first).takeLast(2) else emptyList()
                            val bookmarks = currentBookmarks
                            if (bookmarks.isNotEmpty()) {
                                val visibleBookmarks = bookmarks.filter { "${it.first.id}:${it.second.id}" in keys }
                                val lastBookmark = bookmarks.indexOfLast { "${it.first.id}:${it.second.id}" in keys }
                                vm.repo.readingNotes((visibleBookmarks + bookmarks.drop(lastBookmark + 1).take(6)).map { it.second.id })
                            } else if (currentPhotos.isNotEmpty()) {
                                val photos = currentPhotos
                                val lastPhoto = photos.indexOfLast { "photo:${it.first().first.id}:${it.first().second.url}" in keys }
                                vm.repo.readingNotes(photos.filter { "photo:${it.first().first.id}:${it.first().second.url}" in keys }
                                    .plus(photos.drop(lastPhoto + 1).take(2)).flatten().map { it.first.id })
                            } else vm.repo.readingNotes(keys + nearby.map { it.id })
                        }
                }
                var requestedBoundary by remember(screen, profileKey, profileTab, hashtag) { mutableStateOf<String?>(null) }
                var emptyPages by remember(screen, profileKey, profileTab, hashtag) { mutableIntStateOf(0) }
                val rawHistory by rememberUpdatedState(when (screen) {
                    "profile" -> s.profileEvents
                    "hashtag" -> s.hashtagNotes
                    else -> s.notes.takeIf { s.feedTab == profileTab }.orEmpty()
                })
                val pagingBusy by rememberUpdatedState(s.loading || s.loadingOlder)
                val mediaActive by rememberUpdatedState(LocalMedia.current.active)
                LaunchedEffect(activeListState, screen, profileKey, profileTab, hashtag, activePage) {
                    if (!activePage || screen == "thread") return@LaunchedEffect
                    snapshotFlow {
                        val layout = activeListState.layoutInfo
                        val nearEnd = layout.visibleItemsInfo.lastOrNull()?.index?.let { it >= layout.totalItemsCount - 7 } == true
                        Triple(nearEnd, rawHistory.lastOrNull()?.id, !pagingBusy && mediaActive)
                    }.distinctUntilChanged().collect { (nearEnd, boundary, ready) ->
                        if (!nearEnd) { requestedBoundary = null; emptyPages = 0 }
                        if (nearEnd && ready && boundary != null && boundary != requestedBoundary && (currentNotes.isNotEmpty() || emptyPages < 3)) {
                            if (vm.repo.older(profileKey.takeIf { screen == "profile" })) {
                                requestedBoundary = boundary
                                if (currentNotes.isEmpty()) emptyPages++ else emptyPages = 0
                            }
                        }
                    }
                }
                var refreshing by remember(screen) { mutableStateOf(false) }
                LaunchedEffect(s.loading) { if (!s.loading) refreshing = false }
                val refresh = {
                    if (activePage && !s.loading) { requestedBoundary = null; emptyPages = 0; refreshing = true; vm.repo.refresh(); vm.retryMutes() }
                }
                val initialViewport = with(LocalDensity.current) { LocalConfiguration.current.screenHeightDp.dp.roundToPx() }
                var profileViewport by remember(entry.key) { mutableIntStateOf(initialViewport) }
                val tabSpace = if (screen == "profile") remember(entry.key, profileTab, visibleNotes.isEmpty()) { ProfileTabSpace() } else null
                val tabMinimum = with(LocalDensity.current) {
                    (profileViewport - (tabSpace?.measured ?: 0)).coerceAtLeast(0).toDp()
                }
                PullToRefreshBox(
                    isRefreshing = refreshing && s.loading,
                    onRefresh = refresh,
                    modifier = Modifier.padding(
                        if (screen == "feed" || hasProfileBanner) PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                            bottom = padding.calculateBottomPadding(),
                            start = padding.calculateStartPadding(LocalLayoutDirection.current),
                            end = padding.calculateEndPadding(LocalLayoutDirection.current),
                        ) else padding
                    ).fillMaxSize().nestedScroll(toolbarScroll)
                        .profileTabSwipes(screen == "profile", visibleProfileTabs, profileTab, ::selectProfileTab).semantics {
                        customActions = listOf(
                            CustomAccessibilityAction("Refresh") { if (s.loading) false else { refresh(); true } },
                            CustomAccessibilityAction("Show toolbar") { topBarVisible = true; true },
                        )
                    },
                ) {
                    LazyColumn(Modifier.fillMaxSize().onSizeChanged { profileViewport = it.height }.onGloballyPositioned { playback?.viewport = it.boundsInWindow() }, state = activeListState, contentPadding = PaddingValues(top = if (screen == "feed" && homeToolbar == null) 64.dp else 0.dp, bottom = 24.dp + if (homeToolbar != null) 136.dp else 0.dp)) {
                        if (screen == "profile") item("profile-header") {
                            val key = profileKey!!
                            val p = s.profiles[key]
                            val openGallery = LocalOpenGallery.current
                            Box(Modifier.fillMaxWidth()) {
                                p?.banner?.let { banner ->
                                    Box(Modifier.fillMaxWidth().height(176.dp)
                                        .clickable(onClickLabel = "Open profile banner") { openGallery(listOf(banner), 0) }) {
                                        RemoteImage(banner, Modifier.fillMaxSize(), "Profile banner")
                                        val surface = MaterialTheme.colorScheme.surface
                                        Spacer(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(56.dp)
                                            .background(Brush.verticalGradient(listOf(surface.copy(alpha = 0f), surface))))
                                    }
                                }
                                // The 56dp avatar straddles the banner edge; content keeps its natural height.
                                Column(Modifier.fillMaxWidth().padding(top = if (p?.banner != null) 148.dp else 0.dp)) {
                                Column(Modifier.padding(start = 24.dp, end = 24.dp,
                                    top = if (p?.banner != null) 0.dp else 24.dp, bottom = 24.dp),
                                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
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
                                        p?.lightning?.trim()?.takeIf { '@' in it }?.let { address ->
                                            IconButton(onClick = {
                                                val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Lightning address", address))
                                            }) { Icon(painterResource(R.drawable.zap), "Copy Lightning address", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                                        }
                                        IconButton(onClick = {
                                            val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                                            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nostr public key", Protocol.npub(key)))
                                        }) { Icon(painterResource(R.drawable.copy), "Copy profile public key", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary) }
                                    }
                                    if (!p?.about.isNullOrBlank()) ProfileBiography(key, p, s, ::openReference)
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 24.dp))
                                }
                            }
                        }
                        if (screen == "profile" && visibleProfileTabs.isNotEmpty()) item("profile-tabs") {
                            ScrollableTabRow(edgePadding = 0.dp, selectedTabIndex = visibleProfileTabs.indexOf(profileTab).coerceAtLeast(0), containerColor = MaterialTheme.colorScheme.surface) {
                                visibleProfileTabs.forEach { tab ->
                                    Tab(selected = profileTab == tab, onClick = { selectProfileTab(tab) }, icon = {
                                        FeedCategoryIcon(tab)
                                    })
                                }
                            }
                        }
                        if (s.error == null && !hiddenContent && !refreshing && (emptyLoading || (activePage && s.loading))) item("progress") {
                            Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            }
                        }
                        if ((activePage && s.error != null) || (visibleNotes.isEmpty() && !emptyLoading && (hiddenContent || screen == "thread"))) item("status") {
                            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                                val title = when {
                                    activePage && s.error != null -> "Could not load notes"
                                    hiddenContent -> "Notes hidden"
                                    screen == "thread" -> "Post unavailable"
                                    else -> "No notes yet"
                                }
                                StatusInfo(title, error = activePage && s.error != null, hidden = hiddenContent) {
                                    statusTitle = title
                                    statusReport = readerStatusDetails(if (activePage) s else s.copy(error = null), screen, entry.target, profileTab,
                                        hidden = hiddenContent)
                                }
                            }
                        }
                        if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PHOTOS) {
                            itemsIndexed(photoRows, key = { _, row -> "photo:${row.first().first.id}:${row.first().second.url}" }) { rowIndex, row ->
                                val openGallery = LocalOpenGallery.current
                                ProfileTabRow(tabSpace, "photo:${row.first().first.id}:${row.first().second.url}", profileViewport) {
                                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        row.forEachIndexed { columnIndex, (_, photo) ->
                                            RemoteImage(photo.url, Modifier.weight(1f).aspectRatio(1f).clickable(onClickLabel = "Open photo") {
                                                openGallery(photoGrid.urls, rowIndex * 3 + columnIndex)
                                            }, photo.alt ?: "Photo")
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        } else if (screen in listOf("profile", "feed") && profileTab == ProfileTab.PEOPLE) {
                            itemsIndexed(bookmarkRows, key = { _, row -> "${row.first.id}:${row.second.id}" }) { index, (collection, reference) ->
                                ProfileTabRow(tabSpace, "${collection.id}:${reference.id}", profileViewport) {
                                    if (index == 0 || bookmarkRows[index - 1].first.id != collection.id) {
                                        collection.tagValue("title")?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.padding(horizontal = 24.dp, vertical = 12.dp), style = MaterialTheme.typography.titleSmall) }
                                    }
                                    val person = s.profiles[reference.id]
                                    val name = person?.name ?: FeatherRepository.shortKey(reference.id)
                                    Row(Modifier.fillMaxWidth().clickable { openReference(reference) }.padding(horizontal = 24.dp, vertical = 12.dp),
                                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Avatar(name, 44, person?.picture)
                                        Column(Modifier.weight(1f)) {
                                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            person?.about?.takeIf { it.isNotBlank() }?.let { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        }
                                    }
                                }
                            }
                        } else if (screen in listOf("profile", "feed") && profileTab == ProfileTab.BOOKMARKS) {
                            items(bookmarkRows, key = { "${it.first.id}:${it.second.id}" }) { (collection, reference) ->
                                ProfileTabRow(tabSpace, "${collection.id}:${reference.id}", profileViewport) {
                                    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        collection.tagValue("title")?.takeIf { it.isNotBlank() }?.let { title ->
                                            Text(title, style = MaterialTheme.typography.labelMedium)
                                        }
                                        EmbeddedNote(reference, s.embedded[reference.id], s.profiles, "bookmark:${collection.id}:${reference.id}", ::openReference)
                                    }
                                }
                            }
                        } else items(visibleNotes, key = { it.id }) { note ->
                            ProfileTabRow(tabSpace, note.id, profileViewport) {
                                if (note.kind == 30617) ProjectCard(note)
                                else if (note.kind == 32267) SoftwareAppCard(note)
                                else if (note.kind == 6) RepostRow(note, s, ::openReference, ::openProfile) { original ->
                                    openNote(original)
                                } else NoteRow(note, s, onReference = ::openReference, onProfile = { openProfile(note.pubKey) }, onOpen = { if (note.kind == 7) reactionTarget(note)?.let(::openReference) else { openNote(note) } }, compact = screen != "thread")
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = 20.dp))
                            }
                        }
                        if (screen == "profile") item("profile-tab-minimum") {
                            Spacer(Modifier.fillMaxWidth().height(tabMinimum))
                        }
                        if (s.loadingOlder && screen != "thread") item("older-progress") {
                            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            }
                        }
                    }
                }
            }
        }
    }
    } // Reaction actions.
    } // Per-visit saved UI state.
    if (showWallet && connectedSigner != null) WalletSheet(vm) { showWallet = false }
    if (showSettings) {
        ModalBottomSheet(onDismissRequest = { showSettings = false }, containerColor = MaterialTheme.colorScheme.surface,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Settings(s, onWallet = { showSettings = false; showWallet = true }, onLogout = { vm.logout(); showSettings = false; showAccount = false; goHome() },
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
                        FilledTonalIconButton(onClick = { showAccount = false; openProfile(key) }, modifier = Modifier.size(RoundControlSize)) { Icon(Icons.Default.Person, "View your profile") }
                        IconButton(onClick = { vm.logout(); showAccount = false; goHome() }) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Disconnect account") }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                        FilledTonalIconButton(onClick = { showAccount = false; showSettings = true }, modifier = Modifier.size(RoundControlSize)) {
                            Icon(Icons.Default.Settings, "Settings", Modifier.size(28.dp))
                        }
                        FilledTonalIconButton(onClick = {
                            try { loginLauncher.launch(ExternalSignerLogin.createIntent(emptyList())) }
                            catch (_: Exception) { error = "Install an Android signer such as Amber first." }
                        }, modifier = Modifier.size(RoundControlSize)) {
                            Icon(painterResource(R.drawable.login), "Log in with an Android signer", Modifier.size(28.dp))
                        }
                    }
                }
                error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zapstore.dev/apps/com.greenart7c3.nostrsigner"))) }) {
                        Text("Get Amber")
                    }
                }
            }
        }
    }
    }
    }
}

@Composable
internal fun Avatar(name: String, size: Int = 36, picture: String? = null, openImage: Boolean = false) {
    val openGallery = LocalOpenGallery.current
    Box(Modifier.size(size.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
        RemoteImage(picture, Modifier.fillMaxSize().clip(CircleShape).then(if (openImage && picture != null) Modifier.clickable(onClickLabel = "Open profile picture") { openGallery(listOf(picture), 0) } else Modifier), avatar = true)
    }
}

@Composable
private fun RepostAttribution(repost: Event, state: FeedState, onProfile: () -> Unit) {
    Row(Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // Compact visual row; Compose expands the touch target to 48 dp. The
        // 12 dp gap below keeps that expansion clear of the author's avatar.
        Box(Modifier.width(48.dp).height(24.dp).clickable(onClickLabel = "View reposting profile", onClick = onProfile),
            contentAlignment = Alignment.CenterStart) {
            Icon(painterResource(R.drawable.repost), "Repost", Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(state.profiles[repost.pubKey]?.name ?: FeatherRepository.shortKey(repost.pubKey),
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun RepostRow(repost: Event, state: FeedState, onReference: (NostrContent.Reference) -> Unit,
    onProfile: (String) -> Unit, onOpen: (Event) -> Unit) {
    if (LocalContentFilter.current.hides(repost)) return
    val target = remember(repost.id) { repostTarget(repost) } ?: return
    val original = state.embedded[target.id]?.takeIf { it.kind == 1 }
    if (original != null) {
        NoteRow(original, state, onReference, { onProfile(original.pubKey) }, { onOpen(original) }, repost = repost)
    } else {
        Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Read conversation") { onReference(target) }.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            RepostAttribution(repost, state) { onProfile(repost.pubKey) }
            EmbeddedNote(target, null, state.profiles, repost.id, onReference)
        }
    }
}

@Composable
private fun NoteRow(note: Event, state: FeedState, onReference: (NostrContent.Reference) -> Unit, onProfile: () -> Unit, onOpen: () -> Unit, compact: Boolean = true, repost: Event? = null) {
    if (LocalContentFilter.current.hides(note)) return
    if (note.kind in listOf(9735, 9321)) {
        ZapActivityCard(note, state, onReference)
        return
    }
    val profile = state.profiles[note.pubKey]
    if (note.kind == 30023 && compact) {
        ArticleListRow(note, profile?.name ?: FeatherRepository.shortKey(note.pubKey), onProfile, onOpen)
        return
    }
    val context = LocalContext.current
    var expanded by rememberSaveable(note.id) { mutableStateOf(false) }
    var truncated by remember(note.id) { mutableStateOf(false) }
    val name = profile?.name ?: FeatherRepository.shortKey(note.pubKey)
    Column(Modifier.fillMaxWidth().clickable(onClickLabel = "Read conversation", onClick = onOpen).padding(horizontal = 24.dp, vertical = 24.dp)) {
        if (repost != null) {
            RepostAttribution(repost, state) { onReference(NostrContent.Reference(repost.pubKey, profile = true)) }
            Spacer(Modifier.height(12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onProfile, modifier = Modifier.size(48.dp).semantics { contentDescription = "Open $name's profile" }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                        Avatar(name, picture = profile?.picture)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            NoteTimestamp(note)
        }
        ReplyContext(note, state.embedded, state.profiles, onReference)
        Spacer(Modifier.height(12.dp))
        when (note.kind) {
            30030 -> EmojiPackContent(note)
            30023 -> ArticleContent(note, compact, onReference)
            9802 -> HighlightContent(note, state.profiles, compact, onReference)
            7 -> {
                ReactionContent(note)
                reactionTarget(note)?.let { reference ->
                    Spacer(Modifier.height(12.dp))
                    EmbeddedNote(reference, state.embedded[reference.id], state.profiles, "${repost?.id ?: note.id}:${reference.id}", onReference, compact)
                }
            }
            else -> {
        val detectedTypes by DetectedMedia.types.collectAsStateWithLifecycle()
        val attachments by produceState<List<MediaContent.Attachment>>(emptyList(), note.id, detectedTypes) {
            value = withContext(Dispatchers.Default) { MediaContent.attachments(note, if (note.kind == 20) 32 else 4) }
        }
        val body = remember(note.id, attachments) { MediaContent.text(note, attachments) }
        val quotes = remember(note.id) { NostrContent.quotes(note) }
        val content = richNoteText(body, note, state.profiles, onReference, quotes.map { it.id }.toSet())
        if (content.isNotBlank()) SelectionContainer { Text(content, style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp), maxLines = if (expanded || !compact) Int.MAX_VALUE else 12, overflow = TextOverflow.Ellipsis, onTextLayout = { if (!expanded) truncated = it.hasVisualOverflow }) }
        quotes.forEach { reference ->
            Spacer(Modifier.height(12.dp))
            EmbeddedNote(reference, state.embedded[reference.id], state.profiles, "${repost?.id ?: note.id}:${reference.id}", onReference, compact)
        }
        if (expanded || truncated) IconButton(onClick = { expanded = !expanded }, modifier = Modifier.align(Alignment.CenterHorizontally)) { Icon(if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, if (expanded) "Show less" else "Read more") }
        if (attachments.isNotEmpty()) { Spacer(Modifier.height(12.dp)); NoteMedia(note, attachments, repost?.id ?: note.id) }
        LinkPreviews(body)
            }
        }
        if (note.kind !in listOf(7, 30023, 30030)) Row(verticalAlignment = Alignment.CenterVertically) {
            val counts = state.engagement[note.id] ?: Engagement()
            Row(Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp).clip(CircleShape).clickable(onClickLabel = "Read conversation", onClick = onOpen).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.conversation), "${counts.replies} replies", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (counts.replies > 0) Text(counts.replies.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OrangeHeart(note, counts.reactions, state.sentReactions[note.id].orEmpty())
            NoteZap(note, counts, state.publicKey)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://njump.to/${com.vitorpamplona.quartz.nip19Bech32.entities.NNote.create(note.id)}") }, "Share note")) }) { Icon(Icons.Default.Share, "Share note", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** Parent navigation is separate from the author's profile and never blocks the reply body. */
@Composable
private fun ReplyContext(note: Event, embedded: Map<String, Event>, profiles: Map<String, Profile>,
    onReference: (NostrContent.Reference) -> Unit) {
    val reference = remember(note.id) { NostrContent.replyReference(note) } ?: return
    val parent = embedded[reference.id]
    val filter = LocalContentFilter.current
    if (filter.hides(reference) || (parent != null && filter.hides(parent))) return
    val author = parent?.pubKey ?: reference.author
    val name = profiles[author]?.name ?: FeatherRepository.shortKey(author ?: reference.id)
    val preview = remember(parent?.id) {
        parent?.content?.take(240)?.withoutObjectMarkers()?.replace('\n', ' ')?.trim().orEmpty()
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .clickable(onClickLabel = "Open parent note") { onReference(reference) },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(R.drawable.reply), "Reply to $name", Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.primary)
        Text(if (preview.isEmpty()) name else "$name · $preview", maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Text and links stay selectable; mention labels update when metadata arrives. */
@Composable
private fun richNoteText(body: String, note: Event, profiles: Map<String, Profile>, onReference: (NostrContent.Reference) -> Unit,
    embeddedIds: Set<String> = emptySet()): androidx.compose.ui.text.AnnotatedString {
    val tokens = remember(body, note.id) { NostrContent.tokens(body, note) }
    return richText(body, tokens, profiles, onReference, embeddedIds)
}

@Composable
private fun richText(body: String, tokens: List<NostrContent.Token>, profiles: Map<String, Profile>,
    onReference: (NostrContent.Reference) -> Unit, embeddedIds: Set<String> = emptySet()): androidx.compose.ui.text.AnnotatedString {
    val open by rememberUpdatedState(onReference)
    val context = LocalContext.current
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(body, tokens, profiles, embeddedIds, linkColor, context) {
        buildAnnotatedString {
            var offset = 0
            tokens.forEach { token ->
                append(body.substring(offset, token.start))
                val ref = token.reference
                if (ref != null) {
                    if (ref.id !in embeddedIds || ref.profile) {
                        withLink(LinkAnnotation.Clickable(token.text, TextLinkStyles(style = SpanStyle(color = linkColor))) { open(ref) }) {
                            append(if (ref.hashtag != null) token.text else if (ref.profile) "@${profiles[ref.id]?.name ?: FeatherRepository.shortKey(ref.id)}" else "↗ ${FeatherRepository.shortKey(ref.id)}")
                        }
                    }
                } else if (token.url != null) {
                    withLink(LinkAnnotation.Url(token.url, TextLinkStyles(style = SpanStyle(color = linkColor))) { context.openWebLink(token.url) }) { append(token.text) }
                }
                offset = token.end
            }
            append(body.substring(offset))
        }
    }
}

@Composable
internal fun EmbeddedNote(reference: NostrContent.Reference, note: Event?, profiles: Map<String, Profile>, mediaKey: String, onReference: (NostrContent.Reference) -> Unit, compact: Boolean = true) {
    val filter = LocalContentFilter.current
    if (filter.hides(reference) || (note != null && filter.hides(note))) return
    Surface(onClick = { onReference(reference) }, shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth(), tonalElevation = 0.dp) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (note == null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (reference.kind == 30023) {
                        var waiting by remember(reference.id) { mutableStateOf(true) }
                        LaunchedEffect(reference.id) { kotlinx.coroutines.delay(12_000); waiting = false }
                        if (waiting) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(painterResource(R.drawable.article), "Article unavailable locally; open externally", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(profiles[reference.author]?.name ?: FeatherRepository.shortKey(reference.author ?: reference.id),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    } else {
                        Icon(painterResource(R.drawable.conversation), "Open referenced note", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                        Text(FeatherRepository.shortKey(reference.id), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            } else {
                val profile = profiles[note.pubKey]
                val name = profile?.name ?: FeatherRepository.shortKey(note.pubKey)
                Row(Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(
                        onClick = { onReference(NostrContent.Reference(note.pubKey, profile = true)) },
                        modifier = Modifier.size(48.dp).semantics { contentDescription = "Open $name's profile" },
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterStart) {
                            Avatar(name, 28, profile?.picture)
                        }
                    }
                    Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (note.kind == 30030) EmojiPackContent(note)
                else if (note.kind == 30023) ArticleContent(note, compact, onReference)
                else if (note.kind == 9802) HighlightContent(note, profiles, compact, onReference)
                else {
                val detectedTypes by DetectedMedia.types.collectAsStateWithLifecycle()
                val attachments by produceState<List<MediaContent.Attachment>>(emptyList(), note.id, compact, detectedTypes) {
                    value = withContext(Dispatchers.Default) { MediaContent.attachments(note, limit = if (compact) 1 else 32) }
                }
                val body = remember(note.id, attachments) { MediaContent.text(note, attachments) }
                val text = richNoteText(body, note, profiles, onReference)
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 23.sp), maxLines = if (compact) 8 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
                // One level only. Deeper references remain links, avoiding recursive network/render work.
                if (attachments.isNotEmpty()) NoteMedia(note, attachments, mediaKey)
                LinkPreviews(body, limit = 1)
                }
            }
        }
    }
}

@Composable
private fun Settings(state: FeedState, onLogout: () -> Unit, onWallet: () -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val mediaStore = (context.applicationContext as FeatherApplication).media
    val budget by mediaStore.budgetMb.collectAsStateWithLifecycle()
    val mediaError by mediaStore.error.collectAsStateWithLifecycle()
    val resources by mediaStore.resources.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val usedBytes by produceState<Long?>(null, resources, lifecycle) {
        value = null
        if (resources != null) lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = mediaStore.usedBytes()
                kotlinx.coroutines.delay(2_000)
            }
        }
    }
    var selectedBudget by remember(budget) { mutableIntStateOf(MediaContent.cacheOptionsMb.indexOf(budget)) }
    val scope = rememberCoroutineScope()
    Column(modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        state.publicKey?.let { key ->
            val profile = state.profiles[key]
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Avatar(profile?.name ?: FeatherRepository.shortKey(key), 40, profile?.picture)
                Text(profile?.name ?: FeatherRepository.shortKey(key), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                FilledTonalIconButton(onClick = onWallet, modifier = Modifier.size(RoundControlSize)) { Icon(painterResource(R.drawable.wallet), "Wallet · 21 sats per zap") }
                FilledTonalIconButton(onClick = onLogout, modifier = Modifier.size(RoundControlSize)) { Icon(Icons.AutoMirrored.Filled.ExitToApp, "Log out") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.sensitive_content), null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.weight(1f))
            Switch(checked = state.showSensitive, onCheckedChange = { show ->
                scope.launch {
                    runCatching { (context.applicationContext as FeatherApplication).repository.showSensitive(show) }
                        .onFailure { android.widget.Toast.makeText(context, "Could not save content setting", android.widget.Toast.LENGTH_LONG).show() }
                }
            }, modifier = Modifier.semantics { contentDescription = "Show NSFW and content-warning tagged content" })
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.storage), "Media cache storage", tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(16.dp))
                val mb = MediaContent.cacheOptionsMb[selectedBudget]
                val limit = if (mb >= 1024) "${mb / 1024} GB" else "$mb MB"
                val used = usedBytes?.let(::cacheSizeLabel) ?: "—"
                Text(used, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).semantics { contentDescription = "$used media cache used" })
                if (resources == null) { Spacer(Modifier.width(16.dp)); CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                Spacer(Modifier.width(16.dp))
                Text(limit, style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.semantics { contentDescription = "$limit media cache limit" })
            }
            Slider(value = selectedBudget.toFloat(), onValueChange = { selectedBudget = it.toInt() }, valueRange = 0f..4f, steps = 3,
                modifier = Modifier.semantics { contentDescription = "Media cache storage limit"; stateDescription = "${MediaContent.cacheOptionsMb[selectedBudget]} megabytes" },
                onValueChangeFinished = { scope.launch { mediaStore.setBudget(MediaContent.cacheOptionsMb[selectedBudget]) } })
            mediaError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dergigi/feather"))) }) {
                Icon(painterResource(R.drawable.github), "Feather on GitHub, source code and feedback", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dergigi/feather/releases/tag/v${BuildConfig.VERSION_NAME}")))
                }.padding(horizontal = 12.dp, vertical = 16.dp).semantics { contentDescription = "Version ${BuildConfig.VERSION_NAME}, open GitHub release" })
            Text("·", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(BuildConfig.GIT_COMMIT.take(7), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dergigi/feather/commit/${BuildConfig.GIT_COMMIT}")))
                }.padding(horizontal = 12.dp, vertical = 16.dp).semantics { contentDescription = "Build commit ${BuildConfig.GIT_COMMIT.take(7)}, open GitHub commit" })
        }
    }
}

/** Keep quoted events in their place in the biography; tokenization is done by the repository. */
@Composable
private fun ProfileBiography(profileKey: String, profile: Profile, state: FeedState, onReference: (NostrContent.Reference) -> Unit) {
    var expanded by rememberSaveable(profileKey, profile.about) { mutableStateOf(false) }
    var overflows by remember(profileKey, profile.about) { mutableStateOf(false) }
    val quotes = remember(profile.aboutTokens) {
        profile.aboutTokens.filter { it.reference?.let { ref -> !ref.profile && ref.hashtag == null && ref.external == null } == true }
            .distinctBy { it.reference?.id }.take(3)
    }
    fun textRange(start: Int, end: Int): Pair<String, List<NostrContent.Token>> {
        val tokens = profile.aboutTokens.filter { it.start >= start && it.end <= end }
            .map { it.copy(start = it.start - start, end = it.end - start) }
        return profile.about.substring(start, end) to tokens
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (!expanded) {
            SelectionContainer {
                Text(
                    richText(profile.about, profile.aboutTokens, state.profiles, onReference),
                    style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    onTextLayout = { overflows = it.hasVisualOverflow },
                )
            }
        } else {
            quotes.forEachIndexed { index, token ->
                val (body, tokens) = textRange(if (index == 0) 0 else quotes[index - 1].end, token.start)
                if (body.isNotBlank()) SelectionContainer {
                    Text(richText(body, tokens, state.profiles, onReference), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
                }
                val reference = token.reference!!
                EmbeddedNote(reference, state.embedded[reference.id], state.profiles, "bio:${reference.id}", onReference)
            }
            val (body, tokens) = textRange(quotes.lastOrNull()?.end ?: 0, profile.about.length)
            if (body.isNotBlank()) SelectionContainer {
                Text(richText(body, tokens, state.profiles, onReference), style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 26.sp))
            }
        }
        // Event cards stay out of the compact preview, even when their link fits on one line.
        if (expanded || overflows || quotes.isNotEmpty()) {
            IconButton(
                onClick = { expanded = !expanded },
                modifier = Modifier.align(Alignment.CenterHorizontally).semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" },
            ) {
                Icon(
                    if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    if (expanded) "Collapse biography" else "Expand biography",
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

private fun cacheSizeLabel(bytes: Long): String = when {
    bytes == 0L -> "0 MB"
    bytes < 1024L * 1024 -> "<1 MB"
    bytes < 1024L * 1024 * 1024 -> "${bytes / (1024L * 1024)} MB"
    else -> String.format(java.util.Locale.getDefault(), "%.1f GB", bytes.toDouble() / (1024L * 1024 * 1024))
}
