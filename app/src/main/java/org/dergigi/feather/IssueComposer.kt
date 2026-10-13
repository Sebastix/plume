package org.dergigi.feather

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.quartz.nip55AndroidSigner.api.SignerResult
import com.vitorpamplona.quartz.nip55AndroidSigner.client.ExternalSignerLogin

@Composable
internal fun IssueComposer(vm: FeatherViewModel) {
    val reports = vm.issues
    val visible by reports.visible.collectAsStateWithLifecycle()
    if (!visible) return
    val draft by reports.draft.collectAsStateWithLifecycle()
    val busy by reports.busy.collectAsStateWithLifecycle()
    val error by reports.error.collectAsStateWithLifecycle()
    val published by reports.published.collectAsStateWithLifecycle()
    val signer by vm.signer.collectAsStateWithLifecycle()
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val parsed = ExternalSignerLogin.parseResult(data)
            if (parsed is SignerResult.RequestAddressed.Successful) vm.login(parsed.result.pubkey, parsed.result.packageName)
        }
    }
    Dialog(onDismissRequest = reports::close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = reports::close) { Icon(Icons.Default.Close, "Close issue draft") }
                    Spacer(Modifier.weight(1f))
                    Icon(painterResource(if (draft?.type == IssueType.FEATURE) R.drawable.feature_request else R.drawable.bug_report), null,
                        tint = MaterialTheme.colorScheme.primary)
                }
                val current = draft
                if (current == null) {
                    if (error != null) Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
                    else CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                    return@Column
                }
                if (published != null) {
                    Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center) {
                        Icon(Icons.Default.Check, "Issue published", Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://gitworkshop.dev/${published}"))) }
                                .onFailure { AppMessages.show("Could not open issue") }
                        }) { Text("View issue") }
                    }
                } else {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(if (current.type == IssueType.FEATURE) "Suggest a feature" else "Report a bug", style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(current.title, { reports.edit(it, current.body) }, enabled = !busy,
                            label = { Text("Title") }, singleLine = true, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth())
                        TextField(current.body, { reports.edit(current.title, it) }, enabled = !busy,
                            placeholder = { Text(if (current.type == IssueType.FEATURE) "Your idea" else "What happened?") },
                            shape = RoundedCornerShape(20.dp), minLines = 8, modifier = Modifier.fillMaxWidth(),
                            colors = TextFieldDefaults.colors(focusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedIndicatorColor = androidx.compose.ui.graphics.Color.Transparent))
                        val name = signer?.pubKey?.let { state.profiles[it]?.name ?: FeatherRepository.shortKey(it) }
                        Text(if (name != null) "Public issue · $name" else "Public issue · sign in to send",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        error?.let { text -> TextButton(onClick = { AppMessages.show(text) }) {
                            Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        } }
                    }
                    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.End) {
                        if (signer == null) FilledTonalIconButton(onClick = {
                            try { login.launch(ExternalSignerLogin.createIntent(emptyList())) }
                            catch (_: Exception) { reports.error.value = "Install an Android signer such as Amber first." }
                        }, modifier = Modifier.size(56.dp)) { Icon(painterResource(R.drawable.login), "Sign in to report an issue") }
                        else FilledIconButton(onClick = reports::send, enabled = !busy && current.title.isNotBlank() && current.body.isNotBlank(),
                            modifier = Modifier.size(56.dp)) {
                            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                            else Icon(Icons.AutoMirrored.Filled.Send, "Publish public issue")
                        }
                    }
                }
            }
            CopyableMessageHost()
        }
    }
}
