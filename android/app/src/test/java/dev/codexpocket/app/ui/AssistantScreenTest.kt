package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
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
import java.io.File
import java.time.Instant

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val now = Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()
    private val models = listOf(NativeModel("native-1", "actual-model", "电脑传入模型", "", emptyList(), "", false, true))
    private val runtime = listOf(AssistantRuntimeChoice("r", "我的电脑"))
    private val project = listOf(AssistantWorkspaceChoice("r", "authorized-thread", "已有项目", true))
    private fun snapshot() = AssistantSnapshot(true,"r","coordinator",false,
        AssistantSettings("认真协助", "简洁", "actual-model", "UTC"),
        listOf(AssistantTask("t","整理项目待办",AssistantTaskStatus.Running,"execution",false,null)),
        listOf(AssistantOperation("execution","Execution","t",AssistantOperationPhase.Running,false,"native-task",null,null)))
    private fun content(state: () -> AssistantUiState, actions: MutableList<AssistantAction>, opens: MutableList<Pair<String,String>> = mutableListOf(), fontScale: Float = 1f, theme: String = "light", modelDirectory: List<NativeModel> = models) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(theme) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalPocketGlass provides false, LocalDensity provides Density(d.density, fontScale)) {
                    AssistantScreen(state(),modelDirectory,runtime,project,{actions += it},{r,t -> opens += r to t},{},nowMs=now)
                }
            }
        } }
        compose.waitForIdle()
    }
    private fun scroll(tag: String): SemanticsNodeInteraction {
        // LazyColumn's off-screen items have no SemanticsNode yet; search via its index action.
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).onFirst().performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }
    private fun screenshot(name: String) {
        lateinit var bitmap: Bitmap
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            bitmap = Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val file = File("build/assistant-fixtures/$name.png"); file.parentFile?.mkdirs()
        file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it)) }
        assertTrue(bitmap.width > 0 && bitmap.height > 0)
    }

    @Test fun disabledEntryShowsEnableSettingsAndNeverClaimsReady() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(AssistantSnapshot(enabled=false))},actions)
        compose.onNodeWithTag("assistant-configure").performClick()
        scroll("assistant-enable").performClick()
        assertEquals("assistant/enable",actions.single().method)
        assertEquals("r",actions.single().params.string("runtimeId"))
        compose.onNodeWithTag("assistant-send").assertDoesNotExist()
        compose.onNodeWithText("服务已就绪").assertDoesNotExist()
        screenshot("disabled-settings")
    }

    @Test fun rendersRealMessagesTaskResultsAndOriginalThreadCallbacks() {
        val actions = mutableListOf<AssistantAction>(); val opens = mutableListOf<Pair<String,String>>()
        val done = snapshot().copy(tasks=listOf(AssistantTask("t","整理项目待办",AssistantTaskStatus.Done,"execution",false,"已完成")),
            operations=listOf(AssistantOperation("execution","Execution","t",AssistantOperationPhase.Completed,false,"native-task",null,"**结果**：已整理两项待办")))
        val messages = listOf(AssistantChatMessage("u","User","请整理这个项目的待办",now), AssistantChatMessage("a","Assistant","我会先核对现有记录。",now+1))
        content({AssistantUiState(done,messages)},actions,opens)
        scroll("assistant-message:u")
        compose.onNodeWithText("请整理这个项目的待办").assertExists()
        scroll("assistant-message:a").assertExists()
        screenshot("chat-overview")
        scroll("assistant-result:execution").assertIsDisplayed()
        scroll("assistant-thread:execution").performClick()
        assertEquals(listOf("r" to "native-task"),opens)
        screenshot("chat-result")
    }

    @Test fun sendsOnlyChosenAuthorizedThreadAndRetainsDraftUntilAcknowledged() {
        val actions = mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot()))
        content({state},actions)
        compose.onNodeWithTag("assistant-message-input").performTextInput("真实消息")
        compose.selectAssistantWorkspace("authorized-thread")
        compose.onNodeWithTag("assistant-send").performClick()
        assertEquals("真实消息",actions.last().params.string("message"))
        assertEquals("authorized-thread",actions.last().params.string("threadId"))
        assertFalse(actions.last().params.containsKey("path"))
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("真实消息")
        compose.runOnIdle { state = state.copy(pendingActions=setOf("assistant/send")) }
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(pendingActions=emptySet(),sendAcknowledgement=1) }
        compose.onNodeWithTag("assistant-message-input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
    }

    @Test fun questionsAndApprovalDispatchDistinctExplicitHumanResponses() {
        val actions = mutableListOf<AssistantAction>()
        val questions = listOf(
            AssistantOperation("input","Coordinator",null,AssistantOperationPhase.Waiting,false,"c",AssistantQuestion("q-input",AssistantQuestionKind.Input,"请确认语言"),null),
            AssistantOperation("approval","Execution","t",AssistantOperationPhase.Waiting,false,"native-task",AssistantQuestion("q-approval",AssistantQuestionKind.Approval,"允许写入这次改动？"),null))
        content({AssistantUiState(snapshot().copy(operations=questions))},actions)
        scroll("assistant-answer:input").performTextInput("简体中文")
        scroll("assistant-respond:input").performClick()
        assertEquals(JsonPrimitive("简体中文"), actions.last().params.obj("response")["Answer"])
        scroll("assistant-deny:approval").performClick()
        assertEquals(false,actions.last().params.obj("response").obj("Approval").flag("approved"))
        screenshot("human-response")
    }

    @Test fun settingsUsePassedModelDirectoryAndNotificationTimezoneDrafts() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot())},actions)
        compose.openAssistantPage("Settings")
        scroll("assistant-personality").performTextReplacement("耐心核对")
        scroll("assistant-model").performClick()
        compose.onNodeWithTag("assistant-model:model:actual-model").performClick()
        scroll("assistant-timezone").performTextReplacement("Asia/Shanghai")
        scroll("assistant-notify-failed").performClick()
        scroll("assistant-save-settings").performClick()
        val settings = actions.last().params.obj("settings")
        assertEquals("actual-model",settings.string("model"))
        assertEquals("耐心核对",settings.string("personality"))
        assertEquals("Asia/Shanghai",settings.string("timezone"))
        assertFalse(settings.obj("notifications").flag("failed"))
        screenshot("settings")
    }

    @Test fun memoriesCreateEditDeleteOnlyAfterExplicitControls() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot().copy(memories=listOf(AssistantMemory("m","记忆原文"))))},actions)
        compose.openAssistantPage("Memory")
        compose.onNodeWithTag("assistant-memory-add").performClick()
        compose.onNodeWithTag("assistant-memory-text").performTextInput("新的记忆")
        compose.onNodeWithTag("assistant-memory-save").performClick()
        assertEquals("assistant/memory/add",actions.last().method)
        scroll("assistant-memory-edit:m").performClick()
        compose.onNodeWithTag("assistant-memory-text").performTextReplacement("更新后的记忆")
        compose.onNodeWithTag("assistant-memory-save").performClick()
        assertEquals("m",actions.last().params.string("memoryId"))
        scroll("assistant-memory-delete:m").performClick()
        compose.onNodeWithTag("assistant-confirm-delete").performClick()
        assertEquals("assistant/memory/delete",actions.last().method)
        compose.onNodeWithText("记忆原文").assertExists()
        screenshot("memory")
    }

    @Test fun schedulesCreateWithRealRulesAndMissedStateAndDelete() {
        val actions = mutableListOf<AssistantAction>()
        val schedule = AssistantSchedule("s","错过的事项",buildJsonObject {put("Daily",buildJsonObject {put("hour",9);put("minute",0)})},"UTC",now+3600000,true,null)
        content({AssistantUiState(snapshot().copy(schedules=listOf(schedule)))},actions)
        compose.openAssistantPage("Schedules")
        compose.onNodeWithTag("assistant-schedule-add").performClick()
        compose.onNodeWithTag("assistant-schedule-prompt").performTextInput("新的定时事项")
        compose.onNodeWithTag("assistant-schedule-workspace").performClick()
        compose.onNodeWithTag("assistant-schedule-workspace:authorized-thread").performClick()
        compose.onNodeWithText("仅限电脑验证过的项目范围；原生审批仍需你逐次确认。").assertExists()
        compose.onNodeWithTag("assistant-schedule-save").performClick()
        assertEquals("assistant/schedule/create",actions.last().method)
        assertEquals("UTC",actions.last().params.obj("spec").obj("zone").string("name"))
        assertFalse(actions.last().params.obj("spec").containsKey("directory"))
        compose.onNodeWithText("已错过 · 不补执行").assertExists()
        scroll("assistant-schedule-delete:s").performClick()
        compose.onNodeWithTag("assistant-confirm-delete").performClick()
        assertEquals("assistant/schedule/delete",actions.last().method)
        screenshot("schedules")
    }

    @Test fun largeFontDarkScreenKeepsChatAndMoreMenuInteractive() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot())},actions,fontScale=1.6f,theme="dark")
        compose.onNodeWithTag("assistant-pocket-button").assertDoesNotExist()
        compose.onNodeWithTag("assistant-message-input").performTextInput("大字模式草稿")
        compose.onNodeWithTag("assistant-send").assertIsDisplayed().assertIsEnabled()
        compose.openAssistantPage("Settings")
        scroll("assistant-save-settings").assertIsDisplayed()
        screenshot("dark-large-font")
        compose.returnToAssistantChat()
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("大字模式草稿")
        compose.onNodeWithTag("assistant-send").assertIsDisplayed().assertIsEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun aNativeModelNamedDefaultDoesNotBecomeTheHostDefaultSentinel() {
        val directory=listOf(models.single().copy(model="default",name="电脑的 default 模型"))
        val actions=mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot())},actions,modelDirectory=directory)
        compose.openAssistantPage("Settings")
        scroll("assistant-model").performClick()
        compose.onNodeWithTag("assistant-model:model:default").performClick()
        scroll("assistant-save-settings").performClick()
        assertEquals(JsonPrimitive("default"),actions.last().params.obj("settings")["model"])
    }

    @Test fun taskControlsRespectReconciliationAndPausedQuestionCannotApprove() {
        val tasks=listOf(AssistantTask("queued","等待处理",AssistantTaskStatus.Queued,null,false,null),
            AssistantTask("paused","可继续任务",AssistantTaskStatus.Paused,null,false,null),
            AssistantTask("uncertain","等待运行核对",AssistantTaskStatus.Paused,"unknown-op",true,null),
            AssistantTask("failed","失败任务",AssistantTaskStatus.Failed,null,false,"失败说明"))
        val op=AssistantOperation("paused-input","Execution",null,AssistantOperationPhase.Waiting,true,"thread",AssistantQuestion("q",AssistantQuestionKind.Approval,"已暂停的审批"),null)
        val actions=mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot().copy(tasks=tasks,operations=listOf(op)))},actions)
        scroll("assistant-deny:paused-input").assertIsNotEnabled()
        scroll("assistant-task:queued").assertHasClickAction()
        compose.onNodeWithTag("assistant-task-pause:queued").assertDoesNotExist()
        compose.expandAssistantTask("queued")
        scroll("assistant-task-pause:queued").performClick()
        assertEquals("assistant/task/pause",actions.last().method)
        assertEquals("queued",actions.last().params.string("taskId"))
        compose.expandAssistantTask("paused")
        scroll("assistant-task-resume:paused").performClick()
        assertEquals("assistant/task/resume",actions.last().method)
        assertEquals("paused",actions.last().params.string("taskId"))
        scroll("assistant-task:uncertain").assertExists()
        compose.expandAssistantTask("uncertain")
        compose.onNodeWithTag("assistant-task-resume:uncertain").assertDoesNotExist()
        scroll("assistant-task:failed").assertExists()
        compose.expandAssistantTask("failed")
        compose.onNodeWithTag("assistant-task-resume:failed").assertDoesNotExist()
        assertEquals(2,actions.size)
    }

    @Test fun emptyInitialDirectoryAllowsExplicitHostDefaultAndEnable() {
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(AssistantSnapshot(enabled=false)))
        content({state},actions,modelDirectory=emptyList())
        compose.onNodeWithTag("assistant-configure").performClick()
        scroll("assistant-model").performClick()
        compose.onNodeWithTag("assistant-model:host-default").performClick()
        scroll("assistant-enable").performClick()
        assertEquals("assistant/enable",actions.last().method)
        compose.runOnIdle {state=AssistantUiState(snapshot().copy(settings=AssistantSettings("认真协助","简洁",null,"UTC")))}
        scroll("assistant-save-settings").performClick()
        assertEquals(JsonNull,actions.last().params.obj("settings")["model"])
    }

    @Test fun offlineErrorWhileMemoryDeleteConfirmationIsOpenBlocksMutation() {
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot().copy(memories=listOf(AssistantMemory("m","记忆")))))
        content({state},actions)
        compose.openAssistantPage("Memory")
        scroll("assistant-memory-delete:m").performClick()
        compose.runOnIdle {state=state.copy(error="连接电脑后可继续使用助理")}
        compose.onNodeWithTag("assistant-confirm-delete").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun offlineErrorBlocksEnableEvenWithCachedAvailableRuntime() {
        val actions=mutableListOf<AssistantAction>()
        content({AssistantUiState(AssistantSnapshot(enabled=false),error="连接电脑后可继续使用助理")},actions,modelDirectory=emptyList())
        compose.onNodeWithTag("assistant-configure").performClick()
        scroll("assistant-enable").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun nativeMultipleQuestionsRenderOptionsAndFreeInputThenEncodeEveryNativeId() {
        val actions=mutableListOf<AssistantAction>()
        val raw="""[{"id":"language","header":"语言","question":"使用哪种语言？","isOther":false,"isSecret":false,"options":[{"label":"简体中文","description":"沿用项目语言"},{"label":"English","description":"Use English"}]},{"id":"scope","header":"范围","question":"需要整理哪些内容？","isOther":true,"isSecret":false,"options":[]}]"""
        val operation=AssistantOperation("multi","Execution","t",AssistantOperationPhase.Waiting,false,"thread",AssistantQuestion("request",AssistantQuestionKind.Input,raw),null)
        content({AssistantUiState(snapshot().copy(operations=listOf(operation)))},actions)
        scroll("assistant-question:multi")
        compose.onNodeWithText("使用哪种语言？").assertExists()
        compose.onNodeWithText(raw).assertDoesNotExist()
        compose.onNodeWithTag("question-option:language:0").performClick()
        compose.onNodeWithTag("question-next").performClick()
        compose.onNodeWithTag("question-submit").assertIsNotEnabled()
        compose.onNodeWithTag("question-answer:scope").performTextInput("待办与测试")
        compose.onNodeWithTag("question-submit").performClick()
        assertEquals("assistant/respond",actions.single().method)
        val encoded=actions.single().params.obj("response").string("Answer")
        assertEquals(pocketJson.parseToJsonElement("""{"language":["简体中文"],"scope":["待办与测试"]}"""),pocketJson.parseToJsonElement(encoded))
        screenshot("native-multi-question")
    }

    @Test fun nativeSingleQuestionReturnsPlainAnswerCompatibleWithExecutor() {
        val actions=mutableListOf<AssistantAction>()
        val raw="""[{"id":"only","header":"名称","question":"项目叫什么？","options":[],"isOther":true,"isSecret":false}]"""
        val op=AssistantOperation("single","Execution",null,AssistantOperationPhase.Waiting,false,"thread",AssistantQuestion("request",AssistantQuestionKind.Input,raw),null)
        content({AssistantUiState(snapshot().copy(tasks=emptyList(),operations=listOf(op)))},actions)
        scroll("assistant-question:single")
        compose.onNodeWithTag("question-answer:only").performTextInput("口袋项目")
        compose.onNodeWithTag("question-submit").performClick()
        assertEquals("口袋项目",actions.single().params.obj("response").string("Answer"))
    }

    @Test fun nativeApprovalShowsCommandReasonAndPathsWithoutRawParams() {
        val actions=mutableListOf<AssistantAction>()
        val raw="""{"command":"cargo test --offline","reason":"验证这次改动","changes":[{"path":"src/main.rs","diff":"PRIVATE_INTERNAL_DIFF"}],"cwd":"D:/authorized/project","availableDecisions":["accept","decline"],"PRIVATE_INTERNAL_PARAMS":"DO_NOT_RENDER"}"""
        val op=AssistantOperation("approval-details","Execution",null,AssistantOperationPhase.Waiting,false,"thread",AssistantQuestion("request",AssistantQuestionKind.Approval,raw),null)
        content({AssistantUiState(snapshot().copy(tasks=emptyList(),operations=listOf(op)))},actions)
        scroll("assistant-question:approval-details")
        compose.onNodeWithText("cargo test --offline").assertExists()
        compose.onNodeWithText("验证这次改动").assertExists()
        compose.onNodeWithText("src/main.rs").assertExists()
        compose.onNodeWithText(raw).assertDoesNotExist()
        scroll("assistant-approve:approval-details").performClick()
        assertTrue(actions.single().params.obj("response").obj("Approval").flag("approved"))
    }
}
