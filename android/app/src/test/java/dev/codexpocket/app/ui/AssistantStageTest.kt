package dev.codexpocket.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import dev.codexpocket.app.ui.kit.LocalPocketHaptics
import dev.codexpocket.app.ui.kit.PocketHaptic
import dev.codexpocket.app.ui.kit.PocketHaptics
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The single stage: navigation, the attention capsule, task cards, `@`, merge and discard, the live reply, layout. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantStageTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val actions = mutableListOf<AssistantAction>()
    private val opens = mutableListOf<Pair<String, String>>()
    private val haptics = mutableListOf<PocketHaptic>()
    private var exits = 0
    private val fx = AssistantStageFixtures

    private fun content(theme: String = "light", font: Float = 1f, state: () -> AssistantUiState) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            val density = LocalDensity.current
            // Glass falls back to its solid fill and nothing animates: the frozen, reduced-motion rendering.
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false),
                LocalPocketHaptics provides PocketHaptics { haptics += it }, LocalDensity provides Density(density.density, font)) {
                PocketOverlayHost { AssistantScreen(state(), emptyList(), emptyList(), emptyList(), { actions += it }, { r, t -> opens += r to t }, { exits++ },
                    nowMs = fx.At) }
            }
        } } }
        compose.waitForIdle()
    }
    private fun back() { compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }; compose.waitForIdle() }
    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
    private fun emptyDraft() = compose.onNodeWithTag(AssistantDraft).assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
    private fun textViews(view: View): List<TextView> = when (view) {
        is TextView -> listOf(view); is ViewGroup -> (0 until view.childCount).flatMap { textViews(view.getChildAt(it)) }; else -> emptyList()
    }
    private fun rendered(text: String) = textViews(compose.activity.window.decorView).count { it.isShown && text in it.text.toString() }

    @Test fun singleStageHasNoBottomNavAndSubpagesReturnPreservingDraft() {
        val history = List(30) { AssistantChatMessage("h$it", "User", "记录 $it\n第二行\n第三行", fx.At + it) }
        content { fx.running().let { it.copy(messages = history + it.messages) } }
        listOf("Main", "Tasks", "Artifacts", "Plans").forEach { compose.onNodeWithTag("assistant-nav:$it").assertDoesNotExist() }
        compose.onNodeWithTag(AssistantDraft).performTextInput("保留这个目标")
        compose.onNodeWithTag("assistant-chat-list").performScrollToIndex(10)
        compose.waitForIdle()
        val top = bounds("assistant-message:h10").top
        mapOf("Tasks" to "assistant-tasks-list", "Artifacts" to "assistant-artifact-filter", "Plans" to "assistant-schedules-list",
            "Memory" to "assistant-memory-list", "Settings" to "assistant-save-settings").forEach { (page, tag) ->
            compose.openAssistantPage(page)
            if (page == "Settings") compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst().performScrollToNode(hasTestTag(tag))
            compose.onNodeWithTag(tag).assertExists()
            compose.onNodeWithTag(AssistantDraft).assertDoesNotExist()
            compose.onNodeWithTag("assistant-back").performClick()
            compose.onNodeWithTag(AssistantDraft).assertTextContains("保留这个目标")
            assertEquals("The conversation must come back where it was left", top, bounds("assistant-message:h10").top, 1f)
        }
        // System Back leaves one level at a time: task detail, task list, the stage, then the assistant.
        compose.openAssistantPage("Tasks")
        compose.onNodeWithTag("assistant-tasks-list").performScrollToNode(hasTestTag("assistant-task-open:login"))
        compose.onNodeWithTag("assistant-task-open:login").performClick()
        compose.onNodeWithTag("assistant-task-detail-page:login").assertExists()
        back(); compose.onNodeWithTag("assistant-tasks-list").assertExists()
        back(); compose.onNodeWithTag(AssistantDraft).assertTextContains("保留这个目标")
        assertEquals(0, exits)
        back(); assertEquals(1, exits)
        // Opening an unread task marks it seen; nothing else may be sent by moving between pages.
        assertTrue(actions.all { it.method == "assistant/v2/task/seen" } && opens.isEmpty())
    }

    @Test fun attentionCapsuleReflectsIdleRunningAndPendingApprovalStates() {
        var state by mutableStateOf(fx.idle())
        content { state }
        compose.onNodeWithTag("assistant-attention").assertDoesNotExist()
        compose.onNodeWithTag("assistant-attention-slot").assertHeightIsEqualTo(0.dp)
        val idleTop = bounds("assistant-chat-viewport").top

        compose.runOnIdle { state = fx.running() }
        compose.onNodeWithTag("assistant-attention").assertIsDisplayed().assertContentDescriptionContains("1 个任务进行中 · 修复登录超时", substring = true)
        // The capsule takes room: the conversation's clear area starts below it.
        assertTrue(bounds("assistant-chat-viewport").top >= bounds("assistant-attention").bottom - 1f)
        assertTrue(bounds("assistant-chat-viewport").top > idleTop)
        compose.onNodeWithTag("assistant-attention").performClick()
        compose.onNodeWithTag("assistant-attention-sheet").assertIsDisplayed()
        compose.onNodeWithText("进行中").assertIsDisplayed()
        compose.onNodeWithTag("assistant-attention-running:login").assert(hasAnyDescendant(hasText("正在跑回归测试")))
        compose.onNodeWithTag("assistant-attention-running:login").assert(hasAnyDescendant(hasText("2 子代理")))
        compose.onNodeWithTag("assistant-attention-stop:login").performClick()
        assertTrue("Stopping waits for the confirmation", actions.isEmpty())
        compose.onNodeWithText("确认停止").performClick()
        assertEquals("assistant/task/pause", actions.single().method)
        assertEquals("login", actions.single().params.string("taskId"))
        compose.onNodeWithTag("assistant-attention-thread:login").performClick()
        assertEquals(listOf("r" to "native-login"), opens)
        compose.onNodeWithTag("assistant-attention-sheet").assertDoesNotExist()

        compose.runOnIdle { state = fx.pending() }
        compose.onNodeWithTag("assistant-attention").assertIsDisplayed().assertContentDescriptionContains("1 项待你处理", substring = true)
        compose.onNodeWithTag("assistant-attention").performClick()
        compose.onNodeWithText("待你拍板").assertIsDisplayed()
        // The original request, answered here under its own identity.
        compose.onNodeWithTag("assistant-approve:run-login").performScrollTo().performClick()
        assertEquals("assistant/respond", actions.last().method)
        assertEquals("approve-1", actions.last().params.string("requestId"))
        assertTrue(actions.last().params.obj("response").obj("Approval").flag("approved"))
        assertEquals(PocketHaptic.Confirm, haptics.last())
        compose.onNodeWithContentDescription("关闭").performClick()

        compose.runOnIdle { state = fx.idle() }
        compose.onNodeWithTag("assistant-attention").assertDoesNotExist()
        compose.onNodeWithTag("assistant-attention-slot").assertHeightIsEqualTo(0.dp)
    }

    @Test fun inlineJobCardEvolvesInPlaceAndOpensNativeThread() {
        var state by mutableStateOf(fx.running())
        content { state }
        compose.onAllNodesWithTag("assistant-chat-task:login").assertCountEquals(1)
        val header = compose.onNodeWithTag("assistant-chat-task-toggle:login")
        header.assert(hasText("执行中")).assert(hasText("修复登录超时")).assert(hasText("pocket-shop")).assert(hasText("2 子代理")).assert(hasText("正在跑回归测试"))

        // A frame for a job the snapshot no longer shows as moving is ignored: the snapshot's wording stands.
        compose.runOnIdle { state = fx.delivered().copy(liveJobStages = fx.running().liveJobStages) }
        compose.onAllNodesWithTag("assistant-chat-task:login").assertCountEquals(1)
        header.assert(hasText("已交付")).assert(!hasText("正在跑回归测试")).assert(!hasText("2 子代理"))
        compose.onNodeWithTag("assistant-job-diff:login").assertTextEquals("+42/-11 · 2 文件")
        compose.onNodeWithText("assistant/login-timeout").assertExists()
        compose.onNodeWithTag("assistant-result:run-login").assertExists()
        assertTrue(opens.isEmpty())
        compose.onNodeWithTag("assistant-job-thread:login").performScrollTo().performClick()
        assertEquals(listOf("r" to "native-login"), opens)

        // An execution whose outcome is not established is shown as such, never as finished or failed.
        compose.runOnIdle { state = fx.delivered().let { it.copy(snapshot = it.snapshot!!.copy(tasks = listOf(fx.running.copy(status = AssistantTaskStatus.Failed,
            reconciliationRequired = true)))) } }
        compose.onAllNodesWithTag("assistant-chat-task:login").assertCountEquals(1)
        header.assert(hasText("待核对")).assert(!hasText("已交付")).assert(!hasText("未完成"))
        assertTrue(actions.isEmpty())
    }

    @Test fun atMentionBindsTargetTaskIdAndRespectsReceiptClearing() {
        var state by mutableStateOf(fx.running())
        content { state }
        compose.onNodeWithTag("assistant-mention-strip").assertDoesNotExist()
        compose.onNodeWithTag(AssistantDraft).performTextInput("看一下 @")
        compose.onNodeWithTag("assistant-mention:login").assertIsDisplayed().performClick()
        compose.onNodeWithTag("assistant-mention-strip").assertDoesNotExist()
        compose.onNodeWithTag("assistant-target-chip").assertIsDisplayed().assert(hasText("@修复登录超时"))
        compose.onNodeWithTag(AssistantDraft).assertTextContains("看一下 ")
        compose.onNodeWithTag(AssistantDraft).performTextInput("进度")
        compose.onNodeWithTag(AssistantSend).performClick()
        assertEquals("login", actions.single().params.string("taskId"))
        assertEquals("看一下 进度", actions.single().params.string("message"))
        assertEquals(PocketHaptic.Confirm, haptics.last())
        // The persisted message owns its target; the empty composer is immediately available for another goal.
        compose.onNodeWithTag(AssistantSend).assertIsNotEnabled()
        compose.onNodeWithTag("assistant-target-chip").assertDoesNotExist()
        compose.runOnIdle { state = state.acceptSend(actions.single(), "rejected") }
        emptyDraft()
        val originalId = actions.single().commandId
        compose.scrollAssistantChatTo("assistant-send-failure:$originalId").performClick()
        compose.onNodeWithTag("assistant-send-retry:$originalId").performClick()
        assertEquals("login", actions.last().params.string("taskId"))
        assertNotEquals(actions[0].commandId, actions[1].commandId)
        compose.runOnIdle { state = state.acceptSend(actions.last()) }
        emptyDraft()
        compose.onNodeWithTag("assistant-target-chip").assertDoesNotExist()
        compose.onNodeWithTag(AssistantDraft).performTextInput("另一件事")
        compose.onNodeWithTag(AssistantSend).performClick()
        assertFalse(actions.last().params.containsKey("taskId"))
        // The chip is dismissible: the message then goes to the assistant itself.
        compose.runOnIdle { state = state.acceptSend(actions.last()) }
        compose.onNodeWithTag(AssistantDraft).performTextInput("@登录")
        compose.onNodeWithTag("assistant-mention:login").performClick()
        compose.onNodeWithTag("assistant-target-chip").performClick()
        compose.onNodeWithTag("assistant-target-chip").assertDoesNotExist()
        compose.onNodeWithTag(AssistantDraft).performTextInput("不指定任务")
        compose.onNodeWithTag(AssistantSend).performClick()
        assertFalse(actions.last().params.containsKey("taskId"))
        assertEquals(4, actions.size)
    }

    @Test fun mergeAndDiscardRequireConfirmationAndUseCommandIds() {
        var state by mutableStateOf(fx.delivered())
        content { state }
        val merge = compose.onNodeWithTag("assistant-job-merge:login")
        merge.performScrollTo().performClick()
        assertTrue("A tap alone sends nothing", actions.isEmpty())
        compose.onNodeWithText("取消").performClick()
        assertTrue(actions.isEmpty())
        merge.performClick()
        compose.onNodeWithText("确认合入").performClick()
        // One ordinary action; the repository gives each attempt its own command id on this path.
        assertEquals(listOf("assistant/v2/task/merge"), actions.map { it.method })
        assertEquals("login", actions.single().params.string("taskId"))
        assertEquals("r", actions.single().params.string("runtimeId"))
        assertNull(actions.single().commandId)
        assertEquals(PocketHaptic.Confirm, haptics.last())
        compose.onNodeWithText("确认合入").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/v2/task/merge")) }
        merge.assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("确认合入").assertDoesNotExist()
        assertEquals(1, actions.size)

        // The outcome arrives with the reload, in plain words; a refused merge can be tried again or dropped.
        compose.runOnIdle { state = fx.delivered(AssistantJobMerge("main_dirty", fx.At)) }
        compose.onNodeWithTag("assistant-job-merge-outcome:login").assertTextContains("项目里有尚未提交的改动", substring = true)
        merge.assertIsEnabled()
        val discard = compose.onNodeWithTag("assistant-job-discard:login")
        discard.performScrollTo().performClick()
        assertEquals(1, actions.size)
        compose.onNodeWithText("确认丢弃").performClick()
        assertEquals("assistant/v2/task/discard_worktree", actions.last().method)
        assertEquals("login", actions.last().params.string("taskId"))
        assertNull(actions.last().commandId)
        assertEquals(PocketHaptic.Reject, haptics.last())
        compose.runOnIdle { state = state.copy(pendingActions = setOf("assistant/v2/task/discard_worktree")) }
        discard.assertIsNotEnabled().performTouchInput { click() }
        assertEquals(2, actions.size)
        compose.runOnIdle { state = fx.delivered(AssistantJobMerge("conflict", fx.At, files = listOf("src/login.kt")), ready = false) }
        compose.onNodeWithTag("assistant-job-merge-outcome:login").assertTextContains("src/login.kt", substring = true)
        compose.onNodeWithTag("assistant-job-merge:login").assertDoesNotExist()
        assertTrue(opens.isEmpty())
    }

    @Test fun streamingBubbleShowsLiveTextOnceAndYieldsToStoredMessage() {
        val live = "找到了：两个用例共用同一个临时目录。"
        var state by mutableStateOf(fx.streaming().copy(streamingCoordinatorOpId = null, streamingCoordinatorText = ""))
        content { state }
        // The coordinator is working and has said nothing yet: a thinking hint at the tail.
        compose.onNodeWithTag("assistant-working").assertIsDisplayed()
        compose.onNodeWithTag("assistant-streaming").assertDoesNotExist()

        compose.runOnIdle { state = state.copy(streamingCoordinatorOpId = "c2", streamingCoordinatorText = live) }
        compose.onNodeWithTag("assistant-streaming").assertIsDisplayed()
        compose.onNodeWithTag("assistant-working").assertDoesNotExist()
        compose.waitUntil(5_000) { rendered(live) == 1 }
        val tail = bounds("assistant-streaming")
        assertTrue("The live reply is the last thing above the dock", tail.bottom <= bounds("assistant-chat-viewport").bottom + 1f)

        // The stored message arrives while the frames are still held: only the message is shown.
        compose.runOnIdle { state = state.copy(messages = state.messages + AssistantChatMessage("a3", "Assistant", live, fx.At + 4_000, "c2")) }
        compose.onNodeWithTag("assistant-streaming").assertDoesNotExist()
        compose.onNodeWithTag("assistant-message:a3").assertIsDisplayed()
        compose.waitUntil(5_000) { rendered(live) == 1 }
        // Then the data layer drops what it held and the turn ends: still one, and no hint.
        compose.runOnIdle { state = state.copy(streamingCoordinatorOpId = null, streamingCoordinatorText = "", snapshot = state.snapshot!!.copy(
            operations = state.snapshot!!.operations.map { if (it.id == "c2") it.copy(phase = AssistantOperationPhase.Completed) else it })) }
        compose.onNodeWithTag("assistant-working").assertDoesNotExist()
        compose.onNodeWithTag("assistant-streaming").assertDoesNotExist()
        assertEquals(1, rendered(live))
        assertTrue(actions.isEmpty())
    }

    @Test fun darkNarrowScreenLargeFontAndReducedMotionHaveNoOverlap() {
        var state by mutableStateOf(fx.pending())
        content("dark", 1.4f) { state }
        fun assertLayout(capsule: Boolean) {
            val bar = bounds("assistant-top-bar"); val view = bounds("assistant-chat-viewport"); val dock = bounds("assistant-bottom-dock")
            val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
            val stack = listOfNotNull("top bar" to bar, if (capsule) "capsule" to bounds("assistant-attention") else null, "conversation" to view, "dock" to dock)
            stack.zipWithNext().forEach { (above, below) ->
                assertTrue("${above.first} must end before ${below.first} starts: ${above.second} / ${below.second}", above.second.bottom <= below.second.top + 1f)
            }
            stack.forEach { (name, rect) -> assertTrue("$name must stay on screen: $rect", rect.left >= -1f && rect.right <= root.right + 1f && rect.height > 0f) }
            assertTrue("The conversation keeps a readable area", view.height >= root.height * .3f)
            // At the tail, the last row is clear of the dock.
            val list = compose.onNodeWithTag("assistant-chat-list").fetchSemanticsNode()
            val last = list.children.filter { it.boundsInRoot.height > 2f }.maxByOrNull { it.boundsInRoot.bottom }
            assertTrue("The last row must end above the dock", last == null || last.boundsInRoot.bottom <= view.bottom + 1f)
            val min = with(Density(compose.density.density)) { 48.dp.toPx() } - 1f
            // Text is entered through the field's own row; every other target is at least 48dp both ways.
            compose.onAllNodes(hasClickAction() and !hasSetTextAction()).fetchSemanticsNodes().filter { it.boundsInRoot.height > 0f }.forEach { node ->
                val size = node.size
                assertTrue("Target ${node.config.getOrNull(SemanticsProperties.TestTag) ?: node.config.getOrNull(SemanticsProperties.Text)} is ${size.width}x${size.height}px",
                    size.width >= min && size.height >= min)
            }
        }
        assertLayout(capsule = true)
        compose.onNodeWithTag(AssistantDraft).performTextInput("第一行\n第二行\n第三行 @")
        compose.onNodeWithTag("assistant-mention-strip").assertIsDisplayed()
        assertLayout(capsule = true)
        compose.runOnIdle { state = fx.streaming() }
        assertLayout(capsule = true)
        compose.runOnIdle { state = fx.offline().copy(readError = "助理状态暂未更新，已保留上次读取结果") }
        compose.onNodeWithTag("assistant-workspace-issue").assertIsDisplayed()
        assertLayout(capsule = false)
        assertTrue(actions.isEmpty())
    }

    /** The assistant, stage and pages alike, is built on Pocket's own controls: none of its sources imports Material. */
    @Test fun assistantSourcesNeverReferenceMaterial() {
        val ui = File("src/main/java/dev/codexpocket/app/ui")
        val sources = ui.listFiles { file -> file.isFile && file.name.startsWith("Assistant") && file.extension == "kt" }.orEmpty().sortedBy { it.name }
        assertTrue("Assistant sources were not found from ${File(".").absolutePath}", sources.size >= 15)
        assertEquals(emptyList<String>(), sources.flatMap { file -> file.readLines().mapIndexedNotNull { index, line ->
            if ("androidx.compose.material" in line || "MaterialTheme" in line) "${file.name}:${index + 1}" else null } })
        listOf("AssistantChat.kt", "AssistantSecondaryPages.kt", "AssistantTaskCenter.kt", "AssistantTaskDetails.kt", "AssistantGroupCard.kt").forEach {
            assertFalse("$it was removed and must not come back unused", File(ui, it).exists())
        }
    }
}
