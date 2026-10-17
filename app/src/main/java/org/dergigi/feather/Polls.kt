package org.dergigi.feather

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.delay

internal data class Poll(val options: List<Pair<String, String>>, val multiple: Boolean, val endsAt: Long?, val relays: List<String>)
data class PollResults(val counts: Map<String, Int> = emptyMap(), val voters: Int = 0, val selected: Set<String> = emptySet(), val limited: Boolean = false)
internal fun parsePoll(event: Event): Poll? {
    if (event.kind != 1068) return null
    val options = event.tags.filter { it.firstOrNull() == "option" }.mapNotNull {
        val id = it.getOrNull(1) ?: return@mapNotNull null
        val label = it.getOrNull(2)?.takeIf(String::isNotBlank) ?: return@mapNotNull null
        // Option IDs are opaque references: Primal uses UUIDs with hyphens.
        // Preserve the exact ID in response tags rather than normalizing it.
        if (id.isBlank() || id.length > 256 || id.any(Char::isISOControl)) return@mapNotNull null
        id to label
    }.distinctBy { it.first }
    if (options.size !in 2..64) return null
    val type = event.tagValue("polltype") ?: "singlechoice"
    if (type !in listOf("singlechoice", "multiplechoice")) return null
    val end = event.tagValue("endsAt")
    if (end != null && end.toLongOrNull() == null) return null
    return Poll(options, type == "multiplechoice", end?.toLongOrNull(), event.tags.filter { it.firstOrNull() == "relay" }
        .mapNotNull { it.getOrNull(1)?.let(Protocol::relay) }.distinct().take(8))
}

internal fun tallyPoll(event: Event, responses: List<Event>, account: String?): PollResults {
    val poll = parsePoll(event) ?: return PollResults()
    val ids = poll.options.map { it.first }.toSet()
    val latest = responses.filter { it.kind == 1018 && it.tagValue("e") == event.id && it.createdAt >= event.createdAt &&
        (poll.endsAt == null || it.createdAt <= poll.endsAt) }
        .sortedWith(compareByDescending<Event> { it.createdAt }.thenBy { it.id }).distinctBy { it.pubKey }
    val votes = latest.associate { response ->
        val selections = response.tags.filter { it.firstOrNull() == "response" }
        val relevant = if (poll.multiple) selections else selections.take(1)
        response.pubKey to relevant.mapNotNull { it.getOrNull(1)?.takeIf(ids::contains) }.toSet()
    }
    return PollResults(ids.associateWith { id -> votes.values.count { id in it } }, votes.values.count { it.isNotEmpty() },
        votes[account].orEmpty(), responses.size >= 2000)
}

internal data class PollActions(val vote: (Event, Set<String>) -> Unit = { _, _ -> })
internal val LocalPollActions = staticCompositionLocalOf { PollActions() }

@Composable
internal fun PollContent(event: Event, state: FeedState) {
    val poll = remember(event.id) { parsePoll(event) }
    val results = state.pollResults[event.id] ?: PollResults()
    val actions = LocalPollActions.current
    var selected by remember(event.id, state.publicKey, results.selected) { mutableStateOf(results.selected) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
    LaunchedEffect(event.id) {
        val end = poll?.endsAt ?: return@LaunchedEffect
        while (now < end) { delay(1000); now = System.currentTimeMillis() / 1000 }
    }
    val closed = poll?.endsAt?.let { now >= it } == true
    val busy = event.id in state.pendingVotes
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(event.content, style = MaterialTheme.typography.bodyLarge)
        if (poll == null) {
            Text(stringResource(R.string.ui_poll_unavailable), color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            poll.options.forEach { (id, label) ->
                val count = results.counts[id] ?: 0
                val fraction = if (results.voters == 0) 0f else count.toFloat() / results.voters
                Surface(onClick = {
                    if (state.publicKey == null) actions.vote(event, setOf(id))
                    else selected = if (!poll.multiple) setOf(id) else if (id in selected) selected - id else selected + id
                }, enabled = !closed && !busy, shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
                    Box {
                        Box(Modifier.matchParentSize().fillMaxWidth()) {
                            Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(MaterialTheme.colorScheme.primary.copy(alpha = .16f)))
                        }
                        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            if (id in selected) Icon(Icons.Default.Check, stringResource(R.string.ui_poll_selected), Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                            Text("${(fraction * 100).toInt()}%", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(if (results.limited) R.string.ui_poll_votes_limited else R.string.ui_poll_votes, results.voters),
                    Modifier.weight(1f), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (closed) Text(stringResource(R.string.ui_poll_closed), style = MaterialTheme.typography.labelSmall)
                else if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                else FilledIconButton(onClick = { actions.vote(event, selected) }, enabled = selected.isNotEmpty() && selected != results.selected) {
                    Icon(Icons.Default.Check, stringResource(R.string.ui_poll_vote))
                }
            }
        }
    }
}
