package dev.codexpocket.app.ui.kit

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateTo
import androidx.compose.foundation.LocalOverscrollFactory
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Velocity
import kotlin.math.abs
import kotlin.math.sign

/**
 * Scrolling past an edge pulls the content along on a rubber band and lets it spring back; a fling
 * that reaches an edge spends what is left of its speed the same way. Only a layer moves: nothing
 * is measured again and the scroll position never changes.
 */
internal class PocketOverscrollEffect : OverscrollEffect {
    /** How far the content is displaced along the scrolling axis, in pixels, in the direction of the drag. */
    var offset by mutableFloatStateOf(0f)
        private set
    private var vertical: Boolean? = null
    private var extent = 0f
    /** Bumped by every touch so a spring still returning yields to the finger. */
    private var generation = 0

    private fun main(value: Offset) = if (vertical != false) value.y else value.x
    private fun along(value: Float) = if (vertical != false) Offset(0f, value) else Offset(value, 0f)
    private fun dimension() = if (extent > 0f) extent else 1200f

    override fun applyToScroll(delta: Offset, source: NestedScrollSource, performScroll: (Offset) -> Offset): Offset {
        if (vertical == null && delta != Offset.Zero) vertical = abs(delta.y) >= abs(delta.x)
        val user = source == NestedScrollSource.UserInput
        if (user) generation++
        val move = main(delta)
        var returned = 0f
        // A drag back toward rest first gives up the pull, one to one with the finger.
        if (user && offset != 0f && move != 0f && sign(move) != sign(offset)) {
            val raw = rubberBandInverse(offset, dimension())
            val next = raw + move
            if (sign(next) != sign(raw)) { offset = 0f; returned = -raw } else { offset = rubberBand(next, dimension()); returned = move }
        }
        val remaining = delta - along(returned)
        val consumed = performScroll(remaining)
        val over = main(remaining - consumed)
        if (user && abs(over) > .5f) offset = rubberBand(rubberBandInverse(offset, dimension()) + over, dimension())
        return along(returned) + consumed
    }

    override suspend fun applyToFling(velocity: Velocity, performFling: suspend (Velocity) -> Velocity) {
        val mine = ++generation
        val speed = if (vertical != false) velocity.y else velocity.x
        val left = if (abs(offset) > .5f) {
            // Let go while pulled: the content returns; it is not thrown.
            performFling(Velocity.Zero)
            if (sign(speed) == sign(offset)) speed * .2f else 0f
        } else {
            val rest = velocity - performFling(velocity)
            (if (vertical != false) rest.y else rest.x).coerceIn(-MaxEdgeSpeed, MaxEdgeSpeed) * .5f
        }
        if (abs(offset) <= .5f && abs(left) < 60f) { if (generation == mine) offset = 0f; return }
        AnimationState(offset, left).animateTo(0f, PocketMotion.rebound(), sequentialAnimation = true) {
            if (generation != mine) cancelAnimation() else offset = value
        }
        if (generation == mine) offset = 0f
    }

    // The return spring is decoration: a tap during it is a tap, not a request to stop scrolling.
    override val isInProgress: Boolean get() = false

    override val node: DelegatableNode = object : Modifier.Node(), LayoutModifierNode {
        override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
            val placeable = measurable.measure(constraints)
            return layout(placeable.width, placeable.height) {
                extent = (if (vertical != false) placeable.height else placeable.width).toFloat()
                placeable.placeWithLayer(0, 0) {
                    val shown = offset
                    if (vertical != false) translationY = shown else translationX = shown
                }
            }
        }
    }

    private companion object { const val MaxEdgeSpeed = 9000f }
}

private object PocketOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = PocketOverscrollEffect()
    override fun hashCode(): Int = 0x50434b54
    override fun equals(other: Any?): Boolean = other === this
}

/**
 * Every list and scrolling column inside bounces at its ends. With motion off the platform's own
 * edge treatment is left in place.
 */
@Composable
internal fun ProvidePocketOverscroll(content: @Composable () -> Unit) {
    // One provider either way: switching the setting must not rebuild what is inside.
    val factory = if (rememberPocketMotion()) PocketOverscrollFactory else LocalOverscrollFactory.current
    CompositionLocalProvider(LocalOverscrollFactory provides factory, content = content)
}
