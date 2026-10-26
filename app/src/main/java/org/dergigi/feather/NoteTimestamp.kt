package org.dergigi.feather

import kotlinx.coroutines.launch

import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
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
    val copyScope = androidx.compose.runtime.rememberCoroutineScope()
    val view = LocalView.current
    val repository = (context.applicationContext as FeatherApplication).repository
    val blocktime = repository.blocktime
    val pendingIds by repository.postOutbox.ids.collectAsStateWithLifecycle()
    val pending = note.id in pendingIds
    val sendingIds by repository.outboxSending.collectAsStateWithLifecycle()
    val online by repository.networkOnline.collectAsStateWithLifecycle()
    val sending = online && note.id in sendingIds
    // Order: absolute date/time, block height, relative age. Start with relative age.
    val preference = (context.applicationContext as FeatherApplication).timestamps
    val mode by preference.mode.collectAsStateWithLifecycle()
    var height by remember(note.id) { mutableStateOf<Int?>(null) }
    var visible by remember(note.id) { mutableStateOf(false) }
    var relative by remember(note.createdAt) { mutableStateOf(relativeTime(note.createdAt)) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycle.isAtLeast(Lifecycle.State.STARTED) && visible
    val flip = remember(note.id) { Animatable(0f) }
    LaunchedEffect(pending, active, sending) {
        // Return to an upright hourglass instead of leaving a partial flip frozen offline.
        if (!pending || !active || !sending) {
            flip.snapTo(0f)
            return@LaunchedEffect
        }
        while (true) {
            delay(1_650)
            flip.animateTo(flip.value + 180f, tween(350))
            flip.snapTo(flip.value % 360f)
        }
    }
    val date = remember(note.createdAt) {
        val zone = ZoneId.systemDefault()
        val time = Instant.ofEpochSecond(note.createdAt)
        val olderThanYear = time.isBefore(java.time.ZonedDateTime.now(zone).minusYears(1).toInstant())
        DateTimeFormatter.ofPattern(if (olderThanYear) "yyyy · MMM d · HH:mm" else "MMM d · HH:mm").withZone(zone).format(time)
    }
    LaunchedEffect(mode, active, note.createdAt) {
        if (!active) return@LaunchedEffect
        if (mode == 2) {
            while (true) {
                relative = relativeTime(note.createdAt)
                delay(relative.nextUpdateMs)
            }
        } else if (mode == 1) {
            height = try { blocktime.height(note.createdAt) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { null }
        }
    }
    val blockLabel = remember(height) { height?.let { NumberFormat.getIntegerInstance(Locale.US).format(it) } }
    val label = when (mode) { 1 -> blockLabel ?: date; 2 -> relative.label; else -> date }
    val nextLabel = when (mode) { 0 -> "Show Bitcoin block height"; 1 -> "Show time since"; else -> "Show date and time" }
    Row(
        Modifier.sizeIn(minWidth = 64.dp, minHeight = 48.dp).onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.bottom > 0 && bounds.top < view.height && bounds.right > 0 && bounds.left < view.width
        }.combinedClickable(onClickLabel = "$nextLabel for all notes", onClick = { preference.cycle() },
            onLongClickLabel = stringResource(R.string.ui_copy_nostr_reference),
            onLongClick = { copyScope.launch { context.copyTimestampReference(note) } })
            .semantics { contentDescription = when {
                mode == 1 && blockLabel != null -> "Bitcoin block $blockLabel at note time"
                mode == 2 -> relative.spoken
                else -> date
            } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, alignment),
    ) {
        if (pending) Icon(painterResource(R.drawable.pending_hourglass), stringResource(R.string.ui_post_pending),
            Modifier.size(14.dp).graphicsLayer { rotationZ = flip.value },
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
        Text(label, style = MaterialTheme.typography.labelSmall,
            color = if (mode == 1 && height != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Relative age; notifications may supply a row action for long presses. */
@Composable
internal fun NotificationTimestamp(note: Event, onLongClick: (() -> Unit)? = null, longClickLabel: String? = null, onClick: () -> Unit) {
    val context = LocalContext.current
    val copyScope = androidx.compose.runtime.rememberCoroutineScope()
    val view = LocalView.current
    var visible by remember(note.id) { mutableStateOf(false) }
    var relative by remember(note.createdAt) { mutableStateOf(relativeTime(note.createdAt)) }
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = visible && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    LaunchedEffect(active, note.createdAt) {
        if (active) while (true) {
            relative = relativeTime(note.createdAt)
            delay(relative.nextUpdateMs)
        }
    }
    Text(relative.label, style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.onGloballyPositioned {
            val bounds = it.boundsInWindow()
            visible = bounds.bottom > 0 && bounds.top < view.height && bounds.right > 0 && bounds.left < view.width
        }.combinedClickable(onClick = onClick,
            onLongClickLabel = longClickLabel ?: stringResource(R.string.ui_copy_nostr_reference),
            onLongClick = onLongClick ?: { copyScope.launch { context.copyTimestampReference(note) } })
            .semantics { contentDescription = relative.spoken })
}

private suspend fun android.content.Context.copyTimestampReference(note: Event) {
    val clipboard = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Nostr reference", "nostr:${nostrPointer(note)}"))
}
