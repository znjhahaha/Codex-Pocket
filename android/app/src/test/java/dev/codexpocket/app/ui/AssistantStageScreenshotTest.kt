package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
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

/** Renders the assistant's stage in each of its states to build/reports/assistant for review. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AssistantStageScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var view: android.view.View

    private fun render(name: String, state: AssistantUiState, theme: String = "light", font: Float = 1f, connected: Boolean = true, then: () -> Unit = {}) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme(theme) {
            view = LocalView.current
            val density = LocalDensity.current
            // Software rendering cannot replay the backdrop graph; glass shows its solid fallback and nothing moves.
            CompositionLocalProvider(LocalPocketGlass provides false, LocalPocketGlassPreferences provides PocketGlassPreferences(false, false, false),
                LocalDensity provides Density(density.density, font)) {
                // A new key per picture: setContent keeps remembered state, and an open sheet would carry over.
                androidx.compose.runtime.key(name) { PocketOverlayHost { AssistantScreen(state, emptyList(), emptyList(), listOf(AssistantWorkspaceChoice("r", "shop", "pocket-shop", true)), {}, { _, _ -> }, {},
                    nowMs = AssistantStageFixtures.At, connected = connected) } }
            }
        } } }
        compose.waitForIdle()
        then()
        capture(name)
    }

    private fun capture(name: String) {
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

    private fun states() = listOf("idle" to AssistantStageFixtures.idle(), "running" to AssistantStageFixtures.running(), "pending" to AssistantStageFixtures.pending(),
        "streaming" to AssistantStageFixtures.streaming(), "offline" to AssistantStageFixtures.offline())

    @Test fun mainStageLight() {
        states().forEach { (name, state) -> render("stage-$name-light", state, connected = name != "offline") }
        compose.onNodeWithTag("assistant-chat-list").assertIsDisplayed()
    }

    @Test fun mainStageDark() {
        states().forEach { (name, state) -> render("stage-$name-dark", state, "dark", connected = name != "offline") }
        compose.onNodeWithTag("assistant-chat-list").assertIsDisplayed()
    }

    @Test fun mainStageNarrowLargeFont() {
        states().forEach { (name, state) -> render("stage-$name-360dp-large-font", state, "dark", 1.4f, connected = name != "offline") }
        render("stage-pending-360dp-large-font-light", AssistantStageFixtures.pending(), font = 1.4f)
        compose.onNodeWithTag("assistant-chat-list").assertIsDisplayed()
    }

    @Test fun expandedCardSheetsAndMenu() {
        render("stage-delivered-expanded", AssistantStageFixtures.delivered())
        render("stage-conflict-expanded-dark", AssistantStageFixtures.delivered(AssistantJobMerge("conflict", AssistantStageFixtures.At, files = listOf("src/login.kt")), false), "dark")
        render("stage-attention-sheet", AssistantStageFixtures.pending()) { compose.onNodeWithTag("assistant-attention").performClick() }
        render("stage-attention-sheet-running-dark", AssistantStageFixtures.running(), "dark") { compose.onNodeWithTag("assistant-attention").performClick() }
        render("stage-menu", AssistantStageFixtures.idle()) { compose.onNodeWithTag("assistant-title-menu").performClick() }
        render("stage-tools-sheet", AssistantStageFixtures.idle()) { compose.onNodeWithTag("assistant-tools").performClick() }
        render("stage-mention", AssistantStageFixtures.running()) { compose.onNodeWithTag(AssistantDraft).performTextInput("@") }
        render("stage-merge-dialog", AssistantStageFixtures.delivered()) { compose.onNodeWithTag("assistant-job-merge:login").performClick() }
    }
}
