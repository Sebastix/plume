package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vitorpamplona.quartz.nip01Core.core.Event

internal data class ZapActions(val pending: String? = null, val disabled: Boolean = false, val confirmed: Set<String> = emptySet(), val zap: (Event) -> Unit = {})
internal val LocalZapActions = staticCompositionLocalOf { ZapActions() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletSheet(vm: FeatherViewModel, onDismiss: () -> Unit) {
    val connected by vm.wallet.store.connected.collectAsStateWithLifecycle()
    val busy by vm.walletBusy.collectAsStateWithLifecycle()
    val paying by vm.zapping.collectAsStateWithLifecycle()
    val error by vm.walletError.collectAsStateWithLifecycle()
    var input by remember { mutableStateOf("") } // Never save credentials in saved-instance state.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Wallet", style = MaterialTheme.typography.titleLarge)
            if (connected) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Nostr Wallet Connect · Connected")
                    IconButton(enabled = !busy && paying == null, onClick = vm::disconnectWallet) { Icon(Icons.Default.Delete, "Remove wallet connection") }
                }
            } else {
                OutlinedTextField(value = input, onValueChange = { input = it.take(8192) }, singleLine = true,
                    enabled = !busy, label = { Text("Nostr Wallet Connect") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                IconButton(enabled = !busy && input.isNotBlank(), onClick = {
                    vm.connectWallet(input); input = ""
                }) { Icon(Icons.Default.Check, "Connect wallet") }
            }
            if (busy) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
