package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.crypto.verify
import com.vitorpamplona.quartz.lightning.LnInvoiceUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Display previews, like ants-android: amounts are invoice amounts, not new payment confirmations.
internal data class ZapActivity(val sender: String?, val recipient: String, val amount: String?, val comment: String)
private val zapPreviews = object : LinkedHashMap<String, ZapActivity?>(128, .75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ZapActivity?>?) = size > 1024
}

/** Called only by background repository/composable work. Never perform crypto on the UI thread. */
internal fun zapActivity(event: Event): ZapActivity? = synchronized(zapPreviews) {
    if (zapPreviews.containsKey(event.id)) return@synchronized zapPreviews[event.id]
    val result = runCatching {
        require(event.kind == 9735)
        val recipient = requireNotNull(event.tagValue("p")?.let(Protocol::publicKey))
        val description = requireNotNull(event.tagValue("description"))
        require(description.length <= 32768)
        val request = Event.fromJson(description)
        require(request.kind == 9734 && request.verify() && request.tagValue("p") == recipient &&
            request.tagValue("e") == event.tagValue("e") && request.tagValue("a") == event.tagValue("a") &&
            (event.tagValue("P") == null || event.tagValue("P") == request.pubKey))
        val anonymous = request.tags.any { it.firstOrNull() == "anon" }
        val amount = runCatching { LnInvoiceUtil.getAmountInSats(requireNotNull(event.tagValue("bolt11"))) }
            .getOrNull()?.takeIf { it.signum() > 0 }?.stripTrailingZeros()?.toPlainString()
        ZapActivity(request.pubKey.takeUnless { anonymous }, recipient, amount, request.content.withoutObjectMarkers().take(2000))
    }.getOrNull()
    zapPreviews[event.id] = result
    result
}

internal fun zapNoteReference(event: Event): NostrContent.Reference? =
    event.tagValue("e")?.let(Protocol::publicKey)?.let { NostrContent.Reference(it) }
        ?: bookmarkReferencesForZap(event)

private fun bookmarkReferencesForZap(event: Event): NostrContent.Reference? {
    val address = event.tagValue("a") ?: return null
    val parts = address.split(':', limit = 3)
    if (parts.size != 3) return null
    val kind = parts[0].toIntOrNull() ?: return null
    val author = Protocol.publicKey(parts[1]) ?: return null
    return NostrContent.Reference(address, author = author, kind = kind)
}

internal fun zapActivityReferences(event: Event): List<NostrContent.Reference> {
    val payment = zapActivity(event) ?: return emptyList()
    return listOfNotNull(payment.sender?.let { NostrContent.Reference(it, profile = true) },
        NostrContent.Reference(payment.recipient, profile = true), zapNoteReference(event))
}

@Composable
internal fun ZapActivityCard(event: Event, state: FeedState, onReference: (NostrContent.Reference) -> Unit) {
    val payment by produceState<ZapActivity?>(null, event.id) {
        value = withContext(Dispatchers.Default) { zapActivity(event) }
    }
    val zap = payment ?: return
    if (zap.sender in state.mutedAuthors || zap.recipient in state.mutedAuthors) return
    Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { NoteTimestamp(event) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ZapParty(zap.sender, state.profiles, Modifier.weight(1f), Alignment.Start, onReference)
            Row(Modifier.weight(1.25f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally)) {
                Icon(painterResource(R.drawable.zap), "Zap from sender to recipient", modifier = Modifier.size(24.dp), tint = Color(0xFFFFB300))
                zap.amount?.let { Text("$it sats", style = MaterialTheme.typography.titleMedium, color = Color(0xFFFFB300), maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            ZapParty(zap.recipient, state.profiles, Modifier.weight(1f), Alignment.End, onReference)
        }
        if (zap.comment.isNotBlank()) Text(zap.comment, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        zapNoteReference(event)?.let { reference ->
            EmbeddedNote(reference, state.embedded[reference.id], state.profiles, "${event.id}:${reference.id}", onReference)
        }
    }
}

@Composable
private fun ZapParty(key: String?, profiles: Map<String, Profile>, modifier: Modifier, alignment: Alignment.Horizontal, onReference: (NostrContent.Reference) -> Unit) {
    val profile = profiles[key]
    val name = profile?.name ?: key?.let(FeatherRepository::shortKey) ?: "Anonymous"
    Column(modifier.clickable(enabled = key != null, onClickLabel = "Open $name's profile") {
        key?.let { onReference(NostrContent.Reference(it, profile = true)) }
    }.padding(vertical = 4.dp), horizontalAlignment = alignment, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Avatar(name, 36, profile?.picture)
        Text(name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
