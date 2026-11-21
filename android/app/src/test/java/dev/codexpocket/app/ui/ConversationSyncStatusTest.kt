package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.PocketState
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h720dp-xxhdpi")
class ConversationSyncStatusTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var label by mutableStateOf<String?>(null)
    private var hostClicks = 0
    private fun show(compact: Boolean = false, theme: String = "light") {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(theme) {
                CompositionLocalProvider(LocalPocketGlass provides false,
                    LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false),
                    LocalConversationSyncLabel provides label) {
                    Column {
                        ConversationHeader(PocketState(computerName = "我的电脑", online = true), {}, { hostClicks++ }, {}, compact)
                        Text("正在阅读的正文", Modifier.testTag("body"))
                    }
                }
            }
        } }
        compose.waitForIdle()
    }

    @Test fun refreshNeverChangesHeaderOrBodyBoundsAndReturnsToComputerName() {
        show()
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        val body = compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { label = "更新中" }
        compose.onNodeWithText("更新中", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(header, compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot)
        assertEquals(body, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("conversation-sync", useUnmergedTree = true)
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo.Indeterminate))
            .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.OnClick))
        compose.onNode(hasClickAction() and hasAnyDescendant(hasTestTag("conversation-title")), useUnmergedTree = true).performClick()
        assertEquals(1, hostClicks)
        compose.runOnIdle { label = null }
        compose.onNodeWithText("我的电脑", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("conversation-sync", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(body, compose.onNodeWithTag("body").fetchSemanticsNode().boundsInRoot)
    }

    @Test fun compactDarkHeaderShowsOnlyAccessibleSyncIconWithoutGrowing() {
        show(compact = true, theme = "dark")
        val header = compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { label = "更新中" }
        compose.onNodeWithTag("conversation-sync", useUnmergedTree = true)
            .assertContentDescriptionEquals("更新中").assertIsDisplayed()
        compose.onNodeWithText("更新中", useUnmergedTree = true).assertDoesNotExist()
        assertEquals(header, compose.onNodeWithTag("conversation-header").fetchSemanticsNode().boundsInRoot)
    }
}
