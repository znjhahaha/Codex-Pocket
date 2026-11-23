package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * An opaque container: fill, outline and the ink its content inherits. Reading surfaces stay solid,
 * and a touch on their empty space stops there instead of reaching whatever lies beneath.
 */
@Composable
internal fun PSurface(modifier: Modifier = Modifier, shape: Shape = RectangleShape, color: Color = Pocket.colors.surface,
    ink: Color = Pocket.colors.ink, border: BorderStroke? = null, content: @Composable () -> Unit) {
    Box(modifier.then(if (border != null) Modifier.border(border, shape) else Modifier).clip(shape).background(color)
        .semantics { isTraversalGroup = true }.pointerInput(Unit) { },
        propagateMinConstraints = true) { ProvidePInk(ink, content = content) }
}

/** A solid content card. Glass is kept for navigation and input chrome, never for reading areas. */
@Composable
internal fun PCard(modifier: Modifier = Modifier, color: Color = Pocket.colors.sunken, shape: Shape = PocketRadii.card,
    padding: PaddingValues = PaddingValues(PocketSpace.lg), spacing: Dp = 10.dp, border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit) {
    PSurface(modifier.fillMaxWidth(), shape, color, border = border) {
        Column(Modifier.padding(padding), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
internal fun PDivider(modifier: Modifier = Modifier, color: Color = Pocket.colors.line, thickness: Dp = 1.dp) {
    Box(modifier.fillMaxWidth().height(thickness).background(color))
}

@Composable
internal fun PVerticalDivider(modifier: Modifier = Modifier, color: Color = Pocket.colors.line, thickness: Dp = 1.dp) {
    Box(modifier.fillMaxHeight().width(thickness).background(color))
}
