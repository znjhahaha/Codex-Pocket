package dev.codexpocket.app.ui

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35], qualifiers="w320dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantUnknownStatusLayoutTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val actions=mutableListOf<AssistantAction>()
    private val snapshot=AssistantSnapshot(enabled=true,ready=false,runtimeId="assistant",
        errorCode="service_reconciliation_required",preparationStatus="recoverable_error",
        operations=listOf(AssistantOperation("original","Coordinator",null,AssistantOperationPhase.Completed,false,"thread",null,"Original result")),
        recoveries=listOf(AssistantRecoveryRequest("original","n:1")))
    private fun unknown()=AssistantUiState(snapshot,error="原操作结果尚未确认，正在核对；不会重复提交",
        unconfirmedActions=setOf("assistant/respond"))
    private fun content(state:()->AssistantUiState,fontScale:Float=1f,theme:String="light") {
        compose.activity.runOnUiThread {compose.activity.setContent {PocketTheme(theme) {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides false,LocalDensity provides Density(density.density,fontScale)) {
                AssistantScreen(state(),emptyList(),emptyList(),emptyList(),actions::add,{_,_->},{})
            }
        }}}
        compose.waitForIdle()
    }
    private fun assertCompactStatus(maxLines:Int) {
        val status=compose.onNodeWithTag("assistant-unconfirmed-status").assertIsDisplayed().assertTextEquals("原操作结果待确认")
        val layouts=mutableListOf<TextLayoutResult>()
        status.performSemanticsAction(SemanticsActions.GetTextLayoutResult) {assertTrue(it(layouts))}
        val layout=layouts.single()
        assertTrue("Unknown status should use at most $maxLines lines, got ${layout.lineCount}",layout.lineCount<=maxLines)
        assertFalse(layout.hasVisualOverflow)
        val width=compose.onNodeWithTag("assistant-screen").fetchSemanticsNode().boundsInRoot.width
        assertTrue("Status must retain full row width",status.fetchSemanticsNode().boundsInRoot.width>=width*0.78f)
        compose.onNodeWithTag("assistant-error").assertDoesNotExist()
        compose.onAllNodesWithText("重试准备").assertCountEquals(1)
        compose.onNodeWithTag("assistant-prepare").assertExists()
        compose.onNodeWithTag("assistant-send").assertIsNotEnabled()
        assertTrue(actions.isEmpty())
    }
    private fun assertRecoveryStillFenced() {
        compose.onNodeWithTag("assistant-chat-list").performScrollToNode(hasTestTag("assistant-recovery-end:original:n:1"))
        compose.onNodeWithTag("assistant-recovery-end:original:n:1").assertIsNotEnabled().assertTextEquals("等待原操作结果")
        compose.onNodeWithText("电脑保留了一项尚未结束的请求。结束原请求并核对结果后，可以继续对话；不会重新执行。").assertExists()
        compose.onNodeWithText("结果尚未确认，不能重复发送").assertExists()
        assertTrue(actions.isEmpty())
    }
    private fun screenshot(name:String) {
        lateinit var bitmap:Bitmap
        compose.runOnIdle {
            val view=compose.activity.window.decorView
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
        }
        val file=File("build/assistant-fixtures/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use {assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
    }
    @Test fun narrowLargeFontUnknownUsesFullWidthWithoutPreparationRetry() {
        content({unknown()},fontScale=2f,theme="dark")
        assertCompactStatus(2)
        screenshot("unknown-320dp-font2-dark")
        assertRecoveryStillFenced()
    }
    @Test @Config(qualifiers="w420dp-h800dp-xxhdpi")
    fun normalFontUnknownRemainsCompactAndRecoveryDisabled() {
        content({unknown()})
        assertCompactStatus(1)
        screenshot("unknown-420dp-font1-light")
        assertRecoveryStillFenced()
    }
    @Test fun refreshedPreparationErrorCannotReplaceUnconfirmedStatus() {
        var state by mutableStateOf(unknown())
        content({state},fontScale=2f,theme="dark")
        compose.runOnIdle {state=state.copy(snapshot=snapshot.copy(errorCode="assistant_account_unavailable"),error=snapshot.preparationIssue)}
        assertCompactStatus(2)
        assertRecoveryStillFenced()
    }
    @Test fun independentErrorRetainsExistingPreparationAction() {
        content({AssistantUiState(snapshot,error="电脑连接错误")})
        compose.onNodeWithTag("assistant-unconfirmed-status").assertDoesNotExist()
        compose.onNodeWithTag("assistant-error").assertTextEquals("电脑连接错误")
        compose.onAllNodesWithText("重试准备").assertCountEquals(2)
        assertTrue(actions.isEmpty())
    }
}
