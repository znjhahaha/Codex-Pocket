package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.R
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
class V05ComposerModelUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop { block() } } } }
        compose.waitForIdle()
    }

    @Test fun historyPreviewIsNotAnEditorAndRepeatedExitKeepsMultilineDraft() {
        var reading by mutableStateOf(false)
        var mounted by mutableStateOf(true)
        val draft = "中文第一行\n第二行🙂"
        content {
            if (mounted) PocketComposer(draft, "Model", "", false, ComposerAvailability(true, false, true, false),
                {}, {}, {}, {}, {}, {}, {}, true, readingHistory = reading)
        }
        repeat(12) {
            compose.runOnIdle { reading = true }
            compose.onNodeWithTag("composer-history-preview").assertExists()
            compose.onNodeWithTag("composer-input").assertDoesNotExist()
            compose.runOnIdle { mounted = false }
            compose.waitForIdle()
            compose.runOnIdle { reading = false; mounted = true }
            compose.onNodeWithTag("composer-input").assertTextEquals(draft)
        }
    }

    private fun ready(models: List<NativeModel>, effort: String? = null): PocketState {
        val key = ThreadKey("pc", "store", "chat")
        return PocketState(paired = true, online = true,
            selected = ChatEntity(key, "runtime", "desktop", "Chat", "", "", 0, "", false),
            runtimes = listOf(RuntimeInfo("runtime", "desktop", "v", "store", true, emptySet(),
                setOf("collaborationMode/list", "thread/settings/update"),
                capabilityFields = mapOf("thread/settings/update" to setOf("threadId", "serviceTier")))),
            models = models, model = models.first().model, effort = effort, modelCatalogState = ModelCatalogState.Ready)
    }

    @Test fun historyCapsuleHasRealWidthAndHeightAndExpandsWithoutLosingSelection() {
        var draft by mutableStateOf("first line\nsecond line")
        var reading by mutableStateOf(false)
        lateinit var clearFocus: () -> Unit
        content {
            val manager = LocalFocusManager.current
            clearFocus = { manager.clearFocus(force = true) }
            Box(Modifier.fillMaxWidth()) {
                PocketComposer(draft, "Model", "", false, ComposerAvailability(true, false, true, false),
                    { draft = it }, {}, {}, {}, {}, {}, {}, true, readingHistory = reading, attachmentCount = 3)
            }
        }
        val expandedWidth = compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot.width
        compose.onNodeWithTag("composer-input").performTextInputSelection(TextRange(2, 5))
        compose.runOnIdle { clearFocus(); reading = true }
        compose.waitForIdle()
        compose.onNodeWithTag("composer-surface").assertHeightIsEqualTo(56.dp)
        assertEquals(expandedWidth * .84f, compose.onNodeWithTag("composer-surface").fetchSemanticsNode().boundsInRoot.width, 2f)
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-attachment-count").assertTextEquals("3")
        compose.onNodeWithTag("composer-history-preview").assertTextEquals(draft.replace('\n', ' ')).performClick()
        compose.onNodeWithTag("composer-input").assertIsFocused()
        assertEquals(TextRange(2, 5), compose.onNodeWithTag("composer-input").fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("composer-model").assertExists()
        compose.onNodeWithTag("composer-input").performTextInputSelection(TextRange(2, 5))
        compose.onNodeWithTag("composer-input").performTextInput("X")
        assertEquals("fiX line\nsecond line", draft)
    }

    @Test fun compactRunningTurnOffersStopAndOverflowRestoresQueueAndSteer() {
        var queued = 0
        var steered = 0
        var stopped = 0
        content {
            PocketComposer("direction", "Model", "", true, ComposerAvailability(true, true, true, true),
                {}, {}, {}, {}, { queued++ }, { steered++ }, { stopped++ }, true, readingHistory = true, attachmentCount = 2)
        }
        compose.onNodeWithTag("composer-send").assertDoesNotExist()
        compose.onNodeWithTag("composer-steer").assertDoesNotExist()
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.stop_turn)).performClick()
        assertEquals(1, stopped)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.v02_references)).performClick()
        compose.onNodeWithText("展开输入框").assertExists()
        compose.onNodeWithText("展开输入框").performScrollTo().assertIsDisplayed().performClick()
        compose.onNodeWithTag("composer-expanded-input").assertTextEquals("direction")
        compose.onNodeWithTag("composer-send").performClick()
        compose.onNodeWithTag("composer-steer").performClick()
        assertEquals(1, queued)
        assertEquals(1, steered)
    }

    @Test fun modelMenuLocksHistoryExpansionAndIdleCompactSendRetainsItsMeaning() {
        var open by mutableStateOf(false)
        var sends = 0
        content {
            PocketComposer("draft", "Model", "", false, ComposerAvailability(true, false, true, false),
                {}, {}, {}, {}, { sends++ }, {}, {}, true, readingHistory = true, modelMenuExpanded = open)
        }
        compose.onNodeWithTag("composer-surface").assertHeightIsEqualTo(56.dp)
        compose.onNodeWithTag("composer-send").performClick()
        assertEquals(1, sends)
        compose.runOnIdle { open = true }
        compose.onNodeWithTag("composer-model").assertExists()
        compose.onNodeWithTag("composer-surface").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "多行"))
        compose.runOnIdle { open = false }
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-history-preview").assertTextEquals("draft")
    }

    @Test fun effortChangesKeepSearchAndListScrollWhileModelUsesItsOwnClosingCallback() {
        val models = (0..12).map { NativeModel("id-$it", "provider-$it/astra", "Astra", "", listOf("low", "high"), "low", false, it == 0) }
        var state by mutableStateOf(ready(models))
        var selections = 0
        content {
            ModelSheet(state, { _, _ -> selections++ }, {}, onEffort = { _, value -> state = state.copy(effort = value) })
        }
        compose.onNodeWithTag("model-search").assertDoesNotExist()
        compose.onNodeWithTag("effort-track").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(2f) }
        assertEquals("high", state.effort)
        assertEquals(0, selections)
        compose.onNodeWithTag("model-picker-open").performClick()
        compose.onNodeWithTag("model-row-id-0").assertIsSelected()
        compose.onNodeWithTag("model-list").performScrollToIndex(8)
        val before = compose.onNodeWithTag("model-row-id-8").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithTag("model-list").fetchSemanticsNode().boundsInRoot.top
        compose.runOnIdle { state = state.copy(effort = "low") }
        val after = compose.onNodeWithTag("model-row-id-8").fetchSemanticsNode().boundsInRoot.top -
            compose.onNodeWithTag("model-list").fetchSemanticsNode().boundsInRoot.top
        assertEquals(before, after, 2f)
        compose.onNodeWithTag("model-search").performTextInput("provider-10/")
        compose.onNodeWithTag("model-row-id-10").assertExists().performClick()
        assertEquals(1, selections)
    }

    @Test fun unknownSelectionDoesNotCheckDefaultOrInventSupportedEffort() {
        val model = NativeModel("a", "astra", "Astra", "", listOf("low", "high"), "low", false, true)
        var state by mutableStateOf(ready(listOf(model), "max").copy(model = null))
        content { ModelSheet(state, { _, _ -> }, {}, onEffort = { _, _ -> }) }
        compose.onNodeWithTag("model-row-a").assertIsNotSelected()
        compose.onNodeWithTag("composer-effort").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(model = "missing") }
        compose.onNodeWithTag("model-row-a").assertIsNotSelected()
        compose.onNodeWithTag("composer-effort").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(model = "astra") }
        compose.onNodeWithTag("effort-track").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("当前：max")
    }

    @Test fun unknownEffortHasNoSelectedDefaultUntilNativeDefaultIsKnown() {
        val model = NativeModel("a", "astra", "Astra", "", listOf("low", "high"), "high", false, true)
        var state by mutableStateOf(ready(listOf(model)))
        content { ModelSheet(state, { _, _ -> }, {}) }
        compose.onNodeWithTag("effort-track").assert(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate))
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("请选择强度")
        compose.runOnIdle { state = state.copy(nativeSettings = NativeSettings(effort = NativeSetting(true, null))) }
        compose.onNodeWithTag("effort-track").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "high"))
        compose.onNodeWithTag("effort-title", useUnmergedTree = true).assertTextEquals("high")
    }

    @Test fun planAndFastHonorRuntimeOwnershipAndRunningTurnGates() {
        val model = NativeModel("a", "astra", "Astra", "", emptyList(), "", false, true, serviceTiers = setOf("fast"))
        var state by mutableStateOf(ready(listOf(model)).copy(modes = listOf(NativeMode("Plan", "plan", null, null))))
        var plans = 0
        var fast = 0
        content { ModelSheet(state, { _, _ -> }, { plans++ }, onFast = { fast++ }) }
        compose.onNodeWithTag("composer-plan").performClick()
        compose.onNodeWithTag("composer-fast").performClick()
        assertEquals(1, plans)
        assertEquals(1, fast)
        compose.runOnIdle { state = state.copy(timeline = NativeTimeline(activeTurn = "turn")) }
        compose.onNodeWithTag("composer-plan").assertIsNotEnabled()
        compose.onNodeWithTag("composer-fast").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(timeline = NativeTimeline(), runtimes = state.runtimes.map { it.copy(online = false) }) }
        // Plan hides without catalog capability; Fast keeps its disabled explanatory icon.
        compose.onNodeWithTag("composer-plan").assertDoesNotExist()
        compose.onNodeWithTag("composer-fast").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(paired = false, runtimes = state.runtimes.map { it.copy(online = true) }) }
        compose.onNodeWithTag("composer-plan").assertIsNotEnabled()
        compose.onNodeWithTag("composer-fast").assertIsNotEnabled()
        compose.runOnIdle { state = state.copy(runtimes = state.runtimes.map { it.copy(online = true, capabilities = emptySet()) }) }
        compose.onNodeWithTag("composer-plan").assertDoesNotExist()
        compose.onNodeWithTag("composer-fast").assertIsNotEnabled()
        assertEquals(1, plans)
        assertEquals(1, fast)
    }

    @Test fun effortPillsWrapAtNarrowWidthAndLargeFont() {
        content {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.8f)) {
                Box(Modifier.width(220.dp)) {
                    PocketEffortSelector(listOf("low", "medium", "high", "xhigh", "max", "ultra"), null, true, {})
                }
            }
        }
        compose.onNodeWithTag("effort-default").assertDoesNotExist()
        compose.onNodeWithTag("effort-ultra").assertDoesNotExist()
        val first = compose.onNodeWithTag("effort-low").fetchSemanticsNode().boundsInRoot
        val last = compose.onNodeWithTag("effort-max").fetchSemanticsNode().boundsInRoot
        assertTrue(last.top > first.top)
        val bounds = compose.onNodeWithTag("composer-effort").fetchSemanticsNode().boundsInRoot
        listOf("low", "medium", "high", "xhigh", "max").forEach {
            val pill = compose.onNodeWithTag("effort-$it").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertTrue(pill.left >= bounds.left && pill.right <= bounds.right)
        }
    }
}
