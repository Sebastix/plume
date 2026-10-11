package org.dergigi.feather

import android.content.Context
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.OffsetMapping
import kotlinx.coroutines.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.vitorpamplona.quartz.nip01Core.core.Event
import org.json.JSONObject

internal val LocalReply = staticCompositionLocalOf<(Event) -> Unit> { {} }
internal data class ReplyDraft(val owner: String, val target: Event?, val text: String = "")

/** Private local drafts and exact signed retries, bounded and accessed only on IO. */
internal class ReplyStore(context: Context) {
    private val drafts = context.getSharedPreferences("reply-drafts", Context.MODE_PRIVATE)
    private val pending = context.getSharedPreferences("reply-pending", Context.MODE_PRIVATE)
    private fun key(owner: String, target: Event?) = "$owner:${target?.id ?: "post"}"
    @Synchronized fun load(owner: String, target: Event?): ReplyDraft = ReplyDraft(owner, target,
        drafts.getString(key(owner, target), "").orEmpty().take(8000))
    @Synchronized fun save(draft: ReplyDraft) {
        val key = key(draft.owner, draft.target)
        val edit = drafts.edit()
        if (draft.text.isBlank()) edit.remove(key) else edit.putString(key, draft.text)
        drafts.all.keys.filter { it != key }.drop(15).forEach(edit::remove)
        check(edit.commit()) { "Could not save reply draft" }
    }
    @Synchronized fun signed(draft: ReplyDraft): Event? = pending.getString(key(draft.owner, draft.target), null)?.let {
        runCatching { Event.fromJson(JSONObject(it).getString("event")) }.getOrNull()
    }?.takeIf { it.pubKey == draft.owner && it.content == draft.text }
    @Synchronized fun saveSigned(draft: ReplyDraft, event: Event) {
        val key = key(draft.owner, draft.target)
        val edit = pending.edit().putString(key, JSONObject().put("event", event.toJson()).toString())
        pending.all.keys.filter { it != key }.drop(15).forEach(edit::remove)
        check(edit.commit()) { "Could not save signed reply for retry" }
    }
    @Synchronized fun complete(draft: ReplyDraft) {
        check(drafts.edit().remove(key(draft.owner, draft.target)).commit()) { "Could not clear reply draft" }
        check(pending.edit().remove(key(draft.owner, draft.target)).commit()) { "Could not clear reply retry" }
    }
}

/** Preserve thread scope; ordinary notes remain interoperable NIP-10 replies. */
internal fun replyTags(target: Event, relay: String): Pair<Int, Array<Array<String>>> {
    val hex = Regex("[0-9a-f]{64}")
    val tags = mutableListOf<Array<String>>()
    if (target.kind == 1) {
        val es = target.tags.filter { it.size > 1 && it[0] == "e" && hex.matches(it[1]) }
        val root = es.firstOrNull { it.getOrNull(3) == "root" }
            ?: es.firstOrNull { it.size < 4 || it[3].isEmpty() }
        if (root == null) tags += arrayOf("e", target.id, relay, "root", target.pubKey)
        else {
            tags += arrayOf("e", root[1], root.getOrNull(2)?.let(Protocol::relay).orEmpty(), "root", root.getOrNull(4)?.takeIf(hex::matches).orEmpty())
            tags += arrayOf("e", target.id, relay, "reply", target.pubKey)
        }
        (listOf(target.pubKey) + target.tags.filter { it.firstOrNull() == "p" }.mapNotNull { it.getOrNull(1)?.takeIf(hex::matches) })
            .distinct().take(64).forEach { tags += arrayOf("p", it) }
        return 1 to tags.toTypedArray()
    }
    if (target.kind == 1111) {
        val root = target.tags.firstOrNull { it.size >= 2 && it[0] in listOf("E", "A", "I") }
            ?: error("The parent comment has no root scope")
        val kind = target.tagValue("K") ?: error("The parent comment has no root kind")
        tags += root.copyOf()
        tags += arrayOf("K", kind)
        target.tags.firstOrNull { it.size >= 2 && it[0] == "P" }?.let { tags += it.copyOf() }
    } else {
        val address = target.tagValue("d")?.takeIf { target.kind in 30000..39999 }?.let { "${target.kind}:${target.pubKey}:$it" }
        tags += if (address != null) arrayOf("A", address, relay) else arrayOf("E", target.id, relay, target.pubKey)
        tags += arrayOf("K", target.kind.toString())
        tags += arrayOf("P", target.pubKey, relay)
    }
    if (target.kind in 30000..39999) target.tagValue("d")?.let { tags += arrayOf("a", "${target.kind}:${target.pubKey}:$it", relay) }
    tags += arrayOf("e", target.id, relay, target.pubKey)
    tags += arrayOf("k", target.kind.toString())
    tags += arrayOf("p", target.pubKey, relay)
    return 1111 to tags.toTypedArray()
}

internal val profileMentionPattern = Regex("(?:nostr:)?(?:npub|nprofile)1[023456789acdefghjklmnpqrstuvwxyz]+", RegexOption.IGNORE_CASE)
internal fun replyMentions(text: String): List<String> = profileMentionPattern.findAll(text).take(32)
    .mapNotNull { NostrContent.decode(it.value)?.takeIf { ref -> ref.profile }?.id }.distinct().toList()

private data class MentionInput(val start: Int, val end: Int, val query: String)
private fun mentionInput(value: TextFieldValue): MentionInput? {
    if (!value.selection.collapsed) return null
    val end = value.selection.end.coerceIn(0, value.text.length)
    val start = value.text.lastIndexOf('@', (end - 1).coerceAtLeast(0))
    if (start < 0 || start >= end || (start > 0 && !value.text[start - 1].isWhitespace())) return null
    val query = value.text.substring(start + 1, end)
    return if (query.length in 2..100 && query.none { it.isWhitespace() }) MentionInput(start, end, query) else null
}

/** The draft retains real nostr: references; the editor displays compact human-readable mentions. */
private fun mentionTransformation(text: String, labels: Map<String, String>): VisualTransformation {
    val replacements = profileMentionPattern.findAll(text).mapNotNull { match ->
        labels[match.value]?.let { match.range to "@$it" }
    }.toList()
    if (replacements.isEmpty()) return VisualTransformation.None
    return VisualTransformation { input ->
        if (input.text != text) TransformedText(input, OffsetMapping.Identity)
        else {
            val output = StringBuilder()
            val forward = IntArray(text.length + 1)
            val reverse = mutableListOf(0)
            var cursor = 0
            fun append(end: Int, shown: String) {
                val originalLength = end - cursor
                val start = output.length
                for (i in 0..originalLength) forward[cursor + i] = start + (i * shown.length / originalLength.coerceAtLeast(1))
                for (i in 1..shown.length) reverse.add(cursor + i * originalLength / shown.length)
                output.append(shown)
                cursor = end
            }
            for ((range, label) in replacements) {
                if (cursor < range.first) append(range.first, text.substring(cursor, range.first))
                append(range.last + 1, label)
            }
            if (cursor < text.length) append(text.length, text.substring(cursor))
            TransformedText(AnnotatedString(output.toString()), object : OffsetMapping {
                override fun originalToTransformed(offset: Int) = forward[offset.coerceIn(0, forward.lastIndex)]
                override fun transformedToOriginal(offset: Int) = reverse[offset.coerceIn(0, reverse.lastIndex)]
            })
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReplyComposer(vm: FeatherViewModel) {
    val draft by vm.replyDraft.collectAsStateWithLifecycle()
    val busy by vm.replyBusy.collectAsStateWithLifecycle()
    val uploading by vm.imageUploading.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val current = draft ?: return
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val foreground = lifecycle.isAtLeast(Lifecycle.State.STARTED)
    val media = (LocalContext.current.applicationContext as FeatherApplication).media
    val resources by media.resources.collectAsStateWithLifecycle()
    val focus = remember(current.owner, current.target?.id) { FocusRequester() }
    var editor by remember(current.owner, current.target?.id) { mutableStateOf(TextFieldValue(current.text, TextRange(current.text.length))) }
    var chosenNames by remember(current.owner, current.target?.id) { mutableStateOf<Map<String, String>>(emptyMap()) }
    LaunchedEffect(current.text) {
        if (editor.text != current.text) editor = TextFieldValue(current.text, TextRange(current.text.length))
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(4)) { uris -> vm.uploadImages(uris) }
    val images by produceState<List<String>>(emptyList(), current.text) {
        value = withContext(Dispatchers.Default) {
            Regex("https://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(current.text).map { it.value }
                .filter { url -> runCatching { java.net.URI(url).path?.substringAfterLast('.')?.lowercase() in listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif") }.getOrDefault(false) }
                .distinct().take(8).toList()
        }
    }
    val mention = remember(editor) { mentionInput(editor) }
    var suggestions by remember { mutableStateOf<List<SearchProfile>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(mention?.query, current.owner, busy, foreground) {
        suggestions = emptyList(); searching = false; searchError = null
        val query = mention?.query ?: return@LaunchedEffect
        if (busy || !foreground) return@LaunchedEffect
        searching = true
        try { delay(350); suggestions = VertexProfileSearch.search(query).take(5) }
        catch (_: TimeoutCancellationException) { searchError = "Vertex mention search timed out" }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { searchError = "Vertex mention search: ${e.message}" }
        finally { searching = false }
    }
    val labels by produceState<Map<String, String>>(emptyMap(), current.text, chosenNames, state.profiles) {
        value = withContext(Dispatchers.Default) {
            profileMentionPattern.findAll(current.text).take(32).mapNotNull { match ->
                NostrContent.decode(match.value)?.takeIf { it.profile }?.let { ref ->
                    match.value to (chosenNames[ref.id] ?: state.profiles[ref.id]?.name ?: FeatherRepository.shortKey(ref.id))
                }
            }.toMap()
        }
    }
    val transform = remember(current.text, labels) { mentionTransformation(current.text, labels) }
    val preview by produceState("", current.target?.id) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            current.target?.content?.withoutObjectMarkers()?.take(500).orEmpty()
        }
    }
    val sendButton: @Composable (Modifier) -> Unit = { modifier ->
        FilledIconButton(onClick = vm::sendReply, enabled = !busy && !uploading && current.text.isNotBlank(),
            modifier = modifier, shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary)) {
            if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = LocalContentColor.current)
            else Icon(Icons.AutoMirrored.Filled.Send, if (current.target == null) "Publish post" else "Send reply")
        }
    }
    val composer: @Composable () -> Unit = {
        CopyableMessageHost()
        CompositionLocalProvider(LocalMedia provides MediaEnvironment(resources, foreground, null)) {
        Column(Modifier.fillMaxWidth().imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = if (current.target == null) 152.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = vm::closeReply) { Icon(Icons.Default.Close, "Close and keep draft") }
                Spacer(Modifier.weight(1f))
                IconButton(enabled = !busy && !uploading, onClick = {
                    imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) {
                    if (uploading) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Icon(painterResource(R.drawable.photos), "Attach images")
                }
                if (current.target != null) sendButton(Modifier.size(48.dp))
            }
            current.target?.let { target ->
                val profile = state.profiles[target.pubKey]
                Text(profile?.name ?: FeatherRepository.shortKey(target.pubKey), style = MaterialTheme.typography.titleSmall)
                Text(preview, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextField(value = editor, onValueChange = { next ->
                if (next.text.length <= 8000) { editor = next; vm.editReply(next.text) }
            }, visualTransformation = transform, enabled = !busy,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp).focusRequester(focus).semantics { contentDescription = if (current.target == null) "Post text" else "Reply text" },
                colors = TextFieldDefaults.colors(focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface))
            if (images.isNotEmpty()) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                images.forEach { url ->
                    Column {
                        RemoteImage(url, Modifier.size(88.dp), "Attached image")
                        IconButton(enabled = !busy, onClick = { vm.editReply(current.text.replace(url, "").trim()) }) {
                            Icon(Icons.Default.Close, "Remove image from post")
                        }
                    }
                }
            }
            if (searching) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
            searchError?.let { error -> IconButton(onClick = { AppMessages.show(error) }) {
                Icon(androidx.compose.material.icons.Icons.Default.Info, "Mention search error")
            } }
            if (mention != null && !busy) suggestions.filter { it.event.pubKey !in state.mutedAuthors && (state.showSensitive || !it.sensitive) }.forEach { profile -> key(profile.event.pubKey) {
                Row(Modifier.fillMaxWidth().clickable(onClickLabel = "Mention ${profile.name}") {
                    val input = mentionInput(editor) ?: return@clickable
                    val reference = "nostr:${Protocol.npub(profile.event.pubKey)} "
                    val text = editor.text.replaceRange(input.start, input.end, reference)
                    if (text.length <= 8000) {
                        chosenNames = chosenNames + (profile.event.pubKey to profile.name)
                        editor = TextFieldValue(text, TextRange(input.start + reference.length))
                        vm.editReply(text)
                        focus.requestFocus()
                    }
                }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Avatar(profile.name, 32, profile.picture)
                    Text(profile.name, modifier = Modifier.weight(1f), maxLines = 1,
                        overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
                    val following = profile.event.pubKey in state.following
                    Box(Modifier.size(28.dp).semantics(mergeDescendants = true) {
                        contentDescription = if (following) "Following" else "Not following"
                    }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Person, null, Modifier.size(20.dp),
                            tint = if (following) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        if (following) Icon(Icons.Default.Check, null, Modifier.size(12.dp).align(Alignment.BottomEnd),
                            tint = MaterialTheme.colorScheme.primary)
                    }
                    if (!profile.identity.isNullOrBlank()) {
                        Nip05Identity(profile.identity, profile.event.pubKey, iconOnly = true)
                    } else Spacer(Modifier.size(40.dp).semantics { contentDescription = "No NIP-05 identity" })
                }
            } }
        }
        }
        LaunchedEffect(current.owner, current.target?.id) { focus.requestFocus() }
    }
    if (current.target == null) {
        Dialog(onDismissRequest = vm::closeReply,
            properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
                Box(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
                    composer()
                    sendButton(Modifier.align(Alignment.BottomEnd).padding(end = 24.dp, bottom = 80.dp).size(56.dp))
                }
            }
        }
    } else {
        ModalBottomSheet(onDismissRequest = vm::closeReply,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = MaterialTheme.colorScheme.surface) { composer() }
    }
}
