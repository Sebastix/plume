package org.dergigi.feather

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.quartz.nip01Core.core.Event
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import java.text.NumberFormat
import java.util.Locale
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private data class RelativeTime(val label: String, val spoken: String, val nextUpdateMs: Long)

private fun relativeTime(createdAt: Long): RelativeTime {
    val now = System.currentTimeMillis()
    val age = (now / 1000 - createdAt).coerceAtLeast(0)
    val (unit, suffix, name) = when {
        age >= 604_800 -> Triple(604_800L, "w", "week")
        age >= 86_400 -> Triple(86_400L, "d", "day")
        age >= 3_600 -> Triple(3_600L, "h", "hour")
        age >= 60 -> Triple(60L, "m", "minute")
        else -> Triple(1L, "s", "second")
    }
    val count = age / unit
    val delay = ((unit - age % unit) * 1000 - now % 1000).coerceAtLeast(100)
    return RelativeTime("$count$suffix", "$count $name${if (count == 1L) "" else "s"} ago", delay)
}

@Composable
internal fun NoteTimestamp(note: Event, alignment: Alignment.Horizontal = Alignment.End) {
    val context = LocalContext.current
    val view = LocalView.current
    val blocktime = (context.applicationContext as FeatherApplication).repository.blocktime
    // Order: absolute date/time, block height, relative age. Start with relative age.
    val preference = (context.applicationContext as FeatherApplication).timestamps
    val mode by preference.mode.collectAsStateWithLifecycle()
    var height by remember(note.id) { mutableStateOf<Int?>(null) }
    var loading by remember(note.id) { mutableStateOf(false) }
    var visible by remember(note.id) { mutableStateOf(false) }
    var relative by remember(note.createdAt) { mutableStateOf(relativeTime(note.createdAt)) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && visible
    val date = remember(note.createdAt) {
        DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(note.createdAt))
    }
    LaunchedEffect(mode, active, note.createdAt) {
        if (!active) return@LaunchedEffect
        if (mode == 2) {
            while (true) {
                relative = relativeTime(note.createdAt)
                delay(relative.nextUpdateMs)
            }
        } else if (mode == 1) {
            loading = true
            try {
                height = try { blocktime.height(note.createdAt) }
                    catch (e: CancellationException) { throw e }
                    catch (_: Exception) { null }
                if (height == null) Toast.makeText(context, "Couldn't load block height.", Toast.LENGTH_SHORT).show()
            } finally { loading = false }
        }
    }
    val blockLabel = remember(height) { height?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } }
    val label = when (mode) { 1 -> blockLabel ?: date; 2 -> relative.label; else -> date }
    val nextLabel = when (mode) { 0 -> "Show Bitcoin block height"; 1 -> "Show time since"; else -> "Show date and time" }
    Row(
        Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.bottom > 0 && bounds.top < view.height && bounds.right > 0 && bounds.left < view.width
        }.clickable(onClickLabel = "$nextLabel for all notes") { preference.cycle() }
            .semantics { contentDescription = when {
                mode == 1 && blockLabel != null -> "Bitcoin block $blockLabel at note time"
                mode == 2 -> relative.spoken
                else -> date
            } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, alignment),
    ) {
        if (mode == 1 && loading && height == null) CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.dp)
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (mode == 1 && height != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
