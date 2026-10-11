package org.dergigi.feather

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

/** One transient message, never an unbounded queue of stale errors. */
internal object AppMessages {
    data class Message(val id: Long, val text: String)
    val current = MutableStateFlow<Message?>(null)
    val topHost = MutableStateFlow<Any?>(null)
    private val hosts = mutableListOf<Any>()
    private var sequence = 0L

    @Synchronized fun show(text: String) {
        if (text.isBlank() || current.value?.text == text) return
        current.value = Message(++sequence, text)
    }
    @Synchronized fun dismiss(id: Long) {
        if (current.value?.id == id) current.value = null
    }
    @Synchronized fun attach(host: Any) { hosts.add(host); topHost.value = hosts.last() }
    @Synchronized fun detach(host: Any) { hosts.remove(host); topHost.value = hosts.lastOrNull() }
}

/** Also installed inside modal windows so errors remain tappable above their content. */
@Composable
internal fun CopyableMessageHost() {
    val host = remember { Any() }
    DisposableEffect(host) {
        AppMessages.attach(host)
        onDispose { AppMessages.detach(host) }
    }
    val top by AppMessages.topHost.collectAsStateWithLifecycle()
    val message by AppMessages.current.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = top === host && lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message?.id, active) {
        val shown = message ?: return@LaunchedEffect
        if (!active) return@LaunchedEffect
        delay(accessibility?.calculateRecommendedTimeoutMillis(6_000L,
            containsIcons = true, containsText = true, containsControls = true) ?: 6_000L)
        AppMessages.dismiss(shown.id)
    }
    if (!active) return
    val shown = message ?: return
    val context = LocalContext.current
    fun copy() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Feather message", shown.text))
        AppMessages.dismiss(shown.id)
    }
    Popup(alignment = Alignment.BottomCenter,
        properties = PopupProperties(focusable = false, dismissOnBackPress = false, dismissOnClickOutside = false)) {
        Snackbar(modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).imePadding()
            .padding(16.dp).widthIn(max = 560.dp).clickable(onClickLabel = "Copy message", onClick = ::copy),
            action = {
                IconButton(onClick = ::copy) { Icon(painterResource(R.drawable.copy), "Copy message") }
            }) {
            Text(shown.text, maxLines = 6, overflow = TextOverflow.Ellipsis)
        }
    }
}
