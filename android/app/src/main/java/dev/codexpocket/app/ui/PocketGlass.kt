package dev.codexpocket.app.ui

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import kotlinx.coroutines.flow.collect

internal val LocalPocketBackdrop = staticCompositionLocalOf<Backdrop?> { null }
internal val LocalPocketGlass = staticCompositionLocalOf { true }
/** Clearer optics for floating top controls; reading/editor surfaces keep their denser material. */
internal val LocalPocketClearGlass = staticCompositionLocalOf { false }
internal val LocalPocketGlassPreferences = staticCompositionLocalOf<PocketGlassPreferences?> { null }

/** A separate optical background. Text and controls are never blurred or captured into themselves. */
@Composable
internal fun PocketGlassSurface(
    modifier: Modifier = Modifier,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(28.dp),
    backdrop: Backdrop? = LocalPocketBackdrop.current,
    onClick: (() -> Unit)? = null,
    /** Large text containers need a denser tint so the transcript does not show through the input. */
    dense: Boolean = false,
    content: @Composable () -> Unit,
) {
    val view = LocalView.current
    val preferences = rememberPocketGlassPreferences()
    val colors = MaterialTheme.colorScheme
    val clear = LocalPocketClearGlass.current
    val dark = colors.background.luminance() < .3f
    val glass = LocalPocketGlass.current && !preferences.highContrast && !preferences.batterySaver && view.isHardwareAccelerated
    val optics = glass && Build.VERSION.SDK_INT >= 31 && backdrop != null
    val motion = preferences.animatorsEnabled && !preferences.highContrast && !preferences.batterySaver
    val interaction = remember { MutableInteractionSource() }
    var pressed by remember { mutableStateOf(false) }
    var touchPosition by remember { mutableStateOf(Offset.Unspecified) }

    // Clickable owns acceptance, scroll cancellation, keyboard activation and semantics.
    LaunchedEffect(interaction) {
        val presses = mutableListOf<PressInteraction.Press>()
        try {
            interaction.interactions.collect { event ->
                when (event) {
                    is PressInteraction.Press -> {
                        presses.add(event)
                        touchPosition = event.pressPosition
                    }
                    is PressInteraction.Release -> presses.remove(event.press)
                    is PressInteraction.Cancel -> presses.remove(event.press)
                }
                pressed = presses.isNotEmpty()
            }
        } finally {
            pressed = false
        }
    }
    val press = animateFloatAsState(
        targetValue = if (pressed) 1f else 0f,
        animationSpec = if (motion) spring(dampingRatio = .82f, stiffness = 900f) else snap(),
        label = "glass-press",
    )
    val followTouch = if (onClick != null && motion) Modifier.pointerInput(interaction) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            touchPosition = down.position
            while (true) {
                // Observe only: never consume a down, movement, or up from clickable/scrollable.
                val event = awaitPointerEvent(PointerEventPass.Final)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed || event.changes.count { it.pressed } > 1) break
                if (change.isConsumed && change.position != change.previousPosition) break
                touchPosition = change.position
            }
        }
    } else Modifier
    val click = if (onClick != null) followTouch.clickable(
        interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick,
    ) else Modifier

    // A stable tint keeps the background from showing through more strongly during a press.
    val tint = if (dark) Color(0xFF232325) else Color.White
    val fill = if (optics) tint.copy(alpha = if (clear) (if (dark) .26f else .20f)
        else if (dense) (if (dark) .66f else .68f) else if (dark) .44f else .40f) else colors.surface
    // The lens SDF only understands corner-based shapes. Ovals refract as a capsule; the outer
    // clipPath below still trims every pixel to the oval outline.
    val lensShape = shape as? androidx.compose.foundation.shape.CornerBasedShape ?: RoundedCornerShape(50)
    val layer = if (optics) Modifier.drawBackdrop(
        backdrop = backdrop!!,
        shape = { lensShape },
        effects = {
            // Background motion and edges remain visible through the material. Editors retain
            // enough diffusion for text contrast; floating controls use the clearer lens.
            blur((if (clear) 2.5.dp else if (dense) 8.dp else 5.dp).toPx())
            if (Build.VERSION.SDK_INT >= 33) lens(
                refractionHeight = minOf(8.dp.toPx(), size.minDimension / 4f),
                // Mild bend: at the oval ends a strong lens pulled dark transcript text into a gray smudge.
                refractionAmount = minOf(4.dp.toPx(), size.minDimension * .1f),
                chromaticAberration = false,
            )
        },
        highlight = { null }, shadow = { null }, innerShadow = { null },
        onDrawSurface = { drawRect(fill) },
    ) else Modifier.background(fill, shape)

    val deformation = if (onClick != null) Modifier.graphicsLayer {
            val elastic = if (motion) press.value.coerceIn(-.04f, 1f) else 0f
            val focus = touchPosition.glassFocus(size)
            val x = if (size.width > 0f) focus.x / size.width - .5f else 0f
            val y = if (size.height > 0f) focus.y / size.height - .5f else 0f
            // Bound displacement in dp as well as percent, including on wide header capsules.
            scaleX = 1f - elastic * .04f
            scaleY = 1f - elastic * .04f
            translationX = x * elastic * .8.dp.toPx()
            translationY = y * elastic * .6.dp.toPx()
            transformOrigin = TransformOrigin(.5f + x * .18f, .5f + y * .18f)
            this.shape = shape
            // Low and faint: a 4dp shadow showed as a dark band on the edges of small pills.
            shadowElevation = if (glass) (2f - elastic.coerceAtLeast(0f) * .8f).dp.toPx() else 0f
            ambientShadowColor = Color.Black.copy(alpha = .04f)
            spotShadowColor = Color.Black.copy(alpha = .07f)
        } else Modifier
    val material = modifier
        // The hit target stays outside the visual transform; input containers have no observer.
        .then(click)
        .then(deformation)
        // Canvas clipping avoids shadow/RenderNode replay in software screenshot rendering.
        .drawWithCache {
            val clip = Path().apply {
                when (val outline = shape.createOutline(size, layoutDirection, this@drawWithCache)) {
                    is Outline.Rectangle -> addRect(outline.rect)
                    is Outline.Rounded -> addRoundRect(outline.roundRect)
                    is Outline.Generic -> addPath(outline.path)
                }
            }
            onDrawWithContent { clipPath(clip) { this@onDrawWithContent.drawContent() } }
        }
        .then(layer)
        .drawWithCache {
            val outline = shape.createOutline(size, layoutDirection, this)
            // Illuminate the actual contour. A separate fixed-radius arc can cut across
            // the interior of 12dp preview cards and 22dp model menus.
            val corner = (outline as? Outline.Rounded)?.roundRect?.topLeftCornerRadius
            val reachX = (corner?.x ?: size.minDimension / 2f) + 14.dp.toPx()
            val reachY = (corner?.y ?: size.minDimension / 2f) + 14.dp.toPx()
            val rim = Brush.linearGradient(
                0f to Color.White,
                .35f to Color.White,
                1f to Color.White.copy(alpha = 0f),
                start = Offset.Zero, end = Offset(reachX, reachY),
            )
            onDrawWithContent {
                val feedback = if (motion) press.value.coerceIn(0f, 1f) else if (pressed) 1f else 0f
                if (optics) {
                    // A restrained upper-left rim returns the requested glass edge without
                    // a bright disk following the finger or washing out the icon underneath.
                    drawOutline(outline, rim, alpha = (if (dark) .16f else .32f) + feedback * .04f,
                        style = Stroke(1.dp.toPx()))
                }
                if (feedback > 0f) {
                    if (glass && motion) {
                        drawOutline(outline, Color.White.copy(alpha = feedback * if (dark) .045f else .035f))
                    } else {
                        drawOutline(outline, colors.onSurface.copy(alpha = feedback * if (preferences.highContrast) .16f else .08f))
                    }
                }
                // All light remains below the glyphs and within the actual component contour.
                drawContent()
            }
        }
    Box(modifier = material, propagateMinConstraints = onClick == null) { content() }
}

private fun Offset.glassFocus(size: Size): Offset =
    if (isSpecified && x.isFinite() && y.isFinite()) Offset(x.coerceIn(0f, size.width), y.coerceIn(0f, size.height))
    else Offset(size.width * .5f, size.height * .5f)

internal data class PocketGlassPreferences(
    val highContrast: Boolean,
    val batterySaver: Boolean,
    val animatorsEnabled: Boolean,
)

private fun readPocketGlassPreferences(context: Context) = PocketGlassPreferences(
    highContrast = Settings.Secure.getInt(context.contentResolver, "high_text_contrast_enabled", 0) != 0,
    batterySaver = context.getSystemService(PowerManager::class.java)?.isPowerSaveMode == true,
    animatorsEnabled = ValueAnimator.areAnimatorsEnabled() &&
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f,
)

/** Refresh open surfaces immediately when a system accessibility or power setting changes. */
@Composable
internal fun rememberPocketGlassPreferences(): PocketGlassPreferences {
    LocalPocketGlassPreferences.current?.let { return it }
    val context = LocalContext.current
    var preferences by remember(context) { mutableStateOf(readPocketGlassPreferences(context)) }
    DisposableEffect(context) {
        val resolver = context.contentResolver
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                preferences = readPocketGlassPreferences(context)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(receiverContext: Context?, intent: Intent?) {
                preferences = readPocketGlassPreferences(context)
            }
        }
        resolver.registerContentObserver(Settings.Secure.getUriFor("high_text_contrast_enabled"), false, observer)
        resolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, observer)
        ContextCompat.registerReceiver(context, receiver, IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        preferences = readPocketGlassPreferences(context)
        onDispose {
            resolver.unregisterContentObserver(observer)
            context.unregisterReceiver(receiver)
        }
    }
    return preferences
}
