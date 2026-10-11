package org.dergigi.feather

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first

/** Native, lazy full-screen pages. Only the settled page owns live reading work. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HomeFeedPages(vm: FeatherViewModel, navigation: ReaderNavigation, home: ReaderEntry) {
    val state by vm.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val mediaStore = (LocalContext.current.applicationContext as FeatherApplication).media
    val resources by mediaStore.resources.collectAsStateWithLifecycle()
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    var accountRequested by remember { mutableStateOf(false) }
    var tabs by remember(home.key) {
        mutableStateOf(home.tabs.ifEmpty { listOf(ProfileTab.POSTS, ProfileTab.REPLIES) })
    }
    val pager = rememberPagerState(initialPage = tabs.indexOf(home.tab).coerceAtLeast(0), pageCount = { tabs.size })
    var reordering by remember(home.key) { mutableStateOf(false) }
    val currentTabs by rememberUpdatedState(tabs)
    // Keep discovered pages available for the visit. Reorder only at rest and keep the
    // selected category anchored, rather than changing content underneath the user.
    LaunchedEffect(state.feedTabs) {
        snapshotFlow { pager.isScrollInProgress }.first { !it }
        run {
            val next = (state.feedTabs + tabs).distinct()
            if (next != tabs) {
                val selected = home.tab
                reordering = true
                tabs = next
                home.tabs = next
                try { pager.scrollToPage(next.indexOf(selected).coerceAtLeast(0)) }
                finally { reordering = false }
            }
        }
    }
    LaunchedEffect(pager) {
        snapshotFlow { Triple(pager.settledPage, pager.isScrollInProgress, reordering) }
            .distinctUntilChanged().collect { (page, scrolling, changingOrder) ->
                if (!scrolling && !changingOrder) currentTabs.getOrNull(page)?.let { home.tab = it }
            }
    }
    Box(Modifier.fillMaxSize()) {
    HorizontalPager(
        state = pager,
        key = { tabs[it].name },
        beyondViewportPageCount = 0,
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "${home.tab.label} feed" },
    ) { page ->
        val category = tabs[page]
        FeatherReader(vm, navigation, home.feedPage(category),
            activePage = category == home.tab, homeToolbar = home,
            accountRequested = accountRequested && category == home.tab,
            onAccountHandled = { accountRequested = false })
    }
    // Swipes temporarily reveal only the pill, without changing vertical-scroll visibility.
    AnimatedVisibility(home.toolbar || (pager.isScrollInProgress && !reordering), enter = expandVertically(tween(180)), exit = shrinkVertically(tween(180))) {
        val buttonSurface = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = 0.94f), CircleShape)
        CenterAlignedTopAppBar(
            title = {
                BoxWithConstraints(Modifier.widthIn(max = 240.dp).fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.82f))) {
                    val slotWidth = maxWidth / 5
                    val center = pager.currentPage
                    // Include the entering icons outside either edge. Stable keys and the
                    // continuous page position avoid a jump when currentPage changes halfway.
                    for (index in (center - 3)..(center + 3)) {
                        val category = tabs.getOrNull(index) ?: continue
                        key(category) {
                            IconButton(onClick = {
                                if (!reordering) scope.launch { pager.animateScrollToPage(index) }
                            }, modifier = Modifier.offset {
                                val position = pager.currentPage + pager.currentPageOffsetFraction
                                IntOffset(((2f + index - position) * slotWidth.toPx()).roundToInt(), 0)
                            }.width(slotWidth).height(48.dp).semantics {
                                contentDescription = "${category.label} feed"
                                selected = index == pager.currentPage
                            }) {
                                FeedCategoryIcon(category, Modifier.size(24.dp).graphicsLayer {
                                    val distance = abs(index - pager.currentPage - pager.currentPageOffsetFraction)
                                    alpha = when {
                                        distance <= 1f -> 1f - 0.58f * distance
                                        distance <= 2f -> 0.42f - 0.26f * (distance - 1f)
                                        else -> (0.16f * (3f - distance)).coerceIn(0f, 0.16f)
                                    }
                                    val scale = 1f - distance.coerceIn(0f, 1f) / 6f
                                    scaleX = scale
                                    scaleY = scale
                                })
                            }
                        }
                    }
                }
            },
            navigationIcon = {
                if (home.toolbar) IconButton(onClick = {
                    scope.launch {
                        val list = home.feedPage(home.tab).list
                        if (list.firstVisibleItemIndex > 20) list.scrollToItem(0) else list.animateScrollToItem(0)
                        home.toolbar = true
                    }
                }, modifier = buttonSurface) {
                    Icon(painterResource(R.drawable.feather), "Scroll feed to top", Modifier.size(32.dp), tint = Color.Unspecified)
                } else Spacer(Modifier.size(48.dp))
            },
            actions = {
                if (home.toolbar) IconButton(onClick = {
                    val key = state.publicKey
                    if (key == null) accountRequested = true
                    else navigation.push(ReaderEntry("profile", key))
                }, modifier = (if (state.publicKey != null) Modifier else buttonSurface).semantics { contentDescription = "Account" }) {
                    val account = state.profiles[state.publicKey]
                    if (state.publicKey != null) {
                        CompositionLocalProvider(LocalMedia provides MediaEnvironment(resources,
                            lifecycleState.isAtLeast(Lifecycle.State.STARTED), null)) {
                            Avatar(account?.name ?: FeatherRepository.shortKey(state.publicKey!!), 36, account?.picture)
                        }
                    } else Icon(Icons.Default.Person, null, Modifier.size(22.dp))
                } else Spacer(Modifier.size(48.dp))
            },
            expandedHeight = 64.dp,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent,
                titleContentColor = MaterialTheme.colorScheme.primary,
                actionIconContentColor = MaterialTheme.colorScheme.primary),
        )
    }
    }
}

/** Shared icon vocabulary for profile tabs and the quieter home-feed selector. */
@Composable
internal fun FeedCategoryIcon(category: ProfileTab, modifier: Modifier = Modifier.size(24.dp)) {
    val resource = when (category) {
        ProfileTab.REPLIES -> R.drawable.conversation
        ProfileTab.REPOSTS -> R.drawable.repost
        ProfileTab.HIGHLIGHTS -> R.drawable.highlighter
        ProfileTab.PHOTOS -> R.drawable.photos
        ProfileTab.BOOKMARKS -> R.drawable.bookmark
        ProfileTab.ARTICLES -> R.drawable.article
        ProfileTab.PEOPLE -> R.drawable.people
        ProfileTab.PROJECTS -> R.drawable.project
        ProfileTab.APPS -> R.drawable.apps
        else -> null
    }
    if (resource != null) Icon(painterResource(resource), category.label, modifier)
    else Icon(when (category) {
        ProfileTab.REACTIONS -> Icons.Default.FavoriteBorder
        ProfileTab.VIDEOS -> Icons.Default.PlayArrow
        else -> Icons.Default.Menu
    }, category.label, modifier)
}
