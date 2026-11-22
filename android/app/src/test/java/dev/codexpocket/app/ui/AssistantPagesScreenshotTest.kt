package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Renders the assistant's pages (tasks, task detail, results, plans, memory, settings) to build/reports/assistant for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantPagesScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View
    private val fx = AssistantStageFixtures

    private fun render(name: String, theme: String, font: Float, state: AssistantUiState = fx.pages(), steps: () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            val density = LocalDensity.current
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false),
                LocalDensity provides Density(density.density, font)) {
                // A new key per picture: setContent keeps remembered state, and an open dialog would carry over.
                androidx.compose.runtime.key(name) {
                    PocketOverlayHost { AssistantScreen(state, fx.models, fx.runtimes, fx.workspaces, {}, { _, _ -> }, {}, nowMs = fx.At) }
                }
            }
        } } }
        compose.waitForIdle()
        steps()
        compose.waitForIdle()
        compose.runOnIdle {
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            java.io.File("build/reports/assistant/$name.png").also { it.parentFile.mkdirs() }.outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
            bitmap.recycle()
        }
    }
    private fun scrollTo(tag: String) {
        compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollToIndex)).onFirst().performScrollToNode(hasTestTag(tag))
        compose.waitForIdle()
    }
    private fun openTask(id: String) { compose.openAssistantPage("Tasks"); scrollTo("assistant-task-open:$id"); compose.onNodeWithTag("assistant-task-open:$id").performClick(); compose.waitForIdle() }

    private fun all(suffix: String, theme: String, font: Float) {
        fun shot(name: String, state: AssistantUiState = fx.pages(), steps: () -> Unit) = render("page-$name-$suffix", theme, font, state, steps)
        shot("tasks") { compose.openAssistantPage("Tasks") }
        shot("tasks-pending") { compose.openAssistantPage("Tasks"); compose.onNodeWithTag("assistant-tasks-pending").performClick() }
        shot("task-overview") { openTask("login") }
        shot("task-process") { openTask("login"); compose.onNodeWithTag("assistant-task-section:Process").performClick() }
        shot("task-artifacts") { openTask("login"); compose.onNodeWithTag("assistant-task-section:Artifacts").performClick() }
        shot("task-actions") { openTask("report"); scrollTo("assistant-task-send:report") }
        shot("task-unknown") { openTask("sync") }
        shot("task-stop-dialog", fx.running()) { openTask("login"); scrollTo("assistant-task-stop:login"); compose.onNodeWithTag("assistant-task-stop:login").performClick() }
        shot("results") { compose.openAssistantPage("Artifacts") }
        shot("artifact-text") { compose.openAssistantPage("Artifacts"); compose.onNodeWithTag("assistant-artifact-open:a-text").performClick() }
        shot("artifact-file") { compose.openAssistantPage("Artifacts"); scrollTo("assistant-artifact-open:a-file"); compose.onNodeWithTag("assistant-artifact-open:a-file").performClick() }
        shot("result") { compose.expandAssistantTask("login"); compose.scrollAssistantChatTo("assistant-result-detail:run-login").performClick() }
        shot("plans") { compose.openAssistantPage("Plans") }
        shot("plans-history") { compose.openAssistantPage("Plans"); compose.onNodeWithTag("assistant-schedule-history:s").performClick() }
        shot("plan-create") { compose.openAssistantPage("Plans"); compose.onNodeWithTag("assistant-schedule-add").performClick() }
        shot("plan-create-choice") {
            compose.openAssistantPage("Plans"); compose.onNodeWithTag("assistant-schedule-add").performClick()
            compose.onNodeWithTag("assistant-schedule-kind").performScrollTo().performClick()
        }
        shot("plan-edit") { compose.openAssistantPage("Plans"); scrollTo("assistant-schedule-edit:s2"); compose.onNodeWithTag("assistant-schedule-edit:s2").performClick() }
        shot("plan-delete") { compose.openAssistantPage("Plans"); scrollTo("assistant-schedule-delete:s"); compose.onNodeWithTag("assistant-schedule-delete:s").performClick() }
        shot("memory") { compose.openAssistantPage("Memory") }
        shot("memory-history") { compose.openAssistantPage("Memory"); compose.onNodeWithTag("assistant-memory-history").performClick() }
        shot("memory-edit") { compose.openAssistantPage("Memory"); scrollTo("assistant-memory-edit:m1"); compose.onNodeWithTag("assistant-memory-edit:m1").performClick() }
        shot("settings-top") { compose.openAssistantPage("Settings") }
        shot("settings-models") { compose.openAssistantPage("Settings"); scrollTo("assistant-coordinator-model"); compose.onNodeWithTag("assistant-coordinator-model").performClick() }
        shot("settings-off-catalogue", fx.pages("retired-model")) { compose.openAssistantPage("Settings"); scrollTo("assistant-coordinator-model-note") }
        shot("settings-bottom") { compose.openAssistantPage("Settings"); scrollTo("assistant-global-pause") }
        shot("settings-disabled", AssistantUiState(AssistantSnapshot(enabled = false), computerId = "pc")) { compose.openAssistantPage("Settings") }
        shot("tasks-empty", fx.idle()) { compose.openAssistantPage("Tasks") }
        shot("memory-empty", fx.idle()) { compose.openAssistantPage("Memory") }
    }

    @Test fun pagesLight() { all("light", "light", 1f); compose.onNodeWithTag("assistant-workspace").assertIsDisplayed() }
    @Test fun pagesDark() { all("dark", "dark", 1f); compose.onNodeWithTag("assistant-workspace").assertIsDisplayed() }
    @Test fun pagesNarrowLargeFont() { all("360dp-large-font", "dark", 1.4f); compose.onNodeWithTag("assistant-workspace").assertIsDisplayed() }
    @Test fun pagesNarrowLargeFontLight() { all("360dp-large-font-light", "light", 1.4f); compose.onNodeWithTag("assistant-workspace").assertIsDisplayed() }
}
