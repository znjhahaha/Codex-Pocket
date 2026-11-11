package dev.codexpocket.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Assistant-local colors; follow the selected app theme, including manual overrides. */
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
)

private val LightAssistantPalette = AssistantPalette(
    background = Color(0xFFF3F4F2),
    surface = Color(0xFFFFFFFF),
    outgoing = Color(0xFFD5EBCD),
    onOutgoing = Color(0xFF243B2B),
    accent = Color(0xFF28583F),
    onAccent = Color(0xFFFFFFFF),
    muted = Color(0xFF5B675E),
    border = Color(0xFFDCE2DA),
)

private val DarkAssistantPalette = AssistantPalette(
    background = Color(0xFF171C19),
    surface = Color(0xFF232B25),
    outgoing = Color(0xFF334E3B),
    onOutgoing = Color(0xFFEDF7E9),
    accent = Color(0xFFB3D9A5),
    onAccent = Color(0xFF1C3626),
    muted = Color(0xFFAFBBAF),
    border = Color(0xFF3D4A40),
)

@Composable
fun assistantPalette(): AssistantPalette =
    if (MaterialTheme.colorScheme.background.luminance() < .3f) DarkAssistantPalette else LightAssistantPalette

/** Decorative identity only. The surrounding chat row or control supplies its semantics. */
@Composable
fun AssistantAvatar(modifier: Modifier = Modifier, size: Dp = 32.dp) {
    val palette = assistantPalette()
    Box(
        modifier.size(size).background(palette.outgoing, RoundedCornerShape(30)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = AssistantIcons.Mark,
            contentDescription = null,
            modifier = Modifier.fillMaxSize(.8f),
            tint = palette.accent,
        )
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
    val More: ImageVector by lazy {
        icon("More", "M5 12H5.01M12 12H12.01M19 12H19.01")
    }
    val Plus: ImageVector by lazy {
        icon("Plus", "M12 5V19M5 12H19")
    }
    val Send: ImageVector by lazy {
        icon("Send", "M4 10.5L20 4L13.5 20L10.5 13.5Z", "M10.5 13.5L20 4")
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
    val Project: ImageVector by lazy {
        icon(
            "Project",
            "M3.5 7V17.5C3.5 18.9 4.6 20 6 20H18C19.4 20 20.5 18.9 20.5 17.5V8.5C20.5 7.1 19.4 6 18 6H12L10 4H6C4.6 4 3.5 5.1 3.5 6.5V7Z",
            "M3.5 9.5H20.5",
        )
    }
    val Back: ImageVector by lazy {
        icon("Back", "M14.5 5L7.5 12L14.5 19", autoMirror = true)
    }
    val Check: ImageVector by lazy {
        icon("Check", "M5 12L9.5 16.5L19 7")
    }
    val Close: ImageVector by lazy {
        icon("Close", "M6 6L18 18M18 6L6 18")
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
