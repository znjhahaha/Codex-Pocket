package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.material3.Text
import androidx.compose.ui.platform.testTag
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
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class V04ModelMenuTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun modelState(model: NativeModel, runtimeOnline: Boolean = true): PocketState {
        val key = ThreadKey("pc", "store", "chat")
        return PocketState(paired = true, online = true,
            selected = ChatEntity(key, "runtime", "desktop", "Chat", "", "", 0, "", false),
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "v", "store", runtimeOnline, emptySet(),
                setOf("thread/settings/update"))),
            models = listOf(model), model = model.model, modelCatalogState = ModelCatalogState.Ready)
    }

    @Test fun searchSelectsImmediatelyWithoutConfirmationAndKeepsDraft() {
        val first = NativeModel("a", "alpha", "Alpha", "", listOf("low"), "low", false, true)
        val second = first.copy(id = "b", model = "beta", name = "Beta")
        val others = (1..MODEL_SEARCH_THRESHOLD).map { first.copy(id = "o$it", model = "other-$it", name = "Other $it") }
        var picked: String? = null
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop {
            var open by remember { mutableStateOf(false) }
            var anchor by remember { mutableStateOf<Rect?>(null) }
            var draft by remember { mutableStateOf("keep draft") }
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                PocketComposer(draft, "Alpha", "", false, ComposerAvailability(true, false, true, false),
                    { draft = it }, { open = true }, {}, {}, {}, {}, {}, true,
                    onModelAnchor = { anchor = it }, modelMenuExpanded = open)
            }
            if (open) PocketAnchoredMenu("模型", { open = false }, anchor) {
                ModelSheet(PocketState(online = true, models = listOf(first, second) + others, modelCatalogState = ModelCatalogState.Ready),
                    { model, _ -> picked = model.model; open = false }, {})
            }
        } } } }
        compose.onNodeWithTag("composer-model").performClick()
        compose.onNodeWithTag("composer-input").assertDoesNotExist()
        compose.onNodeWithText("选择模型").assertExists()
        compose.onNodeWithTag("model-search").performTextInput("beta")
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Beta").performClick()
        compose.waitForIdle()
        assertEquals("beta", picked)
        compose.onNodeWithTag("composer-input").assertTextEquals("keep draft")
        compose.onNodeWithTag("model-search").assertDoesNotExist()
    }

    @Test fun modelFirstMenuShowsNativeEffortSliderAndDisablesOfflineEffortChanges() {
        val model = NativeModel("a", "astra", "6 Astra", "", listOf("low", "medium", "high", "xhigh"), "medium", false, true)
        var chosen: String? = null
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            ModelSheet(modelState(model, runtimeOnline = false).copy(effort = "high"),
                { _, _ -> }, {}, onEffort = { _, effort -> chosen = effort })
        } } }
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("high")
        compose.onNodeWithText("6 Astra").assertExists()
        compose.onNodeWithTag("effort-track").assertRangeInfoEquals(ProgressBarRangeInfo(2f, 0f..3f, 2)).assertIsNotEnabled()
        compose.onNodeWithTag("effort-track").assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetProgress))
        compose.onNodeWithTag("composer-effort").assertDoesNotExist()
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithTag("model-picker-open").assertExists()
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertExists()
    }

    @Test fun independentEffortNeverOffersValuesAbsentFromCatalog() {
        val model = NativeModel("a", "astra", "Astra", "", listOf("none", "high", "minimal", "low", "ultra"), "low", false, true)
        var state by mutableStateOf(modelState(model))
        var modelSelections = 0
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            ModelSheet(state, { _, _ -> modelSelections++ }, {},
                onEffort = { _, effort -> state = state.copy(effort = effort) })
        } } }
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("请选择强度")
        compose.onNodeWithTag("effort-track").assertRangeInfoEquals(ProgressBarRangeInfo.Indeterminate)
        compose.onNodeWithTag("effort-default").assertDoesNotExist()
        compose.onNodeWithTag("composer-effort").assertDoesNotExist()
        compose.onNodeWithTag("effort-track").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
        assertEquals("high", state.effort)
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("high")
        compose.onNodeWithTag("effort-track").assertRangeInfoEquals(ProgressBarRangeInfo(1f, 0f..1f))
        // The native catalog skips medium and max; out-of-range progress stays on its high endpoint.
        compose.onNodeWithTag("effort-track").performSemanticsAction(SemanticsActions.SetProgress) { it(4f) }
        assertEquals("high", state.effort)
        compose.onNodeWithTag("effort-track").performSemanticsAction(SemanticsActions.SetProgress) { it(0f) }
        assertEquals("low", state.effort)
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("low")
        compose.onNodeWithTag("effort-track").assertRangeInfoEquals(ProgressBarRangeInfo(0f, 0f..1f))
        assertEquals(0, modelSelections)
    }

    @Test fun sourcePageKeepsDiffMountedAndBackRestoresItsSelection() {
        var disposals = 0
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop {
            var source by remember { mutableStateOf(false) }
            var selectedFile by remember { mutableStateOf("first.kt") }
            DisposableEffect(Unit) { onDispose { disposals++ } }
            Column {
                Text(selectedFile, Modifier.testTag("diff-file"))
                PocketButton("Second file", { selectedFile = "second.kt" })
                PocketButton("Open source", { source = true }, Modifier.testTag("source-trigger"))
            }
            if (source) PocketPageOverlay("Source", { source = false }) {
                Column {
                    Text("Source contents")
                    PocketButton("Back to diff", { source = false })
                }
            }
        } } } }
        compose.onNodeWithText("Second file").performClick()
        compose.onNodeWithTag("source-trigger").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithTag("source-trigger").performClick()
        compose.onNodeWithTag("diff-file").assertDoesNotExist()
        compose.onNodeWithText("Source contents").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        compose.onNodeWithTag("diff-file").assertTextEquals("second.kt")
        assertEquals(0, disposals)
    }

    @Test fun standaloneMenuReceivesRootFileEntryThroughOverlayHost() {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop {
            CompositionLocalProvider(LocalPocketSheetAsScreen provides true,
                LocalPocketScreenHeaderContent provides { PocketButton("项目文件", {}) }) {
                PocketSheet("会话操作", {}) { Text("操作列表") }
            }
        } } } }
        compose.onNodeWithText("项目文件").assertIsDisplayed()
        compose.onNodeWithText("操作列表").assertIsDisplayed()
    }
}
