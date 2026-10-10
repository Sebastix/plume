package org.dergigi.feather

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.vitorpamplona.quartz.nip01Core.core.Event
import java.text.NumberFormat
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun NoteTimestamp(note: Event) {
    val context = LocalContext.current
    val blocktime = (context.applicationContext as FeatherApplication).repository.blocktime
    var showBlock by rememberSaveable(note.id) { mutableStateOf(false) }
    var height by remember(note.id) { mutableStateOf<Int?>(null) }
    var loading by remember(note.id) { mutableStateOf(false) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val date = remember(note.createdAt) {
        DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(note.createdAt))
    }
    LaunchedEffect(showBlock, active, note.createdAt) {
        if (showBlock && active) {
            loading = true
            try {
                height = blocktime.height(note.createdAt)
                if (height == null) {
                    showBlock = false
                    Toast.makeText(context, "Couldn't load block height. Tap the timestamp to try again.", Toast.LENGTH_SHORT).show()
                }
            } finally { loading = false }
        }
    }
    val blockLabel = remember(height) { height?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } }
    Row(
        Modifier.heightIn(min = 48.dp).clickable(onClickLabel = if (showBlock) "Show date and time" else "Show Bitcoin block height") { showBlock = !showBlock }
            .semantics { contentDescription = if (showBlock && height != null) "Bitcoin block $blockLabel at note time" else date },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (loading && height == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.dp)
        Text(if (showBlock && blockLabel != null) blockLabel else date,
            style = MaterialTheme.typography.labelSmall,
            color = if (showBlock && height != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
