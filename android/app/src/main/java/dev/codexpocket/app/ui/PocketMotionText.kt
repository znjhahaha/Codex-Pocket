package dev.codexpocket.app.ui

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Only the glyph layer moves. The tab keeps its measured width and hit target. */
@Composable
internal fun PocketTabLabel(text: String, selected: Boolean, modifier: Modifier = Modifier,
    color: Color = LocalContentColor.current, style: TextStyle = MaterialTheme.typography.labelMedium) {
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
    val target = if (selected) 1f else 0f
    val emphasis: State<Float> = if (motion) animateFloatAsState(target,
        tween(320, easing = FastOutSlowInEasing), label = "tab-type-emphasis") else rememberUpdatedState(target)
    val transform = if (motion) Modifier.graphicsLayer {
        val value = emphasis.value
        scaleX = .96f + .08f * value
        scaleY = .96f + .08f * value
        translationY = (.6f - 1.4f * value).dp.toPx()
    } else Modifier
    Text(text, modifier.then(transform), style = style, fontWeight = FontWeight.Medium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
internal fun PocketDestinationTitle(text: String, modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.titleMedium) {
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
    AnimatedContent(text, modifier, contentAlignment = Alignment.CenterStart, transitionSpec = {
        if (motion) ((slideInVertically(tween(320, easing = FastOutSlowInEasing)) { it / 3 } + fadeIn(tween(240, 60))) togetherWith
            (slideOutVertically(tween(240, easing = FastOutSlowInEasing)) { -it / 3 } + fadeOut(tween(140))))
            .using(SizeTransform(clip = true, sizeAnimationSpec = { _, _ -> tween(320, easing = FastOutSlowInEasing) }))
        else (EnterTransition.None togetherWith ExitTransition.None).using(null)
    }, label = "destination-title") { title -> Text(title, style = style, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A single entrance followed by a moving light band; complete text remains in semantics. */
@Composable
internal fun ActivityTextFlow(running: Boolean, animateEntrance: Boolean, modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit) {
    val preferences = rememberPocketGlassPreferences()
    val motion = preferences.animatorsEnabled && !preferences.batterySaver && !preferences.highContrast
    val reveal = remember { Animatable(if (motion && animateEntrance) .06f else 1f) }
    var entranceFinished by remember { mutableStateOf(!motion || !animateEntrance) }
    LaunchedEffect(motion) {
        if (motion) reveal.animateTo(1f, tween(680, easing = FastOutSlowInEasing)) else reveal.snapTo(1f)
        entranceFinished = true
    }
    val phase: State<Float> = if (running && motion) rememberInfiniteTransition(label = "activity-text").animateFloat(
        0f, 1f, infiniteRepeatable(tween(2300, 400, LinearEasing)), label = "travelling-light") else rememberUpdatedState(1f)
    val light = MaterialTheme.colorScheme.onSurface
    val effect = if (motion && (running || !entranceFinished)) Modifier
        .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (reveal.value < 1f) {
                val edge = size.width * reveal.value
                drawRect(Brush.linearGradient(listOf(Color.White, Color.Transparent),
                    start = Offset(edge - 24.dp.toPx(), 0f), end = Offset(edge, 0f)), blendMode = BlendMode.DstIn)
            }
            if (running || reveal.value < 1f) {
                val width = minOf(76.dp.toPx(), size.width * .45f).coerceAtLeast(1f)
                val x = if (reveal.value < 1f) size.width * reveal.value else (size.width + width * 2) * phase.value - width
                drawRect(Brush.linearGradient(listOf(Color.Transparent, light.copy(alpha = .88f), Color.Transparent),
                    start = Offset(x - width, 0f), end = Offset(x + width, size.height)), blendMode = BlendMode.SrcAtop)
            }
        } else Modifier
    Row(modifier.then(effect), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp), content = content)
}
