package org.dergigi.feather

import androidx.compose.ui.unit.dp

/** Shared diameter for circular controls; the floating home avatar has its own smaller visual size. */
internal const val ROUND_CONTROL_DP = 48
internal val RoundControlSize = ROUND_CONTROL_DP.dp

/** Place the 40dp avatar inside its touch target on the feed’s 24dp content edge. */
internal val AccountControlEndPadding = 24.dp - (RoundControlSize - 40.dp) / 2
