package dev.codexpocket.app.ui.kit

import androidx.activity.BackEventCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * BackHandler that also hears the back gesture's progress where the system reports it (Android 14 and
 * later, for an activity that opts in). Committing stays the same synchronous call on every release;
 * without progress events this is exactly BackHandler.
 */
@Composable
internal fun PBackHandler(enabled: Boolean, onProgress: (BackEventCompat) -> Unit, onCancel: () -> Unit, onBack: () -> Unit) {
    val progress by rememberUpdatedState(onProgress)
    val cancel by rememberUpdatedState(onCancel)
    val back by rememberUpdatedState(onBack)
    val callback = remember {
        object : OnBackPressedCallback(enabled) {
            override fun handleOnBackStarted(backEvent: BackEventCompat) = progress(backEvent)
            override fun handleOnBackProgressed(backEvent: BackEventCompat) = progress(backEvent)
            override fun handleOnBackCancelled() = cancel()
            override fun handleOnBackPressed() = back()
        }
    }
    SideEffect { callback.isEnabled = enabled }
    val dispatcher = checkNotNull(LocalOnBackPressedDispatcherOwner.current) {
        "No OnBackPressedDispatcherOwner was provided via LocalOnBackPressedDispatcherOwner"
    }.onBackPressedDispatcher
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, dispatcher) {
        dispatcher.addCallback(lifecycleOwner, callback)
        onDispose { callback.remove() }
    }
}
