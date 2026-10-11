package org.dergigi.feather

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

/** Capture diagnostics only on tap; opening the dialog never performs network or disk work. */
@Composable
internal fun StatusInfo(title: String, error: Boolean = false, empty: Boolean = false, hidden: Boolean = false, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        if (hidden && !error) Icon(painterResource(R.drawable.account_hidden), "Show details: $title", Modifier.size(32.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else if (empty && !error) Icon(painterResource(R.drawable.tumbleweed), "Show details: $title", Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
        else Icon(if (error) Icons.Default.Warning else Icons.Default.Info, "Show details: $title", Modifier.size(32.dp),
            tint = if (error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Hosted outside the list so incoming notes cannot dismiss details while they are being read. */
@Composable
internal fun StatusDetails(title: String, captured: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            SelectionContainer {
                Text(captured, Modifier.verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = {
            IconButton(onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Feather status", captured))
                if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
            }) { Icon(painterResource(R.drawable.copy), "Copy details") }
        },
        dismissButton = {
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, "Close details") }
        },
    )
}

internal fun readerStatusDetails(state: FeedState, screen: String, target: String?, tab: ProfileTab, hidden: Boolean): String = buildString {
    appendLine(when {
        state.error != null -> state.error
        hidden -> "Loaded notes are hidden by your account’s mute/block list."
        screen == "thread" -> "This post is not available in the local cache or from the relays queried so far. It may be on another relay; this does not confirm that it was deleted."
        screen == "profile" -> "No visible items are available in this profile tab from the local cache or the relays queried so far."
        else -> "No visible notes are available from the local cache or the relays queried so far."
    })
    if (state.error == null) appendLine("No specific error was reported.")
    appendLine()
    appendLine("Feather ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
    appendLine("View: $screen${if (screen in listOf("profile", "feed")) " / ${tab.label}" else ""}")
    target?.let { appendLine("Target: $it") }
    appendLine("Connected relays: ${state.connected.size}")
    appendLine("Account: ${if (state.publicKey == null) "guest" else "connected"}")
    if (state.error != null || !hidden) append("Pull to refresh to try loading again.")
}.trimEnd()
