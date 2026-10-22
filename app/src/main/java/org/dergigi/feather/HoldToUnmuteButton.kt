package org.dergigi.feather

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.*
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A tap demonstrates charging; only a continuous hold commits the account change. */
@Composable
internal fun HoldToUnmuteButton(account: String, name: String, onUnmute: () -> Unit) {
    key(account) {
        val charge = remember { Animatable(0f) }
        val scope = rememberCoroutineScope()
        val haptics = LocalHapticFeedback.current
        val confirm by rememberUpdatedState(onUnmute)
        var motion by remember { mutableStateOf<Job?>(null) }
        var pressId by remember { mutableIntStateOf(0) }
        var confirmed by remember { mutableStateOf(false) }
        var keyboardPress by remember { mutableStateOf<Int?>(null) }
        val label = stringResource(R.string.ui_hold_to_unmute_account, name)
        val tint = MaterialTheme.colorScheme.error.copy(alpha = .8f)

        fun finish() {
            if (confirmed) return
            confirmed = true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            confirm()
        }
        fun begin(): Int {
            motion?.cancel()
            val id = ++pressId
            confirmed = false
            motion = scope.launch {
                val filling = launch { charge.animateTo(1f, tween(800, easing = FastOutSlowInEasing)) }
                // Animation-scale settings must never turn a tap into an immediate unmute.
                delay(800)
                filling.join()
                if (pressId == id) finish()
            }
            return id
        }
        fun end(id: Int, released: Boolean) {
            if (id != pressId) return
            motion?.cancel()
            motion = scope.launch {
                if (released && !confirmed && charge.value < .5f) {
                    // A short tap coasts up to half charge, slowing as it runs out of steam.
                    charge.animateTo(.5f, tween(220, easing = LinearOutSlowInEasing))
                }
                charge.animateTo(0f, tween(420, easing = FastOutSlowInEasing))
            }
        }
        Box(Modifier.size(48.dp)
            .semantics {
                role = Role.Button
                contentDescription = label
                onClick(label = label) { val id = begin(); end(id, true); true }
                onLongClick(label = label) {
                    motion?.cancel()
                    pressId++
                    confirmed = false
                    finish()
                    true
                }
            }
            .onFocusChanged { if (!it.isFocused) keyboardPress?.let { id -> keyboardPress = null; end(id, false) } }
            .onKeyEvent { event ->
                if (event.key !in listOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)) false
                else {
                    if (event.type == KeyEventType.KeyDown && keyboardPress == null && event.nativeKeyEvent.repeatCount == 0)
                        keyboardPress = begin()
                    if (event.type == KeyEventType.KeyUp) keyboardPress?.let { id -> keyboardPress = null; end(id, true) }
                    true
                }
            }.focusable()
            .pointerInput(account) {
                detectTapGestures(onPress = {
                    val id = begin()
                    var released = false
                    try { released = tryAwaitRelease() }
                    finally { end(id, released) }
                })
            }, contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(36.dp)) {
                if (charge.value > 0f) drawArc(tint, -90f, 360f * charge.value, false,
                    style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
            }
            Icon(painterResource(R.drawable.account_hidden), null, Modifier.size(20.dp), tint = tint)
        }
    }
}
