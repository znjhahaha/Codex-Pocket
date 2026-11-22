package dev.codexpocket.app.ui.kit

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalView

/** What the touch means, not how the motor moves. The platform picks the effect and honours its own setting. */
internal enum class PocketHaptic { Tap, Confirm, Reject, ToggleOn, ToggleOff, Tick, Threshold, LongPress }

internal fun interface PocketHaptics { fun perform(kind: PocketHaptic) }

/** Tests and previews substitute a recorder; production resolves the hosting view. */
internal val LocalPocketHaptics = staticCompositionLocalOf<PocketHaptics?> { null }

/** Newer effects fall back to the closest constant the device's release understands. */
internal fun pocketHapticConstant(kind: PocketHaptic, sdk: Int = Build.VERSION.SDK_INT): Int = when (kind) {
    PocketHaptic.Tap -> HapticFeedbackConstants.CONTEXT_CLICK
    PocketHaptic.Confirm -> if (sdk >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.VIRTUAL_KEY
    PocketHaptic.Reject -> if (sdk >= 30) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.LONG_PRESS
    PocketHaptic.ToggleOn -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CONTEXT_CLICK
    PocketHaptic.ToggleOff -> if (sdk >= 34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CONTEXT_CLICK
    PocketHaptic.Tick -> if (sdk >= 34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
    PocketHaptic.Threshold -> when {
        sdk >= 34 -> HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
        sdk >= 30 -> HapticFeedbackConstants.GESTURE_START
        else -> HapticFeedbackConstants.CONTEXT_CLICK
    }
    PocketHaptic.LongPress -> HapticFeedbackConstants.LONG_PRESS
}

@Composable
internal fun rememberPocketHaptics(): PocketHaptics {
    LocalPocketHaptics.current?.let { return it }
    val view = LocalView.current
    return remember(view) { PocketHaptics { kind -> view.performHapticFeedback(pocketHapticConstant(kind)) } }
}
