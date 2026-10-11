package org.dergigi.feather

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.vitorpamplona.quartz.nip01Core.core.Event

internal data class ZapActions(val pending: Set<String> = emptySet(), val confirmed: Set<String> = emptySet(), val zap: (Event) -> Unit = {})
internal val LocalZapActions = staticCompositionLocalOf { ZapActions() }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WalletSheet(vm: FeatherViewModel, onDismiss: () -> Unit) {
    val connected by vm.wallet.store.connected.collectAsStateWithLifecycle()
    val busy by vm.walletBusy.collectAsStateWithLifecycle()
    val paying by vm.zapping.collectAsStateWithLifecycle()
    val error by vm.walletError.collectAsStateWithLifecycle()
    val details by vm.walletDetails.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var loadingDetails by remember { mutableStateOf(false) }
    LaunchedEffect(connected, busy, paying, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (connected && !busy && paying.isEmpty()) {
                loadingDetails = true
                try { vm.refreshWalletDetails() } finally { loadingDetails = false }
            }
        }
    }
    var input by remember { mutableStateOf("") } // Never save credentials in saved-instance state.
    var scanning by remember { mutableStateOf(false) }
    var scanError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scanning = true else scanError = "Camera permission is required to scan a QR code."
    }
    if (scanning && !connected && !busy) WalletQrScanner(
        onDismiss = { scanning = false },
        onError = { scanning = false; scanError = "Could not open the camera." },
        onScan = { raw ->
            scanning = false
            scope.launch {
                val valid = withContext(Dispatchers.Default) {
                    runCatching { WalletConnection.parse(raw).also { it.secret.fill(0) } }.isSuccess
                }
                if (valid) { input = raw.trim(); scanError = null }
                else scanError = "This QR code is not a valid Nostr Wallet Connect connection."
            }
        },
    )
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Wallet", style = MaterialTheme.typography.titleLarge)
            if (connected) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(details.name ?: "Wallet connected", modifier = Modifier.weight(1f).padding(end = 12.dp),
                        style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    details.balanceSats?.let { balance ->
                        val amount = remember(balance) { java.text.NumberFormat.getIntegerInstance().format(balance) }
                        Text("$amount sats", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (loadingDetails) CircularProgressIndicator(Modifier.padding(horizontal = 12.dp).size(16.dp), strokeWidth = 2.dp)
                    IconButton(enabled = !busy && paying.isEmpty(), onClick = vm::disconnectWallet) { Icon(Icons.Default.Delete, "Remove wallet connection") }
                }
            } else {
                OutlinedTextField(value = input, onValueChange = { input = it.take(8192) }, singleLine = true,
                    enabled = !busy, label = { Text("Nostr Wallet Connect") },
                    visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End)) {
                    IconButton(enabled = !busy, onClick = {
                        scanError = null
                        cameraPermission.launch(Manifest.permission.CAMERA)
                    }) { Icon(painterResource(R.drawable.qr_scan), "Scan wallet QR code") }
                    IconButton(enabled = !busy && input.isNotBlank(), onClick = {
                        scanError = null
                        vm.connectWallet(input); input = ""
                    }) {
                        if (busy) CircularProgressIndicator(
                            Modifier.size(24.dp).semantics { contentDescription = "Connecting wallet" }, strokeWidth = 2.dp,
                        ) else Icon(Icons.Default.Check, "Connect wallet")
                    }
                }
                scanError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            if (busy && connected) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
