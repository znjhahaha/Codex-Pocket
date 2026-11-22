package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.ui.kit.PIcon
import dev.codexpocket.app.ui.kit.Pocket

/**
 * The assistant's colours, read from Pocket's shared tokens so they follow the selected appearance.
 * Surfaces are the app's neutrals; the one green is the shared positive tone, used for the assistant's
 * mark, its readiness and work that is moving.
 */
@Immutable
data class AssistantPalette(
    val background: Color,
    val surface: Color,
    val outgoing: Color,
    val onOutgoing: Color,
    val accent: Color,
    val onAccent: Color,
    val muted: Color,
    val border: Color,
    /** A quiet fill behind the accent: the mark's tile, a running badge. */
    val accentSoft: Color = Color.Unspecified,
)

@Composable
fun assistantPalette(): AssistantPalette {
    val c = Pocket.colors
    return AssistantPalette(c.bg, c.sunken, c.fillStrong, c.ink, c.positive, c.bg, c.inkMuted, c.line, c.positiveSoft)
}

/** Decorative identity only. The surrounding chat row or control supplies its semantics. */
@Composable
fun AssistantAvatar(modifier: Modifier = Modifier, size: Dp = 32.dp, tint: Color = Color.Unspecified) {
    val palette = assistantPalette()
    Box(modifier.size(size).background(palette.outgoing, RoundedCornerShape(30)), contentAlignment = Alignment.Center) {
        PIcon(AssistantIcons.Mark, null, Modifier.fillMaxSize(.8f), tint = if (tint != Color.Unspecified) tint else palette.accent)
    }
}

/** Original assistant artwork: a soft pocket face and a matching, compact outline set. */
object AssistantIcons {
    val Mark: ImageVector by lazy {
        icon(
            "Mark",
            "M7 4.5H17C19.2 4.5 20.5 5.9 20.5 8V11.5C20.5 16.5 17 19.5 12 19.5H8.8L5 21L5.7 17.2C4.2 15.8 3.5 13.8 3.5 11.5V8C3.5 5.9 4.8 4.5 7 4.5Z",
            "M8.5 9.2V9.85M15.5 9.2V9.85",
            "M8.5 13.1C10.3 15.3 13.7 15.3 15.5 13.1",
        )
    }
    val Tasks: ImageVector by lazy {
        icon("Tasks", "M3.5 7L5.5 9L9 5M12.5 7H20.5", "M3.5 16L5.5 18L9 14M12.5 16H20.5")
    }
    val Memory: ImageVector by lazy {
        icon(
            "Memory",
            "M7 3.5H19V20.5H7C5.6 20.5 4.5 19.4 4.5 18V6C4.5 4.6 5.6 3.5 7 3.5Z",
            "M4.5 18C4.5 16.6 5.6 15.5 7 15.5H19",
            "M10 3.5V10L12.5 8.5L15 10V3.5",
        )
    }
    val Schedule: ImageVector by lazy {
        icon(
            "Schedule",
            "M6 5H18C19.4 5 20.5 6.1 20.5 7.5V18C20.5 19.4 19.4 20.5 18 20.5H6C4.6 20.5 3.5 19.4 3.5 18V7.5C3.5 6.1 4.6 5 6 5Z",
            "M8 3V7M16 3V7M3.5 9H20.5",
            "M15.5 14.5A3.5 3.5 0 1 1 8.5 14.5A3.5 3.5 0 1 1 15.5 14.5Z",
            "M12 12.5V14.5L13.5 15.5",
        )
    }
    val Settings: ImageVector by lazy {
        icon(
            "Settings",
            "M3.5 7.5H7M12 7.5H20.5M3.5 16.5H12M17 16.5H20.5",
            "M12 7.5A2.5 2.5 0 1 1 7 7.5A2.5 2.5 0 1 1 12 7.5Z",
            "M17 16.5A2.5 2.5 0 1 1 12 16.5A2.5 2.5 0 1 1 17 16.5Z",
        )
    }

    private fun icon(name: String, vararg paths: String, autoMirror: Boolean = false): ImageVector {
        val builder = ImageVector.Builder("Assistant$name", 24.dp, 24.dp, 24f, 24f, autoMirror = autoMirror)
        paths.forEach { path ->
            builder.addPath(
                pathData = PathParser().parsePathString(path).toNodes(),
                fill = null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return builder.build()
    }
}
