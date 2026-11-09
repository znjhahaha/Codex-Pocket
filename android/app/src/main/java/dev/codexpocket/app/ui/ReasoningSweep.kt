package dev.codexpocket.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

internal const val ReasoningSweepMillis = 1_400

/** The same preferences and RESUMED lifecycle policy used by Pocket's native controls. */
@Composable
internal fun rememberNativeMotionAllowed(): Boolean {
    val preferences = rememberPocketGlassPreferences()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val view = LocalView.current
    var foreground by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ ->
            foreground = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return nativeMotionAllowed(preferences, foreground && LocalWindowInfo.current.isWindowFocused && view.isShown)
}

internal fun nativeMotionAllowed(preferences: PocketGlassPreferences, foreground: Boolean): Boolean =
    foreground && preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver

/** Null until measured. boundsInWindow respects ancestor clipping, including summary scrolling. */
internal class NativeMotionVisibility {
    var visible by mutableStateOf<Boolean?>(null)
        private set
    fun modifier(): Modifier = Modifier.onGloballyPositioned { coordinates ->
        val bounds = coordinates.boundsInWindow()
        visible = coordinates.isAttached && bounds.width.isFinite() && bounds.height.isFinite() &&
            bounds.width > 0f && bounds.height > 0f
    }
}

/** Attach directly to Text, after caller decoration: the layer contains only actual text glyphs. */
@Composable
internal fun Modifier.reasoningSweep(active: Boolean): Modifier {
    // Historical paragraphs do not even create a lifecycle observer or animation clock.
    if (!active) return this
    val motion = rememberNativeMotionAllowed()
    val visibility = remember { NativeMotionVisibility() }
    val phase = if (motion && visibility.visible == true) {
        rememberInfiniteTransition(label = "native-reasoning-sweep").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(ReasoningSweepMillis, easing = LinearEasing)),
            label = "native-reasoning-sweep-phase",
        )
    } else null
    val base = MaterialTheme.colorScheme.onSurfaceVariant
    val bright = MaterialTheme.colorScheme.onSurface
    return then(visibility.modifier()).drawWithCache {
        val bounds = Rect(Offset.Zero, size)
        val layer = Paint()
        val band = (size.width * .18f).coerceAtLeast(1f)
        val colors = listOf(base, bright, base)
        onDrawWithContent {
            if (phase == null) {
                drawContent()
            } else {
                // Reading phase only here invalidates drawing, not composition/text measurement.
                val center = -band + (size.width + 2 * band) * phase.value
                drawContext.canvas.saveLayer(bounds, layer)
                try {
                    drawContent()
                    // SrcIn uses the rendered glyph alpha (including ellipsis), not text bounds.
                    drawRect(Brush.linearGradient(colors,
                        start = Offset(center - band, 0f), end = Offset(center + band, 0f)),
                        blendMode = BlendMode.SrcIn)
                } finally { drawContext.canvas.restore() }
            }
        }
    }
}
