package dev.codexpocket.app.ui

import android.app.Application
import android.graphics.Bitmap
import java.io.File
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
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
class AssistantModelPanelTest {
    @get:Rule val compose=createEmptyComposeRule()
    private lateinit var activity:ActivityController<ComponentActivity>
    private var visible by mutableStateOf(true)
    private var keyboard by mutableStateOf(false)
    private var settings by mutableStateOf(AssistantSettings())
    private val actions=mutableListOf<AssistantAction>()
    private fun content(reducedMotion: Boolean = false, chat: Boolean = false) {
        compose.mainClock.autoAdvance=false
        compose.runOnUiThread {
            activity=Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent { AssistantTestTheme { CompositionLocalProvider(
                LocalPocketGlassPreferences provides PocketGlassPreferences(false,false,!reducedMotion)) {
                // Constrain the overlay host too: IME insets reduce the actual menu viewport.
                Box(Modifier.fillMaxSize().padding(bottom=if(keyboard) 260.dp else 0.dp)) { PocketBackdrop {
                Box(Modifier.fillMaxSize().testTag("available-viewport")) {
                    Box(Modifier.align(Alignment.BottomCenter)) {
                        val models=List(35) { NativeModel("m$it","model-$it","Model $it","",listOf("low","high"),"high",false,false) }
                        if (chat) ModelSheet(PocketState(paired=true,online=true,models=models,modelCatalogState=ModelCatalogState.Ready),{_,_->},{})
                        else AssistantModelControls(AssistantUiState(AssistantSnapshot(enabled=false,runtimeId="scope",scopeId="scope",preparationStatus="idle",settings=settings),computerId="pc"),
                            models,true,visible,{actions+=it()})
                    }
                }
            } } } } }
        }
        settle()
    }
    private fun settle() { repeat(30) {compose.mainClock.advanceTimeBy(16);compose.waitForIdle()} }
    private fun shot(name:String) {
        compose.runOnIdle {
            val decor=activity.get().window.decorView
            val bitmap=Bitmap.createBitmap(decor.width,decor.height,Bitmap.Config.ARGB_8888)
            decor.draw(android.graphics.Canvas(bitmap))
            val file=File("build/assistant-fixtures/$name.png");file.parentFile!!.mkdirs()
            file.outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}
        }
    }
    @After fun close() {if(::activity.isInitialized) compose.runOnUiThread {activity.close()} }

    @Test fun downwardHandleDragRestoresSearchAndScrollAndHidesOutsideAssistant() {
        content()
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        shot("model-panel-expanded")
        compose.onNodeWithTag("model-search").performTextInput("model");settle()
        compose.onNodeWithTag("model-list").performScrollToIndex(15);settle()
        val before=compose.onNodeWithTag("model-row-m14").fetchSemanticsNode().boundsInRoot.top-compose.onNodeWithTag("model-list").fetchSemanticsNode().boundsInRoot.top
        compose.onNodeWithTag("assistant-model-handle").performTouchInput {swipe(center,center+Offset(0f,220f),300)};settle()
        compose.onNodeWithTag("assistant-model-panel").assertDoesNotExist()
        shot("model-panel-ball")
        compose.onNodeWithTag("assistant-model-ball").assertIsDisplayed().performClick();settle()
        compose.onNodeWithTag("model-search").assertTextEquals("model")
        assertEquals(before,compose.onNodeWithTag("model-row-m14").fetchSemanticsNode().boundsInRoot.top-compose.onNodeWithTag("model-list").fetchSemanticsNode().boundsInRoot.top,2f)
        compose.onNodeWithTag("assistant-model-collapse").performClick();settle()
        compose.runOnIdle {visible=false};settle()
        compose.onNodeWithTag("assistant-model-ball").assertDoesNotExist()
    }
    @Test fun listScrollDoesNotCollapseAndBallMovesAboveReducedImeViewport() {
        content()
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        compose.onNodeWithTag("model-list").performTouchInput {swipeUp()};settle()
        compose.onNodeWithTag("assistant-model-panel").assertIsDisplayed()
        compose.onNodeWithTag("assistant-model-ball").assertDoesNotExist()
        compose.onNodeWithTag("assistant-model-collapse").performClick();settle()
        val before=compose.onNodeWithTag("assistant-model-ball").fetchSemanticsNode().boundsInRoot.bottom
        compose.runOnIdle {keyboard=true};settle()
        val after=compose.onNodeWithTag("assistant-model-ball").fetchSemanticsNode().boundsInRoot.bottom
        assertTrue(after<before)
        assertTrue(actions.all {it.method=="assistant/read"})
    }
    @Test fun reducedMotionRetainsCollapseAndRestoreControls() {
        content(reducedMotion=true)
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        compose.onNodeWithTag("assistant-model-collapse").performClick();settle()
        compose.onNodeWithTag("assistant-model-ball").assertIsDisplayed().performClick();settle()
        compose.onNodeWithTag("model-search").assertIsDisplayed()
    }
    @Test fun keyboardViewportKeepsRetryFullyVisibleWhileOnlyModelListShrinks() {
        content()
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        val originalListHeight=rawBounds("model-list").height
        val originalRetryHeight=rawBounds("assistant-model-retry").height
        compose.runOnIdle {keyboard=true};settle()
        listOf("assistant-model-handle","model-search","assistant-model-retry").forEach(::assertFullyInsidePanel)
        assertEquals("Retry keeps its complete touch target",originalRetryHeight,rawBounds("assistant-model-retry").height,2f)
        assertTrue("The catalog takes the remaining height",rawBounds("model-list").height<originalListHeight)
        compose.onNodeWithTag("model-list").performScrollToIndex(30);settle()
        compose.onNodeWithTag("model-row-m29").assertIsDisplayed()
        assertFullyInsidePanel("assistant-model-retry")
        val before=actions.size
        compose.onNodeWithTag("assistant-model-retry").assertIsEnabled().performTouchInput {click(center)};settle()
        assertEquals("Visible retry remains physically clickable",before+1,actions.size)
        assertEquals("assistant/read",actions.last().method)
        shot("model-panel-keyboard-footer")
    }
    private fun rawBounds(tag:String):Rect {
        val node=compose.onNodeWithTag(tag).fetchSemanticsNode()
        return Rect(node.positionInRoot,Size(node.size.width.toFloat(),node.size.height.toFloat()))
    }
    private fun assertFullyInsidePanel(tag:String) {
        compose.onNodeWithTag(tag).assertIsDisplayed()
        val panel=compose.onNodeWithTag("assistant-model-panel").fetchSemanticsNode().boundsInRoot
        val bounds=rawBounds(tag)
        assertTrue("$tag must be fully visible inside the menu: $bounds / $panel",bounds.height>0 && bounds.width>0 &&
            bounds.top>=panel.top-2f && bounds.bottom<=panel.bottom+2f && bounds.left>=panel.left-2f && bounds.right<=panel.right+2f)
    }
    @Test fun ordinaryChatModelListStaysExpandedOnDownwardScroll() {
        content(chat=true)
        compose.onNodeWithTag("model-list").performTouchInput {swipeDown()};settle()
        compose.onNodeWithTag("model-sheet").assertIsDisplayed()
        compose.onNodeWithTag("assistant-model-ball").assertDoesNotExist()
    }
    @Test fun searchAreaDownwardDragCollapsesAndRestoresRoleAndQuery() {
        content()
        compose.onAllNodesWithText("选择模型").assertCountEquals(1)
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        compose.onNodeWithText("工作").performClick();settle()
        compose.onNodeWithTag("model-search").performTextInput("model-2");settle()
        compose.onNodeWithTag("model-search").performTouchInput { swipe(center, center + Offset(0f, 220f), 350) };settle()
        compose.onNodeWithTag("assistant-model-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-model-ball").performClick();settle()
        compose.onNodeWithText("工作").assertIsSelected()
        compose.onNodeWithTag("model-search").assertTextEquals("model-2")
    }

    @Test fun onePanelUsesIndependentModelAndEffortSelectionsForEachRole() {
        settings=AssistantSettings(model="model-1",coordinatorModel="model-2",effort="low",coordinatorEffort="high")
        content()
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        compose.onNodeWithText("Model 2").assertExists()
        compose.onNodeWithTag("assistant-effort-inherit").performClick();settle()
        assertEquals(JsonNull,actions.last().params.obj("patch")["coordinator_effort"])
        assertFalse(actions.last().params.obj("patch").containsKey("effort"))
        compose.onNodeWithText("工作").performClick();settle()
        compose.onNodeWithText("Model 1").assertExists()
        compose.onNodeWithTag("assistant-effort-inherit").performClick();settle()
        assertEquals(JsonNull,actions.last().params.obj("patch")["effort"])
        assertFalse(actions.last().params.obj("patch").containsKey("coordinator_effort"))
        compose.onNodeWithTag("assistant-model-handle").performTouchInput {swipe(center,center+Offset(0f,220f),350)};settle()
        compose.onNodeWithTag("assistant-model-ball").performClick();settle()
        compose.onNodeWithText("工作").assertIsSelected()
        compose.onNodeWithText("Model 1").assertExists()
        compose.runOnIdle {keyboard=true};settle()
        listOf("assistant-model-handle","assistant-model-choose","effort-track","assistant-effort-inherit","assistant-model-retry").forEach(::assertFullyInsidePanel)
    }

    @Test fun downwardListDragCollapsesOnlyWhenGestureStartedAtTop() {
        content()
        compose.onNodeWithTag("assistant-model-open").performClick();settle()
        compose.onNodeWithTag("model-list").performScrollToIndex(14);settle()
        compose.onNodeWithTag("model-list").performTouchInput { swipeDown() };settle()
        compose.onNodeWithTag("assistant-model-panel").assertIsDisplayed()
        compose.onNodeWithTag("model-list").performScrollToIndex(0);settle()
        compose.onNodeWithTag("model-list").performTouchInput { swipe(center, center + Offset(0f, 220f), 350) };settle()
        compose.onNodeWithTag("assistant-model-panel").assertDoesNotExist()
        compose.onNodeWithTag("assistant-model-ball").assertIsDisplayed()
    }
}
