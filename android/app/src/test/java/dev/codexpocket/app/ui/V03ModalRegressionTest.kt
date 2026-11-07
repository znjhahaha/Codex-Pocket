@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
package dev.codexpocket.app.ui

import android.os.Parcel
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V03ModalRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent {
                PocketTheme("light") {
                    val inputMode = LocalInputModeManager.current
                    SideEffect { inputMode.requestInputMode(InputMode.Keyboard) }
                    CompositionLocalProvider(LocalPocketGlass provides false) { PocketBackdrop(block) }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun selection(tag: String, expected: TextRange) {
        assertEquals(expected, compose.onNodeWithTag(tag).fetchSemanticsNode()
            .config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun nestedMenuRestoresExpandedEditorSelectionAndTypingThenCompactSelection() {
        content {
            var draft by remember { mutableStateOf("draft text") }
            PocketComposer(draft, "Codex", "", false, ComposerAvailability(true, false, true, false),
                { draft = it }, {}, {}, {}, {}, {}, {}, true)
        }
        compose.onNodeWithTag("composer-input").performTextInputSelection(TextRange(2, 5))
        compose.onNodeWithContentDescription("展开输入框").performClick()
        compose.onNodeWithTag("composer-expanded-input").assertIsFocused()
        selection("composer-expanded-input", TextRange(2, 5))
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.v02_references)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.v02_attach)).assertIsDisplayed()
        compose.onNodeWithTag("composer-expanded-input").assertDoesNotExist()
        compose.onNodeWithTag("pocket-modal").performKeyInput { pressKey(Key.Escape) }
        compose.waitForIdle() // The covered editor stays covered until the exit transition finishes.
        compose.onNodeWithTag("composer-expanded-input").assertIsFocused()
        selection("composer-expanded-input", TextRange(2, 5))
        compose.onNodeWithTag("composer-expanded-input").performTextInput("X")
        compose.onNodeWithTag("composer-expanded-input").assertTextEquals("drX text")
        compose.onNodeWithTag("composer-expanded-input").performTextInputSelection(TextRange(1, 3))
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-input").assertTextEquals("drX text").assertIsFocused()
        selection("composer-input", TextRange(1, 3))
    }

    @Test fun nestedBackRestoresPreviouslyFocusedOuterButton() {
        content {
            var inner by remember { mutableStateOf(false) }
            PocketSheet("Outer", {}) {
                PocketButton("Open inner", { inner = true }, Modifier.testTag("outer-trigger"))
                if (inner) PocketDialog({ inner = false }, { Text("Inner") },
                    confirmButton = { PocketButton("Done", { inner = false }) })
            }
        }
        compose.onNodeWithTag("outer-trigger").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("outer-trigger").assertIsFocused().performClick()
        compose.onNodeWithText("Inner").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("outer-trigger").assertIsFocused()
    }

    @Test fun tabAndShiftTabWrapInsideTopModalInBothDirections() {
        content {
            var inner by remember { mutableStateOf(false) }
            PocketButton("Page action", {}, Modifier.testTag("page-action"))
            PocketSheet("Outer", {}) {
                PocketButton("Open inner", { inner = true })
                if (inner) PocketSheet("Inner", { inner = false }) {
                    PocketButton("First", {}, Modifier.testTag("first"))
                    PocketButton("Last", {}, Modifier.testTag("last"))
                }
            }
        }
        compose.onNodeWithText("Open inner").performClick()
        compose.onNodeWithContentDescription("关闭").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithContentDescription("关闭").assertIsFocused()
        fun tab(reverse: Boolean = false) {
            compose.onNodeWithTag("pocket-modal").performKeyInput {
                if (reverse) keyDown(Key.ShiftLeft)
                pressKey(Key.Tab)
                if (reverse) keyUp(Key.ShiftLeft)
            }
        }
        repeat(2) {
            tab(); compose.onNodeWithTag("first").assertIsFocused()
            tab(); compose.onNodeWithTag("last").assertIsFocused()
            tab(); compose.onNodeWithContentDescription("关闭").assertIsFocused()
            tab(true); compose.onNodeWithTag("last").assertIsFocused()
            tab(true); compose.onNodeWithTag("first").assertIsFocused()
            tab(true); compose.onNodeWithContentDescription("关闭").assertIsFocused()
        }
        compose.onNodeWithText("Outer").assertDoesNotExist()
        compose.onNodeWithTag("page-action").assertDoesNotExist()
    }

    @Test fun longDraftsStayOutOfSavedStateWhilePerChatSelectionsRestore() {
        // External strings stand in for the repository's persisted drafts, not saved UI state.
        val drafts = List(3) { "draft-$it\n" + "x".repeat(200_000) }
        var chat by mutableIntStateOf(0)
        var generation by mutableIntStateOf(0)
        var registry: SaveableStateRegistry = SaveableStateRegistry(null) { true }
        content {
            key("saved-host") {
                if (generation >= 0) {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    val holder = rememberSaveableStateHolder()
                    holder.SaveableStateProvider(chat) {
                        PocketComposer(drafts[chat], "Codex", "", false, ComposerAvailability(false, false, true, false),
                            {}, {}, {}, {}, {}, {}, {}, true)
                    }
                }
                }
            }
        }
        repeat(3) { index ->
            compose.runOnIdle { chat = index }
            compose.onNodeWithTag("composer-input").performTextInputSelection(TextRange(index + 1, index + 5))
            selection("composer-input", TextRange(index + 1, index + 5))
        }
        lateinit var saved: Map<String, List<Any?>>
        compose.runOnIdle { saved = registry.performSave() }
        val parcel = Parcel.obtain()
        try {
            parcel.writeMap(saved)
            assertTrue("Only small UI state should be saved, not the three draft bodies", parcel.dataSize() < 16_384)
            parcel.setDataPosition(0)
            @Suppress("UNCHECKED_CAST")
            saved = parcel.readHashMap(javaClass.classLoader) as Map<String, List<Any?>>
        } finally { parcel.recycle() }
        compose.runOnIdle { generation = -1 }
        compose.waitForIdle()
        compose.runOnIdle { registry = SaveableStateRegistry(saved) { true }; generation = 1 }
        for (index in listOf(2, 0, 1)) {
            compose.runOnIdle { chat = index }
            compose.onNodeWithTag("composer-input").assertTextEquals(drafts[index])
            selection("composer-input", TextRange(index + 1, index + 5))
        }
    }

    @Test fun exitingModalBlocksRepeatedConfirmationAndPageClicksUntilRemoved() {
        var shown by mutableStateOf(false)
        var confirmations = 0
        var dismissals = 0
        var pageClicks = 0
        content {
            PocketButton("Page", { pageClicks++ }, Modifier.testTag("exit-page-action"))
            if (shown) PocketDialog({ dismissals++; shown = false }, { Text("Confirm once") },
                confirmButton = {
                    PocketButton("Confirm", { confirmations++; shown = false }, Modifier.testTag("exit-confirm"))
                })
        }
        val host = compose.onNodeWithTag("pocket-overlay-host")
        val origin = host.fetchSemanticsNode().boundsInRoot.topLeft
        val pagePoint = compose.onNodeWithTag("exit-page-action").fetchSemanticsNode().boundsInRoot.center - origin
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        val confirmPoint = compose.onNodeWithTag("exit-confirm").fetchSemanticsNode().boundsInRoot.center - origin
        compose.onNodeWithTag("exit-confirm").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.mainClock.autoAdvance = false
        try {
            host.performTouchInput { click(confirmPoint) }
            compose.mainClock.advanceTimeBy(32)
            compose.waitForIdle()
            // Still in the 200ms exit: both modal actions and the covered page are inaccessible.
            compose.onNodeWithTag("exit-confirm").assertDoesNotExist()
            compose.onNodeWithTag("exit-page-action").assertDoesNotExist()
            host.performTouchInput { click(confirmPoint); click(pagePoint) }
            host.performKeyInput { pressKey(Key.Enter); pressKey(Key.Escape) }
            compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
            assertEquals(1, confirmations)
            assertEquals(0, dismissals)
            assertEquals(0, pageClicks)
            compose.mainClock.advanceTimeBy(300)
        } finally { compose.mainClock.autoAdvance = true }
        compose.waitForIdle()
        compose.onNodeWithTag("exit-page-action").performTouchInput { click() }
        assertEquals(1, pageClicks)
    }
}
