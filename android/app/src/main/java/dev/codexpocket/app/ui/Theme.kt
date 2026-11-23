package dev.codexpocket.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import dev.codexpocket.app.ui.kit.PocketColors
import dev.codexpocket.app.ui.kit.PocketDarkColors
import dev.codexpocket.app.ui.kit.Pocket
import dev.codexpocket.app.ui.kit.PocketLightColors
import dev.codexpocket.app.ui.kit.PocketPressIndication
import androidx.compose.foundation.LocalIndication
import dev.codexpocket.app.ui.kit.ProvidePocketOverscroll
import dev.codexpocket.app.ui.kit.ProvidePocketTokens

/**
 * Pocket's appearance for everything inside: its own colours and type, its press response on every
 * plain clickable, selection handles in the accent, and lists that bounce at their ends.
 */
@Composable
fun PocketTheme(theme: String, content: @Composable () -> Unit) {
    val dark = theme == "dark" || (theme == "system" && isSystemInDarkTheme())
    val colors = pocketTokens(dark)
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
    ProvidePocketTokens(colors) {
        CompositionLocalProvider(
            // Every plain clickable answers a touch the way Pocket's own controls do, not with a ripple.
            LocalIndication provides PocketPressIndication(pressedScale = .985f),
            LocalTextSelectionColors provides TextSelectionColors(colors.accent, colors.accent.copy(alpha = .32f)),
        ) { ProvidePocketOverscroll(content) }
    }
}

/** Pocket's own tokens for this appearance. */
internal fun pocketTokens(dark: Boolean): PocketColors = if (dark) PocketDarkColors else PocketLightColors

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> if (baseContext !== this) baseContext.findActivity() else null
    else -> null
}

/** A single low-contrast accent for the sparse native backdrop. */
@Composable
internal fun pocketAmbientInk(): Color {
    val colors = Pocket.colors
    return colors.accent.copy(alpha = if (colors.bg.luminance() < .5f) .22f else .18f)
}
