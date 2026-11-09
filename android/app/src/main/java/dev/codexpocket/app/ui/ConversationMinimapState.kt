package dev.codexpocket.app.ui

import android.content.Context
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.data.TranscriptRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged

internal const val MinimapIdleMillis = 2_500L
internal const val MinimapMotionMillis = 240
internal const val MinimapSeekIntervalMillis = 50L

/** Share this instance with the host so its animated reservation and the rail stay identical. */
internal class ConversationMinimapState {
    var expanded by mutableStateOf(false)
        private set
    var held by mutableStateOf(false)
        internal set
    internal var previewKey by mutableStateOf<String?>(null)
    internal var previewFraction by mutableFloatStateOf(0f)
    internal var focused by mutableStateOf(false)
    internal var seeking by mutableStateOf(false)
    internal var seekKey by mutableStateOf<String?>(null)
    internal var navigationToken: Any? = null
    internal var interaction by mutableLongStateOf(0L)
    internal var expansion: State<Float> = mutableFloatStateOf(0f)
    val reservedWidth: Dp get() = 24.dp + 24.dp * expansion.value
    val isInteracting: Boolean get() = held || previewKey != null || seeking

    fun expand() { expanded = true; touch() }
    fun collapse() { expanded = false; previewKey = null; touch() }
    internal fun touch() { interaction++ }
}

@Composable
internal fun rememberConversationMinimapState(listState: LazyListState, scope: String = ""): ConversationMinimapState {
    val state = remember(listState, scope) { ConversationMinimapState() }
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    state.expansion = animateFloatAsState(if (state.expanded) 1f else 0f,
        if (motion) tween(MinimapMotionMillis) else snap(), label = "conversation-minimap-width")
    val touchExploration = rememberMinimapTouchExploration()
    // Compose exposes input focus but no public virtual-node accessibility-focus observer.
    // During touch exploration keep an opened rail until explicit collapse, so TalkBack focus
    // cannot lose its node to a timeout. Keyboard focus is tracked by the rail itself.
    val focused = state.focused || touchExploration || state.seeking
    LaunchedEffect(state.expanded, state.held, state.previewKey, focused, state.interaction) {
        if (minimapCanIdleCollapse(state.expanded, state.held, state.previewKey != null, focused)) {
            val interaction = state.interaction
            delay(MinimapIdleMillis)
            if (interaction == state.interaction && minimapCanIdleCollapse(state.expanded,
                    state.held, state.previewKey != null, state.focused || touchExploration || state.seeking)) state.collapse()
        }
    }
    return state
}

@Composable
private fun rememberMinimapTouchExploration(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) { context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager }
    var enabled by remember(manager) { mutableStateOf(manager?.isTouchExplorationEnabled == true) }
    DisposableEffect(manager) {
        val listener = AccessibilityManager.TouchExplorationStateChangeListener { enabled = it }
        manager?.addTouchExplorationStateChangeListener(listener)
        onDispose { manager?.removeTouchExplorationStateChangeListener(listener) }
    }
    return enabled
}

internal fun minimapCanIdleCollapse(expanded: Boolean, held: Boolean, preview: Boolean, focused: Boolean) =
    expanded && !held && !preview && !focused

/** One trailing request, no timer/loop while idle. A release may bypass the cadence once. */
internal class MinimapSeekCoalescer {
    private var pending: MinimapTarget? = null
    private var last: MinimapTarget? = null
    private var nextAt = Long.MIN_VALUE
    fun offer(target: MinimapTarget, now: Long, flush: Boolean = false): MinimapTarget? {
        // Flush changes the cadence, not the destination. Reapplying an already
        // completed fractional seek can shift text after a row finishes measuring.
        // Cancelled seeks and preview-to-hidden expansion explicitly abandon last.
        pending = target.takeUnless { it == last }
        return take(now, flush)
    }
    fun waitMillis(now: Long): Long = if (pending == null || nextAt <= now) 0 else nextAt - now
    fun take(now: Long, flush: Boolean = false): MinimapTarget? {
        if (!flush && now < nextAt) return null
        val target = pending ?: return null
        pending = null
        last = target
        nextAt = now + MinimapSeekIntervalMillis
        return target
    }
    fun cancel() { pending = null; last = null }
    fun abandon(target: MinimapTarget) { if (last == target) last = null }
}

internal class MinimapVisibilityLatch {
    private var identity: String? = null
    private var visible = false
    fun update(conversationKey: String, loadedViewportHeights: Float): Boolean {
        if (identity != conversationKey) { identity = conversationKey; visible = false }
        if (loadedViewportHeights.isFinite() && loadedViewportHeights >= 1.5f) visible = true
        return visible
    }
}

/** Latches at 1.5 viewport heights, including a single tall loaded row; folding never flickers it. */
@Composable
internal fun rememberConversationMinimapVisibility(conversationKey: String, rows: List<TranscriptRow>,
    listState: LazyListState): Boolean {
    val latch = remember(conversationKey, listState) { MinimapVisibilityLatch() }
    var visible by remember(conversationKey, listState) { mutableStateOf(false) }
    val rowKeys by rememberUpdatedState(remember(rows) { rows.map { it.key }.toSet() })
    LaunchedEffect(conversationKey, listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val viewHeight = info.viewportEndOffset - info.afterContentPadding - maxOf(0, info.viewportStartOffset)
            val measured = info.visibleItemsInfo.filter { it.key in rowKeys && it.size > 0 }
            if (viewHeight <= 0 || measured.isEmpty()) 0f
            else (measured.sumOf { it.size.toDouble() } / measured.size * rowKeys.size / viewHeight).toFloat()
        }.distinctUntilChanged().collect { visible = latch.update(conversationKey, it) }
    }
    return visible && rows.isNotEmpty()
}
