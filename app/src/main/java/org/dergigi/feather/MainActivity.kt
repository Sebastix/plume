package org.dergigi.feather

import android.app.Activity.RESULT_OK
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip55AndroidSigner.api.CommandType
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.api.permission.Permission
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Sky = Color(0xFF79C4F2)
private val Ink = Color(0xFF293945)
private val Blue = Color(0xFF2479A9)
private val Mist = Color(0xFFF0F9FF)

class MainActivity : ComponentActivity() {
    private val repo get() = (application as FeatherApplication).repository
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Blue, onPrimary = Color.White,
                primaryContainer = Mist, onPrimaryContainer = Ink, background = Color.White,
                surface = Color.White, onSurface = Ink, onBackground = Ink, surfaceContainer = Mist,
                outlineVariant = Color(0xFFEDF1F4))) { FeatherApp() }
        }
    }
    override fun onStart() { super.onStart(); repo.foreground() }
    override fun onStop() { repo.background(); super.onStop() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatherApp(vm: FeatherViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val posted by vm.posted.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    var screen by rememberSaveable { mutableStateOf("feed") }
    var profileKey by rememberSaveable { mutableStateOf<String?>(null) }
    var threadId by rememberSaveable { mutableStateOf<String?>(null) }
    var reply by remember { mutableStateOf(vm.repo.draftReply()) }
    var showAccount by rememberSaveable { mutableStateOf(false) }
    var showFind by rememberSaveable { mutableStateOf(false) }
    var previousPosted by rememberSaveable { mutableIntStateOf(posted) }
    val listState = rememberLazyListState()
    fun goHome() { screen = "feed"; profileKey = null; threadId = null; vm.repo.viewProfile(null); vm.repo.viewThread(null) }
    fun openProfile(key: String) { profileKey = key; screen = "profile"; vm.repo.viewProfile(key); vm.repo.viewThread(null) }
    val signingLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { vm.signerResult(if (it.resultCode == RESULT_OK) it.data else null) }
    DisposableEffect(signingLauncher, s.publicKey, s.signerPackage) {
        vm.bindLauncher { intent -> scope.launch { try { signingLauncher.launch(intent) } catch (_: Exception) { vm.signerResult(null) } } }
        onDispose { vm.bindLauncher(null) }
    }
    val loginLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val data = it.data
        if (it.resultCode == RESULT_OK && data != null) {
            val result = ExternalSignerLogin.parseResult(data)
            if (result is SignerResult.RequestAddressed.Successful) {
                vm.login(result.result.pubkey, result.result.packageName); showAccount = false; goHome()
            } else vm.message.value = "Could not connect the signer. Please try again."
        }
    }
    LaunchedEffect(message) { message?.let { snack.showSnackbar(it); vm.message.value = null } }
    LaunchedEffect(posted) { if (posted != previousPosted) { previousPosted = posted; goHome(); reply = null } }
    LaunchedEffect(screen, profileKey, threadId) {
        vm.repo.viewProfile(profileKey.takeIf { screen == "profile" })
        vm.repo.viewThread(threadId.takeIf { screen == "thread" })
    }
    BackHandler(screen != "feed" && !busy) { goHome() }
    Scaffold(
        snackbarHost = { SnackbarHost(snack) },
        topBar = {
            TopAppBar(title = {
                if (screen == "feed") Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(R.drawable.feather), null, Modifier.size(32.dp), tint = Color.Unspecified)
                    Spacer(Modifier.width(6.dp)); Text("feather", fontWeight = FontWeight.Medium)
                } else Text(when (screen) { "profile" -> "Profile"; "thread" -> "Conversation"; "compose" -> if (reply == null) "New note" else "Reply"; else -> "Settings" })
            }, navigationIcon = {
                if (screen != "feed") IconButton(onClick = { goHome() }, enabled = !busy) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            }, actions = {
                if (screen == "feed") {
                    IconButton(onClick = { showFind = true }) { Icon(Icons.Default.Search, "Find a profile") }
                    IconButton(onClick = { showAccount = true }) { Icon(Icons.Default.Person, "Account") }
                    IconButton(onClick = { screen = "settings" }) { Icon(Icons.Default.Settings, "Settings") }
                }
            }, colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.White))
        },
        floatingActionButton = {
            if (screen in listOf("feed", "profile", "thread")) FloatingActionButton(onClick = {
                if (s.signerPackage == null) showAccount = true else { reply = if (screen == "thread") s.notes.find { it.id == threadId } else vm.repo.draftReply(); vm.repo.saveDraftReply(reply); screen = "compose" }
            }, containerColor = Mist, contentColor = Blue) { Icon(Icons.Default.Create, "Write a note") }
        }, containerColor = Color.White,
    ) { padding ->
        when (screen) {
            "compose" -> Composer(vm, reply, Modifier.padding(padding))
            "settings" -> Settings(s, vm, Modifier.padding(padding))
            else -> {
                val notes = when (screen) {
                    "profile" -> s.notes.filter { it.pubKey == profileKey }
                    "thread" -> s.notes.filter { it.id == threadId || it.tags.any { tag -> tag.size >= 2 && tag[0] == "e" && tag[1] == threadId && (tag.size < 4 || tag[3] != "mention") } }.sortedBy { it.createdAt }
                    else -> s.notes.filter { !Protocol.isReply(it) && (s.publicKey == null || it.pubKey == s.publicKey || it.pubKey in s.following) }
                }
                LazyColumn(Modifier.padding(padding).fillMaxSize(), state = if (screen == "feed") listState else rememberLazyListState(), contentPadding = PaddingValues(bottom = 100.dp)) {
                    if (screen == "feed") item("heading") {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(if (s.publicKey == null) "Around Nostr" else "Following", style = MaterialTheme.typography.titleMedium)
                                Text(if (s.publicKey == null) "Connect your account for your own feed." else "Latest notes, in order.", style = MaterialTheme.typography.bodySmall, color = Color(0xFF647582))
                            }
                            IconButton(onClick = vm.repo::refresh, enabled = !s.loading) { Icon(Icons.Default.Refresh, "Refresh feed", tint = Blue) }
                        }
                    }
                    if (screen == "profile") item("profile-header") {
                        val key = profileKey!!
                        val p = s.profiles[key]
                        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Avatar(p?.name ?: key, 56)
                            Text(p?.name ?: FeatherRepository.shortKey(key), style = MaterialTheme.typography.headlineSmall)
                            if (!p?.about.isNullOrBlank()) Text(p.about)
                            SelectionContainer { Text(Protocol.npub(key), style = MaterialTheme.typography.bodySmall, color = Color(0xFF647582)) }
                            if (s.publicKey != key) OutlinedButton(onClick = { if (s.signerPackage == null) showAccount = true else vm.toggleFollow(key) }, enabled = !busy) {
                                Text(if (busy) "Updating…" else if (key in s.following) "Unfollow" else "Follow")
                            }
                        }
                    }
                    if (s.loading) item("progress") { LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp), color = Sky) }
                    s.error?.let { error -> item("error") { Text(error, Modifier.padding(20.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) } }
                    if (notes.isEmpty() && !s.loading) item("empty") {
                        Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Icon(painterResource(R.drawable.feather), null, Modifier.size(64.dp), tint = Color.Unspecified)
                            Text(if (screen == "thread") "No notes here yet." else "A little quiet here.", style = MaterialTheme.typography.titleMedium)
                            Text(if (s.publicKey != null && s.following.isEmpty() && screen == "feed") "Find someone by their npub and follow them, or refresh to load your existing follows." else "Try refreshing or choosing another relay.", style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = vm.repo::refresh) { Text("Refresh") }
                        }
                    }
                    items(notes, key = { it.id }) { note ->
                        NoteRow(note, s.profiles[note.pubKey], onProfile = { openProfile(note.pubKey) }, onOpen = { threadId = note.id; screen = "thread" }, onReply = {
                            if (s.signerPackage == null) showAccount = true else { reply = note; vm.repo.saveDraftReply(note); screen = "compose" }
                        })
                        HorizontalDivider(color = Color(0xFFEDF1F4), modifier = Modifier.padding(horizontal = 20.dp))
                    }
                    if (notes.isNotEmpty() && screen != "thread") item("older") { TextButton(onClick = { vm.repo.older(profileKey.takeIf { screen == "profile" }) }, enabled = !s.loading, modifier = Modifier.fillMaxWidth().padding(12.dp)) { Text("Load older notes") } }
                }
            }
        }
    }
    if (showAccount) {
        var keyInput by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { if (!busy) showAccount = false }, title = { Text(if (s.publicKey == null) "Your Nostr account" else "Account") }, text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (s.publicKey != null) {
                    Text(s.profiles[s.publicKey]?.name ?: "Connected account")
                    SelectionContainer { Text(Protocol.npub(s.publicKey!!), style = MaterialTheme.typography.bodySmall) }
                    Text(if (s.signerPackage != null) "Signing with your Android signer." else "Read-only account.")
                }
                Text("Connect an Android signer such as Amber. Your private key stays in the signer.")
                Button(onClick = {
                    try { loginLauncher.launch(ExternalSignerLogin.createIntent(listOf(Permission(CommandType.SIGN_EVENT, 1), Permission(CommandType.SIGN_EVENT, 3)))) }
                    catch (_: Exception) { error = "Install an Android signer such as Amber first." }
                }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Connect signer") }
                if (error != null) {
                    Text(error!!, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://zapstore.dev/apps/com.greenart7c3.nostrsigner"))) }) { Text("Get Amber") }
                }
                OutlinedTextField(keyInput, { keyInput = it }, label = { Text("Or paste an npub to read only") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = {
                    val key = Protocol.publicKey(keyInput)
                    if (key == null) error = "Enter a valid npub, nprofile, or public hex key."
                    else { vm.login(key, null); showAccount = false; goHome() }
                }, enabled = !busy && keyInput.isNotBlank()) { Text("Read this account") }
                if (s.publicKey != null) TextButton(onClick = { vm.logout(); showAccount = false; goHome() }, enabled = !busy) { Text("Disconnect account") }
            }
        }, confirmButton = { TextButton(onClick = { showAccount = false }) { Text("Done") } })
    }
    if (showFind) {
        var input by remember { mutableStateOf("") }
        var invalid by remember { mutableStateOf(false) }
        AlertDialog(onDismissRequest = { showFind = false }, title = { Text("Find a profile") }, text = {
            OutlinedTextField(input, { input = it; invalid = false }, label = { Text("npub or nprofile") }, isError = invalid,
                supportingText = { if (invalid) Text("Enter a valid public key.") }, singleLine = true)
        }, confirmButton = { TextButton(onClick = { val key = Protocol.publicKey(input); if (key == null) invalid = true else { openProfile(key); showFind = false } }) { Text("Open") } }, dismissButton = { TextButton(onClick = { showFind = false }) { Text("Cancel") } })
    }
}

@Composable
private fun Avatar(name: String, size: Int = 36) {
    Box(Modifier.size(size.dp).background(Mist, CircleShape), contentAlignment = Alignment.Center) {
        Text(name.take(1).uppercase(), color = Blue, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun NoteRow(note: Event, profile: Profile?, onProfile: () -> Unit, onOpen: () -> Unit, onReply: () -> Unit) {
    val context = LocalContext.current
    var expanded by remember(note.id) { mutableStateOf(false) }
    val name = profile?.name ?: FeatherRepository.shortKey(note.pubKey)
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).clickable(onClick = onProfile), verticalAlignment = Alignment.CenterVertically) {
                Avatar(name); Spacer(Modifier.width(10.dp)); Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleSmall)
            }
            Text(remember(note.createdAt) { DateTimeFormatter.ofPattern("MMM d · HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochSecond(note.createdAt)) }, style = MaterialTheme.typography.labelSmall, color = Color(0xFF647582))
        }
        Spacer(Modifier.height(12.dp))
        SelectionContainer { Text(note.content, style = MaterialTheme.typography.bodyLarge, maxLines = if (expanded) Int.MAX_VALUE else 12, overflow = TextOverflow.Ellipsis) }
        if (note.content.length > 600 || note.content.count { it == '\n' } > 10) TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) { Text(if (expanded) "Show less" else "Read more") }
        val links = remember(note.id) { Regex("https?://[^\\s<>]+", RegexOption.IGNORE_CASE).findAll(note.content).map { it.value.trimEnd('.', ',', ')', ']', '!', '?') }.distinct().take(3).toList() }
        links.forEach { link -> TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link))) } }, contentPadding = PaddingValues(0.dp)) { Text(Uri.parse(link).host ?: "Open link", maxLines = 1, overflow = TextOverflow.Ellipsis) } }
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onOpen, contentPadding = PaddingValues(end = 16.dp)) { Text("Conversation", style = MaterialTheme.typography.labelMedium) }
            TextButton(onClick = onReply) { Text("Reply", style = MaterialTheme.typography.labelMedium) }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "https://njump.to/${com.vitorpamplona.quartz.nip19Bech32.entities.NNote.create(note.id)}") }, "Share note")) }) { Icon(Icons.Default.Share, "Share note", Modifier.size(18.dp), tint = Color(0xFF647582)) }
        }
    }
}

@Composable
private fun Composer(vm: FeatherViewModel, reply: Event?, modifier: Modifier) {
    val draft by vm.draft.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    Column(modifier.fillMaxSize().imePadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        reply?.let { Text("Replying to ${vm.state.value.profiles[it.pubKey]?.name ?: FeatherRepository.shortKey(it.pubKey)}", color = Blue); Text(it.content, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall) }
        OutlinedTextField(draft, vm::editDraft, placeholder = { Text("What's on your mind?") }, modifier = Modifier.fillMaxWidth().weight(1f), enabled = !busy)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("${draft.length}/10,000 · Public", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Button(onClick = { vm.post(reply) }, enabled = draft.isNotBlank() && !busy) {
                if (busy) { CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp); Spacer(Modifier.width(8.dp)) }
                else { Icon(Icons.AutoMirrored.Filled.Send, null, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp)) }
                Text(if (busy) "Publishing…" else "Publish")
            }
        }
    }
}

@Composable
private fun Settings(state: FeedState, vm: FeatherViewModel, modifier: Modifier) {
    var relays by remember(state.relays) { mutableStateOf(state.relays.joinToString("\n")) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Text("Relays", style = MaterialTheme.typography.titleMedium); Text("${state.connected.size} connected · up to four relays", style = MaterialTheme.typography.bodySmall) }
        item { OutlinedTextField(relays, { relays = it }, modifier = Modifier.fillMaxWidth(), label = { Text("One wss:// address per line") }, minLines = 4, isError = error != null); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { Button(onClick = { try { vm.repo.setRelays(relays); error = null; vm.message.value = "Relays updated." } catch (e: IllegalArgumentException) { error = e.message } }) { Text("Save relays") } }
        item { HorizontalDivider() }
        item { Text("A little lighter.", style = MaterialTheme.typography.headlineSmall); Spacer(Modifier.height(8.dp)); Text("Feather ${BuildConfig.VERSION_NAME}\nA small, native Nostr client.\nNo DMs. No ads. No analytics.", style = MaterialTheme.typography.bodyMedium) }
        item { Text("Cached notes stay on this device. Relay connections pause when you leave the app. Public notes and follows are sent to your selected relays.", style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/dergigi/feather"))) }) { Text("Source code & feedback") } }
    }
}
