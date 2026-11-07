package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
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
class InlineDiffPreviewUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var density = 1f
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent {
            PocketTheme("light") { CompositionLocalProvider(LocalPocketGlass provides false) {
                density = LocalDensity.current.density
                Surface(Modifier.fillMaxSize()) { ProvideMarkdownRendering { PocketOverlayHost { block() } } }
            } }
        } }
        compose.waitForIdle()
    }
    private fun file() = ActivityFile("preview", "src/Long.kt", "update",
        "@@ -121,80 +121,80 @@\n val line121 = \"" + "wide code ".repeat(30) + "\"\n" +
            (122..200).joinToString("\n") { "-val line$it = 0\n+val line$it = \"" + "wide code ".repeat(30) + "\"" })
    private fun waitLines() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag("diff-gutter:1", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
    }

    @Test fun previewIsBoundedAndOnlyVisibleLinesAreComposed() {
        content { Column { InlineDiffPreview(file()) } }
        waitLines()
        val preview = compose.onNodeWithTag("inline-diff:preview").fetchSemanticsNode().boundsInRoot
        assertTrue(preview.height <= 280f * density + 1f)
        compose.onNodeWithTag("diff-gutter:79", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("diff-lines").performScrollToIndex(79)
        compose.onNodeWithTag("diff-gutter:79", useUnmergedTree = true).assertExists()
    }

    @Test fun horizontalScrollLeavesRealLineNumbersInPlace() {
        lateinit var reading: DiffReadingState
        content { reading = rememberDiffReadingState("fixture"); Column { InlineDiffPreview(file(), readingState = reading) } }
        waitLines()
        val gutter = compose.onNodeWithTag("diff-gutter:1", useUnmergedTree = true)
        val before = gutter.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("diff-code:1", useUnmergedTree = true).performTouchInput { swipeLeft() }
        compose.runOnIdle { assertFalse(reading.wrap); assertTrue(reading.horizontal.value > 0) }
        val after = gutter.fetchSemanticsNode().boundsInRoot
        assertEquals(before.left, after.left, 1f)
        assertEquals(before.right, after.right, 1f)
        compose.onNodeWithText("121  121", useUnmergedTree = true).assertExists()
    }

    @Test fun enlargeAndReturnRetainReadingPositionAndChildHasNoComments() {
        lateinit var reading: DiffReadingState
        content { reading = rememberDiffReadingState("fixture"); Column { InlineDiffPreview(file(), readingState = reading) } }
        waitLines()
        compose.onNodeWithTag("diff-lines").performScrollToIndex(30)
        compose.onNodeWithTag("diff-code:31", useUnmergedTree = true).performTouchInput { swipeLeft() }
        var index = 0
        var x = 0
        compose.runOnIdle { index = reading.list.firstVisibleItemIndex; x = reading.horizontal.value }
        compose.onNodeWithContentDescription("放大文件差异").performClick()
        compose.onNodeWithTag("inline-diff-reader").assertExists()
        compose.onNodeWithTag("diff-gutter:31", useUnmergedTree = true).assertIsNotEnabled()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
        compose.onNodeWithContentDescription("返回行内差异").performClick()
        compose.runOnIdle { assertEquals(index, reading.list.firstVisibleItemIndex); assertEquals(x, reading.horizontal.value) }
    }

    @Test fun typedOldSideCommentStillDeliversToMainDraft() {
        var draft: FileCommentDraft? = null
        val removed = ActivityFile("old", "src/Old.kt", "delete", "@@ -42 +0,0 @@\n-val old = 1")
        content { Column { InlineDiffPreview(removed, onDraftComment = { draft = it }) } }
        waitLines()
        assertTrue(compose.onNodeWithTag("inline-diff:old").fetchSemanticsNode().boundsInRoot.height < 180f * density)
        compose.onNodeWithTag("diff-gutter:1", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Keep this check")
        compose.onNodeWithText("加入草稿").performClick()
        compose.runOnIdle {
            assertEquals(ReviewSide.OLD, draft!!.side)
            assertEquals(42, draft!!.line)
            assertEquals("src/Old.kt", draft!!.path)
        }
    }

    @Test fun binaryFallbackIs280dpAndNeverFabricatesTextLinesOrStats() {
        val binary = ActivityFile("binary", "image.png", "update", "GIT binary patch\nliteral 20\n+encoded data")
        content { Column { InlineDiffPreview(binary, stateKey = "main/turn1/item1/binary") } }
        compose.onNodeWithTag("inline-diff-fallback:Binary").assertExists()
        compose.onNodeWithTag("diff-lines").assertDoesNotExist()
        compose.onNodeWithText("+1 −0").assertDoesNotExist()
        val height = compose.onNodeWithTag("inline-diff:binary").fetchSemanticsNode().boundsInRoot.height
        assertEquals(280f * density, height, 1f)
    }

    @Test fun switchingExplicitScopeDoesNotInheritAnotherItemsScroll() {
        var scope by mutableStateOf("main/turn1/item1/file")
        content { Column { InlineDiffPreview(file(), stateKey = scope) } }
        waitLines()
        compose.onNodeWithTag("diff-lines").performScrollToIndex(30)
        compose.runOnIdle { scope = "child/turn2/item2/file" }
        waitLines()
        compose.onNodeWithTag("diff-gutter:1", useUnmergedTree = true).assertExists()
    }

    @Test fun emptyAndTruncatedFallbacksAreFixedHeightWithoutInventingRows() {
        content { Column {
            InlineDiffPreview(ActivityFile("empty", "empty.kt", "update", ""), stateKey = "main/item/empty")
            InlineDiffPreview(ActivityFile("partial", "partial.kt", "update", "@@ -1 +1 @@\n-old\n+new"),
                stateKey = "main/item/partial", truncated = true)
        } }
        compose.onNodeWithTag("inline-diff-fallback:Empty").assertExists()
        compose.onNodeWithTag("inline-diff-fallback:Truncated").assertExists()
        for (id in listOf("empty", "partial")) {
            assertEquals(280f * density, compose.onNodeWithTag("inline-diff:$id").fetchSemanticsNode().boundsInRoot.height, 1f)
        }
        compose.onNodeWithTag("diff-lines").assertDoesNotExist()
    }
}
