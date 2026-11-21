package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.codexpocket.app.MainActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PocketLightPointsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var focused by mutableStateOf(true)
    private var active by mutableStateOf(true)
    private lateinit var owner: Owner
    private lateinit var view: android.view.View
    private class Owner : LifecycleOwner { override val lifecycle = LifecycleRegistry(this) }
    private fun show(edgeOnly: Boolean = false, content: @Composable () -> Unit = {}) {
        compose.activity.runOnUiThread {
            owner = Owner().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.activity.setContent { PocketTheme("dark") {
                view = LocalView.current
                val actualWindow = LocalWindowInfo.current
                val window = remember(actualWindow) { object : WindowInfo by actualWindow {
                    override val isWindowFocused get() = focused
                } }
                CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalWindowInfo provides window,
                    LocalLifecycleOwner provides owner) {
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                        PocketLightPoints(Modifier.matchParentSize(), edgeOnly, active)
                        content()
                    }
                }
            } }
        }
        compose.waitForIdle()
    }
    private fun frame(ms: Long) {
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(ms)
        compose.waitForIdle()
    }
    private fun pixels(): IntArray = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        val data = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(data, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        bitmap.recycle()
        data
    }

    @Test fun motionStopsWithLifecycleFocusAccessibilityAndPowerSettings() {
        // Compose cancels infinite animations while autoAdvance is true. Mount static first,
        // then enable the real animation after switching to the manually advanced clock.
        active = false
        show()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { active = true }
        frame(32)
        val first = pixels()
        frame(5000)
        assertFalse("Foreground light points should drift", first.contentEquals(pixels()))
        for (gate in listOf<() -> Unit>(
            { focused = false },
            { focused = true; owner.lifecycle.currentState = Lifecycle.State.STARTED },
            { owner.lifecycle.currentState = Lifecycle.State.RESUMED; preferences = PocketGlassPreferences(false, false, false) },
            { preferences = PocketGlassPreferences(false, true, true) },
            { preferences = PocketGlassPreferences(true, false, true) },
            { preferences = PocketGlassPreferences(false, false, true); active = false },
        )) {
            compose.runOnIdle(gate)
            frame(32)
            val stopped = pixels()
            frame(5000)
            assertArrayEquals(stopped, pixels())
        }
    }

    @Test fun decorativeLayerDoesNotInterceptControlsOrPaintOverHomeProse() {
        preferences = PocketGlassPreferences(false, false, false)
        var taps = 0
        show(edgeOnly = true) { PocketButton("可点击", { taps++ }, modifier = Modifier.padding(32.dp)) }
        compose.onNodeWithText("可点击").performClick()
        assertEquals(1, taps)
        // The middle of the lower page remains exactly the opaque background color.
        compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val expected = androidx.compose.ui.graphics.Color(0xFF19191B).toArgb()
            for (y in view.height / 2 until view.height step 20) {
                assertEquals(expected, bitmap.getPixel(view.width / 2, y))
            }
            bitmap.recycle()
        }
    }
}
