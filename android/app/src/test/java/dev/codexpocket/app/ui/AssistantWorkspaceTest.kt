package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import kotlinx.serialization.json.*
import java.io.File
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantWorkspaceTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun attachmentOnlySendRetainsFailureAndClearsOnlyAfterMatchingAcceptance() {
        val context=compose.activity
        val file=LocalAttachment(java.util.UUID.randomUUID().toString(),"diagram.png","image/png")
        AssistantAttachmentDraft(context,"main:attachment-ui:a").add(listOf(file))
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true),computerId="attachment-ui"))
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantScreen(state,emptyList(),emptyList(),emptyList(),{actions+=it},{_,_->},{})
        }}}
        compose.onNodeWithText("diagram.png").assertExists()
        compose.onNodeWithTag("assistant-workspace-send").performClick()
        assertEquals(listOf(file),actions.single().attachments)
        val first=actions.single()
        compose.runOnIdle {state=state.copy(sendReceipts=listOf(AssistantSendReceipt(first.commandId!!,"attachment-ui","r","assistant:a","rejected")))}
        compose.onNodeWithText("diagram.png").assertExists()
        compose.onNodeWithTag("assistant-workspace-send").performClick()
        val accepted=actions.last()
        compose.runOnIdle {state=state.copy(sendReceipts=listOf(AssistantSendReceipt(accepted.commandId!!,"attachment-ui","r","assistant:a","accepted")))}
        compose.onNodeWithText("diagram.png").assertDoesNotExist()
    }
    @Test fun artifactFollowupCarriesAttachmentAndExactTaskAndVersion() {
        val task=AssistantTask("attachment-task","修改报告",AssistantTaskStatus.Done,null,false,null)
        val artifact=AssistantArtifact("artifact-1",task.id,"op","报告.png","file",null,null,"v1",1,"available")
        val file=LocalAttachment(java.util.UUID.randomUUID().toString(),"notes.txt","text/plain")
        val key=JsonArray(listOf("artifact-pc","a","r",task.id,"v1").map(::JsonPrimitive)).toString()
        AssistantAttachmentDraft(compose.activity,key).add(listOf(file))
        val actions=mutableListOf<AssistantAction>()
        val state=AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true,tasks=listOf(task)),computerId="artifact-pc")
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantTaskFollowup(task,state,true,{actions+=it()},"按原成果继续",artifact)
        }}}
        compose.onNodeWithTag("assistant-task-send:${task.id}").performClick()
        val action=actions.single();assertEquals(listOf(file),action.attachments)
        assertEquals(task.id,action.params["taskId"]?.jsonPrimitive?.content)
        assertEquals("artifact-1",action.params["artifactId"]?.jsonPrimitive?.content)
        assertEquals("v1",action.params["artifactVersion"]?.jsonPrimitive?.content)
        saveRender("assistant-attachment-followup-light.png")
    }
    @Test fun attachmentPickerIsAvailableInMainAndTaskFollowup() {
        val task=AssistantTask("attachment-task","整理附件",AssistantTaskStatus.Done,null,false,null)
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantScreen(AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true,tasks=listOf(task))),
                emptyList(),emptyList(),emptyList(),{},{_,_->},{})
        }}}
        // The picker is behind `＋` on the stage, and on the task's own page.
        compose.onNodeWithTag("assistant-tools").performClick()
        compose.onNodeWithTag("assistant-attach:main").assertExists()
        compose.onNodeWithContentDescription("关闭").performClick()
        compose.openAssistantPage("Tasks")
        compose.onNodeWithTag("assistant-task-open:attachment-task").performClick()
        compose.onNodeWithTag("assistant-attach:attachment-task").performScrollTo().assertExists()
    }
    @Test fun preparationFailureHasOneActionAndIndependentReadFailureStaysVisible() {
        val issue="助理准备遇到问题，请检查电脑端后重试"
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot=AssistantSnapshot(preparationStatus="recoverable_error"),error=issue))
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantScreen(state,emptyList(),emptyList(),emptyList(),{actions+=it},{_,_->},{})
        }}}
        compose.onAllNodesWithText(issue).assertCountEquals(1)
        compose.onNodeWithTag("assistant-workspace-error").assertDoesNotExist()
        compose.onNodeWithTag("assistant-prepare").performClick()
        assertEquals("assistant/prepare",actions.single().method)
        compose.runOnIdle {state=state.copy(readError="无法读取助理历史")}
        compose.onNodeWithText("无法读取助理历史").assertExists()
        compose.onNodeWithText("重试读取").performClick()
        assertEquals("assistant/read",actions.last().method)
        compose.onAllNodesWithText(issue).assertCountEquals(1)
    }
    @Test fun everyPageOpensFromTheStageAndDraftSurvivesNavigation() {
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantScreen(AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true)),emptyList(),emptyList(),emptyList(),{},{_,_->},{})
        }}}
        compose.onNodeWithTag("assistant-workspace-draft").performTextInput("保留这个目标")
        listOf("Tasks","Artifacts","Plans","Memory","Settings").forEach {
            compose.openAssistantPage(it)
            compose.onNodeWithTag("assistant-workspace-draft").assertDoesNotExist()
            compose.returnToAssistantChat()
        }
        compose.onNodeWithTag("assistant-workspace-draft").assertTextContains("保留这个目标")
    }
    @Test fun pendingRequestHasTheSameIdentityInMainAndTaskAndStopWaitsForConfirmation() {
        val task=AssistantTask("task","整理本周项目进展",AssistantTaskStatus.WaitingUser,"op",false,null)
        val op=AssistantOperation("op","Execution","task",AssistantOperationPhase.Waiting,false,"native",AssistantQuestion("q",AssistantQuestionKind.Approval,"允许保存本次报告？"),null)
        val actions=mutableListOf<AssistantAction>()
        val snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true,protocolVersion=2,acceptingMessages=true,tasks=listOf(task),operations=listOf(op))
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {ProvideMarkdownRendering {
            AssistantScreen(AssistantUiState(snapshot,computerId="approval-test"),emptyList(),emptyList(),emptyList(),{actions+=it},{_,_->},{})
        }}}}
        compose.onNodeWithTag("assistant-approve:op").performScrollTo().performClick()
        compose.openAssistantPage("Tasks")
        compose.onNodeWithTag("assistant-task-open:task").performClick()
        compose.onNodeWithTag("assistant-approve:op").performScrollTo().performClick()
        assertEquals(actions[0].commandId,actions[1].commandId)
        assertEquals("q",actions[1].params["requestId"]?.jsonPrimitive?.content)
        compose.onNodeWithTag("assistant-task-stop:task").performScrollTo().performClick()
        assertEquals(2,actions.size)
        compose.onNodeWithText("确认停止").performClick()
        assertEquals("assistant/task/pause",actions.last().method)
    }
    @Test fun acceptedReceiptClearsOnlyOriginalDraftAndUnreadTaskRenders() {
        val actions=mutableListOf<AssistantAction>()
        val record=AssistantTaskRecord("task",1,"running",null,null,7,true)
        val snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true,protocolVersion=2,acceptingMessages=true,
            tasks=listOf(AssistantTask("task","为下周会议整理进展与待决事项",AssistantTaskStatus.Running,"op",false,null)),
            domain=AssistantDomain("main:a",listOf(record),emptyList(),emptyList()))
        var state by mutableStateOf(AssistantUiState(snapshot,messages=listOf(
            AssistantChatMessage("1","User","把本周进展整理成一页简报。",1),
            AssistantChatMessage("2","Assistant","已经开始整理。简报会包括已完成事项、风险和下周需要决定的问题。",2,taskId="task")),computerId="render-test"))
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantScreen(state,emptyList(),emptyList(),emptyList(),{actions+=it},{_,_->},{})
        }}}
        compose.onNodeWithTag("assistant-workspace-draft").performTextInput("请附上来源")
        compose.onNodeWithTag("assistant-workspace-send").performClick()
        val sent=actions.single()
        compose.onNodeWithTag("assistant-workspace-draft").performTextInput("，再加一张表")
        compose.runOnIdle {state=state.copy(sendReceipts=listOf(AssistantSendReceipt(sent.commandId!!,"render-test","r","assistant:a","accepted")))}
        compose.onNodeWithTag("assistant-workspace-draft").assertTextContains("再加一张表",substring=true)
        saveRender("assistant-main-light.png")
        compose.onNodeWithTag("assistant-chat-task-toggle:task").assert(hasText("新进展"))
        compose.openAssistantPage("Tasks")
        compose.onNodeWithText("新进展").assertExists()
        saveRender("assistant-tasks-light.png")
    }
    @Test fun mainTaskCardUpdatesInPlaceAndAnswersOriginalRequest() {
        val actions=mutableListOf<AssistantAction>()
        val task=AssistantTask("task","整理发布简报",AssistantTaskStatus.Running,"op",false,null)
        val child=AssistantTask("child","核对版本",AssistantTaskStatus.Done,null,false,null)
        val record=AssistantTaskRecord("task",1,"running",null,null,1,true)
        val childRecord=AssistantTaskRecord("child",2,"done","task",null,1,false)
        var state by mutableStateOf(AssistantUiState(snapshot=AssistantSnapshot(enabled=true,runtimeId="r",assistantId="a",ready=true,
            protocolVersion=2,acceptingMessages=true,tasks=listOf(task,child),
            domain=AssistantDomain("main:a",listOf(record,childRecord),emptyList(),emptyList())),
            messages=listOf(AssistantChatMessage("msg","Assistant","正在整理",1,taskId="task")),computerId="summary-card"))
        compose.activity.runOnUiThread {compose.activity.setContent {AssistantTestTheme {
            AssistantScreen(state,emptyList(),emptyList(),emptyList(),{actions+=it},{_,_->},{})
        }}}
        compose.onNodeWithTag("assistant-chat-task:task").assertExists()
        compose.expandAssistantTask("task")
        compose.onNodeWithText("子任务 1/1 已完成").assertExists()
        compose.runOnIdle {
            val snapshot=state.snapshot!!
            state=state.copy(snapshot=snapshot.copy(tasks=listOf(task.copy(status=AssistantTaskStatus.WaitingUser),child),
                operations=listOf(AssistantOperation("op","Execution","task",AssistantOperationPhase.Waiting,false,"native",
                    AssistantQuestion("q",AssistantQuestionKind.Approval,"允许保存简报？"),null)),
                domain=snapshot.domain!!.copy(tasks=listOf(record.copy(state="waiting_user"),childRecord))))
        }
        compose.onAllNodesWithTag("assistant-chat-task:task").assertCountEquals(1)
        compose.onNodeWithTag("assistant-approve:op").performScrollTo().performClick()
        assertEquals("q",actions.single().params["requestId"]?.jsonPrimitive?.content)
        assertEquals("assistant/respond",actions.single().method)
        saveRender("assistant-main-task-pending-light.png")
    }
    private fun saveRender(name:String) {
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            val bitmap=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            File("build/reports/assistant",name).apply {parentFile?.mkdirs()}.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)
            }
        }
    }
}
