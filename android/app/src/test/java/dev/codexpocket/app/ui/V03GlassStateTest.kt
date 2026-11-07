package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
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
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V03GlassStateTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { block() } } }
        compose.waitForIdle()
    }
    @Test fun failedAndOfflineCatalogNeverOfferCachedDefaultAsSuccess() {
        var status by mutableStateOf(ModelCatalogState.Failed)
        var retries = 0
        var selections = 0
        val model = NativeModel("id", "codex", "Cached default", "", emptyList(), "", false, true)
        content { ModelSheet(PocketState(online = true, models = listOf(model), modelCatalogState = status),
            { _, _ -> selections++ }, {}, { retries++ }) }
        compose.onNodeWithText("模型目录读取失败").assertExists()
        compose.onNodeWithText("Cached default").assertDoesNotExist()
        compose.onNodeWithText("重新读取").performClick()
        assertEquals(1, retries); assertEquals(0, selections)
        compose.runOnIdle { status = ModelCatalogState.Offline }
        compose.onNodeWithText("电脑离线，连接后可重新读取模型").assertExists()
        compose.onNodeWithText("Cached default").assertDoesNotExist()
        compose.runOnIdle { status = ModelCatalogState.Ready }
        // Ready: the effort card names the current model; its chevron opens the list where a model is picked.
        compose.onNodeWithText("Cached default").assertExists()
        compose.onNodeWithTag("model-picker-entry").performClick()
        compose.onNodeWithText("Cached default").performClick()
        assertEquals(1, selections)
    }
    @Test fun everyNonReadyCatalogStateHasDistinctHonestText() {
        var status by mutableStateOf(ModelCatalogState.Idle)
        content { ModelSheet(PocketState(online = true, modelCatalogState = status), { _, _ -> fail("No model may be selected") }, {}) }
        val labels = mapOf(
            ModelCatalogState.Idle to "尚未读取模型目录",
            ModelCatalogState.Loading to "正在读取模型目录",
            ModelCatalogState.Empty to "当前运行时未返回可用模型",
            ModelCatalogState.Unsupported to "当前运行时不支持模型目录",
            ModelCatalogState.Offline to "电脑离线，连接后可重新读取模型",
            ModelCatalogState.Failed to "模型目录读取失败",
        )
        labels.forEach { (value, label) ->
            compose.runOnIdle { status = value }
            compose.onNodeWithText(label).assertExists()
        }
    }
    @Test fun reducedMotionKeepsTouchActionWithoutElasticDisplacement() {
        var clicks = 0
        content {
            CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false)) {
                PocketGlassSurface(Modifier.size(120.dp, 60.dp).testTag("reduced-control"), backdrop = null, onClick = { clicks++ }) { Text("Action") }
            }
        }
        val node = compose.onNodeWithTag("reduced-control")
        val before = node.fetchSemanticsNode().boundsInRoot
        node.performTouchInput { down(center) }
        compose.mainClock.advanceTimeBy(200)
        assertEquals(before, node.fetchSemanticsNode().boundsInRoot)
        node.performTouchInput { up() }
        assertEquals(1, clicks)
    }
    @Test fun unknownProgressDoesNotClaimAMeasuredFraction() {
        content {
            CompositionLocalProvider(LocalPocketGlassPreferences provides PocketGlassPreferences(false, true, false)) {
                Column {
                    PocketProgressStatus("连接电脑", Modifier.testTag("unknown"))
                    PocketProgressStatus("上传附件", Modifier.testTag("measured"), .42f)
                }
            }
        }
        compose.onNodeWithTag("unknown").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithTag("measured").assertRangeInfoEquals(ProgressBarRangeInfo(.42f, 0f..1f))
        compose.onNodeWithText("42%").assertExists()
    }
}
