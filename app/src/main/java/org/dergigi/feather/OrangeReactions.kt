package org.dergigi.feather

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONObject

internal const val ORANGE_HEART = "🧡"
internal data class ReactionActions(val pending: String? = null, val react: (Event) -> Unit = {})
internal val LocalReactionActions = staticCompositionLocalOf { ReactionActions() }

@Composable
internal fun OrangeHeart(note: Event, count: Int, sent: Boolean) {
    val actions = LocalReactionActions.current
    val pending = actions.pending == note.id
    Row(Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)
        .clickable(enabled = !sent && actions.pending == null, onClickLabel = "React with an orange heart") { actions.react(note) }
        .padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Icon(if (sent) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
            if (sent) "Orange heart sent" else "React with an orange heart",
            Modifier.size(20.dp), tint = if (sent) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant)
        if (count > 0) Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Only public signed events, never keys. All calls belong on Dispatchers.IO.
 * Persist before publishing so a timeout or process restart can retry the same ID. */
internal class ReactionStore(context: Context) {
    private val prefs = context.getSharedPreferences("orange-reactions", Context.MODE_PRIVATE)
    data class Record(val event: Event, val accepted: Boolean)
    fun get(author: String, target: String): Record? = prefs.getString("$author:$target", null)?.let { raw ->
        runCatching { val json = JSONObject(raw); Record(Event.fromJson(json.getString("event")), json.optBoolean("accepted")) }.getOrNull()
    }
    fun put(target: String, event: Event, accepted: Boolean) {
        val key = "${event.pubKey}:$target"
        val editor = prefs.edit().putString(key, JSONObject().put("event", event.toJson()).put("accepted", accepted)
            .put("saved", System.currentTimeMillis()).toString())
        val old = prefs.all.filterKeys { it != key }.entries.sortedByDescending {
            runCatching { JSONObject(it.value as String).optLong("saved") }.getOrDefault(0)
        }
        old.drop(255).forEach { editor.remove(it.key) }
        check(editor.commit()) { "Could not save reaction for retry" }
    }
    fun accepted(author: String): Set<String> = prefs.all.entries.asSequence().filter { it.key.startsWith("$author:") }
        .filter { runCatching { JSONObject(it.value as String).optBoolean("accepted") }.getOrDefault(false) }
        .map { it.key.substringAfter(':') }.toSet()
}
