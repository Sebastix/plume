package org.dergigi.feather

import android.animation.ValueAnimator
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

internal data class FirstUse(val welcome: Boolean = false, val swipe: Boolean = false, val actions: Boolean = false)
internal data class LaunchIcon(val x: Int, val y: Int, val width: Int, val height: Int)

/** Device-local education, deliberately separate from account preferences. */
internal class Onboarding(context: Context, private val scope: CoroutineScope) {
    private val prefs by lazy { context.getSharedPreferences("first-use", Context.MODE_PRIVATE) }
    private val ready = CompletableDeferred<Unit>()
    private val lock = Mutex()
    val state = MutableStateFlow<FirstUse?>(null)
    val icon = MutableStateFlow<LaunchIcon?>(null)
    val allowed = MutableStateFlow(true)
    init {
        scope.launch(Dispatchers.IO) {
            // Existing signed-in installations do not need a login introduction.
            val established = context.getSharedPreferences("feather", Context.MODE_PRIVATE).contains("signer")
            state.value = FirstUse(prefs.getBoolean("welcome", established), prefs.getBoolean("swipe", false), prefs.getBoolean("actions", false))
            if (established && !prefs.contains("welcome")) prefs.edit().putBoolean("welcome", true).apply()
            ready.complete(Unit)
        }
    }
    fun dismiss(key: String) {
        scope.launch(Dispatchers.IO) {
            ready.await()
            lock.withLock {
                val old = state.value ?: return@withLock
                val next = when (key) {
                    "welcome" -> old.copy(welcome = true)
                    "swipe" -> old.copy(swipe = true)
                    "actions" -> old.copy(actions = true)
                    else -> return@withLock
                }
                if (next == old) return@withLock
                state.value = next
                prefs.edit().putBoolean(key, true).apply()
            }
        }
    }
}

/** Same artwork and measured bounds as Android's splash; the feed is already running underneath. */
@Composable
internal fun WelcomeFlight(onboarding: Onboarding, pageCount: Int, initialPage: Int, onLogin: () -> Unit,
    onReveal: (Int) -> Unit, feedSwitcher: @Composable (PagerState, Boolean) -> Unit) {
    val login = remember { Animatable(0f) }
    val fall = remember { Animatable(0f) }
    val veil = remember { Animatable(1f) }
    var signerHintDismissed by rememberSaveable { mutableStateOf(false) }
    var swipeHintDismissed by rememberSaveable { mutableStateOf(false) }
    val preview = rememberPagerState(initialPage = initialPage) { pageCount }
    val latestReveal by rememberUpdatedState(onReveal)
    val dismissSwipe = {
        swipeHintDismissed = true
        onboarding.dismiss("swipe")
    }
    val geometry by onboarding.icon.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val finish = { onboarding.dismiss("welcome") }
    val latestLogin by rememberUpdatedState(onLogin)
    BackHandler(onBack = finish)
    LaunchedEffect(lifecycle) {
        if (!lifecycle.isAtLeast(Lifecycle.State.RESUMED)) return@LaunchedEffect
        if (ValueAnimator.areAnimatorsEnabled()) login.animateTo(1f, tween(450))
        else login.snapTo(1f)
    }
    LaunchedEffect(lifecycle, swipeHintDismissed) {
        if (!lifecycle.isAtLeast(Lifecycle.State.RESUMED) || !swipeHintDismissed) return@LaunchedEffect
        preview.scrollToPage(preview.currentPage)
        latestReveal(preview.currentPage)
        if (!ValueAnimator.areAnimatorsEnabled()) { finish(); return@LaunchedEffect }
        delay(180)
        fall.animateTo(1f, tween(2400, easing = LinearEasing))
        veil.animateTo(0f, tween(800))
        finish()
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val fallback = with(density) { 288.dp.toPx() }
        val width = geometry?.width?.toFloat() ?: fallback
        val height = geometry?.height?.toFloat() ?: fallback
        val screenWidth = with(density) { maxWidth.toPx() }
        val screenHeight = with(density) { maxHeight.toPx() }
        var origin by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
        val startX = geometry?.x?.minus(origin.x) ?: ((screenWidth - width) / 2f)
        val startY = geometry?.y?.minus(origin.y) ?: ((screenHeight - height) / 2f)
        Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }
            .graphicsLayer { alpha = veil.value }.background(MaterialTheme.colorScheme.background)) {
            HorizontalPager(state = preview, userScrollEnabled = signerHintDismissed && !swipeHintDismissed,
                modifier = Modifier.fillMaxSize()) { page ->
            val colors = listOf(Color(0xFF7AC8EE), Color(0xFFB39DDB), Color(0xFF81C784), Color(0xFFFFB74D), Color(0xFFF48FB1))
            Box(Modifier.fillMaxSize()) {
            Image(painterResource(R.drawable.splash_feather), null,
                Modifier.offset { IntOffset(startX.roundToInt(), startY.roundToInt()) }
                    .size(with(density) { width.toDp() }, with(density) { height.toDp() })
                    .graphicsLayer {
                        val progress = fall.value
                        translationX = sin(progress * PI * 3).toFloat() * screenWidth * .1f
                        translationY = progress * (screenHeight - startY + height)
                        rotationZ = sin(progress * PI * 3).toFloat() * 18f
                    },
                colorFilter = if (page == initialPage) null else ColorFilter.tint(colors[Math.floorMod(page - initialPage, colors.size)]))
            }
            }
        }
        // Identical placement to the real home account control, so it stays put as the veil fades.
        IconButton(onClick = { finish(); latestLogin() },
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 8.dp, end = 12.dp)
                .size(RoundControlSize).graphicsLayer {
                    alpha = login.value
                    scaleX = .85f + .15f * login.value
                    scaleY = scaleX
                }) {
            Box(Modifier.size(40.dp).background(MaterialTheme.colorScheme.surface, CircleShape)
                .border(2.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.login), stringResource(R.string.ui_log_in), Modifier.size(24.dp), tint = MaterialTheme.colorScheme.primary)
            }
        }
        AnimatedVisibility(login.value == 1f && !signerHintDismissed,
            modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 68.dp, start = 24.dp, end = 12.dp),
            enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            FirstUseHint(stringResource(R.string.ui_hint_signer), onDismiss = { signerHintDismissed = true })
        }
        AnimatedVisibility(signerHintDismissed,
            modifier = Modifier.align(Alignment.BottomCenter), enter = fadeIn(tween(180))) {
            feedSwitcher(preview, !swipeHintDismissed)
        }
        AnimatedVisibility(signerHintDismissed && !swipeHintDismissed,
            modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(horizontal = 24.dp).padding(bottom = 96.dp),
            enter = fadeIn(tween(180)), exit = fadeOut(tween(180))) {
            FirstUseHint(stringResource(R.string.ui_hint_swipe), onDismiss = dismissSwipe)
        }
        IconButton(onClick = finish, modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 8.dp, start = 12.dp)) {
            Icon(Icons.Default.Close, stringResource(R.string.ui_skip_welcome), tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .6f))
        }
    }
}

@Composable
internal fun FirstUseHint(text: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.widthIn(max = 360.dp), shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Row(Modifier.padding(start = 18.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, stringResource(R.string.ui_dismiss_hint)) }
        }
    }
}
