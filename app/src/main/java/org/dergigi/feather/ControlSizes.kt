package org.dergigi.feather

import androidx.compose.ui.unit.dp

/** Shared diameter for circular controls; the floating home avatar has its own smaller visual size. */
internal const val ROUND_CONTROL_DP = 48
internal val RoundControlSize = ROUND_CONTROL_DP.dp

internal const val ACCOUNT_AVATAR_DP = 36
internal val AccountAvatarBorder = 2.dp
internal val AccountAvatarOuterSize = ACCOUNT_AVATAR_DP.dp + AccountAvatarBorder * 2

/** Align the picture with feed avatars; its border extends beyond the content edge. */
internal val AccountControlEndPadding = 24.dp - (RoundControlSize - ACCOUNT_AVATAR_DP.dp) / 2
