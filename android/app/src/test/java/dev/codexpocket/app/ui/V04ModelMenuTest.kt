package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
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
        compose.onNodeWithTag("model-picker-entry").performClick()
        compose.onNodeWithTag("model-search").performTextInput("beta")
        compose.onNodeWithText("Alpha").assertDoesNotExist()
        compose.onNodeWithText("Beta").performClick()
        compose.waitForIdle()
        assertEquals("beta", picked)
        compose.onNodeWithTag("composer-input").assertTextEquals("keep draft")
        compose.onNodeWithTag("model-search").assertDoesNotExist()
    }

    @Test fun effortCardShowsLevelModelEntryAndSteppedSlider() {
        val model = NativeModel("a", "astra", "6 Astra", "", listOf("low", "medium", "high", "xhigh"), "medium", false, true)
        var chosen: String? = null
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            ModelSheet(PocketState(online = true, models = listOf(model), modelCatalogState = ModelCatalogState.Ready, effort = "high"),
                { _, _ -> }, {}, onEffort = { _, effort -> chosen = effort })
        } } }
        compose.onNodeWithTag("effort-title").assertTextEquals(compose.activity.effortLabel("high"))
        compose.onNodeWithText("6 Astra").assertExists()
        compose.onNodeWithTag("composer-effort").assert(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.ProgressBarRangeInfo))
        // Effort changes are honest no-ops while the computer is not ready.
        compose.onNodeWithTag("composer-effort").assert(SemanticsMatcher.keyNotDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithTag("model-picker-entry").performClick()
        compose.onNodeWithText("选择模型").assertExists()
        compose.onNodeWithTag("model-list-back").performClick()
        compose.onNodeWithTag("effort-title").assertExists()
    }

    @Test fun independentEffortNeverOffersValuesAbsentFromCatalog() {
        var effort by mutableStateOf<String?>(null)
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") {
            PocketEffortSelector(listOf("low", "high"), effort, true, { effort = it })
        } } }
        compose.onNodeWithTag("effort-high").performClick().assertIsSelected()
        assertEquals("high", effort)
        compose.onNodeWithTag("effort-max").assertDoesNotExist()
        compose.onNodeWithTag("effort-default").performClick().assertIsSelected()
        assertNull(effort)
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
