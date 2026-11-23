package dev.codexpocket.app.ui.kit

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp

/**
 * Replaces content in place: the old softens, shrinks and fades as the new sharpens out of the
 * blur, and the space they occupy eases between the two sizes. [slide] moves the new content in
 * from below (positive) or above (negative) by that fraction of its height, for values that count.
 * Switching back mid-way sharpens the earlier content again from where it is.
 */
@Composable
internal fun <S> PSwap(state: S, modifier: Modifier = Modifier, contentAlignment: Alignment = Alignment.Center, blur: Dp = 6.dp,
    scale: Float = .9f, slide: Float = 0f, label: String = "swap", content: @Composable (S) -> Unit) {
    val motion = rememberPocketMotion()
    AnimatedContent(state, modifier, contentAlignment = contentAlignment, label = label, transitionSpec = {
        if (!motion) (EnterTransition.None togetherWith ExitTransition.None).using(null)
        else {
            val arrive = fadeIn(tween(200, 60, PocketMotion.EaseOut)) + scaleIn(PocketSprings.snappy(.32f), scale) +
                slideInVertically(PocketSprings.snappy(.32f)) { (it * slide).toInt() }
            val leave = fadeOut(tween(140, easing = PocketMotion.EaseOut)) + scaleOut(tween(180, easing = PocketMotion.EaseOut), scale) +
                slideOutVertically(tween(180, easing = PocketMotion.EaseOut)) { (-it * slide).toInt() }
            (arrive togetherWith leave).using(SizeTransform(clip = false) { _, _ -> PocketMotion.resize<IntSize>() })
        }
    }) { shown ->
        val soft by transition.animateFloat({ tween(if (targetState == EnterExitState.Visible) 240 else 150, easing = PocketMotion.EaseOut) },
            label = "$label-blur") { if (it == EnterExitState.Visible) 0f else 1f }
        Box(if (motion && blur > 0.dp) Modifier.pocketBlur { soft * blur.toPx() } else Modifier) { content(shown) }
    }
}

/** A line of text that blur-replaces when it changes. */
@Composable
internal fun PSwapText(text: String, modifier: Modifier = Modifier, style: TextStyle? = null, color: Color = Color.Unspecified,
    maxLines: Int = 1, contentAlignment: Alignment = Alignment.CenterStart) {
    PSwap(text, modifier, contentAlignment, blur = 4.dp, scale = .96f, label = "swap-text") { shown ->
        PText(shown, style = style, color = color, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
    }
}
