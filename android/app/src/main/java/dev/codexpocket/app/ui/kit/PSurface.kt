package dev.codexpocket.app.ui.kit

import androidx.compose.ui.semantics.heading
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

/**
 * A group of rows on a page: an optional heading above, and the rows together on one outlined
 * surface. This is how every settings-like page lays its content out.
 */
@Composable
internal fun PSection(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (title != null) PText(title, Modifier.padding(horizontal = 4.dp).semantics { heading() }, style = Pocket.type.titleSmall)
        PSurface(Modifier.fillMaxWidth(), shape = PocketRadii.card, color = Pocket.colors.surface,
            border = BorderStroke(1.dp, Pocket.colors.line.copy(alpha = .55f))) {
            Column(Modifier.padding(6.dp), content = content)
        }
    }
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
