package org.dergigi.feather

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ProfileFollowButton(vm: FeatherViewModel, target: String) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = state.publicKey ?: return
    if (target == owner) return
    val busy by vm.followBusy.collectAsStateWithLifecycle()
    val pending by vm.firstFollow.collectAsStateWithLifecycle()
    val following = target in state.followedAccounts
    IconButton(onClick = { vm.changeFollow(target, !following) }, enabled = busy == null) {
        if (busy == target) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(24.dp)) {
            Icon(Icons.Default.Person, stringResource(if (following) R.string.ui_unfollow_profile else R.string.ui_follow_profile),
                Modifier.size(20.dp).align(Alignment.CenterStart), tint = MaterialTheme.colorScheme.primary)
            Icon(if (following) Icons.Default.Check else Icons.Default.Add, null,
                Modifier.size(12.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (pending == (owner to target)) AlertDialog(
        onDismissRequest = { vm.firstFollow.value = null },
        title = { Text(stringResource(R.string.ui_first_follow_title)) },
        text = { Text(stringResource(R.string.ui_first_follow_body)) },
        confirmButton = { TextButton(onClick = { vm.changeFollow(target, true, allowCreate = true) }) {
            Text(stringResource(R.string.ui_first_follow_create))
        } },
        dismissButton = { TextButton(onClick = { vm.firstFollow.value = null }) {
            Text(stringResource(R.string.ui_follow_cancel))
        } }
    )
}
