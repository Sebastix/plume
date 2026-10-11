package org.dergigi.feather

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.vitorpamplona.quartz.nip01Core.core.Event

/** Shared explicit 21-sat action for notes and their gallery images. */
@Composable
internal fun NoteZap(note: Event, counts: Engagement, publicKey: String?,
    tint: Color = MaterialTheme.colorScheme.onSurfaceVariant, iconOnly: Boolean = false, enabled: Boolean = true) {
    val zapAmount = remember(counts.zapSats) { counts.zapSats.stripTrailingZeros().toPlainString() }
    val zapActions = LocalZapActions.current
    val zapped = note.id in zapActions.confirmed ||
        (publicKey != null && counts.zappedByAccount == publicKey)
    Row((if (iconOnly) Modifier.size(56.dp) else Modifier.widthIn(min = 56.dp).heightIn(min = 48.dp)).clip(CircleShape)
        .clickable(enabled = enabled && note.id !in zapActions.pending,
            onClickLabel = "Zap 21 sats") { zapActions.zap(note) }.padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (note.id in zapActions.pending) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = tint)
            else Icon(painterResource(R.drawable.zap),
                if (zapped) "You zapped this post; $zapAmount sats received" else "Zap 21 sats; $zapAmount sats received",
                Modifier.size(20.dp), tint = if (zapped) Color(0xFFFFB300) else tint)
        }
        if (!iconOnly && counts.zapSats.signum() > 0) Text(zapAmount, style = MaterialTheme.typography.labelMedium, color = tint)
    }
}
