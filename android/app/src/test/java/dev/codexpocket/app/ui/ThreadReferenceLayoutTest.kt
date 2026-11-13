package dev.codexpocket.app.ui

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35], qualifiers = "w360dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThreadReferenceLayoutTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var activity: ActivityController<ComponentActivity>
    private val title = "重设计助理界面并默认 CLI 静默启动"
    private val url = "codex://threads/01a1ca02-c5db-74a0-a1d5-cf3ef5eb0473"
    private val suffix = "继续完成任务"
    private val message get() = "[$title]($url) $suffix"

    private fun content(block: @Composable () -> Unit) {
        compose.runOnUiThread {
            activity = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            activity.get().setContent {
                PocketTheme("dark") {
                    CompositionLocalProvider(LocalPocketGlass provides false) { PocketBackdrop { block() } }
                }
            }
        }
        compose.waitForIdle()
    }

    @After fun close() { if (::activity.isInitialized) compose.runOnUiThread { activity.close() } }

    // Read the actually drawn glyph's layout, not a parent's replacement placeholder.
    private fun glyphBaseline(needle: String, index: Int): Float {
        val matches = mutableListOf<Float>()
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true)
            .fetchSemanticsNodes()
        compose.runOnIdle {
            nodes.forEach { node ->
                val layouts = mutableListOf<TextLayoutResult>()
                node.config[SemanticsActions.GetTextLayoutResult].action!!.invoke(layouts)
                layouts.forEach { layout ->
                    val start = layout.layoutInput.text.text.indexOf(needle)
                    val offset = start + index
                    if (start >= 0 && layout.layoutInput.placeholders.none { offset in it.start until it.end }) {
                        matches += node.positionInRoot.y + layout.getLineBaseline(layout.getLineForOffset(offset))
                    }
                }
            }
        }
        assertEquals("Expected one drawn glyph for $needle[$index]", 1, matches.size)
        return matches.single()
    }

    @Test fun screenshotTextFlowsInReadingOrderAtNarrowWidthsAndLargeFont() {
        var width by mutableStateOf(300.dp)
        var fontScale by mutableFloatStateOf(1f)
        content {
            val baseDensity = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(baseDensity.density, fontScale)) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(start = 20.dp, top = 32.dp)) {
                    Surface(Modifier.width(width).testTag("link-bubble"), shape = RoundedCornerShape(16.dp), color = assistantPalette().outgoing) {
                        SelectionContainer { ReferenceText(message, emptyList(), Modifier.padding(14.dp).testTag("link-prose")) }
                    }
                }
            }
        }
        val output = java.io.File(System.getProperty("user.dir"), "build/outputs/thread-link-layout").apply { mkdirs() }
        val diagnostics = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((widthDp, scale) in listOf(300 to 1f, 240 to 1f, 300 to 1.3f)) {
            compose.runOnIdle { width = widthDp.dp; fontScale = scale }
            compose.waitForIdle()
            val titleLast = glyphBaseline(title, title.lastIndex)
            val followingFirst = glyphBaseline(suffix, 0)
            val detail = "bubble=${widthDp}dp inner=${widthDp - 28}dp fontScale=$scale titleLastBaseline=$titleLast followingFirstBaseline=$followingFirst"
            diagnostics += detail
            if (followingFirst + 1f < titleLast) failures += detail
            // PixelCopy capture can time out under Robolectric; draw the actual native
            // content view instead. This fixture has no GPU backdrop/optics.
            lateinit var bitmap: android.graphics.Bitmap
            compose.runOnIdle {
                val view = activity.get().findViewById<android.view.View>(android.R.id.content)
                bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(bitmap))
            }
            java.io.File(output, "phone-$widthDp-font-${(scale * 100).toInt()}.png").outputStream().use {
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        }
        java.io.File(output, "baselines.txt").writeText(diagnostics.joinToString("\n"))
        assertTrue("Following prose must not precede the link title's last line: $failures", failures.isEmpty())
    }

    @Test fun wrappedTitleAndIconOpenTheSameCopyMenu() {
        lateinit var readClipboard: () -> String?
        content {
            val clipboard = LocalClipboardManager.current
            readClipboard = { clipboard.getText()?.text }
            Box(Modifier.width(240.dp)) {
                SelectionContainer { ReferenceText(message, emptyList(), Modifier.padding(14.dp).testTag("link-prose")) }
            }
        }
        val prose = compose.onNodeWithTag("link-prose")
        val layouts = mutableListOf<TextLayoutResult>()
        prose.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val offset = layout.layoutInput.text.text.indexOf(title) + title.lastIndex
        prose.performTouchInput { click(layout.getBoundingBox(offset).center) }
        compose.onNodeWithTag("thread-reference-menu").assertExists()
        compose.onNodeWithTag("thread-reference-open").assertIsNotEnabled()
        compose.onNodeWithTag("thread-reference-copy").performClick()
        compose.runOnIdle { assertEquals(url, readClipboard()) }
        compose.onNodeWithContentDescription("会话链接", useUnmergedTree = true).performClick()
        compose.onNodeWithTag("thread-reference-menu").assertExists()
        compose.onNodeWithTag("thread-reference-copy").performClick()
        compose.runOnIdle { assertEquals(url, readClipboard()) }
    }

    @Test fun codeAndFileExamplesStayLiteralAndHaveNoThreadMenu() {
        val literal = "`[$title]($url)`\n```text\n[$title]($url)\n```\n[文件](D:/project/${url.substringAfterLast('/')})"
        content { ReferenceText(literal, emptyList(), Modifier.width(300.dp).testTag("literal-prose")) }
        compose.onNodeWithTag("literal-prose").assertTextEquals(literal)
        compose.onAllNodesWithContentDescription("会话链接", useUnmergedTree = true).assertCountEquals(0)
        compose.onAllNodesWithTag("thread-reference-menu").assertCountEquals(0)
    }
}
