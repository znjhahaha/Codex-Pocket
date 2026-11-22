package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
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
class AssistantProductUiTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun taskDetailAnswersOriginalRequestAndNeverSendsApprovalAsChat() {
        val actions=mutableListOf<AssistantAction>()
        val task=AssistantTask("task","整理简报",AssistantTaskStatus.WaitingUser,"op",false,null)
        val op=AssistantOperation("op","Execution","task",AssistantOperationPhase.Waiting,false,"thread",AssistantQuestion("question",AssistantQuestionKind.Approval,"允许这次写入？"),null)
        val state=AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="runtime",tasks=listOf(task),operations=listOf(op),ready=true,fresh=true))
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("light") {
            ProvideMarkdownRendering {
                AssistantDetailPage("Task:task",state,true,{make->actions+=make()},{_,_->},emptyList(),{})
            }
        }}}
        compose.onNodeWithTag("assistant-approve:op").performClick()
        assertEquals("assistant/respond",actions.single().method)
        assertEquals("question",actions.single().params["requestId"].toString().trim('"'))
    }
    @Test fun fixedNavigationExposesPendingAndResults() {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme("light") {
            AssistantScreen(AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="r",ready=true)),emptyList(),emptyList(),emptyList(),{},{_,_->},{})
        }}}
        compose.openAssistantPage("Tasks")
        compose.onNodeWithTag("assistant-tasks-list").assertExists()
        compose.openAssistantPage("Artifacts")
        compose.onNodeWithTag("assistant-artifact-filter").assertExists()
    }
}
