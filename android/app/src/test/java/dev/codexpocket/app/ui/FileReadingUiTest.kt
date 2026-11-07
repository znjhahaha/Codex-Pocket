package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
class FileReadingUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") { CompositionLocalProvider(LocalPocketGlass provides false) { Surface { block() } } }
        } }
        compose.waitForIdle()
    }

    @Test fun browserRespectsReadGateAndDoesNotPresentAnUnloadedDirectoryAsEmpty() {
        var canRead by mutableStateOf(false)
        var listing by mutableStateOf<ProjectFileListing?>(null)
        var opened: String? = null
        content { ProjectFilesScreen(listing, false, null, {}, { opened = it }, {}, canRead = canRead) }
        compose.onNodeWithText("此目录没有可显示的文件").assertDoesNotExist()
        compose.runOnIdle { listing = ProjectFileListing("D:/repo", listOf(ProjectFileEntry("D:/repo/readme.md", "readme.md", false)), roots = listOf("D:/repo")) }
        compose.onNodeWithText("readme.md").assertIsNotEnabled()
        compose.runOnIdle { assertNull(opened); canRead = true }
        compose.onNodeWithText("readme.md").performClick()
        compose.runOnIdle { assertEquals("D:/repo/readme.md", opened) }
    }

    @Test fun sourceJumpTargetsRealLineAndCommentStaysDraftOnly() {
        var draft: FileCommentDraft? = null
        val source = SourcePreview("src/App.kt", (1..100).joinToString("\n") { "val line$it = $it" }, true)
        content { SourceScreen(source, {}, onDraftComment = { draft = it }) }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("第 1 行，添加草稿评论").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("文件超过后端 256 KiB", substring = true).assertExists()
        compose.onNodeWithContentDescription("搜索已加载源码").performClick()
        compose.onAllNodes(hasSetTextAction())[1].performTextInput("80")
        compose.onNodeWithText("跳转", useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("第 80 行，添加草稿评论").performClick()
        compose.onAllNodes(hasSetTextAction()).onLast().performTextInput("Please keep the guard")
        compose.onNodeWithText("加入草稿", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(FileCommentDraft("src/App.kt", 80, ReviewSide.NEW, "Please keep the guard"), draft) }
    }
}
