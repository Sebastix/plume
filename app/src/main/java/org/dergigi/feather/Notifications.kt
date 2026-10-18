package org.dergigi.feather

import androidx.compose.ui.res.stringResource

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.delay
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withContext

internal val notificationKinds = listOf(1, 1111, 1244, 6, 16, 7, 9735, 9321)
internal fun notificationTarget(event: Event): NostrContent.Reference? = when (event.kind) {
    6, 16 -> repostTarget(event)
    7 -> reactionTarget(event)
    9735, 9321 -> zapNoteReference(event)
    else -> NostrContent.Reference(event.id, author = event.pubKey, kind = event.kind)
}
private enum class NotificationType(val label: String) {
    ALL("Clear notification filters"), REPLIES("Replies"), REACTIONS("Reactions"),
    ZAPS("Zaps and nutzaps"), REPOSTS("Reposts"), MENTIONS("Mentions");

    fun includes(event: Event): Boolean = when (this) {
        ALL -> true
        REPLIES -> event.kind in listOf(1, 1111, 1244) && Protocol.isReply(event)
        REACTIONS -> event.kind == 7
        ZAPS -> event.kind in listOf(9735, 9321)
        REPOSTS -> event.kind in listOf(6, 16)
        MENTIONS -> event.kind in listOf(1, 1111, 1244) && !Protocol.isReply(event)
    }
}

internal data class NotificationPreview(val event: Event, val actor: String?, val text: String,
    val action: String, val reaction: ReactionChoice? = null, val amount: String? = null)

internal data class NotificationSnapshot(
    val owner: String,
    val types: Int,
    val previews: List<NotificationPreview>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun NotificationsPage(vm: FeatherViewModel, navigation: ReaderNavigation, entry: ReaderEntry, home: ReaderEntry, active: Boolean) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = state.publicKey
    if (owner == null) {
        LaunchedEffect(active) { if (active) home.toolbar = true }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(32.dp),
            verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Notifications, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ui_notifications_sign_in), style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        return
    }
    // Zero means all; each category otherwise toggles independently.
    val savedFilters by vm.notificationFilters.collectAsStateWithLifecycle()
    val selection = savedFilters
    if (selection == null || selection.first != owner) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
        return
    }
    val selectedTypes = selection.second
    val includedTypes = remember(selectedTypes) {
        NotificationType.entries.filter { it != NotificationType.ALL && selectedTypes and (1 shl it.ordinal) != 0 }
    }
    val events = state.notifications.takeIf { state.notificationsFor == owner }.orEmpty()
    val filter = remember(state.mutedAuthors, state.embedded, state.showSensitive, state.deletedReactions, state.deletedNotes) {
        ContentFilter(state.mutedAuthors, state.embedded, state.deletedReactions, state.showSensitive, state.deletedNotes)
    }
    // Keep rows alongside the retained LazyListState. Mounting a temporary empty list
    // on Back would clamp its saved index before the background previews are ready.
    val snapshot by produceState<NotificationSnapshot?>(
        entry.notifications?.takeIf { it.owner == owner && it.types == selectedTypes },
        owner, events, state.embedded, filter, selectedTypes,
    ) {
        val previousRows = entry.notifications?.takeIf { it.owner == owner }?.previews.orEmpty().associateBy { it.event.id }
        val rows = withContext(Dispatchers.Default) {
            events.filter { event -> (includedTypes.isEmpty() || includedTypes.any { it.includes(event) }) && !filter.hides(event) }.mapNotNull { event ->
                val target = notificationTarget(event)
                if (target != null && filter.hides(target)) return@mapNotNull null
                val payment = if (event.kind in listOf(9735, 9321)) zapActivity(event) ?: return@mapNotNull null else null
                val actor = if (payment != null) payment.sender else event.pubKey
                if (actor in state.mutedAuthors) return@mapNotNull null
                val source = if (event.kind in listOf(1, 1111, 1244)) event else state.embedded[target?.id]
                val text = payment?.comment?.takeIf { it.isNotBlank() }
                    ?: source?.let { it.tagValue("title") ?: MediaContent.text(it, MediaContent.attachments(it, 32)) }
                    ?: previousRows[event.id]?.text.orEmpty()
                NotificationPreview(event, actor, text.withoutObjectMarkers().take(400), when(event.kind) {
                    6, 16 -> "Repost"; 7 -> "Reaction"; 9735 -> "Zap"; 9321 -> "Nutzap"
                    else -> if (Protocol.isReply(event)) "Reply" else "Mention"
                }, if (event.kind == 7) reactionChoice(event) else null,
                    payment?.amount?.let { "$it ${payment.unit}" })
            }
        }
        val updated = NotificationSnapshot(owner, selectedTypes, rows)
        entry.notifications = updated
        value = updated
    }
    val readySnapshot = snapshot?.takeIf { it.owner == owner && it.types == selectedTypes }
    val previews = readySnapshot?.previews.orEmpty()
    var previousTypes by remember { mutableIntStateOf(selectedTypes) }
    LaunchedEffect(selectedTypes) {
        if (previousTypes != selectedTypes) entry.list.scrollToItem(0)
        previousTypes = selectedTypes
    }
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(active, message) { if (active) message?.let { AppMessages.show(it); vm.message.compareAndSet(it, null) } }
    LaunchedEffect(active, owner) {
        if (active) {
            vm.accountSettings.retry()
            vm.repo.openReference(null); vm.repo.viewProfile(null); vm.repo.viewThread(null); vm.repo.viewHashtag(null)
        }
    }
    val threshold = with(LocalDensity.current) { 32.dp.toPx() }
    val toolbarScroll = remember(entry, home, threshold) {
        object : NestedScrollConnection {
            private var distance = 0f
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (source != NestedScrollSource.UserInput) return Offset.Zero
                if (!entry.list.canScrollBackward) { home.toolbar = true; distance = 0f; return Offset.Zero }
                if (distance * consumed.y < 0f) distance = 0f
                distance += consumed.y
                if (distance <= -threshold) { home.toolbar = false; distance = 0f }
                else if (distance >= threshold) { home.toolbar = true; distance = 0f }
                return Offset.Zero
            }
        }
    }
    val context = LocalContext.current
    val media = (context.applicationContext as FeatherApplication).media
    val resources by media.resources.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val visiblePreviews = remember(previews, filter) { previews.filterNot { filter.hides(it.event) || it.actor in filter.authors } }
    val emptySettled by produceState(false, active, events, visiblePreviews.isEmpty(), state.notificationsLoading) {
        value = false
        if (active && !state.notificationsLoading && visiblePreviews.isEmpty()) { delay(1_500); value = true }
    }
    fun open(reference: NostrContent.Reference) {
        if (filter.hides(reference)) return
        val note = if (reference.profile) null else events.find { it.id == reference.id } ?: state.embedded[reference.id]
        if (reference.external != null && note == null) { context.openWebLink("https://njump.to/${reference.external}"); return }
        val destination = ReaderEntry(if (reference.profile) "profile" else "thread", note?.id ?: reference.id)
        destination.reference = reference
        if (note != null) destination.notes[ProfileTab.POSTS] = listOf(note)
        navigation.push(destination)
        vm.repo.openReference(reference)
        vm.repo.viewProfile(reference.id.takeIf { reference.profile })
        vm.repo.viewThread(note?.id ?: reference.id.takeUnless { reference.profile }, note)
    }
    LaunchedEffect(active, owner, previews) {
        if (active) snapshotFlow { entry.list.layoutInfo.visibleItemsInfo.mapNotNull { it.key as? String } }
            .distinctUntilChanged().collect { vm.repo.readingNotes(it) }
    }
    LaunchedEffect(active, entry.list) {
        if (active) snapshotFlow { entry.list.firstVisibleItemIndex to entry.list.firstVisibleItemScrollOffset }
            .distinctUntilChanged().collect { (index, offset) -> if (index == 0 && offset == 0) home.toolbar = true }
    }
    // Also protect the saved offset when recreating a screen without a retained snapshot.
    if (readySnapshot == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
        return
    }
    CompositionLocalProvider(
        LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        LocalMedia provides MediaEnvironment(resources, active && lifecycle.isAtLeast(Lifecycle.State.STARTED), null),
    ) {
        PullToRefreshBox(isRefreshing = active && state.notificationsLoading,
            onRefresh = { vm.accountSettings.retry(); vm.repo.refreshNotifications() }, modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            LazyColumn(state = entry.list, modifier = Modifier.fillMaxSize().nestedScroll(toolbarScroll),
                contentPadding = PaddingValues(bottom = 144.dp)) {
                stickyHeader("notification-filters") {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Row(Modifier.fillMaxWidth().padding(start = 8.dp, end = 72.dp, top = 8.dp, bottom = 8.dp)
                            .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.SpaceEvenly) {
                            NotificationType.entries.forEach { type ->
                                if (type == NotificationType.ALL) {
                                    OutlinedIconButton(onClick = { vm.setNotificationFilters(0) }, enabled = selectedTypes != 0,
                                        modifier = Modifier.size(48.dp),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        colors = IconButtonDefaults.outlinedIconButtonColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                                            disabledContentColor = MaterialTheme.colorScheme.primary)) {
                                        Icon(Icons.Default.Notifications,
                                            if (selectedTypes == 0) "All notifications" else type.label, Modifier.size(24.dp))
                                    }
                                    return@forEach
                                }
                                IconToggleButton(checked = type in includedTypes,
                                    onCheckedChange = {
                                        vm.setNotificationFilters(selectedTypes xor (1 shl type.ordinal))
                                    },
                                    modifier = Modifier.size(48.dp).semantics { contentDescription = type.label },
                                    colors = IconButtonDefaults.iconToggleButtonColors(
                                        checkedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        checkedContentColor = MaterialTheme.colorScheme.primary,
                                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant)) {
                                    when (type) {
                                        NotificationType.ALL -> Unit
                                        NotificationType.REACTIONS -> Icon(Icons.Default.FavoriteBorder, null)
                                        NotificationType.MENTIONS -> Text("@", style = MaterialTheme.typography.titleLarge)
                                        else -> Icon(painterResource(when(type) {
                                            NotificationType.REPLIES -> R.drawable.conversation
                                            NotificationType.ZAPS -> R.drawable.zap
                                            else -> R.drawable.repost
                                        }), null, Modifier.size(24.dp))
                                    }
                                }
                            }
                        }
                    }
                }
                if (visiblePreviews.isEmpty()) item("notification-status") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        if (state.notificationsLoading || !emptySettled) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        else if (state.error != null) IconButton(onClick = { AppMessages.show(state.error!!) }) {
                            Icon(Icons.Default.Warning, stringResource(R.string.ui_notification_connection_error), tint = MaterialTheme.colorScheme.error)
                        } else Icon(Icons.Default.Notifications, stringResource(R.string.ui_no_notifications), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(visiblePreviews, key = { it.event.id }) { item ->
                    val profile = state.profiles[item.actor]
                    val name = profile?.name ?: item.actor?.let(FeatherRepository::shortKey) ?: "Anonymous"
                    val tint = when(item.event.kind) { 9735 -> Color(0xFFFFB300); 9321 -> Color(0xFFC084FC); else -> MaterialTheme.colorScheme.primary }
                    Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${item.action.lowercase()}") {
                        (notificationTarget(item.event) ?: item.actor?.let { NostrContent.Reference(it, profile = true) })?.let(::open)
                    }.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(Modifier.widthIn(min = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            val reaction = item.reaction
                            if (reaction?.image != null) RemoteImage(reaction.image, Modifier.size(22.dp), "Reaction ${reaction.content}", crop = false)
                            else if (reaction?.content in listOf("", "+")) Icon(Icons.Default.Favorite, item.action, Modifier.size(22.dp), tint = Color(0xFFFF8A00))
                            else if (reaction != null) Text(reaction.display.take(16))
                            else if (item.event.kind == 7) Icon(Icons.Default.FavoriteBorder, item.action, Modifier.size(22.dp), tint = tint)
                            else if (item.action == "Mention") Text("@", color = tint, style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.semantics { contentDescription = "Mention" })
                            else Icon(painterResource(when(item.event.kind) {
                                6, 16 -> R.drawable.repost; 9735, 9321 -> R.drawable.zap; else -> R.drawable.conversation
                            }), item.action, Modifier.size(22.dp), tint = tint)
                        }
                        Box(Modifier.size(40.dp).then(if (item.actor != null) Modifier.clickable(onClickLabel = "Open $name's profile") {
                            open(NostrContent.Reference(item.actor, profile = true))
                        } else Modifier), contentAlignment = Alignment.Center) { Avatar(name, 36, profile?.picture) }
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            item.amount?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = tint) }
                            if (item.text.isNotBlank()) Text(item.text, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        NotificationTimestamp(item.event)
                    }
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
