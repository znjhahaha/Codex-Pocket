package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * A sent message leaves the field it was typed in. The field reports where its text sits; sending
 * arms the flight; the next message that was not on the page when it was armed takes off from
 * there and lands in its place in the list. Anything else that appears is simply shown.
 */
@Stable
internal class PSendFlight internal constructor() {
    internal var motion = true
    /** Where the typed text sits now, in root coordinates. */
    internal var source: Rect? = null
    private var origin: Rect? = null
    private val seen = HashSet<Any>()
    /** Counts each arming, so the wait for the message can be given up after a while. */
    internal var armings by mutableIntStateOf(0)
        private set
    /** Counts flights that have taken off: the field can answer each one. */
    var launches by mutableIntStateOf(0)
        internal set

    /**
     * Call as the message is sent. [present] are the keys already on the page, which never fly. The
     * place the text is in at this moment is where the flight starts, wherever the field goes next.
     */
    fun arm(present: Collection<Any> = emptyList()) {
        seen.addAll(present)
        origin = if (motion) source else null
        if (origin != null) armings++
    }

    internal fun disarm() { origin = null }

    /** Where [key] takes off from, once, if it is the message that was just sent; null for everything else. */
    fun take(key: Any, eligible: Boolean = true): Rect? {
        val new = seen.add(key)
        val from = origin
        if (!new || !eligible || from == null) return null
        origin = null
        return from
    }
}

/** The flight of the list this composition belongs to, for the field that feeds it. */
internal val LocalPSendFlight = staticCompositionLocalOf<PSendFlight?> { null }

/** A flight for each [scope], such as a conversation. An armed flight waits [waitMillis] for its message. */
@Composable
internal fun rememberPSendFlight(scope: Any? = Unit, waitMillis: Long = 8_000): PSendFlight {
    val flight = remember(scope) { PSendFlight() }
    flight.motion = rememberPocketMotion()
    LaunchedEffect(flight) {
        snapshotFlow { flight.armings }.collectLatest { if (it > 0) { pocketPause(waitMillis); flight.disarm() } }
    }
    return flight
}

/** Marks the element holding the typed text. */
internal fun Modifier.pocketSendSource(flight: PSendFlight?): Modifier =
    if (flight == null) this else onGloballyPositioned { flight.source = Rect(it.positionInRoot(), it.size.toSize()) }

/**
 * A bubble that starts where its text was typed ([from], as given by [PSendFlight.take]) and springs to
 * where it is laid out: up with a little overshoot, across without any. [inset] is the bubble's own
 * padding, so the text inside it starts exactly over the text in the field. Null [from] changes nothing.
 */
@Composable
internal fun Modifier.pocketSendLanding(from: Rect?, flight: PSendFlight? = null, insetX: Dp = 16.dp, insetY: Dp = 11.dp): Modifier {
    if (from == null) return this
    val rise = remember { Animatable(0f) }
    val across = remember { Animatable(0f) }
    // Where the bubble is laid out, followed for as long as it flies: the list may still be scrolling it into view.
    var travel by remember { mutableStateOf<Offset?>(null) }
    var landed by remember { mutableStateOf(false) }
    val inset = with(LocalDensity.current) { Offset(insetX.toPx(), insetY.toPx()) }
    LaunchedEffect(Unit) {
        snapshotFlow { travel }.first { it != null }
        flight?.let { it.launches++ }
        launch { across.animateTo(1f, PocketSprings.smooth(.4f)) }
        rise.animateTo(1f, PocketSprings.of(response = .5f, damping = .76f))
        landed = true
    }
    return onGloballyPositioned {
        if (!landed) { val at = it.positionInRoot(); travel = Offset(from.left - at.x - inset.x, from.top - at.y - inset.y) }
    // A layout step of its own between the two: the place that is reported is then where the bubble is laid out,
    // not where the layer below has moved it to.
    }.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }.graphicsLayer {
        val way = travel
        if (way == null) { alpha = 0f; return@graphicsLayer }
        val y = rise.value; val x = across.value
        if (!rise.isRunning && !across.isRunning && y >= 1f && x >= 1f) return@graphicsLayer
        translationX = way.x * (1f - x)
        translationY = way.y * (1f - y)
        // Solid almost at once: it is the typed text itself that is seen to leave.
        alpha = (y * 8f).coerceIn(0f, 1f)
        val size = 1f + .03f * (1f - x.coerceIn(0f, 1f))
        scaleX = size; scaleY = size
        transformOrigin = TransformOrigin(0f, 0f)
    }
}

/** What accompanies a flying bubble (its time, its actions) appears once the bubble is nearly home. */
@Composable
internal fun Modifier.pocketSendTrail(from: Rect?, delayMillis: Long = 260): Modifier {
    if (from == null) return this
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { pocketPause(delayMillis); shown.animateTo(1f, PocketMotion.enter()) }
    return graphicsLayer { alpha = shown.value }
}
