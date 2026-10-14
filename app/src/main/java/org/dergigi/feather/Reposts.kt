package org.dergigi.feather

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONObject
import kotlinx.coroutines.launch

internal data class RepostActions(val pending: Map<String, Boolean> = emptyMap(), val repost: (Event) -> Unit = {}, val quote: (Event) -> Unit = {})
internal val LocalRepostActions = staticCompositionLocalOf { RepostActions() }

@Composable
internal fun RepostButton(note: Event, sent: Boolean) {
    val actions = LocalRepostActions.current
    val pending = note.id in actions.pending
    val active = actions.pending[note.id] ?: sent
    val rotation = remember(note.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(Modifier.size(48.dp).clip(CircleShape).combinedClickable(enabled = !pending,
        onClickLabel = if (active) "Undo repost" else "Repost", onLongClickLabel = "Quote post",
        onClick = {
            actions.repost(note)
            scope.launch {
                rotation.snapTo(0f)
                rotation.animateTo(360f, keyframes {
                    durationMillis = 380
                    0f at 0 using FastOutSlowInEasing
                    378f at 280 using FastOutSlowInEasing
                    360f at 380
                })
            }
        }, onLongClick = { actions.quote(note) }), contentAlignment = Alignment.Center) {
        Icon(painterResource(R.drawable.repost), if (active) "Undo repost; hold to quote" else "Repost; hold to quote", Modifier.size(20.dp).graphicsLayer { rotationZ = rotation.value },
            tint = if (active) Color(0xFF79C4F2) else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Bounded account-scoped signed-event journal, accessed only on IO. */
internal class RepostStore(context: Context) {
    private val prefs = context.getSharedPreferences("repost-journal", Context.MODE_PRIVATE)
    data class Record(val event: Event, val accepted: Boolean)
    @Synchronized fun records(owner: String): List<Record> = prefs.all.filterKeys { it.startsWith("$owner:") }.values.mapNotNull {
        runCatching { val json = JSONObject(it as String); Record(Event.fromJson(json.getString("event")), json.getBoolean("accepted")) }.getOrNull()
    }
    @Synchronized fun put(target: String, event: Event, accepted: Boolean) {
        val key = "${event.pubKey}:$target"
        val editor = prefs.edit().putString(key, JSONObject().put("event", event.toJson()).put("accepted", accepted).put("saved", System.currentTimeMillis()).toString())
        prefs.all.filterKeys { it != key }.entries.sortedByDescending {
            runCatching { JSONObject(it.value as String).optLong("saved") }.getOrDefault(0)
        }.drop(127).forEach { editor.remove(it.key) }
        check(editor.commit()) { "Could not save repost for retry" }
    }
}
