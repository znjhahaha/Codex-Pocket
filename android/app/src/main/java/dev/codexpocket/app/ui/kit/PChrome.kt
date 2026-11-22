package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.background
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
