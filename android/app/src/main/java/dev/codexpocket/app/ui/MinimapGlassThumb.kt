package dev.codexpocket.app.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens

/** Samples only the miniature ink layer, never the slider itself or an empty transcript gutter. */
@Composable
internal fun MinimapGlassThumb(backdrop: Backdrop, held: Boolean, visibility: Float, modifier: Modifier) {
    val preferences = rememberPocketGlassPreferences()
    val dark = MaterialTheme.colorScheme.background.luminance() < .3f
    val optics = LocalPocketGlass.current && !preferences.highContrast && !preferences.batterySaver &&
        LocalView.current.isHardwareAccelerated && Build.VERSION.SDK_INT >= 31
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    val press by animateFloatAsState(if (held) 1f else 0f,
        if (motion) spring(dampingRatio = .88f, stiffness = 780f) else snap(), label = "minimap-glass-grip")
    val shape = RoundedCornerShape(10.dp)
    val fallback = MaterialTheme.colorScheme.surfaceContainerHigh
    val material = if (optics) Modifier.drawBackdrop(
        backdrop = backdrop, shape = { shape },
        effects = {
            // Preserve the thin strokes so the refraction is visible through the moving lens.
            blur(.35.dp.toPx())
            if (Build.VERSION.SDK_INT >= 33) lens(
                refractionHeight = minOf(3.5.dp.toPx(), size.minDimension * .12f),
                refractionAmount = minOf(1.6.dp.toPx(), size.minDimension * .055f),
                chromaticAberration = false)
        }, highlight = { null }, shadow = { null }, innerShadow = { null },
        onDrawSurface = { drawRect(Color.White.copy(alpha = if (dark) .07f else .20f)) },
    ) else Modifier.background(fallback, shape)
    Box(modifier.graphicsLayer {
        alpha = visibility.coerceIn(0f, 1f)
        scaleX = 1f + press * .018f
        scaleY = .97f + visibility * .03f + press * .025f
        this.shape = shape
        shadowElevation = (1.5f + press * .8f).dp.toPx()
        ambientShadowColor = Color.Black.copy(alpha = .08f)
        spotShadowColor = Color.Black.copy(alpha = .12f)
        clip = true
    }.then(material).drawWithContent {
        drawContent()
        val inset = .45.dp.toPx()
        val rectSize = Size((size.width - 2 * inset).coerceAtLeast(0f), (size.height - 2 * inset).coerceAtLeast(0f))
        val radius = CornerRadius((minOf(10.dp.toPx(), size.minDimension / 2f) - inset).coerceAtLeast(0f))
        // Even neutral edge: sampled ink supplies the optics, with no painted corner gleam.
        drawRoundRect(if (dark) Color.White.copy(alpha = .16f) else Color.Black.copy(alpha = .12f), Offset(inset, inset), rectSize, radius,
            style = Stroke(.65.dp.toPx()))
        if (press > 0f) drawRoundRect(Color.White.copy(alpha = press * .04f), Offset(inset, inset), rectSize, radius)
    })
}
