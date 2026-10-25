package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.res.stringResource
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl

@Composable
internal fun IssueContent(event: Event, compact: Boolean, onReference: (NostrContent.Reference) -> Unit, status: Int? = null, state: FeedState? = null) {
    val openIssues = LocalOpenRepositoryIssues.current
    val repository = remember(event.id) { event.tags.firstOrNull { it.firstOrNull() == "a" && it.getOrNull(1)?.startsWith("30617:") == true } }
    val address = remember(repository) { repository?.getOrNull(1)?.split(':', limit = 3) }
    val repoLink = remember(repository) {
        address?.takeIf { it.size == 3 && Protocol.publicKey(it[1]) != null && it[2].isNotBlank() }?.let {
            NAddress.create(30617, it[1], it[2], relay = repository?.getOrNull(2)?.let(Protocol::relay)?.normalizeRelayUrl())
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val feature = event.tags.any { it.firstOrNull() == "t" && it.getOrNull(1) in listOf("enhancement", "feature") }
            val statusLabel = stringResource(when (status) {
                1631 -> R.string.ui_issue_resolved
                1632 -> R.string.ui_issue_closed
                1633 -> R.string.ui_issue_draft
                else -> R.string.ui_issue_open
            })
            val statusIcon = when (status) {
                1631 -> Icons.Outlined.CheckCircle
                1633 -> Icons.Outlined.Edit
                else -> null
            }
            if (status == 1632) Icon(painterResource(R.drawable.issue_closed), statusLabel, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else if (statusIcon != null) Icon(statusIcon, statusLabel, Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
            else Icon(painterResource(if (feature) R.drawable.feature_request else R.drawable.bug_report),
                statusLabel, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            if (repoLink != null) Text(address?.getOrNull(2).orEmpty().take(120),
                modifier = Modifier.weight(1f, fill = false).clickable(onClickLabel = "Open repository issues") { openIssues(repository?.getOrNull(1).orEmpty(), listOfNotNull(repository?.getOrNull(2)?.let(Protocol::relay))) },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        event.tagValue("subject")?.takeIf { it.isNotBlank() }?.let {
            Text(it.take(500), style = MaterialTheme.typography.titleLarge, maxLines = if (compact) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis)
        }
        if (compact) Text((LocalTranslations.current?.text(event) ?: event.content).take(1200).withoutObjectMarkers(), style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
        else ArticleMarkdown(event, onReference, articleLayout = false, embedState = state)
    }
}
