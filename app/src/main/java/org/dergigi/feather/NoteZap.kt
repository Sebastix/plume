package org.dergigi.feather

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import java.text.NumberFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event

/** Shared one-tap zap and amount picker for notes and their gallery images. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun NoteZap(note: Event, counts: Engagement, publicKey: String?,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, iconOnly: Boolean = false, enabled: Boolean = true, onPickerVisibilityChanged: (Boolean) -> Unit = {}) {
    val zapAmount = remember(counts.zapSats) { counts.zapSats.stripTrailingZeros().toPlainString() }
    val zapActions = LocalZapActions.current
    var picker by remember(note.id) { mutableStateOf(false) }
    var custom by remember(note.id) { mutableStateOf(false) }
    var input by remember(note.id) { mutableStateOf("") }
    var choices by remember(note.id) { mutableStateOf(zapActions.amounts) }
    val callback by rememberUpdatedState(onPickerVisibilityChanged)
    LaunchedEffect(enabled) { if (!enabled) { picker = false; custom = false } }
    LaunchedEffect(picker, custom) { callback(picker || custom) }
    DisposableEffect(Unit) { onDispose { callback(false) } }
    val format = remember { NumberFormat.getIntegerInstance() }
    val pending = note.id in zapActions.pending
    val zapped = note.id in zapActions.confirmed ||
        (publicKey != null && counts.zappedByAccount == publicKey)
    Box {
        Row((if (iconOnly) Modifier.size(56.dp) else Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)).clip(CircleShape)
            .combinedClickable(enabled = enabled && !pending,
                onClickLabel = "Zap 21 sats", onLongClickLabel = "Choose zap amount",
                onClick = { zapActions.zap(note, DEFAULT_ZAP_SATS) },
                onLongClick = { choices = zapActions.amounts; picker = true }).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (note.id in zapActions.pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
                else Icon(painterResource(R.drawable.zap),
                    if (zapped) "You zapped this post; $zapAmount sats received" else "Zap 21 sats; $zapAmount sats received",
                    Modifier.size(20.dp), tint = if (zapped) Color(0xFFFFB300) else tint)
            }
            if (!iconOnly && counts.zapSats.signum() > 0) Text(zapAmount, style = MaterialTheme.typography.labelMedium, color = tint)
        }
        DropdownMenu(expanded = picker && enabled, onDismissRequest = { picker = false }) {
            FlowRow(Modifier.widthIn(max = 320.dp).padding(horizontal = 8.dp), maxItemsInEachRow = 3) {
                choices.forEach { amount ->
                    TextButton(enabled = !pending, onClick = {
                        picker = false
                        zapActions.zap(note, amount)
                    }, modifier = Modifier.semantics { contentDescription = "Zap $amount sats" }) {
                        Text(format.format(amount))
                    }
                }
                IconButton(enabled = !pending, onClick = {
                    picker = false; input = ""; custom = true
                }) { Icon(Icons.Default.Add, "Custom zap amount") }
            }
        }
    }
    if (custom && enabled) {
        val amount = input.toLongOrNull()?.takeIf { it in 1..MAX_ZAP_SATS }
        AlertDialog(onDismissRequest = { custom = false },
            text = {
                OutlinedTextField(value = input, onValueChange = { value ->
                    if (value.length <= 16 && value.all { it in '0'..'9' }) input = value
                }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    suffix = { Text("sats") },
                    modifier = Modifier.semantics { contentDescription = "Zap amount in sats" })
            },
            confirmButton = {
                IconButton(enabled = amount != null && !pending, onClick = {
                    amount?.let { custom = false; zapActions.zap(note, it) }
                }) { Icon(painterResource(R.drawable.zap), amount?.let { "Zap $it sats" } ?: "Enter zap amount") }
            })
    }
}
