package dev.codexpocket.app.ui.kit

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.ui.LocalPocketGlass
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

/** A sent message leaves the field it was typed in and lands in its place; nothing else flies. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class KitSendFlightTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var messages by mutableStateOf(listOf("old"))
    private var lift by mutableIntStateOf(0)
    private lateinit var flight: PSendFlight
    private lateinit var view: android.view.View

    private fun content() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            view = LocalView.current
            CompositionLocalProvider(LocalPocketGlassPreferences provides preferences, LocalPocketGlass provides false) {
                flight = rememberPSendFlight()
                Box(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxWidth().offset(y = -lift.dp).padding(top = 80.dp, end = 16.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        messages.forEach { key ->
                            val from = remember(key) { flight.take(key) }
                            Box(Modifier.pocketSendLanding(from, flight).testTag("bubble:$key").size(160.dp, 44.dp).background(Pocket.colors.fillStrong, PocketRadii.capsule))
                            PText("12:00", Modifier.pocketSendTrail(from).testTag("trail:$key"))
                        }
                    }
                    Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 60.dp).pocketSendSource(flight).testTag("field").size(240.dp, 40.dp))
                }
            }
        } } }
        compose.waitForIdle()
    }
    private fun advance(ms: Long) { repeat((ms / 16).toInt().coerceAtLeast(1)) { compose.mainClock.advanceTimeBy(16); compose.waitForIdle() } }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun capture(name: String) = compose.runOnIdle {
        val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
        view.draw(android.graphics.Canvas(bitmap))
        java.io.File("build/reports/kit/$name.png").also { it.parentFile.mkdirs() }.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    @Test fun theSentMessageStartsOverTheTypedTextAndSpringsToItsPlace() {
        content()
        val field = bounds("field")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { flight.arm(listOf("old")); messages = messages + "new" }
        advance(32)
        val start = bounds("bubble:new")
        val inset = 16 * view.resources.displayMetrics.density
        assertEquals("Its text starts over the typed text", field.left - inset, start.left, field.width * .08f)
        assertTrue("and at the field, far below where it will rest: $start", start.top > field.top - 60 * view.resources.displayMetrics.density)
        capture("send-flight-032")
        var last = start; var peak = start.top
        repeat(22) {
            advance(32)
            val now = bounds("bubble:new")
            assertTrue("It only travels toward the trailing side", now.left >= last.left - .5f)
            peak = minOf(peak, now.top); last = now
        }
        capture("send-flight-352")
        advance(1500)
        val rest = bounds("bubble:new")
        assertTrue("It rose above its resting place before settling", peak < rest.top - .5f)
        assertEquals("It lands under the message before it", bounds("trail:old").bottom + 12 * view.resources.displayMetrics.density, rest.top, 1f)
        assertEquals(1, flight.launches)
        assertEquals("The message that was already there never moved", 80 * view.resources.displayMetrics.density, bounds("bubble:old").top, 1f)
    }

    @Test fun aListStillScrollingTheMessageIntoViewDoesNotThrowTheFlightOff() {
        content()
        val field = bounds("field")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { flight.arm(listOf("old")); messages = messages + "new" }
        advance(32)
        val start = bounds("bubble:new")
        // The whole list moves up, as it does when it follows its end; the bubble still leaves from the field.
        compose.runOnIdle { lift = 80 }
        advance(16)
        val moved = bounds("bubble:new")
        assertEquals("It is still where the text was typed", start.left, moved.left, field.width * .05f)
        assertTrue("and has not jumped with the list: $start then $moved", kotlin.math.abs(moved.top - start.top) < 75 * view.resources.displayMetrics.density)
        advance(1600)
        assertEquals("and it lands where the list now puts it", bounds("trail:old").bottom + 12 * view.resources.displayMetrics.density, bounds("bubble:new").top, 1f)
    }

    @Test fun onlyTheMessageSentAfterArmingFliesAndTheWaitRunsOut() {
        content()
        compose.mainClock.autoAdvance = false
        // Nothing was sent: a message that simply arrives is shown in place.
        compose.runOnIdle { messages = messages + "arrived" }
        advance(32)
        val arrived = bounds("bubble:arrived")
        advance(600)
        assertEquals(arrived, bounds("bubble:arrived"))
        // Sent, but answered too late: the flight has been given up.
        compose.runOnIdle { flight.arm(messages) }
        advance(9_000)
        compose.runOnIdle { messages = messages + "late" }
        advance(32)
        val late = bounds("bubble:late")
        advance(600)
        assertEquals(late, bounds("bubble:late"))
        // One arming carries one message.
        compose.runOnIdle { flight.arm(messages); messages = messages + "first" + "second" }
        advance(32)
        val second = bounds("bubble:second")
        advance(1500)
        assertEquals("The second new message does not fly", second, bounds("bubble:second"))
        assertEquals(1, flight.launches)
    }

    @Test fun withMotionOffTheMessageIsSimplyInItsPlace() {
        preferences = PocketGlassPreferences(false, false, false)
        content()
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { flight.arm(listOf("old")); messages = messages + "new" }
        advance(32)
        val now = bounds("bubble:new")
        advance(800)
        assertEquals(now, bounds("bubble:new"))
        assertEquals(0, flight.launches)
    }
}
