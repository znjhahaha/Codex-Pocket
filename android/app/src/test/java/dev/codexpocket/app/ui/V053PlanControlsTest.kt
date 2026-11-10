package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
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
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V053PlanControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val plan = ProposedPlan("turn", "plan", "# Plan\n\n" + "A full paragraph.\n\n".repeat(40))
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") { CompositionLocalProvider(LocalPocketGlass provides false) {
                PocketBackdrop { ProvideMarkdownRendering { Box(Modifier.fillMaxSize()) { block() } } }
            } }
        } }
        compose.waitForIdle()
    }

    @Test fun feedbackRequiresExplicitSubmitAndCancelRestoresChoices() {
        var feedback by mutableStateOf<String?>(null)
        var sent = ""
        var executed = 0
        content { PlanConfirmation(plan, null, { executed++ }, { feedback = "" },
            feedback = feedback, onFeedbackChange = { feedback = it }, onSubmitFeedback = { sent = feedback.orEmpty() },
            onCancelFeedback = { feedback = null }) }
        compose.onNodeWithTag("plan-modify").performClick()
        compose.onNodeWithTag("plan-feedback-input").performTextInput("只修改第二步")
        compose.runOnIdle { assertEquals("", sent); assertEquals(0, executed) }
        compose.onNodeWithTag("plan-feedback-submit").performClick()
        compose.runOnIdle { assertEquals("只修改第二步", sent) }
        compose.onNodeWithContentDescription("返回方案确认").performClick()
        compose.onNodeWithTag("plan-implement").assertIsDisplayed()
    }

    @Test fun dismissDoesNotExecuteAndPreviewIsBoundedWithReadOnlyReader() {
        var dismissed = 0
        content { Column { PlanConfirmation(plan, null, { fail("No implementation") }, {}, feedback = null, onDismiss = { dismissed++ }) } }
        compose.onNodeWithTag("plan-skip").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun previewOpensFullReaderWithoutOfferingChildMutation() {
        content { PlanProposalCard(plan, "child") }
        val previewHeight = compose.onNodeWithTag("plan-preview").fetchSemanticsNode().boundsInRoot.height
        assertTrue(previewHeight <= 240f * compose.activity.resources.displayMetrics.density + 1f)
        compose.onNodeWithContentDescription("展开完整计划").performClick()
        compose.onNodeWithTag("plan-reader").assertIsDisplayed()
        compose.onNodeWithTag("plan-reopen").assertDoesNotExist()
        compose.onNodeWithContentDescription("关闭计划阅读").performClick()
        compose.onNodeWithTag("proposed-plan").assertIsDisplayed()
    }

    @Test fun deniedFeedbackOpeningKeepsChoicesAndOfflineCanDismissLocally() {
        var dismissed = 0
        var reason by mutableStateOf<String?>(null)
        content { PlanConfirmation(plan, reason, {}, {}, feedback = null, onDismiss = { dismissed++ }) }
        compose.onNodeWithTag("plan-modify").performClick()
        compose.onNodeWithTag("plan-feedback-input").assertDoesNotExist()
        compose.runOnIdle { reason = "连接电脑后可实施计划" }
        compose.onNodeWithTag("plan-implement").assertIsNotEnabled()
        compose.onNodeWithTag("plan-skip").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test fun formalPlanUsesStructuredTableInsteadOfDroppingCells() {
        val table = ProposedPlan("turn", "table-plan", "# Plan\n\n| 项目 | 结果 |\n| --- | --- |\n| 检查 | 完成 |")
        content { PlanProposalCard(table, "main") }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("markdown-table").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("markdown-table").assertExists()
        // AndroidView exposes its own accessibility subtree, outside Compose semantics.
        fun tableViews(view: android.view.View): List<MarkdownTableView> =
            if (view is MarkdownTableView) listOf(view)
            else if (view is android.view.ViewGroup) (0 until view.childCount).flatMap { tableViews(view.getChildAt(it)) }
            else emptyList()
        compose.runOnIdle {
            val rendered = tableViews(compose.activity.window.decorView).single()
            assertEquals("项目\t结果\n检查\t完成", rendered.contentDescription.toString())
            assertTrue(rendered.measuredHeight > 0)
        }
    }

    @Test fun streamingRevisionKeepsTheReaderOpen() {
        var current by mutableStateOf(plan)
        content { PlanProposalCard(current, "streaming-plan") }
        compose.onNodeWithContentDescription("展开完整计划").performClick()
        compose.onNodeWithTag("plan-reader").assertIsDisplayed()
        compose.runOnIdle { current = current.copy(content = current.content + "\n\nNew tail paragraph.") }
        compose.onNodeWithTag("plan-reader").assertIsDisplayed()
        compose.onNodeWithContentDescription("关闭计划阅读").performClick()
        compose.onNodeWithTag("proposed-plan").assertIsDisplayed()
    }

    @Test fun fastIsTopRightAndPendingCannotToggle() {
        val key = ThreadKey("pc", "store", "chat")
        val model = NativeModel("a", "astra", "6 Astra", "", listOf("low", "high"), "high", false, true, serviceTiers = setOf("fast"))
        var s by mutableStateOf(PocketState(paired = true, online = true,
            selected = ChatEntity(key, "runtime", "desktop", "Chat", "", "", 0, "", false),
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "v", "store", true, emptySet(), setOf("thread/settings/update"),
                capabilityFields = mapOf("thread/settings/update" to setOf("threadId", "serviceTier")))),
            models = listOf(model), model = model.model, modelCatalogState = ModelCatalogState.Ready))
        var toggles = 0
        content { ModelSheet(s, { _, _ -> }, {}, onFast = { toggles++; s = s.copy(fastPending = setOf(key)) }) }
        val fast = compose.onNodeWithTag("composer-fast").fetchSemanticsNode().boundsInRoot
        val title = compose.onNodeWithTag("effort-title", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(fast.center.x > title.center.x)
        compose.onNodeWithTag("composer-fast").performClick().assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, toggles) }
        compose.onNodeWithText("本轮结束后可切换").assertDoesNotExist()
    }
}
