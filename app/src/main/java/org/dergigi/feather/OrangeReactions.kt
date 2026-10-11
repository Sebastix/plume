package org.dergigi.feather

import android.content.Context
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Add
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.security.MessageDigest
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONObject

internal const val ORANGE_HEART = "🧡"
internal data class ReactionActions(
    val pending: Set<String> = emptySet(),
    val choices: List<ReactionChoice> = defaultReactions,
    val customEmojis: List<ReactionChoice> = emptyList(),
    val remove: (Event, ReactionChoice) -> Unit = { _, _ -> },
    val react: (Event, ReactionChoice) -> Unit = { _, _ -> },
)
internal val LocalReactionActions = staticCompositionLocalOf { ReactionActions() }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun OrangeHeart(note: Event, count: Int, sent: List<ReactionChoice>,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    iconOnly: Boolean = false,
    onPickerVisibilityChanged: (Boolean) -> Unit = {}) {
    val actions = LocalReactionActions.current
    val pending = note.id in actions.pending
    var picker by remember(note.id) { mutableStateOf(false) }
    var fullPicker by remember(note.id) { mutableStateOf(false) }
    var menuChoices by remember(note.id) { mutableStateOf(actions.choices) }
    val pickerCallback by rememberUpdatedState(onPickerVisibilityChanged)
    LaunchedEffect(picker, fullPicker) { pickerCallback(picker || fullPicker) }
    DisposableEffect(Unit) { onDispose { pickerCallback(false) } }
    // Repository snapshots and accepted local reactions are ordered newest first.
    val latestReaction = sent.firstOrNull()
    Box {
        Row((if (iconOnly) Modifier.size(56.dp) else Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)).clip(CircleShape)
            .combinedClickable(enabled = note.id !in actions.pending,
                onClickLabel = if (latestReaction != null) "Remove your reaction" else "React with an orange heart", onLongClickLabel = "Choose a reaction",
                onClick = { if (latestReaction != null) actions.remove(note, latestReaction) else actions.react(note, defaultReactions.first()) },
                onLongClick = { menuChoices = actions.choices; picker = true })
            .padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
                else if (latestReaction != null) {
                    Box(Modifier.size(24.dp).semantics {
                        contentDescription = "Your reaction: ${latestReaction.content}; tap to remove; hold to choose another emoji"
                    }, contentAlignment = Alignment.Center) {
                        if (latestReaction.image == null) Text(latestReaction.content, fontSize = 20.sp)
                        else {
                            Text("◌", fontSize = 20.sp, color = tint)
                            RemoteImage(latestReaction.image, Modifier.size(22.dp),
                                "Your reaction: ${latestReaction.content}", crop = false)
                        }
                    }
                } else Icon(Icons.Default.FavoriteBorder, "React; hold to choose an emoji",
                    Modifier.size(20.dp), tint = tint)
            }
            if (!iconOnly && count > 0) Text(count.toString(), style = MaterialTheme.typography.labelMedium, color = tint)
        }
        DropdownMenu(expanded = picker, onDismissRequest = { picker = false }) {
            FlowRow(Modifier.widthIn(max = 344.dp).padding(horizontal = 4.dp), maxItemsInEachRow = 7) {
                menuChoices.forEach { choice ->
                    val alreadySent = choice in sent
                    IconButton(enabled = note.id !in actions.pending, onClick = {
                        picker = false
                        if (alreadySent) actions.remove(note, choice) else actions.react(note, choice)
                    }, modifier = Modifier.size(48.dp)) {
                        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                            Text(if (choice.image == null) choice.content else "◌", fontSize = 24.sp,
                                modifier = Modifier.semantics { contentDescription = if (alreadySent) "Remove ${choice.content} reaction" else "React with ${choice.content}" })
                            choice.image?.let { RemoteImage(it, Modifier.size(28.dp), "React with ${choice.content}", crop = false) }
                            if (alreadySent) Icon(Icons.Default.Check, "Already sent", Modifier.size(14.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
                IconButton(enabled = note.id !in actions.pending, onClick = {
                    picker = false
                    fullPicker = true
                }, modifier = Modifier.size(48.dp)) {
                    Icon(Icons.Default.Add, "Open full emoji picker")
                }
            }
        }
    }
    if (fullPicker) {
        val latestActions by rememberUpdatedState(actions)
        val latestSent by rememberUpdatedState(sent)
        val recent = remember(menuChoices) { menuChoices.filter { it.image == null }.map { it.content } }
        val height = (LocalConfiguration.current.screenHeightDp * 0.6f).coerceAtMost(480f).dp
        ModalBottomSheet(onDismissRequest = { fullPicker = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            SearchableEmojiPicker(recent, actions.customEmojis, Modifier.fillMaxWidth().height(height)) { choice ->
                if (note.id !in latestActions.pending && validReactionChoice(choice)) {
                    fullPicker = false
                    if (choice in latestSent) latestActions.remove(note, choice) else latestActions.react(note, choice)
                }
            }
        }
    }
}

/** Only public signed events, never keys. All calls belong on Dispatchers.IO.
 * Persist before publishing so a timeout or process restart can retry the same ID. */
internal class ReactionStore(context: Context) {
    private val prefs = context.getSharedPreferences("orange-reactions", Context.MODE_PRIVATE)
    data class Record(val event: Event, val accepted: Boolean)
    private var cached: List<Record>? = null
    @Synchronized
    fun records(author: String): List<Record> {
        val records = cached ?: prefs.all.values.mapNotNull { raw ->
            runCatching { val json = JSONObject(raw as String); Record(Event.fromJson(json.getString("event")), json.optBoolean("accepted")) }.getOrNull()
        }.also { cached = it }
        return records.filter { it.event.pubKey == author }
    }
    fun get(author: String, target: String, choice: ReactionChoice): Record? = records(author)
        .firstOrNull { reactionTarget(it.event)?.id == target && reactionChoice(it.event) == choice }

    @Synchronized
    fun put(target: String, event: Event, accepted: Boolean) {
        val choice = requireNotNull(reactionChoice(event))
        val digest = MessageDigest.getInstance("SHA-256").digest(choice.key.toByteArray()).joinToString("") { "%02x".format(it) }
        val key = "${event.pubKey}:$target:$digest"
        val legacy = "${event.pubKey}:$target"
        val editor = prefs.edit().putString(key, JSONObject().put("event", event.toJson()).put("accepted", accepted)
            .put("saved", System.currentTimeMillis()).toString())
        // Migrate previous orange-heart receipts/retries without dropping their signed event IDs.
        if (choice == defaultReactions.first()) editor.remove(legacy)
        val old = prefs.all.filterKeys { it != key && !(choice == defaultReactions.first() && it == legacy) }.entries.sortedByDescending {
            runCatching { JSONObject(it.value as String).optLong("saved") }.getOrDefault(0)
        }
        old.drop(255).forEach { editor.remove(it.key) }
        check(editor.commit()) { "Could not save reaction for retry" }
        cached = null
    }
}
