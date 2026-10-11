package org.dergigi.feather

import androidx.compose.foundation.layout.fillMaxSize
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
@Composable
internal fun HomeFeedPages(vm: FeatherViewModel, navigation: ReaderNavigation, home: ReaderEntry) {
    val state by vm.state.collectAsStateWithLifecycle()
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
    HorizontalPager(
        state = pager,
        key = { tabs[it].name },
        beyondViewportPageCount = 0,
        modifier = Modifier.fillMaxSize().semantics { contentDescription = "${home.tab.label} feed" },
    ) { page ->
        val category = tabs[page]
        FeatherReader(vm, navigation, home.feedPage(category),
            activePage = category == home.tab)
    }
}
