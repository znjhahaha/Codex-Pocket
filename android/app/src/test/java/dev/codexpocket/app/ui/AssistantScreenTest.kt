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
        listOf(AssistantOperation("execution","Execution","t",AssistantOperationPhase.Running,false,"native-task",null,null)),ready=true)
    private fun content(state: () -> AssistantUiState, actions: MutableList<AssistantAction>, opens: MutableList<Pair<String,String>> = mutableListOf(), fontScale: Float = 1f, theme: String = "light", modelDirectory: List<NativeModel> = models, connected: () -> Boolean = {true}) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme(theme) {
                val d = LocalDensity.current
                CompositionLocalProvider(LocalPocketGlass provides false, LocalDensity provides Density(d.density, fontScale)) {
                    // Dialogs open in Pocket's overlay layer, as they do under the app's root.
                    PocketOverlayHost {AssistantScreen(state(),modelDirectory,runtime,project,{actions += it},{r,t -> opens += r to t},{},nowMs=now,connected=connected())}
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

    @Test fun disabledEntryRetainsComposerAndOffersPreparationWithoutRuntimeSelection() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(AssistantSnapshot(enabled=false))},actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("保留草稿")
        compose.onNodeWithTag("assistant-prepare").performClick()
        assertEquals("assistant/prepare",actions.single().method)
        assertTrue(actions.single().params.isEmpty())
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("保留草稿")
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        compose.onNodeWithText("服务已就绪").assertDoesNotExist()
        screenshot("disabled-prepare")
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
        // The execution's result sits inside its task's card; the full text and the original thread are one page further.
        compose.expandAssistantTask("t")
        scroll("assistant-result:execution").assertIsDisplayed()
        scroll("assistant-result-detail:execution").performClick()
        scroll("assistant-thread:execution").performClick()
        assertEquals(listOf("r" to "native-task"),opens)
        screenshot("chat-result")
    }

    @Test fun sendsOnlyChosenAuthorizedThreadAndRetainsDraftUntilAcknowledged() {
        val actions = mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(snapshot()))
        content({state},actions)
        compose.onNodeWithTag(AssistantDraft).performTextInput("真实消息")
        compose.selectAssistantWorkspace("authorized-thread")
        compose.onNodeWithTag(AssistantSend).performClick()
        assertEquals("真实消息",actions.last().params.string("message"))
        assertEquals("authorized-thread",actions.last().params.string("threadId"))
        assertFalse(actions.last().params.containsKey("path"))
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("真实消息")
        compose.runOnIdle { state = state.copy(pendingActions=setOf("assistant/send")) }
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(pendingActions=emptySet()).acceptSend(actions.last()) }
        compose.onNodeWithTag(AssistantDraft).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("")))
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

    @Test fun readFailurePreservesWaitingDraftAndBlocksSubmissionUntilFreshRead() {
        val actions=mutableListOf<AssistantAction>()
        val op=AssistantOperation("input","Coordinator",null,AssistantOperationPhase.Waiting,false,"c",AssistantQuestion("q",AssistantQuestionKind.Input,"请选择顺序"),null)
        var state by mutableStateOf(AssistantUiState(snapshot().copy(operations=listOf(op),fresh=true)))
        content({state},actions)
        scroll("assistant-answer:input").assertIsEnabled().performTextInput("fixture answer")
        compose.runOnIdle {state=state.copy(readError="刷新失败，显示上次读取结果",snapshot=state.snapshot!!.copy(fresh=false))}
        scroll("assistant-answer:input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("fixture answer"))).assertIsNotEnabled()
        scroll("assistant-respond:input").assertIsNotEnabled()
        compose.onNodeWithTag("assistant-read-retry").performClick()
        assertEquals(listOf("assistant/read"),actions.map {it.method})
        scroll("assistant-respond:input").assertIsNotEnabled()
        compose.runOnIdle {state=state.copy(readError=null,snapshot=state.snapshot!!.copy(fresh=true))}
        scroll("assistant-answer:input").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText,AnnotatedString("fixture answer"))).assertIsEnabled()
        scroll("assistant-respond:input").assertIsEnabled()
        compose.onNodeWithContentDescription("等待你确认").assertExists()
        scroll("assistant-respond:input").performClick()
        assertEquals("assistant/respond",actions.last().method)
        assertEquals("q",actions.last().params.string("requestId"))
        assertEquals(JsonPrimitive("fixture answer"),actions.last().params.obj("response")["Answer"])
    }

    @Test fun dispatchCacheOffersReadRefreshWithoutPreparation() {
        val actions=mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot().copy(ready=false,fresh=false,dispatchInFlight=true),readError="显示上次读取结果")},actions)
        compose.onNodeWithTag("assistant-prepare").assertDoesNotExist()
        compose.onNodeWithTag("assistant-read-retry").performClick()
        assertEquals(listOf("assistant/read"),actions.map {it.method})
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
        compose.onNodeWithTag("assistant-schedule-workspace:${AssistantWorkspaceChoice("r","authorized-thread","").selectionKey}").performClick()
        compose.onNodeWithTag("assistant-project-permission-status").assertExists()
        compose.onNodeWithTag("assistant-schedule-save").performClick()
        assertEquals("assistant/schedule/create",actions.last().method)
        assertEquals("authorized-thread",actions.last().params.string("threadId"))
        assertEquals("UTC",actions.last().params.obj("spec").obj("zone").string("name"))
        assertFalse(actions.last().params.obj("spec").containsKey("directory"))
        compose.onNodeWithText("已错过 · 已记录").assertExists()
        compose.onNodeWithText("错过后跳过").assertExists()
        scroll("assistant-schedule-delete:s").performClick()
        compose.onNodeWithTag("assistant-confirm-delete").performClick()
        assertEquals("assistant/schedule/delete",actions.last().method)
        screenshot("schedules")
    }

    @Test fun largeFontDarkScreenKeepsChatAndMoreMenuInteractive() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(snapshot())},actions,fontScale=1.6f,theme="dark")
        compose.onNodeWithTag("assistant-pocket-button").assertDoesNotExist()
        compose.onNodeWithTag(AssistantDraft).performTextInput("大字模式草稿")
        compose.onNodeWithTag(AssistantSend).assertIsDisplayed().assertIsEnabled()
        compose.openAssistantPage("Settings")
        scroll("assistant-save-settings").assertIsDisplayed()
        screenshot("dark-large-font")
        compose.returnToAssistantChat()
        compose.onNodeWithTag(AssistantDraft).assertTextEquals("大字模式草稿")
        compose.onNodeWithTag(AssistantSend).assertIsDisplayed().assertIsEnabled()
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
        // Stopping is offered on the card of a task that can still be stopped, and waits for the confirmation.
        compose.expandAssistantTask("queued")
        scroll("assistant-job-stop:queued").performClick()
        assertTrue(actions.isEmpty())
        compose.onNodeWithText("确认停止").performClick()
        assertEquals("assistant/task/pause",actions.last().method)
        assertEquals("queued",actions.last().params.string("taskId"))
        compose.expandAssistantTask("paused")
        compose.onNodeWithTag("assistant-job-stop:paused").assertDoesNotExist()
        scroll("assistant-chat-task-open:paused").performClick()
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.ScrollToIndex)).onFirst().performScrollToNode(hasText("继续任务"))
        compose.onNodeWithText("继续任务").performClick()
        assertEquals("assistant/task/resume",actions.last().method)
        assertEquals("paused",actions.last().params.string("taskId"))
        compose.returnToAssistantChat()
        // A task whose execution is being reconciled says so, and cannot be resumed until it is settled.
        scroll("assistant-chat-task-toggle:uncertain").assert(hasText("待核对"))
        compose.expandAssistantTask("uncertain")
        scroll("assistant-chat-task-open:uncertain").performClick()
        compose.onNodeWithText("继续任务").assertDoesNotExist()
        compose.returnToAssistantChat()
        compose.expandAssistantTask("failed")
        scroll("assistant-chat-task-open:failed").performClick()
        compose.onNodeWithText("继续任务").assertDoesNotExist()
        assertEquals(2,actions.size)
    }

    @Test fun emptyInitialDirectoryAllowsExplicitHostDefaultAndEnable() {
        val actions=mutableListOf<AssistantAction>()
        var state by mutableStateOf(AssistantUiState(AssistantSnapshot(enabled=false)))
        content({state},actions,modelDirectory=emptyList())
        compose.openAssistantPage("Settings")
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
        var online by mutableStateOf(true)
        content({state},actions,connected={online})
        compose.openAssistantPage("Memory")
        scroll("assistant-memory-delete:m").performClick()
        compose.runOnIdle {state=state.copy(error="连接电脑后可继续使用助理");online=false}
        compose.onNodeWithTag("assistant-confirm-delete").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun offlineErrorBlocksEnableEvenWithCachedAvailableRuntime() {
        val actions=mutableListOf<AssistantAction>()
        content({AssistantUiState(AssistantSnapshot(enabled=false),error="连接电脑后可继续使用助理")},actions,modelDirectory=emptyList())
        compose.openAssistantPage("Settings")
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

    private fun withCoordinator(model: String?) = snapshot().let { it.copy(settings = it.settings!!.copy(coordinatorModel = model)) }
    private fun chooseCoordinator(option: String) {
        scroll("assistant-coordinator-model").performClick()
        compose.onNodeWithTag("assistant-coordinator-model:$option").performScrollTo().performClick()
    }

    @Test fun choosingACoordinatorModelSendsItAndLeavesTheTaskModelAlone() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(withCoordinator(null))},actions)
        compose.openAssistantPage("Settings")
        scroll("assistant-coordinator-model").assert(hasText("跟随默认"))
        chooseCoordinator("model:actual-model")
        scroll("assistant-coordinator-model").assert(hasText("电脑传入模型"))
        scroll("assistant-save-settings").performClick()
        val settings = actions.single().params.obj("settings")
        assertEquals("actual-model",settings.string("coordinator_model"))
        assertEquals("actual-model",settings.string("model"))
        assertEquals("认真协助",settings.string("personality"))
    }

    @Test fun followingTheDefaultSendsNoCoordinatorModel() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(withCoordinator("actual-model"))},actions)
        compose.openAssistantPage("Settings")
        scroll("assistant-coordinator-model").assert(hasText("电脑传入模型"))
        chooseCoordinator("follow-default")
        scroll("assistant-coordinator-model").assert(hasText("跟随默认"))
        scroll("assistant-save-settings").performClick()
        val settings = actions.single().params.obj("settings")
        // The settings object leaves the field out, which the computer reads as none.
        assertFalse(settings.containsKey("coordinator_model"))
        assertEquals("actual-model",settings.string("model"))
    }

    @Test fun savingAnotherFieldPreservesTheStoredCoordinatorModel() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(withCoordinator("actual-model").let { it.copy(settings = it.settings!!.copy(model = null)) })},actions)
        compose.openAssistantPage("Settings")
        scroll("assistant-tone").performTextReplacement("耐心")
        scroll("assistant-notify-completed").performClick()
        scroll("assistant-save-settings").performClick()
        val settings = actions.single().params.obj("settings")
        assertEquals("actual-model",settings.string("coordinator_model"))
        assertEquals(JsonNull,settings["model"])
        assertEquals("耐心",settings.string("tone"))
        assertFalse(settings.obj("notifications").flag("completed"))
    }

    @Test fun offCatalogueCoordinatorModelIsShownAndSurvivesAnUnrelatedSave() {
        val actions = mutableListOf<AssistantAction>()
        content({AssistantUiState(withCoordinator("retired-model"))},actions)
        compose.openAssistantPage("Settings")
        scroll("assistant-coordinator-model").assert(hasText("retired-model（不在列表中）"))
        scroll("assistant-personality").performTextReplacement("耐心核对")
        compose.onNodeWithTag("assistant-settings-issue").assertDoesNotExist()
        scroll("assistant-save-settings").assertIsEnabled().performClick()
        val settings = actions.single().params.obj("settings")
        assertEquals("retired-model",settings.string("coordinator_model"))
        assertEquals("耐心核对",settings.string("personality"))
        // It is offered only as what is stored: the list holds the default and the catalogue.
        scroll("assistant-coordinator-model").performClick()
        compose.onNodeWithTag("assistant-coordinator-model:follow-default").assertExists()
        compose.onNodeWithTag("assistant-coordinator-model:model:actual-model").assertExists()
        compose.onNodeWithTag("assistant-coordinator-model:model:retired-model").assertDoesNotExist()
    }
}
