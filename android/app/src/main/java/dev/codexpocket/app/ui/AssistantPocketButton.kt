package dev.codexpocket.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.*

/**
 * The assistant's entry on the home bar: a round well holding the assistant's face on a disc of the
 * app's accent (green belongs to the assistant's own stage). The face blinks while it waits and
 * smiles under a finger; a tap sends one ring outward and makes it hop before the page opens out of
 * the button. With something to confirm, an arc keeps circling the disc and a dot sits on its edge.
 */
@Composable
fun AssistantPocketButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, attention: Boolean = false) {
    val c = Pocket.colors
    val motion = rememberPocketMotion()
    val taps = rememberPTaps()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ring = remember { Animatable(1f) }
    LaunchedEffect(taps.count) {
        if (taps.count == 0 || !motion) return@LaunchedEffect
        ring.snapTo(0f); ring.animateTo(1f, tween(560, easing = PocketMotion.EaseOut))
    }
    val circling = rememberGlyphLoop(attention && enabled, 2400)
    val disc = c.accentSoft.compositeOver(c.surface)
    Box(modifier.requiredSize(60.dp).pocketMorphSource("assistant", 30.dp).testTag("assistant-pocket-button")
        // The ring is drawn outside the button, so it is never clipped and moves nothing.
        .drawBehind {
            val t = ring.value
            if (t < 1f) drawCircle(c.accent.copy(alpha = .4f * (1f - t)), size.minDimension / 2f * (1f + .5f * t),
                style = Stroke((2.5f * (1f - t) + .5f).dp.toPx()))
        }
        .pocketPressScale(interaction, .9f).clip(CircleShape).background(c.sunken)
        .border(1.dp, Brush.linearGradient(listOf(c.accent.copy(alpha = if (enabled) .55f else .2f), c.line)), CircleShape)
        .pocketPressable(enabled, Role.Button, interaction, pressedScale = 1f, haptic = PocketHaptic.Tap, onClick = taps.then(onClick))
        .semantics { contentDescription = if (attention) "打开口袋助理，有待确认事项" else "打开口袋助理" }, contentAlignment = Alignment.Center) {
        Box(Modifier.size(46.dp).drawBehind {
            drawCircle(Brush.radialGradient(listOf(disc, disc.copy(alpha = .72f)), center = Offset(size.width * .35f, size.height * .3f), radius = size.width * .9f))
            if (attention && enabled) {
                val inset = 1.5.dp.toPx()
                drawArc(c.accent, 360f * circling.value - 90f, 84f, false, Offset(inset, inset), Size(size.width - 2 * inset, size.height - 2 * inset),
                    style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
        }, contentAlignment = Alignment.Center) {
            AssistantFace(Modifier.size(40.dp).graphicsLayer { alpha = if (enabled) 1f else .38f }.testTag("assistant-pocket-face"),
                tint = c.onAccentSoft, lively = enabled, held = pressed, cheer = taps.count)
        }
        if (attention) Box(Modifier.align(Alignment.TopEnd).padding(7.dp).size(10.dp)
            .background(if (enabled) c.accent else c.inkMuted, CircleShape).border(2.dp, c.sunken, CircleShape))
    }
}
