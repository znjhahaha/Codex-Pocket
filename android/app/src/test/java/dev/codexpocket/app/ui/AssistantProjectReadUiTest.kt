package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantProjectReadUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val selected=mutableListOf<AssistantWorkspaceChoice?>()
    private val actions=mutableListOf<AssistantAction>()
    private val choice=AssistantWorkspaceChoice("desktop","thread","Desktop project",configStoreId="desktop-store")
    private val snapshot=AssistantSnapshot(enabled=true,ready=true,runtimeId="assistant")
    private fun content(workspaces:()->List<AssistantWorkspaceChoice>,state:()->AssistantUiState={AssistantUiState(snapshot)}) {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("light") {
            CompositionLocalProvider(LocalPocketGlass provides false) {
                AssistantScreen(state(),emptyList(),emptyList(),workspaces(),actions::add,{_,_->},{},nowMs=1_893_456_000_000L,onProjectSelected=selected::add)
            }
        }}}
        compose.waitForIdle()
    }
    @Test fun selectingProjectRequestsOneReadAndSnapshotRecompositionDoesNotRecheck() {
        var state by mutableStateOf(AssistantUiState(snapshot))
        content({listOf(choice)},{state})
        assertTrue(selected.isEmpty())
        compose.selectAssistantWorkspace("thread")
        assertEquals(listOf(choice),selected)
        compose.runOnIdle {state=state.copy(snapshot=snapshot.copy(paused=false))}
        assertEquals(1,selected.filterNotNull().size)
        // The chip reopens the chooser, which shows what the computer verified and offers to verify again.
        compose.onNodeWithTag("assistant-workspace-project").performClick()
        compose.onNodeWithTag("assistant-project-permission-status").assertTextEquals(choice.permissionStatus)
        compose.onNodeWithTag("assistant-project-recheck").performClick()
        assertEquals(2,selected.filterNotNull().size)
        assertTrue(actions.isEmpty())
    }
    @Test fun scheduleWriteToggleRequiresFreshProofAndResetsOnRevocation() {
        var current by mutableStateOf(choice)
        content({listOf(current)})
        compose.openAssistantPage("Schedules")
        compose.onNodeWithTag("assistant-schedule-add").performClick()
        compose.onNodeWithTag("assistant-schedule-workspace").performClick()
        compose.onNodeWithTag("assistant-schedule-workspace:${choice.selectionKey}").performClick()
        assertEquals(choice,selected.filterNotNull().single())
        val toggle=compose.onNodeWithTag("assistant-schedule-writes")
        toggle.assertIsNotEnabled().assertIsOff()
        compose.runOnIdle {current=current.copy(canWrite=true,permissionStatus="已核验 · 可写")}
        toggle.performScrollTo().assertIsEnabled()
        toggle.performSemanticsAction(SemanticsActions.OnClick) {it()}
        toggle.assertIsOn()
        compose.runOnIdle {current=current.copy(canWrite=false,permissionStatus="权限已失效，当前按只读处理")}
        toggle.assertIsNotEnabled().assertIsOff()
        compose.runOnIdle {current=current.copy(canWrite=true,permissionStatus="已核验 · 可写")}
        toggle.assertIsEnabled().assertIsOff()
        assertEquals(1,selected.filterNotNull().size)
        assertTrue(actions.isEmpty())
    }
}
