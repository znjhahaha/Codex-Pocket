package dev.codexpocket.app.ui.kit

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Pocket's answer to a touch: the control settles in quickly, a soft light gathers under the finger,
 * and on release it comes back on a looser spring. Applied after the caller's clip, so the light
 * follows the control's own outline.
 */
internal data class PocketPressIndication(val pressedScale: Float = .97f, val highlight: Boolean = true,
    val tint: Color = Color.Unspecified) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, pressedScale, highlight, tint)
}

private data class PressScaleElement(val source: InteractionSource, val pressedScale: Float) : ModifierNodeElement<PressNode>() {
    override fun create() = PressNode(source, pressedScale, false, Color.Unspecified)
    override fun update(node: PressNode) = node.update(source, pressedScale)
    override fun InspectorInfo.inspectableProperties() { name = "pocketPressScale"; properties["pressedScale"] = pressedScale }
}

/**
 * Scales everything chained after it while [source] is pressed. Put it ahead of the clip and the
 * background so the whole control moves, and give the same source to the click that follows.
 */
internal fun Modifier.pocketPressScale(source: InteractionSource, pressedScale: Float = .97f): Modifier =
    this then PressScaleElement(source, pressedScale)

private class PressNode(private var source: InteractionSource, private var pressedScale: Float, private val highlight: Boolean,
    private val tint: Color) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val press = Animatable(0f)
    private var presses = 0
    private var focused = false
    /** Where the finger came down, in this node's own coordinates. */
    private var origin = Offset.Unspecified
    private var settle: Job? = null
    private var watch: Job? = null

    fun update(source: InteractionSource, pressedScale: Float) {
        if (this.pressedScale != pressedScale) { this.pressedScale = pressedScale; invalidateDraw() }
        if (this.source === source) return
        this.source = source
        if (isAttached) { watch?.cancel(); settle?.cancel(); presses = 0; focused = false; watch = observe() }
    }

    private fun motion(): Boolean {
        val preferences = currentValueOf(LocalPocketGlassPreferences)
        return if (preferences != null) preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
        else ValueAnimator.areAnimatorsEnabled()
    }

    override fun onAttach() { watch = observe() }

    private fun observe(): Job {
        if (press.value != 0f) coroutineScope.launch { press.snapTo(0f) }
        return coroutineScope.launch {
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> { presses++; origin = interaction.pressPosition }
                    is PressInteraction.Release, is PressInteraction.Cancel -> presses = (presses - 1).coerceAtLeast(0)
                    is FocusInteraction.Focus -> { focused = true; if (highlight) invalidateDraw() }
                    is FocusInteraction.Unfocus -> { focused = false; if (highlight) invalidateDraw() }
                }
                if (interaction !is PressInteraction) return@collect
                val pressed = presses > 0
                settle?.cancel()
                settle = launch {
                    if (!motion()) { press.snapTo(if (pressed) 1f else 0f); return@launch }
                    // A quick tap still shows: carry the press to most of its depth before letting go.
                    if (!pressed && press.value < PressFloor) press.animateTo(PressFloor, tween(PressFloorMillis, easing = PocketMotion.EaseOut))
                    if (pressed) press.animateTo(1f, PocketMotion.press()) else press.animateTo(0f, PocketMotion.release())
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        // Below rest on the way back: the looser release carries the control a little past its size.
        val travel = press.value.coerceIn(-.6f, 1.05f)
        val factor = 1f - (1f - pressedScale) * travel
        if (factor != 1f) scale(factor) { this@draw.drawContent() } else drawContent()
        if (!highlight) return
        val depth = travel.coerceAtLeast(0f)
        val alpha = maxOf(depth * .06f, if (focused) .08f else 0f)
        if (alpha <= 0f) return
        val ink = tint.takeIf { it != Color.Unspecified } ?: currentValueOf(LocalPInk).takeIf { it != Color.Unspecified }
            ?: currentValueOf(LocalPocketColors).ink
        drawRect(ink.copy(alpha = alpha))
        if (depth <= 0f) return
        // Light gathers where the finger is and spreads as the press deepens. Sized mostly by the
        // shorter side, so a wide row is not washed out and a small button is still lit.
        val centre = if (origin.isSpecified) Offset(origin.x.coerceIn(0f, size.width), origin.y.coerceIn(0f, size.height)) else center
        val reach = (size.minDimension * 1.2f + size.maxDimension * .15f).coerceIn(40.dp.toPx(), 160.dp.toPx())
        drawRect(Brush.radialGradient(0f to ink.copy(alpha = .09f * depth), .55f to ink.copy(alpha = .04f * depth), 1f to Color.Transparent,
            center = centre, radius = reach * (.35f + .65f * depth)))
    }

    private companion object { const val PressFloor = .6f; const val PressFloorMillis = 70 }
}

@Composable
private fun rememberPressIndication(scale: Float, highlight: Boolean, tint: Color = Color.Unspecified) =
    remember(scale, highlight, tint) { PocketPressIndication(scale, highlight, tint) }

/**
 * A click with Pocket's press response and an optional haptic. Put the clip before this modifier.
 * [pressedScale] moves only what is chained after it; pair with [pocketPressScale] to move the whole
 * control. [tint] is the highlight's colour where the control's ink differs from the page's.
 */
@Composable
internal fun Modifier.pocketPressable(enabled: Boolean = true, role: Role? = Role.Button, interactionSource: MutableInteractionSource? = null,
    pressedScale: Float = .97f, highlight: Boolean = true, haptic: PocketHaptic? = null, onClickLabel: String? = null,
    tint: Color = Color.Unspecified, onClick: () -> Unit): Modifier {
    val haptics = rememberPocketHaptics()
    return clickable(interactionSource, rememberPressIndication(pressedScale, highlight, tint), enabled, onClickLabel, role) {
        haptic?.let(haptics::perform); onClick()
    }
}

/** One of a group. The tick is felt only when the selection actually changes. */
@Composable
internal fun Modifier.pocketSelectable(selected: Boolean, enabled: Boolean = true, role: Role? = Role.Tab,
    interactionSource: MutableInteractionSource? = null, pressedScale: Float = .97f, highlight: Boolean = false,
    haptic: PocketHaptic? = PocketHaptic.Tick, tint: Color = Color.Unspecified, onClick: () -> Unit): Modifier {
    val haptics = rememberPocketHaptics()
    return selectable(selected, interactionSource, rememberPressIndication(pressedScale, highlight, tint), enabled, role) {
        if (!selected) haptic?.let(haptics::perform)
        onClick()
    }
}

@Composable
internal fun Modifier.pocketToggleable(value: Boolean, enabled: Boolean = true, role: Role? = Role.Switch,
    interactionSource: MutableInteractionSource? = null, pressedScale: Float = 1f, haptics: Boolean = true,
    onValueChange: (Boolean) -> Unit): Modifier {
    val feedback = rememberPocketHaptics()
    return toggleable(value, interactionSource, rememberPressIndication(pressedScale, false), enabled, role) { next ->
        if (haptics) feedback.perform(if (next) PocketHaptic.ToggleOn else PocketHaptic.ToggleOff)
        onValueChange(next)
    }
}
