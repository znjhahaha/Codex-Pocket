package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.background
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.window.Popup
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import dev.codexpocket.app.ui.LocalPocketBackdrop
import dev.codexpocket.app.ui.LocalPocketClearGlass
import dev.codexpocket.app.ui.PocketDestinationTitle
import dev.codexpocket.app.ui.PocketGlassSurface
import dev.codexpocket.app.ui.PocketIcons

private enum class PFrameSlot { Header, Dock, Content }

/**
 * A page whose chrome floats over its content. Only the content is recorded into the backdrop, so
 * the glass header and dock refract what scrolls beneath them and never sample themselves. The
 * content receives the padding it needs to keep its first and last items clear of the chrome.
 */
@Composable
internal fun PFrame(modifier: Modifier = Modifier, header: @Composable () -> Unit, dock: (@Composable () -> Unit)? = null,
    content: @Composable (PaddingValues) -> Unit) {
    val background = Pocket.colors.bg
    val backdrop = rememberLayerBackdrop { drawRect(background); drawContent() }
    SubcomposeLayout(modifier.fillMaxSize().background(background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal)).imePadding()) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val top = subcompose(PFrameSlot.Header) {
            CompositionLocalProvider(LocalPocketBackdrop provides backdrop, LocalPocketClearGlass provides true) { header() }
        }.map { it.measure(loose.copy(minWidth = constraints.maxWidth)) }
        val bottom = subcompose(PFrameSlot.Dock) {
            if (dock != null) CompositionLocalProvider(LocalPocketBackdrop provides backdrop) { dock() }
        }.map { it.measure(loose.copy(minWidth = constraints.maxWidth)) }
        val topHeight = top.maxOfOrNull { it.height } ?: 0
        val bottomHeight = bottom.maxOfOrNull { it.height } ?: 0
        val body = subcompose(PFrameSlot.Content) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                content(PaddingValues(top = topHeight.toDp(), bottom = bottomHeight.toDp()))
            }
        }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.forEach { it.placeRelative(0, 0) }
            top.forEach { it.placeRelative(0, 0) }
            bottom.forEach { it.placeRelative(0, constraints.maxHeight - it.height) }
        }
    }
}

/** A round glass control for page chrome. [glyph] replaces the static icon with an animated one. */
@Composable
internal fun PGlassIconButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    glyph: (@Composable () -> Unit)? = null) {
    PocketGlassSurface(modifier.size(PocketSpace.touch).semantics { contentDescription = label }, CircleShape, onClick = onClick) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            ProvidePInk(Pocket.colors.ink) { if (glyph != null) glyph() else PIcon(icon, null, Modifier.size(20.dp)) }
        }
    }
}

/**
 * The top bar: back, a title capsule with an optional status slot, then trailing actions. The title
 * changes in place when the destination does.
 */
@Composable
internal fun PGlassBar(title: String, onBack: (() -> Unit)?, modifier: Modifier = Modifier, backLabel: String = "返回",
    status: (@Composable () -> Unit)? = null, onTitle: (() -> Unit)? = null, actions: @Composable RowScope.() -> Unit = {}) {
    Row(modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (onBack != null) PGlassIconButton(PocketIcons.ArrowBack, backLabel, onBack)
        PocketGlassSurface(Modifier.weight(1f), PocketRadii.capsule, onClick = onTitle) {
            Row(Modifier.fillMaxWidth().heightIn(min = PocketSpace.touch).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ProvidePInk(Pocket.colors.ink) {
                    PocketDestinationTitle(title, Modifier.weight(1f, fill = false).semantics { heading() })
                    status?.invoke()
                }
            }
        }
        actions()
    }
}

private enum class PScaffoldSlot { Bar, Body }

/**
 * A page with a bar across its top. The body fills the page and is handed the padding that keeps it
 * clear of the bar and the system bars; a page without a bar clears the status bar instead.
 */
@Composable
internal fun PScaffold(modifier: Modifier = Modifier, topBar: @Composable () -> Unit = {}, color: Color = Pocket.colors.bg,
    content: @Composable (PaddingValues) -> Unit) {
    val insets = WindowInsets.systemBars
    SubcomposeLayout(modifier.fillMaxSize().background(color)) { constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val bar = subcompose(PScaffoldSlot.Bar) { ProvidePInk(Pocket.colors.ink, content = topBar) }.map { it.measure(loose) }
        val top = bar.maxOfOrNull { it.height } ?: 0
        val padding = PaddingValues(start = insets.getLeft(this, layoutDirection).toDp(), top = if (top > 0) top.toDp() else insets.getTop(this).toDp(),
            end = insets.getRight(this, layoutDirection).toDp(), bottom = insets.getBottom(this).toDp())
        val body = subcompose(PScaffoldSlot.Body) { ProvidePInk(Pocket.colors.ink) { content(padding) } }.map { it.measure(loose) }
        layout(constraints.maxWidth, constraints.maxHeight) {
            body.forEach { it.place(0, 0) }
            bar.forEach { it.place(0, 0) }
        }
    }
}

/** At least a finger's width in both directions, whatever is drawn inside. */
internal fun Modifier.pocketMinTouch(): Modifier = sizeIn(minWidth = PocketSpace.touch, minHeight = PocketSpace.touch)

/**
 * Holding the content shows a short label above it; lifting lets it linger a moment and go. The
 * hold is only observed, so the content's own click and long-click are untouched.
 */
@Composable
internal fun PTooltip(text: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val motion = rememberPocketMotion()
    val scale by animateFloatAsState(if (shown) 1f else .9f, PocketSprings.snappy<Float>().orSnap(motion), label = "tooltip-scale")
    val alpha by animateFloatAsState(if (shown) 1f else 0f, PocketMotion.exit<Float>().orSnap(motion), label = "tooltip-alpha")
    LaunchedEffect(shown) { if (shown) { pocketPause(1600); shown = false } }
    Box(modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) { waitForUpOrCancellation(PointerEventPass.Initial) }
            if (lifted == null) shown = true
        }
    }) {
        content()
        if (shown || alpha > .01f) Popup(alignment = Alignment.TopCenter, offset = IntOffset(0, with(LocalDensity.current) { -40.dp.roundToPx() })) {
            Box(Modifier.graphicsLayer { this.alpha = alpha; scaleX = scale; scaleY = scale }.clip(PocketRadii.chip).background(Pocket.colors.solid)
                .padding(horizontal = 10.dp, vertical = 6.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
                PText(text, style = Pocket.type.caption, color = Pocket.colors.onSolid, maxLines = 1)
            }
        }
    }
}
