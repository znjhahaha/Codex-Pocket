package dev.codexpocket.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import dev.codexpocket.app.ui.kit.PocketColors
import dev.codexpocket.app.ui.kit.PocketDarkColors
import dev.codexpocket.app.ui.kit.PocketLightColors
import dev.codexpocket.app.ui.kit.PocketPressIndication
import androidx.compose.foundation.LocalIndication
import dev.codexpocket.app.ui.kit.ProvidePocketOverscroll
import dev.codexpocket.app.ui.kit.ProvidePocketTokens

/** Codex-neutral surfaces; violet is reserved for selected controls. */
private val Light = lightColorScheme(
    primary = Color(0xFF242426), onPrimary = Color.White,
    primaryContainer = Color(0xFFEDE8F5), onPrimaryContainer = Color(0xFF5C477E),
    secondary = Color(0xFF70568D), tertiary = Color(0xFF70568D),
    surface = Color(0xFFFFFFFF), background = Color(0xFFFAFAFA),
    onSurface = Color(0xFF242426), onBackground = Color(0xFF242426),
    surfaceContainer = Color(0xFFF0F0F0), surfaceContainerLow = Color(0xFFF6F6F6),
    surfaceContainerHigh = Color(0xFFEAEAEA), surfaceContainerHighest = Color(0xFFE2E2E4),
    surfaceVariant = Color(0xFFEFEFF0), onSurfaceVariant = Color(0xFF67676C),
    outline = Color(0xFF8A8A90), outlineVariant = Color(0xFFE3E3E5),
)
private val Dark = darkColorScheme(
    primary = Color(0xFFF0F0F2), onPrimary = Color(0xFF242426),
    primaryContainer = Color(0xFF393044), onPrimaryContainer = Color(0xFFD4BCEB),
    secondary = Color(0xFFC5AAE0), tertiary = Color(0xFFC5AAE0),
    surface = Color(0xFF242426), background = Color(0xFF19191B),
    onSurface = Color(0xFFEEEEF0), onBackground = Color(0xFFEEEEF0),
    surfaceContainer = Color(0xFF303033), surfaceContainerLow = Color(0xFF252527),
    surfaceContainerHigh = Color(0xFF37373A), surfaceContainerHighest = Color(0xFF404044),
    surfaceVariant = Color(0xFF303033), onSurfaceVariant = Color(0xFFACACB3),
    outline = Color(0xFF84848C), outlineVariant = Color(0xFF39393D),
)

@Composable
fun PocketTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val colors = if (dark) Dark else Light
    val view = LocalView.current
    if (!LocalInspectionMode.current) {
        SideEffect {
            view.context.findActivity()?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !dark
                    isAppearanceLightNavigationBars = !dark
                }
            }
        }
    }
    MaterialTheme(colorScheme = colors, typography = Typography(
        headlineLarge = TextStyle(fontFamily = PocketReadingFont, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.6).sp),
        headlineMedium = TextStyle(fontFamily = PocketReadingFont, fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-.4).sp),
        titleLarge = TextStyle(fontFamily = PocketReadingFont, fontSize = 21.sp, lineHeight = 29.sp, fontWeight = FontWeight.Medium),
        titleMedium = TextStyle(fontFamily = PocketReadingFont, fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.Medium),
        titleSmall = TextStyle(fontFamily = PocketReadingFont, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
        bodyLarge = TextStyle(fontFamily = PocketReadingFont,  fontSize = 16.sp, lineHeight = 26.sp),
        bodyMedium = TextStyle(fontFamily = PocketReadingFont, fontSize = 16.sp, lineHeight = 26.sp),
        bodySmall = TextStyle(fontFamily = PocketReadingFont, fontSize = 13.sp, lineHeight = 21.sp),
        labelLarge = TextStyle(fontFamily = PocketReadingFont, fontSize = 15.sp, lineHeight = 23.sp, fontWeight = FontWeight.Medium),
        labelMedium = TextStyle(fontFamily = PocketReadingFont, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
        labelSmall = TextStyle(fontFamily = PocketReadingFont, fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Normal),
    )) { ProvidePocketTokens(pocketTokens(dark, colors)) { CompositionLocalProvider(LocalContentColor provides colors.onSurface,
        // Every plain clickable answers a touch the way Pocket's own controls do, not with a ripple.
        LocalIndication provides PocketPressIndication(pressedScale = .985f)) { ProvidePocketOverscroll(content) } } }
}

/** Pocket's own tokens for this appearance. Screens still on Material read the same values from [scheme]. */
internal fun pocketTokens(dark: Boolean, scheme: ColorScheme): PocketColors = (if (dark) PocketDarkColors else PocketLightColors).copy(
    danger = scheme.error, onDanger = scheme.onError, dangerSoft = scheme.errorContainer, onDangerSoft = scheme.onErrorContainer)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

/** A single low-contrast accent for the sparse native backdrop. */
@Composable
internal fun pocketAmbientInk(): Color {
    val colors = MaterialTheme.colorScheme
    return colors.secondary.copy(alpha = if (colors.background.luminance() < .5f) .22f else .18f)
}
