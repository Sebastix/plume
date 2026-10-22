package org.dergigi.feather

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

internal class AvatarModeration(val owner: String?, val mute: (String) -> Unit, val report: (String) -> Unit,
    val isMuted: (String) -> Boolean, val unmute: (String) -> Unit) {
    var expanded by mutableStateOf<Any?>(null)
}
internal val LocalAvatarModeration = staticCompositionLocalOf<AvatarModeration?> { null }

/** The avatar keeps its position and height while the actions roll out beside it. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun ModerationAvatar(pubkey: String, name: String, picture: String?, size: Int = 36,
    slotSize: Int = 48, expansionKey: Any? = null, onClick: () -> Unit) {
    val actions = LocalAvatarModeration.current
    val token = remember(pubkey, expansionKey) { expansionKey ?: Any() }
    val enabled = actions?.owner != null && actions.owner != pubkey
    val expanded = enabled && actions?.expanded === token
    DisposableEffect(token, actions) {
        onDispose { if (actions?.expanded === token) actions.expanded = null }
    }
    BackHandler(expanded) { actions?.expanded = null }
    // Collapse only the trailing hit-area padding; the avatar and row height stay fixed.
    val avatarWidth by animateDpAsState(if (expanded) (size + 2).dp else slotSize.dp, label = "Avatar action spacing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(avatarWidth).height(slotSize.dp).combinedClickable(
            onClickLabel = "Open $name's profile",
            onLongClickLabel = if (enabled) stringResource(R.string.ui_moderation_actions) else null,
            onLongClick = if (enabled) ({ actions?.expanded = if (expanded) null else token }) else null,
            onClick = { actions?.expanded = null; onClick() },
        ), contentAlignment = Alignment.CenterStart) { Avatar(name, size, picture) }
        AnimatedVisibility(expanded,
            enter = expandHorizontally(expandFrom = Alignment.Start) + slideInHorizontally { -it } + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.Start) + slideOutHorizontally { -it } + fadeOut()) {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                Row(Modifier.padding(end = 6.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (actions?.isMuted?.invoke(pubkey) == true) {
                        HoldToUnmuteButton(pubkey, name, size = 36) {
                            actions.expanded = null
                            actions.unmute(pubkey)
                        }
                    } else IconButton(onClick = { actions?.expanded = null; actions?.mute?.invoke(pubkey) },
                        modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape)) {
                        Icon(painterResource(R.drawable.account_hidden), stringResource(R.string.ui_mute_account, name),
                            Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    IconButton(onClick = { actions?.expanded = null; actions?.report?.invoke(pubkey) },
                        modifier = Modifier.size(36.dp).background(MaterialTheme.colorScheme.errorContainer, CircleShape)) {
                        Icon(painterResource(R.drawable.report_flag), stringResource(R.string.ui_report_account, name),
                            Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun Modifier.avatarModerationName(pubkey: String, expansionKey: Any, onClick: () -> Unit): Modifier {
    val actions = LocalAvatarModeration.current
    if (actions?.owner == null || actions.owner == pubkey) return this
    return combinedClickable(
        onLongClickLabel = stringResource(R.string.ui_moderation_actions),
        onLongClick = { actions.expanded = if (actions.expanded === expansionKey) null else expansionKey },
        onClick = onClick,
    )
}
