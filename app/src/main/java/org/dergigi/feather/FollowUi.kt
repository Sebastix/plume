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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun ProfileFollowButton(vm: FeatherViewModel, target: String, size: Int? = null) {
    val state by vm.state.collectAsStateWithLifecycle()
    val owner = state.publicKey ?: return
    if (target == owner) return
    val busy by vm.followBusy.collectAsStateWithLifecycle()
    val following = target in state.followedAccounts
    IconButton(onClick = { vm.changeFollow(target, !following) }, enabled = target !in busy,
        modifier = if (size != null) Modifier.size(size.dp) else Modifier,
        colors = IconButtonDefaults.iconButtonColors(
            containerColor = if (size != null) MaterialTheme.colorScheme.secondaryContainer else androidx.compose.ui.graphics.Color.Transparent)) {
        if (target in busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(24.dp)) {
            Icon(Icons.Default.Person, stringResource(if (following) R.string.ui_unfollow_profile else R.string.ui_follow_profile),
                Modifier.size(20.dp).align(Alignment.CenterStart), tint = MaterialTheme.colorScheme.primary)
            Icon(if (following) Icons.Default.Check else Icons.Default.Add, null,
                Modifier.size(12.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
internal fun ProfileFollowConfirmation(vm: FeatherViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val pending by vm.firstFollow.collectAsStateWithLifecycle()
    val owner = state.publicKey ?: return
    val request = pending ?: return
    if (request.first != owner || request.second.startsWith("#")) return
    val target = request.second
    AlertDialog(
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

@Composable
internal fun HashtagFollowButton(vm: FeatherViewModel, target: String, onLogin: () -> Unit) {
    val tag = normalizeFollowedTag(target) ?: return
    val state by vm.state.collectAsStateWithLifecycle()
    val busy by vm.followBusy.collectAsStateWithLifecycle()
    val pending by vm.firstFollow.collectAsStateWithLifecycle()
    val actionKey = "#$tag"
    val following = tag in state.followedHashtags
    IconButton(onClick = {
        if (state.publicKey == null) onLogin() else vm.changeHashtagFollow(tag, !following)
    }, enabled = actionKey !in busy) {
        if (actionKey in busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        else Box(Modifier.size(24.dp)) {
            Icon(painterResource(R.drawable.hashtag),
                stringResource(if (following) R.string.ui_unfollow_hashtag else R.string.ui_follow_hashtag),
                Modifier.size(20.dp).align(Alignment.CenterStart), tint = MaterialTheme.colorScheme.primary)
            Icon(if (following) Icons.Default.Check else Icons.Default.Add, null,
                Modifier.size(12.dp).align(Alignment.BottomEnd), tint = MaterialTheme.colorScheme.primary)
        }
    }
    if (state.publicKey != null && pending == (state.publicKey to actionKey)) AlertDialog(
        onDismissRequest = { vm.firstFollow.value = null },
        title = { Text(stringResource(R.string.ui_first_hashtag_title)) },
        text = { Text(stringResource(R.string.ui_first_hashtag_body)) },
        confirmButton = { TextButton(onClick = { vm.changeHashtagFollow(tag, true, allowCreate = true) }) {
            Text(stringResource(R.string.ui_first_follow_create))
        } },
        dismissButton = { TextButton(onClick = { vm.firstFollow.value = null }) {
            Text(stringResource(R.string.ui_follow_cancel))
        } }
    )
}
