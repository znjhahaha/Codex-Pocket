package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.input.TextFieldValue
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
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
class ReferenceTextTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val skill = buildJsonObject {
        put("type", "skill"); put("name", "internal-review"); put("displayName", "代码审查")
        put("path", "D:/skills/review/SKILL.md"); put("description", "检查实现和测试"); put("source", "工作区")
    }
    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop { block() } } } }
        compose.waitForIdle()
    }
    @Test fun visualOffsetsRoundTripEveryUtf16PositionWithoutChangingComposingText() {
        val first = insertVerifiedReference("中文🙂 /r @a", "中文🙂 /r @a", 5, 7, skill)!!
        val app = JsonObject(skill + ("type" to JsonPrimitive("mention")))
        val start = first.text.indexOf('@')
        val second = insertVerifiedReference(first.text, first.text, start, start + 2, app, first.spans)!!
        val value = TextFieldValue(second.text, TextRange(2, second.text.length), TextRange(0, 2))
        val result = ReferenceVisualTransformation(second.spans, ReferenceBlue).filter(value.annotatedString)
        var previous = -1
        for (offset in 0..value.text.length) {
            val transformed = result.offsetMapping.originalToTransformed(offset)
            assertTrue(transformed >= previous)
            assertEquals(offset, result.offsetMapping.transformedToOriginal(transformed))
            previous = transformed
        }
        previous = -1
        for (offset in 0..result.text.length) {
            val original = result.offsetMapping.transformedToOriginal(offset)
            assertTrue(original >= previous)
            assertTrue(original in 0..value.text.length)
            previous = original
        }
        assertEquals(TextRange(0, 2), value.composition)
        assertEquals(second.text, value.text)
        assertFalse(result.text.text.contains("internal-review"))
        assertEquals(ReferenceBlue, result.text.spanStyles.first().item.color)
    }
    @Test fun unverifiedTokensHaveIdentityOffsetMappingAndNoBlueStyle() {
        val text = AnnotatedString("\$internal-review @internal-app 中文")
        val result = ReferenceVisualTransformation(emptyList(), ReferenceBlue).filter(text)
        assertEquals(text, result.text)
        assertTrue(result.text.spanStyles.isEmpty())
        for (offset in 0..text.length) assertEquals(offset, result.offsetMapping.originalToTransformed(offset))
    }
    @Test fun composerCopiesDisplayNameAndPastedCopyHasNoNewReferenceIdentity() {
        val initial = insertVerifiedReference("/r", "/r", 0, 2, skill)!!
        var draft by mutableStateOf(initial.text)
        var spans by mutableStateOf(initial.spans)
        lateinit var readClipboard: () -> String?
        content {
            val clipboard = LocalClipboardManager.current
            readClipboard = { clipboard.getText()?.text }
            PocketComposer(draft, "Model", "", false, ComposerAvailability(true, false, true, false),
                { next -> spans = rebaseReferenceSpans(draft, next, spans); draft = next },
                {}, {}, {}, {}, {}, {}, true, referenceSpans = spans)
        }
        val input = compose.onNodeWithTag("composer-input")
        input.performTextInputSelection(TextRange(0, 4))
        input.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.CopyText) { it() }
        compose.runOnIdle { assertEquals("代码审查", readClipboard()) }
        input.performTextInputSelection(TextRange(draft.length))
        input.performTextInput("代码审查")
        assertEquals(1, spans.size)
        assertEquals("\$internal-review 代码审查", prepareReferenceInputs(draft, spans).text)
        input.performTextInputSelection(TextRange(0, 4))
        input.performTextInput("普通文本")
        assertTrue(spans.isEmpty())
    }
    @Test fun readonlySentReferenceOpensMetadataWithoutEditableControls() {
        val raw = buildJsonObject { put("content", JsonArray(listOf(skill))) }
        content { SelectionContainer { SentReferenceText("使用 \$internal-review", raw, Modifier.testTag("sent-reference")) } }
        compose.onNodeWithTag("sent-reference").assertTextEquals("使用  代码审查")
        compose.onNodeWithContentDescription("查看代码审查").performClick()
        compose.onNodeWithTag("reference-metadata").assertExists()
        compose.onNodeWithText("检查实现和测试").assertExists()
        compose.onNodeWithText("工作区").assertExists()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(0)
    }
    @Test fun planFocusRequestReopensMultilineEditorAndPreservesSelection() {
        var reading by mutableStateOf(false)
        var focusRequest by mutableIntStateOf(0)
        lateinit var clearFocus: () -> Unit
        content {
            val manager = androidx.compose.ui.platform.LocalFocusManager.current
            clearFocus = { manager.clearFocus(force = true) }
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                PocketComposer("中文第一行\n第二行", "Model", "", false, ComposerAvailability(true, false, true, false),
                    {}, {}, {}, {}, {}, {}, {}, true, readingHistory = reading, focusRequest = focusRequest)
            }
        }
        compose.onNodeWithTag("composer-input").performTextInputSelection(TextRange(1, 4))
        compose.runOnIdle { clearFocus(); reading = true }
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.runOnIdle { focusRequest++ }
        val input = compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals("中文第一行\n第二行")
        assertEquals(TextRange(1, 4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("composer-history-preview").assertDoesNotExist()
        compose.onNodeWithTag("composer-model").assertExists()
    }
    @Test fun immediateFocusThenHistoryGestureCompactsAndStaysCompactAfterGestureEnds() {
        var reading by mutableStateOf(false)
        var scrolling by mutableStateOf(false)
        content {
            PocketComposer("中文\n多行草稿", "Model", "", false, ComposerAvailability(true, false, true, false),
                {}, {}, {}, {}, {}, {}, {}, true, readingHistory = reading, transcriptScrolling = scrolling)
        }
        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        compose.runOnIdle { reading = true; scrolling = true }
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.runOnIdle { scrolling = false }
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-history-preview").performClick()
        compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals("中文\n多行草稿")
    }
    @Test fun realHistorySwipeAfterEnteringAndTappingComposerHidesModelCapsule() {
        content {
            val history = rememberLazyListState(initialFirstVisibleItemIndex = 59)
            val reading by remember { derivedStateOf { history.canScrollForward } }
            Column(Modifier.fillMaxSize()) {
                LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("reference-history"), state = history) {
                    items(60) { Text("历史消息 $it", Modifier.fillMaxWidth().height(100.dp)) }
                }
                PocketComposer("中文\n多行草稿", "Model", "", false, ComposerAvailability(true, false, true, false),
                    {}, {}, {}, {}, {}, {}, {}, true, readingHistory = reading, transcriptScrolling = history.isScrollInProgress)
            }
        }
        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        compose.onNodeWithTag("reference-history").performTouchInput { swipeDown() }
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
    }
    @Test fun delayedHistoryThresholdAfterGestureEndsCannotRetainComposerFocus() {
        var reading by mutableStateOf(false)
        var scrolling by mutableStateOf(false)
        content {
            PocketComposer("中文\n多行草稿", "Model", "", false, ComposerAvailability(true, false, true, false),
                {}, {}, {}, {}, {}, {}, {}, true, readingHistory = reading, transcriptScrolling = scrolling)
        }
        compose.onNodeWithTag("composer-input").performClick().assertIsFocused()
        compose.runOnIdle { scrolling = true }
        compose.onNodeWithTag("composer-model").assertExists()
        compose.runOnIdle { scrolling = false }
        compose.onNodeWithTag("composer-input").assertIsFocused()
        compose.runOnIdle { reading = true }
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.waitForIdle()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
    }
    @Test fun quickReferenceMetadataCloseCannotRestoreAHistoryEditingLock() {
        val initial = insertVerifiedReference("/r", "/r", 0, 2, skill)!!
        var reading by mutableStateOf(false)
        content {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                PocketComposer(initial.text, "Model", "", false, ComposerAvailability(true, false, true, false),
                    {}, {}, {}, {}, {}, {}, {}, true, readingHistory = reading, referenceSpans = initial.spans)
            }
        }
        val input = compose.onNodeWithTag("composer-input")
        input.performTextInputSelection(TextRange(2))
        val layouts = mutableListOf<TextLayoutResult>()
        input.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val iconCenter = layouts.single().getBoundingBox(0).center
        input.performTouchInput { click(iconCenter) }
        compose.onNodeWithTag("reference-metadata").assertExists()
        // Close while still reading history and before the exit animation restores the old focused child.
        compose.runOnIdle { reading = true }
        compose.onNodeWithTag("pocket-modal").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.Dismiss) { it() }
        compose.onNodeWithTag("reference-metadata").assertDoesNotExist()
        compose.onNodeWithTag("composer-history-preview").assertExists()
        compose.onNodeWithTag("composer-model").assertDoesNotExist()
        compose.onNodeWithTag("composer-history-preview").performClick()
        compose.onNodeWithTag("composer-input").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(initial.text)))
            .assertTextEquals("\u2003代码审查 ")
    }
}
