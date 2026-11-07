@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
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
class V03OverlayTest {
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

    @Test fun nestedModalClosesTopFirstAndUsesSameRootWithAllSideGaps() {
        content {
            var outer by remember { mutableStateOf(true) }
            var inner by remember { mutableStateOf(false) }
            Text("page beneath", Modifier.testTag("under-page"))
            if (outer) PocketSheet("Outer", { outer = false }) {
                PocketButton("Open inner", { inner = true })
                if (inner) PocketDialog({ inner = false }, { Text("Inner") }, confirmButton = { PocketButton("Done", { inner = false }) })
            }
        }
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val modal = compose.onNodeWithTag("pocket-modal-surface").fetchSemanticsNode().boundsInRoot
        assertTrue(modal.left > root.left && modal.right < root.right)
        assertTrue(modal.top > root.top && modal.bottom < root.bottom)
        compose.onNodeWithTag("under-page").assertDoesNotExist()
        compose.onNodeWithText("Open inner").performClick()
        compose.onNodeWithText("Outer").assertDoesNotExist()
        compose.onNodeWithTag("pocket-modal").performKeyInput { pressKey(Key.Escape) }
        compose.onNodeWithText("Outer").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithText("page beneath").assertIsDisplayed()
    }

    @Test fun sheetScrollAndActionsSurviveLargeFontAndReducedKeyboardViewport() {
        content {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                // A resized IME viewport, without needing an emulator keyboard.
                PocketOverlayHost(Modifier.fillMaxWidth().height(380.dp)) {
                    PocketSheet("编辑长消息", {}) {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            repeat(20) { Text("第 $it 段需要滚动的内容") }
                            PocketButton("末尾操作", {})
                        }
                    }
                }
            }
        }
        compose.onNodeWithText("末尾操作").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭").assertIsDisplayed()
    }

    @Test fun expandedComposerPreservesDraftSelectionAndActionMeaning() {
        var sent = 0
        var steered = 0
        content {
            var draft by remember { mutableStateOf("draft text") }
            PocketComposer(draft, "Codex", "高", true, ComposerAvailability(true, true, true, true),
                { draft = it }, {}, {}, {}, { sent++ }, { steered++ }, {}, true,
                attachments = { Text("attachment.txt") })
        }
        compose.onNodeWithTag("composer-input").performTextInputSelection(androidx.compose.ui.text.TextRange(2, 5))
        compose.onNodeWithContentDescription("展开输入框").performClick()
        compose.onNodeWithTag("composer-expanded-input").assertTextEquals("draft text")
        assertEquals(androidx.compose.ui.text.TextRange(2, 5), compose.onNodeWithTag("composer-expanded-input").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange])
        compose.onNodeWithText("attachment.txt").assertExists()
        compose.onNodeWithTag("composer-expanded-input").performTextReplacement("edited draft")
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.onNodeWithTag("composer-input").assertTextEquals("edited draft")
        compose.onNodeWithText("attachment.txt").assertExists()
        compose.onNodeWithTag("composer-send").performClick()
        compose.onNodeWithTag("composer-steer").performClick()
        assertEquals(1, sent); assertEquals(1, steered)
    }
}
