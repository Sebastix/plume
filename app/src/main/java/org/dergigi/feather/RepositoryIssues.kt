package org.dergigi.feather

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.catch
import com.vitorpamplona.quartz.nip01Core.core.Event

internal data class RepositoryIssueSnapshot(
    val issues: List<Event> = emptyList(),
    val statuses: Map<String, Int> = emptyMap(),
    val relays: List<String> = emptyList(),
    val loading: Boolean = true,
    val failed: Boolean = false,
    val more: Boolean = false,
)

internal val LocalOpenRepositoryIssues = staticCompositionLocalOf<(String, List<String>) -> Unit> { { _, _ -> } }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RepositoryIssuesScreen(vm: FeatherViewModel, navigation: ReaderNavigation, entry: ReaderEntry) {
    val address = entry.target ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val published by vm.issues.published.collectAsStateWithLifecycle()
    val requests = remember(address, entry.issueLimit, entry.refreshRequested, published) {
        vm.repo.repositoryIssues(address, entry.reference?.relays.orEmpty(), entry.issueLimit).catch {
            emit((entry.repositoryIssues ?: RepositoryIssueSnapshot()).copy(loading = false, failed = true))
        }
    }
    val snapshot by requests.collectAsStateWithLifecycle(initialValue = entry.repositoryIssues ?: RepositoryIssueSnapshot())
    SideEffect { entry.repositoryIssues = snapshot }
    BackHandler { navigation.back() }
    val filter = ContentFilter(state.mutedAuthors, state.embedded, state.deletedReactions, state.showSensitive, state.deletedNotes, state.mutedWords)
    val issues = snapshot.issues.filterNot(filter::hides).filter {
        val status = snapshot.statuses[it.id] ?: 1630
        when (entry.issueFilter) { 1 -> status in listOf(1630, 1633); 2 -> status in listOf(1631, 1632); else -> true }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(address.substringAfter(':').substringAfter(':'), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = { navigation.back() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.ui_back))
                } },
                actions = {
                    IconButton(onClick = { entry.refreshRequested++ }) { Icon(Icons.Default.Refresh, stringResource(R.string.ui_refresh_issues)) }
                    IconButton(onClick = { vm.issues.open(IssueType.BUG, repository = address, relays = snapshot.relays) }) {
                        Icon(painterResource(R.drawable.bug_report), stringResource(R.string.ui_report_a_bug))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(R.string.ui_issues_all, R.string.ui_issues_open, R.string.ui_issues_closed).forEachIndexed { index, label ->
                    FilterChip(selected = entry.issueFilter == index, onClick = { entry.issueFilter = index },
                        label = { Text(stringResource(label)) })
                }
            }
            LazyColumn(state = entry.list, modifier = Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp)) {
                items(issues, key = { it.id }) { issue ->
                    Column(Modifier.fillMaxWidth().clickable {
                        navigation.push(ReaderEntry("thread", issue.id).also {
                            it.reference = NostrContent.Reference(issue.id, author = issue.pubKey, kind = 1621, relays = snapshot.relays.take(2))
                        })
                    }.padding(vertical = 16.dp)) {
                        IssueContent(issue, compact = true, onReference = {}, status = snapshot.statuses[issue.id])
                    }
                }
                item {
                    Box(Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                        when {
                            snapshot.loading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                            snapshot.failed -> IconButton(onClick = { entry.refreshRequested++ }) {
                                Icon(Icons.Default.Refresh, stringResource(R.string.ui_retry_issue_loading))
                            }
                            snapshot.more && entry.issueLimit < 600 -> IconButton(onClick = { entry.issueLimit += 100 }) {
                                Icon(Icons.Default.KeyboardArrowDown, stringResource(R.string.ui_load_more_issues))
                            }
                            issues.isEmpty() -> Icon(painterResource(R.drawable.bug_report), stringResource(R.string.ui_no_matching_issues),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .4f))
                        }
                    }
                }
            }
        }
    }
}
