package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.paint
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** A tinted glyph, 24dp unless the caller sizes it. A null description marks it decorative. */
@Composable
internal fun PIcon(icon: ImageVector, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    PIcon(rememberVectorPainter(icon), contentDescription, modifier, tint)
}

@Composable
internal fun PIcon(painter: Painter, contentDescription: String?, modifier: Modifier = Modifier, tint: Color = Color.Unspecified) {
    val ink = if (tint != Color.Unspecified) tint else Pocket.ink
    val described = if (contentDescription != null) Modifier.semantics {
        this.contentDescription = contentDescription
        role = Role.Image
    } else Modifier
    // The caller's size comes first in the chain, so it constrains the 24dp default.
    Box(modifier.size(24.dp).paint(painter, colorFilter = ColorFilter.tint(ink), contentScale = ContentScale.Fit).then(described))
}
