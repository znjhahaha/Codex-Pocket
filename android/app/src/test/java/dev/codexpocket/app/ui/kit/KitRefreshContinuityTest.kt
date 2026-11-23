package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.size
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.LocalPocketGlassPreferences
import dev.codexpocket.app.ui.PocketGlassPreferences
import dev.codexpocket.app.ui.PocketTheme
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
class KitRefreshContinuityTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var trigger by mutableIntStateOf(0)
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private lateinit var progress: State<Float>

    private fun screen() {
        compose.mainClock.autoAdvance = false
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    CompositionLocalProvider(LocalPocketGlassPreferences provides preferences) {
                        progress = rememberGlyphPulse(trigger)
                        MotionIcon(MotionGlyphs.Refresh, { progress.value }, Modifier.size(32.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        // With the clock frozen before setContent, commit the initial effect before changing its key.
        frames(2)
        assertEquals(1f, compose.runOnIdle { progress.value }, .0001f)
    }

    private fun frames(count: Int) {
        repeat(count) {
            compose.mainClock.advanceTimeBy(16)
            compose.waitForIdle()
        }
    }

    @Test fun anotherRefreshDuringTheTurnDoesNotJumpBackToItsStartingPose() {
        screen()
        compose.runOnIdle { trigger++ }
        frames(12)
        val before = compose.runOnIdle { progress.value }
        assertTrue("The first refresh must actually be in flight: $before", before in .1f.. .8f)
        compose.runOnIdle { trigger++ }
        frames(1)
        val after = compose.runOnIdle { progress.value }
        assertTrue("A second refresh rewound the visible turn: $before -> $after", after >= before)
        frames(80)
        assertEquals(1f, compose.runOnIdle { progress.value }, .0001f)
    }

    @Test fun disablingMotionDuringRefreshImmediatelyShowsTheRestingPose() {
        screen()
        compose.runOnIdle { trigger++ }
        frames(12)
        val before = compose.runOnIdle { progress.value }
        assertTrue("The first refresh must actually be in flight: $before", before in .1f.. .8f)
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        frames(2)
        assertEquals("Motion preference changes must stop an already-running refresh", 1f,
            compose.runOnIdle { progress.value }, .0001f)
    }

    @Test fun enablingMotionAgainWaitsForANewRefresh() {
        screen()
        compose.runOnIdle { trigger++ }
        frames(12)
        assertTrue("The first refresh must actually be in flight", compose.runOnIdle { progress.value } in .1f.. .8f)
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, false) }
        frames(2)
        compose.runOnIdle { trigger++ }
        frames(2)
        assertEquals("Refreshes with motion disabled stay at rest", 1f, compose.runOnIdle { progress.value }, .0001f)
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, true) }
        frames(6)
        assertEquals("Enabling motion must not replay an earlier refresh", 1f, compose.runOnIdle { progress.value }, .0001f)
        compose.runOnIdle { trigger++ }
        frames(12)
        assertTrue("A new refresh must still animate", compose.runOnIdle { progress.value } in .1f.. .8f)
        frames(80)
        assertEquals(1f, compose.runOnIdle { progress.value }, .0001f)
    }
}
