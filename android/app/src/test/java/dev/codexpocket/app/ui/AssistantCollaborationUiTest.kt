package dev.codexpocket.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application=Application::class,sdk=[35],qualifiers="w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantCollaborationUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val actions = mutableListOf<AssistantAction>()
    private val opens = mutableListOf<Pair<String,String>>()
    private val snapshot = AssistantSnapshot(enabled=true,runtimeId="managed",ready=true,
        tasks=listOf(AssistantTask("task","核查资料",AssistantTaskStatus.Running,"op",false,null,
            AssistantAssignment("task","member",completionCriteria="引用可验证"))),
        members=listOf(AssistantMember("member","研究员","核对来源","main","D:/project","native",true)),
        operations=listOf(AssistantOperation("op","Execution","task",AssistantOperationPhase.Running,false,"native",null,null)))
    private fun content(theme: () -> String = {"dark"}, glass: Boolean = false, renderGlass: () -> Boolean = {glass}, preferences: PocketGlassPreferences? = null, state: () -> AssistantUiState) {
        compose.runOnUiThread {
            activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {PocketTheme(theme()) {CompositionLocalProvider(LocalPocketGlass provides renderGlass(), LocalPocketGlassPreferences provides preferences) {PocketBackdrop {
                AssistantScreen(state(),emptyList(),emptyList(),listOf(AssistantWorkspaceChoice("managed","child","研究子代理",parentThreadId="native",isSubagent=true)),
                    {actions += it},{r,t -> opens += r to t},{})
            }}}}
        }
        compose.waitForIdle()
    }
    @After fun close() {if(::activity.isInitialized) compose.runOnUiThread {activity.close()}}

    @Test fun neutralLightAndDarkDisclosureRenderingExportsDesktopFixtures() {
        var theme by mutableStateOf("light")
        var renderOptics by mutableStateOf(true)
        content(theme={theme},glass=true,renderGlass={renderOptics}) {AssistantUiState(snapshot.copy(groups=listOf(AssistantGroup("g","调整助理界面",listOf("task"),true,true,"running")),events=listOf(AssistantEvent(1,"render-group","op","started","",3,taskId="task",groupId="g"))),messages=listOf(
            AssistantChatMessage("render-user","User","请帮我核查这些资料",1),
            AssistantChatMessage("render-assistant","Assistant","我会核对来源并整理结论。展开任务可以查看负责成员和详细进展。",2)) + List(8) {i ->
                AssistantChatMessage("render-scroll-$i","Assistant","记录 ${i+1}：核对引用与项目要求，保留完整对话，整理下一步工作。",1000+i.toLong())})}
        fun capture(name: String) {
            val output=java.io.File(System.getProperty("user.dir"),"../../.local/assistant-redesign/glass").canonicalFile
            output.mkdirs()
            // Native Skia cannot safely replay the hardware backdrop graph. Exercise the
            // app's existing fallback for software export; production optics stay enabled.
            compose.runOnIdle {renderOptics=false}
            compose.waitForIdle()
            var renderer="captureToImage"
            val bitmap=runCatching {compose.onRoot().captureToImage().asAndroidBitmap()}.getOrElse {
                renderer="native_view_draw"
                lateinit var image:android.graphics.Bitmap
                compose.runOnIdle {
                    val view=activity.get().window.decorView
                    image=android.graphics.Bitmap.createBitmap(view.width,view.height,android.graphics.Bitmap.Config.ARGB_8888)
                    view.draw(android.graphics.Canvas(image))
                }
                image
            }
            java.io.File(output,"$name.png").outputStream().use {assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
            val textBounds=mutableListOf<String>()
            var clippedText=false
            compose.runOnIdle {
                fun inspect(view: android.view.View) {
                    if(view is android.widget.TextView) {
                        textBounds += "Native text: chars=${view.text.length}, height=${view.height}, layoutHeight=${view.layout?.height}, lines=${view.lineCount}, scrollY=${view.scrollY}"
                        if(view.layout != null && view.layout.height <= view.height-view.totalPaddingTop-view.totalPaddingBottom && view.scrollY != 0) clippedText=true
                    }
                    if(view is android.view.ViewGroup) for(i in 0 until view.childCount) inspect(view.getChildAt(i))
                }
                inspect(activity.get().window.decorView)
            }
            java.io.File(output,"$name.renderer.txt").writeText("Robolectric rendering only: $renderer\nPocketGlassSurface with transcript layerBackdrop enabled. Official no-optics fallback used only for software export.\nHardware accelerated view: ${activity.get().window.decorView.isHardwareAccelerated}\nNo synthetic blur or transparency. API blur is not established by software rendering.\n"+textBounds.joinToString("\n"))
            assertFalse("Fully measured transcript text must not retain internal scroll: $textBounds",clippedText)
            assertTrue(bitmap.width>0 && bitmap.height>0)
        }
        for(mode in listOf("light","dark")) {
            compose.runOnIdle {theme=mode}
            compose.waitForIdle()
            compose.onNodeWithTag("assistant-chat-list").performScrollToIndex(0)
            compose.onNodeWithTag("assistant-chat-list").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(centerX,height*.55f),androidx.compose.ui.geometry.Offset(centerX,height*.45f),durationMillis=600)
            }
            capture("$mode-collapsed")
            compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-group:g"))
            compose.onNodeWithTag("assistant-group:g").performClick()
            if(compose.onAllNodesWithText("完成标准 · 引用可验证").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("assistant-task:task").performClick()
            if(compose.onAllNodesWithText("核对来源").fetchSemanticsNodes().isEmpty()) compose.onNodeWithTag("assistant-member:task:member").performClick()
            compose.onNodeWithTag("assistant-chat-list").performScrollToIndex(1)
            capture("$mode-expanded")
            compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-group:g"))
            compose.onNodeWithTag("assistant-group:g").performClick()
        }
    }

    @Test fun preparationErrorGatesSendAndExpandedQueuedTaskShowsEntirePromptBeforeThreadExists() {
        val prompt="核查资料并逐条说明引用来源。".repeat(10)+"最后必须保留这段完整说明。"
        var state by mutableStateOf(AssistantUiState(snapshot.copy(preparationStatus="needs_login",errorCode="assistant_needs_login",
            tasks=listOf(snapshot.tasks.single().copy(prompt=prompt,status=AssistantTaskStatus.Queued,activeRunId=null)),operations=emptyList())))
        content {state}
        compose.onNodeWithTag("assistant-message-input").performTextInput("登录前保留草稿")
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        compose.onAllNodesWithText("请在电脑端登录 Codex 后重试").onFirst().assertExists()
        compose.onNodeWithTag("assistant-task:task").performClick()
        compose.onNodeWithTag("assistant-task-full-prompt:task").assertTextEquals(prompt)
        compose.runOnIdle {state=state.copy(snapshot=state.snapshot!!.copy(preparationStatus="ready",errorCode=null))}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("登录前保留草稿")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun glassFallbackKeepsChromeComposerAndDisclosureUsableWithoutMotion() {
        content(glass=true,preferences=PocketGlassPreferences(highContrast=true,batterySaver=true,animatorsEnabled=false)) {AssistantUiState(snapshot)}
        compose.onNodeWithTag("assistant-header-glass").assertIsDisplayed()
        compose.onNodeWithTag("assistant-composer-glass").assertIsDisplayed()
        compose.onNodeWithTag("assistant-task:task").performClick()
        compose.onNodeWithTag("assistant-message-input").performTextInput("回退仍可输入")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
        assertTrue(actions.isEmpty())
    }

    @Test fun taskMemberAndNativeAgentsExpandProgressivelyAndSurviveTabNavigation() {
        content {AssistantUiState(snapshot)}
        compose.onNodeWithTag("assistant-member:task:member").assertDoesNotExist()
        compose.onNodeWithTag("assistant-task:task").performClick()
        compose.onNodeWithText("完成标准 · 引用可验证").assertExists()
        compose.onNodeWithTag("assistant-member:task:member").performClick()
        compose.onNodeWithTag("assistant-native-agents:native").performClick()
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-native-thread:child"))
        compose.onNodeWithTag("assistant-native-thread:child").performClick()
        assertEquals(listOf("managed" to "child"),opens)
        compose.onNodeWithTag("assistant-more").performClick()
        compose.onNodeWithTag("assistant-tab:Memory").performClick()
        compose.onNodeWithTag("assistant-back").performClick()
        compose.onNodeWithTag("assistant-native-thread:child").assertExists()
        assertTrue(actions.isEmpty())
    }

    @Test fun collapsedGroupHidesTaskMemberDetailsButPendingHumanRequestRemainsVisible() {
        val waiting=snapshot.tasks.single().copy(status=AssistantTaskStatus.WaitingUser)
        val op=snapshot.operations.single().copy(phase=AssistantOperationPhase.Waiting,question=AssistantQuestion("q",AssistantQuestionKind.Input,"请选择方向"))
        content {AssistantUiState(snapshot.copy(tasks=listOf(waiting),operations=listOf(op),groups=listOf(AssistantGroup("g","调整助理界面",listOf("task"),true,true,"waiting_user"))))}
        compose.onNodeWithTag("assistant-group:g").assertExists()
        compose.onNodeWithText("1 项分工 · 1 项待回答").assertExists()
        compose.onNodeWithTag("assistant-task:task").assertDoesNotExist()
        compose.onNodeWithText("完成标准 · 引用可验证").assertDoesNotExist()
        compose.onNodeWithTag("assistant-question:op").assertExists()
        compose.onNodeWithTag("assistant-group:g").performClick()
        compose.onNodeWithTag("assistant-task:task").performClick()
        compose.onNodeWithText("完成标准 · 引用可验证").assertExists()
        compose.onNodeWithTag("assistant-group:g").performClick()
        compose.onNodeWithTag("assistant-task:task").assertDoesNotExist()
        compose.onNodeWithTag("assistant-question:op").assertExists()
        assertTrue(actions.isEmpty())
    }

    @Test fun targetSendWorksWithoutRuntimeDirectoryAndQueuedDeliveryNeverClaimsSuccess() {
        var state by mutableStateOf(AssistantUiState(snapshot))
        content {state}
        compose.onNodeWithTag("assistant-message-input").performTextInput("补充要求")
        compose.onNodeWithTag("assistant-tools").performClick()
        compose.onNodeWithTag("assistant-target").performClick()
        compose.onNodeWithTag("assistant-target:member:member").performClick()
        compose.onNodeWithTag("assistant-send").performClick()
        assertEquals("member",actions.single().params.string("memberId"))
        compose.runOnIdle {state=state.copy(snapshot=snapshot.copy(deliveries=listOf(AssistantDelivery("d","补充要求","member",null,"queued",null))),sendAcknowledgement=1)}
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-delivery:d"))
        compose.onNodeWithText("待送达 · 补充要求").assertExists()
        compose.onNodeWithTag("assistant-message-input").assert(SemanticsMatcher.expectValue(androidx.compose.ui.semantics.SemanticsProperties.EditableText, androidx.compose.ui.text.AnnotatedString("")))
        compose.onNodeWithText("已送达 · 补充要求").assertDoesNotExist()
    }

    @Test fun draftSurvivesAutomaticRuntimePreparationAndRetryHasNoRuntimeParameter() {
        var state by mutableStateOf(AssistantUiState(AssistantSnapshot(enabled=false)))
        content {state}
        compose.onNodeWithTag("assistant-message-input").performTextInput("准备后继续")
        compose.onNodeWithTag("assistant-prepare").performClick()
        assertEquals("assistant/prepare",actions.single().method)
        assertTrue(actions.single().params.isEmpty())
        compose.runOnIdle {state=AssistantUiState(snapshot)}
        compose.onNodeWithTag("assistant-message-input").assertTextEquals("准备后继续")
        compose.onNodeWithTag("assistant-send").assertIsEnabled()
    }

    @Test fun answeredSnapshotHistoryRemainsCollapsedWithMemberAndNativeSourceAttribution() {
        val history=AssistantSnapshot.from(Json.parseToJsonElement("""{"questionHistory":[{"id":"history","operationId":"op","requestId":"q","atMs":42,"item":{
          "type":"dynamicToolCall","tool":"request_user_input","status":"completed","success":true,
          "arguments":{"questions":[{"id":"q","question":"采用哪个方案？"}]},
          "contentItems":[{"type":"text","text":"{\"answers\":{\"q\":{\"answers\":[\"方案 A\"]}}}"}]
        }}]}""").jsonObject).questionHistory
        val completed=snapshot.operations.single().copy(phase=AssistantOperationPhase.Completed,
            question=AssistantQuestion("q",AssistantQuestionKind.Input,"采用哪个方案？"))
        content {AssistantUiState(snapshot.copy(operations=listOf(completed),questionHistory=history))}
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasText("已询问 1 个问题"))
        compose.onNodeWithText("研究员").assertExists()
        compose.onNodeWithText("采用哪个方案？").assertDoesNotExist()
        compose.onNodeWithTag("assistant-question:op").assertDoesNotExist()
        compose.onNodeWithTag("question-history-toggle:assistant:history").performClick()
        compose.onNodeWithText("回答：方案 A").assertExists()
        compose.onNodeWithTag("question-history-source:assistant:history").performClick()
        assertEquals(listOf("managed" to "native"),opens)
        assertTrue(actions.isEmpty())
        compose.onNodeWithTag("question-submit").assertDoesNotExist()
    }

    @Test fun reassignmentKeepsOldExecutionResultAuthorInsteadOfCurrentResponsibleMember() {
        val completed=snapshot.operations.single().copy(phase=AssistantOperationPhase.Completed,result="已完成来源核查")
        val old=snapshot.members.single()
        val newer=old.copy(id="new",name="新负责成员")
        content {AssistantUiState(snapshot.copy(members=listOf(old,newer),operations=listOf(completed),
            assignments=listOf(AssistantAssignment("task","new")),
            events=listOf(AssistantEvent(1,"historic","op","completed","",1,taskId="task",memberId="member"))))}
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-result:op"))
        compose.onNodeWithText("研究员").assertExists()
        compose.onNodeWithText("新负责成员").assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }
}
