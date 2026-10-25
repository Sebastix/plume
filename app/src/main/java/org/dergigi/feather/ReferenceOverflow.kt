package org.dergigi.feather

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/** The same compact, icon-only overflow used for secondary gallery actions. */
@Composable
internal fun ReferenceOverflow(
    identity: String,
    copyDescription: String,
    openDescription: String,
    opening: Boolean,
    onCopy: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember(identity) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, modifier = modifier) {
            Icon(Icons.Default.MoreVert, stringResource(R.string.ui_more_reference_actions))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            Row {
                IconButton(onClick = { expanded = false; onCopy() }) {
                    Icon(painterResource(R.drawable.copy), copyDescription, Modifier.size(20.dp))
                }
                IconButton(enabled = !opening, onClick = { expanded = false; onOpen() }) {
                    Icon(painterResource(R.drawable.open_in_app), openDescription)
                }
            }
        }
    }
}
