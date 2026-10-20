package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event

internal data class DetailPerson(val pubkey: String?, val amount: String? = null)
internal data class DetailReaction(val sample: Event, val people: List<DetailPerson>)
internal data class NoteDetails(val reposts: List<DetailPerson> = emptyList(), val reactions: List<DetailReaction> = emptyList(),
    val zaps: List<DetailPerson> = emptyList(), val profiles: Map<String, Profile> = emptyMap(), val relays: List<String> = emptyList())

@Composable
internal fun NoteDetailsPanel(note: Event, state: FeedState, onReference: (NostrContent.Reference) -> Unit) {
    val repository = (LocalContext.current.applicationContext as FeatherApplication).repository
    val stream = remember(note.id, repository) { repository.noteDetails(note) }
    val details by stream.collectAsState(initial = null)
    val data = details
    val clipboard = LocalClipboardManager.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .65f)
    Column(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (data == null) Box(Modifier.fillMaxWidth().height(32.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.dp, color = muted)
        } else {
            @Composable fun people(items: List<DetailPerson>, icon: @Composable () -> Unit) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { icon() }
                    LazyRow(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        items(items, key = { it.pubkey ?: "anonymous" }) { person ->
                            val profile = state.profiles[person.pubkey] ?: data.profiles[person.pubkey]
                            val name = profile?.name ?: person.pubkey?.let(FeatherRepository::shortKey)
                                ?: stringResource(R.string.ui_anonymous_zap)
                            val label = if (person.amount == null) name else "$name · ${person.amount} sats"
                            Column(Modifier.widthIn(min = 34.dp).heightIn(min = 34.dp).semantics { contentDescription = label }
                                .then(if (person.pubkey != null) Modifier.clickable(onClickLabel = label) {
                                    onReference(NostrContent.Reference(person.pubkey, profile = true))
                                } else Modifier), horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center) {
                                if (person.pubkey != null) Avatar(name, size = 28, picture = profile?.picture)
                                else Icon(Icons.Default.Person, label, Modifier.size(28.dp), tint = muted)
                                person.amount?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = muted,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 2.dp)) }
                            }
                        }
                    }
                }
            }
            if (data.reposts.isNotEmpty()) people(data.reposts) {
                Icon(painterResource(R.drawable.repost), stringResource(R.string.ui_note_reposts), Modifier.size(16.dp), tint = muted)
            }
            data.reactions.forEach { reaction -> people(reaction.people) {
                val choice = reactionChoice(reaction.sample)
                when {
                    choice?.image != null -> RemoteImage(choice.image, Modifier.size(18.dp), choice.content, crop = false)
                    else -> Text(if (reaction.sample.content.isBlank() || reaction.sample.content == "+") "🧡" else reaction.sample.content.take(12),
                        style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            } }
            if (data.zaps.isNotEmpty()) people(data.zaps) {
                Icon(painterResource(R.drawable.zap), stringResource(R.string.ui_note_zaps), Modifier.size(16.dp), tint = muted)
            }
            if (data.relays.isNotEmpty()) Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(painterResource(R.drawable.storage), stringResource(R.string.ui_seen_on_relays), Modifier.size(16.dp), tint = muted)
                LazyRow(Modifier.weight(1f).padding(start = 8.dp)) {
                    items(data.relays, key = { it }) { relay ->
                        Text(relay.removePrefix("wss://").removePrefix("ws://").trimEnd('/'),
                            style = MaterialTheme.typography.labelSmall, color = muted, maxLines = 1,
                            modifier = Modifier.clickable(onClickLabel = relay) { clipboard.setText(AnnotatedString(relay)) }
                                .padding(horizontal = 6.dp, vertical = 4.dp))
                    }
                }
            }
            if (data.reposts.isEmpty() && data.reactions.isEmpty() && data.zaps.isEmpty() && data.relays.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.people), stringResource(R.string.ui_no_note_details), Modifier.size(18.dp), tint = muted)
                }
            }
        }
    }
}
