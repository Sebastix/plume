package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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

@Composable
internal fun SoftwareAppCard(event: Event) {
    val context = LocalContext.current
    val app by produceState<SoftwareApp?>(null, event.id) {
        value = withContext(Dispatchers.Default) {
            val identifier = event.tagValue("d")?.takeIf { it.isNotBlank() } ?: return@withContext null
            val address = NAddress.create(32267, event.pubKey, identifier, relay = ZAPSTORE_RELAY.normalizeRelayUrl())
            SoftwareApp(event.tagValue("name")?.takeIf { it.isNotBlank() }?.take(100) ?: identifier.take(100),
                (event.tagValue("summary")?.takeIf { it.isNotBlank() } ?: event.content).trim().take(300),
                MediaContent.secureUrl(event.tagValue("icon")), "https://zapstore.dev/apps/$address")
        }
    }
    val model = app
    if (model == null) {
        Box(Modifier.fillMaxWidth().height(96.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    } else Surface(onClick = { context.openWebLink(model.url) }, modifier = Modifier.fillMaxWidth(),
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
            Icon(painterResource(R.drawable.open_external), "Open in Zapstore", Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
        }
    }
}
