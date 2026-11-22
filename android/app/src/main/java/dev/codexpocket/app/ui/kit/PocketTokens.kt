package dev.codexpocket.app.ui.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.codexpocket.app.ui.PocketReadingFont

/**
 * Pocket's own design tokens. Neutral surfaces carry the content; violet marks selection and
 * progress; green, amber and red are reserved for outcomes. Nothing here depends on Material.
 */
@Immutable
internal data class PocketColors(
    val dark: Boolean,
    /** Page background. */
    val bg: Color,
    /** Raised reading surface: cards on the page, sheets. */
    val surface: Color,
    /** Quiet container sitting on the page: grouped rows, input wells. */
    val sunken: Color,
    val fill: Color,
    val fillStrong: Color,
    val fillStrongest: Color,
    val ink: Color,
    val inkMuted: Color,
    val inkFaint: Color,
    val line: Color,
    /** Highest-emphasis action fill and the text on it. */
    val solid: Color,
    val onSolid: Color,
    val accent: Color,
    val accentSoft: Color,
    val onAccentSoft: Color,
    val positive: Color,
    val positiveSoft: Color,
    val warning: Color,
    val warningSoft: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerSoft: Color,
    val onDangerSoft: Color,
)

internal val PocketLightColors = PocketColors(
    dark = false,
    bg = Color(0xFFFAFAFA), surface = Color(0xFFFFFFFF), sunken = Color(0xFFF6F6F6),
    fill = Color(0xFFF0F0F0), fillStrong = Color(0xFFEAEAEA), fillStrongest = Color(0xFFE2E2E4),
    ink = Color(0xFF242426), inkMuted = Color(0xFF67676C), inkFaint = Color(0xFF8A8A90), line = Color(0xFFE3E3E5),
    solid = Color(0xFF242426), onSolid = Color.White,
    accent = Color(0xFF70568D), accentSoft = Color(0xFFEDE8F5), onAccentSoft = Color(0xFF5C477E),
    positive = Color(0xFF1F7A47), positiveSoft = Color(0xFFE3F3E9),
    warning = Color(0xFF96600A), warningSoft = Color(0xFFFBF0DC),
    danger = Color(0xFFB3261E), onDanger = Color.White, dangerSoft = Color(0xFFF9DEDC), onDangerSoft = Color(0xFF8C1D18),
)

internal val PocketDarkColors = PocketColors(
    dark = true,
    bg = Color(0xFF19191B), surface = Color(0xFF242426), sunken = Color(0xFF252527),
    fill = Color(0xFF303033), fillStrong = Color(0xFF37373A), fillStrongest = Color(0xFF404044),
    ink = Color(0xFFEEEEF0), inkMuted = Color(0xFFACACB3), inkFaint = Color(0xFF84848C), line = Color(0xFF39393D),
    solid = Color(0xFFF0F0F2), onSolid = Color(0xFF242426),
    accent = Color(0xFFC5AAE0), accentSoft = Color(0xFF393044), onAccentSoft = Color(0xFFD4BCEB),
    positive = Color(0xFF7FD6A2), positiveSoft = Color(0xFF233A2D),
    warning = Color(0xFFEFC06A), warningSoft = Color(0xFF3D3220),
    danger = Color(0xFFF2B8B5), onDanger = Color(0xFF601410), dangerSoft = Color(0xFF8C1D18), onDangerSoft = Color(0xFFF9DEDC),
)

/** One reading family for CJK and Latin; sizes match the existing Pocket scale. */
@Immutable
internal data class PocketType(
    val display: TextStyle,
    val headline: TextStyle,
    val titleLarge: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    val caption: TextStyle,
    /** Tabular digits for counters and amounts that change in place. */
    val number: TextStyle,
    val mono: TextStyle,
)

private fun reading(size: Int, line: Int, weight: FontWeight? = null, tracking: Float = 0f) =
    TextStyle(fontFamily = PocketReadingFont, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight,
        letterSpacing = if (tracking == 0f) TextUnit.Unspecified else tracking.sp)

internal val PocketTypeScale = PocketType(
    display = reading(30, 38, FontWeight.SemiBold, -.6f),
    headline = reading(26, 34, FontWeight.SemiBold, -.4f),
    titleLarge = reading(21, 29, FontWeight.Medium),
    title = reading(17, 25, FontWeight.Medium),
    titleSmall = reading(15, 23, FontWeight.Medium),
    body = reading(16, 26),
    bodySmall = reading(13, 21),
    label = reading(15, 23, FontWeight.Medium),
    labelSmall = reading(13, 20, FontWeight.Medium),
    caption = reading(13, 20, FontWeight.Normal),
    number = reading(26, 32, FontWeight.SemiBold, -.4f).copy(fontFeatureSettings = "tnum"),
    mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp),
)

internal object PocketRadii {
    val chip = RoundedCornerShape(10.dp)
    val control = RoundedCornerShape(16.dp)
    val field = RoundedCornerShape(18.dp)
    val card = RoundedCornerShape(20.dp)
    val panel = RoundedCornerShape(24.dp)
    val sheet = RoundedCornerShape(28.dp)
    /** Straight edges with two complete half-circle ends. */
    val capsule = RoundedCornerShape(percent = 50)
}

internal object PocketSpace {
    val hair = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    /** Smallest touch target on any interactive element. */
    val touch = 48.dp
}

internal val LocalPocketColors = staticCompositionLocalOf { PocketLightColors }
internal val LocalPocketType = staticCompositionLocalOf { PocketTypeScale }
/** Foreground for text and glyphs inside the current container. */
internal val LocalPInk = compositionLocalOf { Color.Unspecified }
internal val LocalPTextStyle = compositionLocalOf<TextStyle?> { null }

internal object Pocket {
    val colors: PocketColors @Composable @ReadOnlyComposable get() = LocalPocketColors.current
    val type: PocketType @Composable @ReadOnlyComposable get() = LocalPocketType.current
    /** The container's foreground, or body ink at the root. */
    val ink: Color @Composable @ReadOnlyComposable get() = LocalPInk.current.takeIf { it != Color.Unspecified } ?: LocalPocketColors.current.ink
}

@Composable
internal fun ProvidePocketTokens(colors: PocketColors, type: PocketType = PocketTypeScale, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalPocketColors provides colors, LocalPocketType provides type,
        LocalPInk provides colors.ink, LocalPTextStyle provides type.body, content = content)
}

/** Set the foreground and, optionally, the default text style for a container's content. */
@Composable
internal fun ProvidePInk(ink: Color, style: TextStyle? = null, content: @Composable () -> Unit) {
    if (style == null) CompositionLocalProvider(LocalPInk provides ink, content = content)
    else CompositionLocalProvider(LocalPInk provides ink, LocalPTextStyle provides style, content = content)
}
