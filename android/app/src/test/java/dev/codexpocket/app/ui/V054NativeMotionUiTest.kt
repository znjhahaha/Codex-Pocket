package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.WindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V054NativeMotionUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val backdrop = Color(0xFF101010)
    private var preferences by mutableStateOf(PocketGlassPreferences(false, false, true))
    private var focused by mutableStateOf(true)
    private lateinit var owner: MotionLifecycle
    private lateinit var drawView: android.view.View

    private class MotionLifecycle : LifecycleOwner {
        override val lifecycle = LifecycleRegistry(this)
    }

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            owner = MotionLifecycle().apply { lifecycle.currentState = Lifecycle.State.RESUMED }
            compose.activity.setContent {
                drawView = androidx.compose.ui.platform.LocalView.current
                PocketTheme("dark") {
                    val platformWindow = LocalWindowInfo.current
                    val window = remember(platformWindow) {
                        object : WindowInfo by platformWindow {
                            override val isWindowFocused get() = focused
                        }
                    }
                    CompositionLocalProvider(LocalPocketGlassPreferences provides preferences,
                        LocalLifecycleOwner provides owner, LocalWindowInfo provides window) {
                        Column(Modifier.width(320.dp).background(backdrop)) { block() }
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun frame(millis: Long) {
        compose.runOnIdle { androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications() }
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(millis)
        compose.waitForIdle()
    }
    private fun pixels(tag: String): PixelMap {
        // Robolectric has no platform redraw/PixelCopy fence. Draw the actual Compose view
        // into native Skia directly; device tests separately capture the hardware window.
        val bounds = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        return compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(drawView.width, drawView.height, android.graphics.Bitmap.Config.ARGB_8888)
            drawView.draw(android.graphics.Canvas(bitmap))
            val crop = android.graphics.Bitmap.createBitmap(bitmap, bounds.left.toInt().coerceAtLeast(0), bounds.top.toInt().coerceAtLeast(0),
                bounds.width.toInt().coerceAtLeast(1), bounds.height.toInt().coerceAtLeast(1))
            val pixels = crop.asImageBitmap().toPixelMap()
            if (crop !== bitmap) crop.recycle()
            bitmap.recycle()
            pixels
        }
    }
    private fun differences(a: PixelMap, b: PixelMap): Int {
        assertEquals(a.width, b.width); assertEquals(a.height, b.height)
        var changed = 0
        for (y in 0 until a.height) for (x in 0 until a.width) {
            if (a[x, y].toArgb() != b[x, y].toArgb()) changed++
        }
        return changed
    }

    @Test fun featherRevealReservesFinalBoundsAndReopeningDoesNotReplay() {
        val reveal = QuestionRevealState()
        var shown by mutableStateOf(false)
        content {
            if (shown) QuestionReveal("one", reveal, Modifier.testTag("reveal")) {
                Box(Modifier.size(240.dp, 180.dp).background(Color.Red))
            }
            Text("Composer anchor", Modifier.testTag("anchor"))
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { shown = true }
        frame(32)
        val bounds = compose.onNodeWithTag("reveal").fetchSemanticsNode().boundsInRoot
        val anchor = compose.onNodeWithTag("anchor").fetchSemanticsNode().boundsInRoot
        frame(80)
        val partial = pixels("reveal")
        assertEquals(Color.Red.toArgb(), partial[partial.width / 2, 2].toArgb())
        assertNotEquals(Color.Red.toArgb(), partial[partial.width / 2, partial.height - 2].toArgb())
        repeat(6) {
            frame(48)
            assertEquals(bounds, compose.onNodeWithTag("reveal").fetchSemanticsNode().boundsInRoot)
            assertEquals(anchor, compose.onNodeWithTag("anchor").fetchSemanticsNode().boundsInRoot)
        }
        val final = pixels("reveal")
        assertEquals(Color.Red.toArgb(), final[final.width / 2, final.height - 2].toArgb())
        compose.runOnIdle { shown = false }; frame(16)
        compose.runOnIdle { shown = true }; frame(16)
        assertEquals(0, differences(final, pixels("reveal")))
    }

    @Test fun nativeSummarySweepChangesOnlyGlyphsAndNeverRemeasuresText() {
        var active by mutableStateOf(false)
        var measurements = 0
        content {
            NativeSummaryProse("Native summary    文本\nSecond line", "summary",
                Modifier.fillMaxWidth().background(backdrop).testTag("summary").layout { measurable, constraints ->
                    measurements++
                    val placeable = measurable.measure(constraints)
                    layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
                }, active = active)
            NativeSummaryProse("Historical summary", "history", Modifier.testTag("history"))
            Text("Ordinary assistant message", Modifier.testTag("ordinary"))
        }
        val plain = pixels("summary")
        val history = pixels("history"); val ordinary = pixels("ordinary")
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { active = true }; frame(48)
        val measured = measurements
        frame(656)
        val sweep = pixels("summary")
        assertTrue("The sweep must visibly change native text ink", differences(plain, sweep) > 5)
        var emptyPixels = 0
        for (y in 0 until plain.height) for (x in 0 until plain.width) {
            if (plain[x, y].toArgb() == backdrop.toArgb()) {
                emptyPixels++
                assertEquals("No rectangular highlight at $x,$y", plain[x, y].toArgb(), sweep[x, y].toArgb())
            }
        }
        assertTrue(emptyPixels > plain.width)
        assertEquals(measured, measurements)
        assertEquals(0, differences(history, pixels("history")))
        assertEquals(0, differences(ordinary, pixels("ordinary")))

        // Change each live gate while mounted: the original prose must be restored immediately.
        for (fallback in listOf(PocketGlassPreferences(true, false, true),
                PocketGlassPreferences(false, true, true), PocketGlassPreferences(false, false, false))) {
            compose.runOnIdle { preferences = fallback }; frame(32)
            assertEquals(0, differences(plain, pixels("summary")))
        }
        compose.runOnIdle { preferences = PocketGlassPreferences(false, false, true); focused = false }; frame(32)
        assertEquals(0, differences(plain, pixels("summary")))
        compose.runOnIdle { focused = true; owner.lifecycle.currentState = Lifecycle.State.STARTED }; frame(32)
        assertEquals(0, differences(plain, pixels("summary")))
    }

    @Test fun pageMotionKeepsAnswerAndDoesNotFocusFreeTextOrSubmit() {
        val batch = QuestionBatches.blocking("batch", Json.parseToJsonElement("""
            {"questions":[{"id":"one","question":"First?"},{"id":"two","question":"Second?"}]}
        """).jsonObject)
        val session = QuestionSession(batch)
        session.editOther("one", "Existing answer")
        content { QuestionCard(batch, session, QuestionPhase.Waiting, { fail("Motion must not submit") }) }
        compose.onNodeWithTag("question-answer:one").assertIsNotFocused()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("question-next").performClick(); frame(32)
        val bounds = compose.onNodeWithTag("question-card").fetchSemanticsNode().boundsInRoot
        repeat(5) {
            frame(40)
            assertEquals(bounds, compose.onNodeWithTag("question-card").fetchSemanticsNode().boundsInRoot)
            compose.onNodeWithTag("question-answer:two").assertIsNotFocused()
        }
        compose.onNodeWithTag("question-previous").performClick(); frame(240)
        assertEquals("Existing answer", session.answer("one"))
        compose.onNodeWithTag("question-answer:one").assertIsNotFocused()
    }

    @Test fun onlyLastExpandedNativeSummarySweepsAndCompletedTurnIsStatic() {
        val reasoning = TimelineItem("turn", "reasoning", "reasoning", raw = buildJsonObject {
            put("summary", JsonArray(listOf(JsonPrimitive("Previous native summary"), JsonPrimitive("Current native summary"))))
        })
        var timing by mutableStateOf(NativeTurnInfo("turn", status = "inProgress"))
        content { LiveTurnStatus("turn", ConversationPresentation.LiveStatus.Thinking, reasoning = reasoning, timing = timing) }
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("live-reasoning-toggle").performClick(); frame(288)
        val previous = pixels("native-summary-paragraph:0")
        val current = pixels("native-summary-paragraph:1")
        frame(640)
        assertEquals(0, differences(previous, pixels("native-summary-paragraph:0")))
        assertTrue(differences(current, pixels("native-summary-paragraph:1")) > 5)
        compose.runOnIdle { timing = timing.copy(status = "completed") }; frame(32)
        val completed = pixels("native-summary-paragraph:1")
        frame(700)
        assertEquals(0, differences(completed, pixels("native-summary-paragraph:1")))
    }

    @Test fun visibilityUsesClippedViewportInsteadOfCompositionPresence() {
        val visibility = NativeMotionVisibility()
        content {
            Box(Modifier.height(48.dp).clipToBounds()) {
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("scroll")) {
                    Spacer(Modifier.height(180.dp))
                    NativeSummaryProse("Offscreen current summary", "offscreen",
                        visibility.modifier().testTag("offscreen"), active = true)
                }
            }
        }
        compose.runOnIdle { assertEquals(false, visibility.visible) }
        compose.onNodeWithTag("offscreen").performScrollTo()
        compose.runOnIdle { assertEquals(true, visibility.visible) }
    }

    @Test fun backgroundAppearanceIsFullyVisibleAndDoesNotReplayOnResume() {
        val reveal = QuestionRevealState()
        var shown by mutableStateOf(false)
        content {
            if (shown) QuestionReveal("one", reveal, Modifier.testTag("reveal")) {
                Box(Modifier.size(240.dp, 180.dp).background(Color.Red))
            }
        }
        compose.mainClock.autoAdvance = false
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.STARTED; shown = true }; frame(32)
        val static = pixels("reveal")
        assertEquals(Color.Red.toArgb(), static[static.width / 2, static.height - 2].toArgb())
        compose.runOnIdle { owner.lifecycle.currentState = Lifecycle.State.RESUMED }; frame(96)
        assertEquals(0, differences(static, pixels("reveal")))
    }

    @Test fun switchingFocusedSecretQuestionDoesNotReuseItsImeOrReplaySameIdentity() {
        val batch = QuestionBatches.blocking("secret-pages", Json.parseToJsonElement("""
            {"questions":[{"id":"one","question":"Access code?","isSecret":true},{"id":"two","question":"Ordinary answer?"}]}
        """).jsonObject)
        val session = QuestionSession(batch)
        content { QuestionCard(batch, session, QuestionPhase.Waiting, { fail("Motion must not submit") }) }
        compose.onNodeWithTag("question-answer:one").performClick().performTextInput("private fixture")
        compose.onNodeWithTag("question-answer:one").assertIsFocused()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag("question-next").performClick(); frame(32)
        compose.onNodeWithTag("question-answer:two").assertIsNotFocused()
        val bounds = compose.onNodeWithTag("question-card").fetchSemanticsNode().boundsInRoot
        repeat(5) {
            frame(40)
            assertEquals(bounds, compose.onNodeWithTag("question-card").fetchSemanticsNode().boundsInRoot)
        }
        compose.onNodeWithTag("question-previous").performClick(); frame(240)
        compose.onNodeWithTag("question-answer:one").assertIsNotFocused()
        assertEquals("private fixture", session.answer("one"))
        assertTrue(session.currentQuestion!!.secret)
        assertFalse(session.toString().contains("private fixture"))
    }

    @Test fun accessibilityAndPowerAppearancesAreStaticAndNeverReplayWhenReenabled() {
        val reveal = QuestionRevealState()
        var id by mutableStateOf<String?>(null)
        content {
            if (id != null) QuestionReveal(id, reveal, Modifier.testTag("static-reveal")) {
                Box(Modifier.size(240.dp, 180.dp).background(Color.Red))
            }
        }
        compose.mainClock.autoAdvance = false
        listOf(PocketGlassPreferences(true, false, true), PocketGlassPreferences(false, true, true),
            PocketGlassPreferences(false, false, false)).forEachIndexed { index, fallback ->
            compose.runOnIdle { preferences = fallback; id = "question-$index" }; frame(32)
            val static = pixels("static-reveal")
            assertEquals(Color.Red.toArgb(), static[static.width / 2, static.height - 2].toArgb())
            compose.runOnIdle { preferences = PocketGlassPreferences(false, false, true) }; frame(96)
            assertEquals(0, differences(static, pixels("static-reveal")))
        }
    }
}
