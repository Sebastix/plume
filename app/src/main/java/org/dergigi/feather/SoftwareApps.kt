package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.relay.normalizer.normalizeRelayUrl
import com.vitorpamplona.quartz.nip19Bech32.entities.NAddress
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal const val ZAPSTORE_RELAY = "wss://relay.zapstore.dev"
private data class SoftwareApp(val name: String, val summary: String, val icon: String?, val url: String)

private fun softwareApp(event: Event): SoftwareApp? {
    val identifier = event.tagValue("d")?.takeIf { it.isNotBlank() } ?: return null
    val address = NAddress.create(32267, event.pubKey, identifier, relay = ZAPSTORE_RELAY.normalizeRelayUrl())
    return SoftwareApp(event.tagValue("name")?.takeIf { it.isNotBlank() }?.take(100) ?: identifier.take(100),
        event.tagValue("summary").orEmpty().trim().take(500), MediaContent.secureUrl(event.tagValue("icon")), "https://zapstore.dev/apps/$address")
}

@Composable
internal fun SoftwareAppCard(event: Event, onOpen: () -> Unit) {
    val app by produceState<SoftwareApp?>(null, event.id) {
        value = withContext(Dispatchers.Default) { softwareApp(event) }
    }
    val model = app
    if (model == null) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else Surface(onClick = onOpen, modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.padding(24.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                var loaded by remember(model.icon) { mutableStateOf(false) }
                if (!loaded) Icon(painterResource(R.drawable.apps), null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                RemoteImage(model.icon, Modifier.fillMaxSize(), null, crop = false, onImageSize = { _, _ -> loaded = true })
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (model.summary.isNotBlank()) Text(model.summary, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

internal fun Event.appAddress(): String? = tagValue("d")?.takeIf { kind == 32267 && it.isNotBlank() }
    ?.let { "32267:$pubKey:$it" }

internal fun Event.matchesThread(target: String?): Boolean = target != null && (id == target || appAddress() == target)
internal fun Event.isAppDiscussion(): Boolean = kind == 32267 || (kind == 1111 && tagValue("K") == "32267")

/** Older Zapstore root comments contain only uppercase scope tags. */
internal fun Event.commentsOnApp(address: String): Boolean = kind == 1111 &&
    (tagValue("a") == address || (tagValue("A") == address && tagValue("e") == null && tagValue("k") != "1111"))

@Composable
internal fun SoftwareAppContent(event: Event, onReference: (NostrContent.Reference) -> Unit) {
    val context = LocalContext.current
    val gallery = LocalOpenGallery.current
    val model by produceState<SoftwareApp?>(null, event.id) { value = withContext(Dispatchers.Default) { softwareApp(event) } }
    val app = model ?: return
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RemoteImage(app.icon, Modifier.size(80.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(20.dp)), app.name, crop = false)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(app.name, style = MaterialTheme.typography.headlineSmall)
                Text(event.tagValue("d").orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { context.openWebLink(app.url) }) {
                Icon(painterResource(R.drawable.open_external), "Open app in Zapstore", tint = MaterialTheme.colorScheme.primary)
            }
        }
        if (app.summary.isNotBlank()) Text(app.summary, style = MaterialTheme.typography.bodyLarge)
        val images = remember(event.id) { event.tags.filter { it.firstOrNull() == "image" }.mapNotNull { MediaContent.secureUrl(it.getOrNull(1)) }.distinct().take(20) }
        if (images.isNotEmpty()) androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            items(images.size) { index ->
                RemoteImage(images[index], Modifier.width(160.dp).height(300.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(16.dp))
                    .clickable(onClickLabel = "Open app screenshot") { gallery(images, index, event) }, "${app.name} screenshot ${index + 1}", crop = false)
            }
        }
        if (event.content.isNotBlank()) ArticleMarkdown(event, onReference, articleLayout = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            (MediaContent.secureUrl(event.tagValue("website")) ?: MediaContent.secureUrl(event.tagValue("url")))?.let { url ->
                IconButton(onClick = { context.openWebLink(url) }) { Icon(Icons.Default.Home, "Open app website", tint = MaterialTheme.colorScheme.primary) }
            }
            MediaContent.secureUrl(event.tagValue("repository"))?.let { url ->
                IconButton(onClick = { context.openWebLink(url) }) { Icon(painterResource(R.drawable.github), "Open source repository", tint = MaterialTheme.colorScheme.primary) }
            }
            event.tagValue("license")?.takeIf { it.isNotBlank() }?.let {
                Text(it.take(80), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
