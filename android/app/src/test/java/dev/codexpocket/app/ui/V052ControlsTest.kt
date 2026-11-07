package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V052ControlsTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") { CompositionLocalProvider(LocalPocketGlass provides false) {
                PocketBackdrop { ProvideMarkdownRendering { Box(Modifier.fillMaxSize()) { block() } } }
            } }
        } }
        compose.waitForIdle()
    }

    @Test fun planChoiceExecutesImmediatelyButFeedbackOnlyOpensComposer() {
        var executions = 0
        var edits = 0
        var reason by mutableStateOf<String?>(null)
        content { PlanConfirmation(ProposedPlan("t", "p", "# Plan"), reason, { executions++; reason = "正在提交" }, { edits++ }) }
        compose.onNodeWithText("提出修改…").performClick()
        compose.runOnIdle { assertEquals(1, edits); assertEquals(0, executions) }
        compose.onNodeWithContentDescription("返回方案确认").performClick()
        compose.onNodeWithTag("plan-implement").performClick()
        compose.onNodeWithTag("plan-implement").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, executions) }
    }

    @Test fun executionChecklistNeverOffersImplementationActions() {
        val item = TimelineItem("t", "p", "turn/plan/updated", raw = buildJsonObject { put("params", buildJsonObject {
            put("plan", buildJsonArray { add(buildJsonObject { put("step", "验证读取链路"); put("status", "completed") }) })
        }) })
        content { NativeActivityCard(item, "fixture", true, {}, {}, { fail("Execution step cannot execute a proposal") }) }
        compose.onNodeWithText("验证读取链路").assertIsDisplayed()
        compose.onNodeWithText("已完成").assertDoesNotExist()
        compose.onNodeWithText("按计划继续").assertDoesNotExist()
        compose.onNodeWithText("修改计划").assertDoesNotExist()
    }

    @Test fun contextDefaultsToCompactCountsAndExactDetailsAreExpandable() {
        val usage = buildJsonObject {
            put("last", buildJsonObject { put("totalTokens", 325955); put("inputTokens", 325295); put("outputTokens", 660) })
            put("total", buildJsonObject { put("totalTokens", 248165491) }); put("modelContextWindow", 427500)
        }
        content { ContextIndicator(PocketState(advanced = AdvancedConversationState(usage = usage),
            contextSnapshot = ContextSnapshot(usage, "ok", "native", 1)), {}) }
        compose.onNodeWithTag("context-indicator").performClick()
        compose.onNodeWithText("326.0k / 427.5k tokens").assertIsDisplayed()
        compose.onNodeWithText("累计使用 248165491 tokens").assertDoesNotExist()
        compose.onNodeWithTag("context-details-toggle").performClick()
        compose.onNodeWithText("累计使用 248165491 tokens").assertIsDisplayed()
        compose.onNodeWithText("最近一轮 325955 tokens").assertIsDisplayed()
    }

    @Test fun approvalOnlyOffersAdvertisedDecisionsAndDoesNotRepeat() {
        val a = PendingApproval("r", JsonPrimitive("id"), "item/commandExecution/requestApproval", buildJsonObject {
            put("threadId", "t"); put("command", "read-only-command --long-path"); put("cwd", "D:/fixture")
            put("availableDecisions", buildJsonArray { add("accept"); add("decline") })
        })
        val responses = mutableListOf<String>()
        content { ApprovalCard(a, true) { _, result -> responses += result.string("decision") } }
        compose.onNodeWithText("本会话允许").assertDoesNotExist()
        compose.onNodeWithText("取消任务").assertDoesNotExist()
        compose.onNodeWithText("允许一次").performClick()
        compose.onNodeWithText("允许一次").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("accept"), responses) }
    }
}
