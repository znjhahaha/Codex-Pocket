package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class HomeSourceAndImageCollapseTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread {
            compose.activity.setContent { PocketTheme("dark") { Surface { block() } } }
        }
        compose.waitForIdle()
    }

    @Test fun viewedImageHidesThumbnailWhenCollapsedAndCanReopen() = imageCollapse("imageView")
    @Test fun generatedImageHidesThumbnailWhenCollapsedAndCanReopen() = imageCollapse("imageGeneration")

    private fun imageCollapse(type: String) {
        val item = TimelineItem.from("turn", buildJsonObject {
            put("id", "image"); put("type", type); put("status", "completed")
            put("path", "D:/work/context-panel.png")
            put("toolOutputBlocks", buildJsonArray { add(buildJsonObject {
                put("type", "image"); put("status", "expired"); put("mediaId", "b".repeat(32))
                put("runtimeId", "runtime"); put("threadId", "thread"); put("turnId", "turn")
                put("itemId", "image"); put("mimeType", "image/png"); put("byteLength", 16)
            }) })
        })
        content {
            NativeActivityTrace(TranscriptRow("image-row", TranscriptKind.Activity, listOf(item), true), "test", false, {}, {})
        }
        val toggle = compose.onNodeWithTag("activity-toggle:${item.key}")
        toggle.performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("tool-media-grid").fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        toggle.performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("tool-media-grid").assertDoesNotExist()
        toggle.performClick()
        compose.onNodeWithTag("tool-media-grid").assertExists()
        compose.onNodeWithContentDescription("图片 1").performClick()
        compose.onNodeWithTag("image-viewer").assertExists()
    }

    @Test fun homeShowsEverySourceWithOnlyASmallCliMark() {
        val base = PocketPreviewStates.chats.first()
        val chats = listOf("cli", "desktop", "vscode").mapIndexed { index, source ->
            base.copy(key = base.key.copy(nativeId = "source-$index"), title = "会话 $source",
                source = source, status = "completed", pinned = false, archived = false)
        }
        content {
            ChatsHome(PocketPreviewStates.home.copy(computerId = base.key.computerId, chats = chats), {}, {}, { _, _, _ -> }, {}, {})
        }
        compose.onNodeWithText("全部来源").assertDoesNotExist()
        compose.onNodeWithText("CLI").assertDoesNotExist()
        compose.onNodeWithText("桌面 / IDE").assertDoesNotExist()
        chats.forEach { compose.onNodeWithTag("home-chat-${it.key.encoded}").assertExists() }
        compose.onAllNodesWithContentDescription("CLI 会话", useUnmergedTree = true).assertCountEquals(1)
        compose.onNodeWithTag("home-cli-${chats.first().key.encoded}", useUnmergedTree = true).assertWidthIsEqualTo(androidx.compose.ui.unit.Dp(14f))
    }
}
