package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
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
class ComposerSuggestionsUiTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun content(block: @Composable () -> Unit) {
        compose.activity.runOnUiThread { compose.activity.setContent { PocketTheme("light") { PocketBackdrop { block() } } } }
        compose.waitForIdle()
    }

    private val skill = buildJsonObject {
        put("type", "skill"); put("name", "review"); put("displayName", "代码审查")
        put("path", "skills/review/SKILL.md"); put("description", "检查实现和测试"); put("source", "工作区")
    }

    @Test fun typingSlashShowsInlinePaletteWithoutExecutingOrSending() {
        var draft by mutableStateOf("")
        var state by mutableStateOf(ComposerSuggestionsState(online = true, actions = setOf(ComposerAction.Plan)))
        var actions = 0
        var sends = 0
        content {
            Box(Modifier.fillMaxSize(), contentAlignment = androidx.compose.ui.Alignment.BottomCenter) {
                PocketComposer(draft, "Model", "high", false, ComposerAvailability(true, false, true, false),
                    { draft = it }, {}, {}, {}, { sends++ }, {}, {}, true,
                    suggestions = state, onQuery = { state = state.copy(trigger = it) }, onSelectAction = { actions++ })
            }
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInput("/pla")
        compose.onNodeWithTag("composer-suggestions").assertIsDisplayed()
        compose.onNodeWithText("/plan").assertExists()
        compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals("/pla")
        compose.onNodeWithTag("composer-model").assertExists()
        compose.onNodeWithTag("composer-expanded-input").assertDoesNotExist()
        assertEquals(0, actions)
        assertEquals(0, sends)
        compose.onNodeWithText("/plan").performClick()
        assertEquals(1, actions)
        assertEquals(0, sends)
        assertEquals("", draft)
        compose.onNodeWithTag("composer-input").assertIsFocused()
    }

    @Test fun selectingSkillUsesParentReplacementAndKeepsSuffixAndCaret() {
        var draft by mutableStateOf("/rev keep the suffix")
        var state by mutableStateOf(ComposerSuggestionsState(online = true))
        var spans by mutableStateOf<List<ReferenceSpan>>(emptyList())
        var selections = 0
        var textOnlyWrites = 0
        content {
            PocketComposer(draft, "Model", "", false, ComposerAvailability(true, false, true, false),
                { next -> textOnlyWrites++; spans = rebaseReferenceSpans(draft, next, spans); draft = next }, {}, {}, {}, {}, {}, {}, true, suggestions = state,
                onQuery = { state = state.copy(trigger = it, data = listOf(skill)) },
                onReference = { value, expectedText, start, endExclusive ->
                    assertEquals(skill, value)
                    selections++
                    assertEquals(draft, expectedText)
                    val inserted = insertVerifiedReference(draft, expectedText, start, endExclusive, value, spans)!!
                    spans = inserted.spans
                    draft = inserted.text
                    true
                }, referenceSpans = spans)
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInputSelection(TextRange(4))
        compose.onNodeWithText("工作区").assertExists()
        compose.onNodeWithText("检查实现和测试").assertExists()
        compose.onNodeWithText("代码审查").performClick()
        assertEquals(1, selections)
        assertEquals(0, textOnlyWrites)
        assertEquals("代码审查 keep the suffix", draft)
        val restored = restoreReferenceDraft(draft, encodeReferenceSpans(spans))
        assertEquals(listOf(ReferenceSpan(0, 4, "代码审查", skill)), restored.spans)
        val prepared = prepareReferenceInputs(restored.text, restored.spans)
        assertEquals("\$review keep the suffix", prepared.text)
        assertEquals(listOf(buildJsonObject {
            put("type", "skill"); put("name", "review"); put("path", "skills/review/SKILL.md")
        }), prepared.references)
        val input = compose.onNodeWithTag("composer-input").assertIsFocused()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString(draft)))
        input.assertTextEquals("\u2003代码审查 keep the suffix")
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        compose.onNodeWithTag("composer-suggestions").assertDoesNotExist()
    }

    @Test fun rejectedReferenceSelectionLeavesDraftAndSelectionIntact() {
        var state by mutableStateOf(ComposerSuggestionsState(online = true))
        var changes = 0
        var references = 0
        content {
            PocketComposer("/rev suffix", "Model", "", false, ComposerAvailability(true, false, true, false),
                { changes++ }, {}, {}, {}, {}, {}, {}, true, suggestions = state,
                onQuery = { state = state.copy(trigger = it, data = listOf(skill)) },
                onReference = { _, _, _, _ -> references++; false })
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInputSelection(TextRange(4))
        compose.onNodeWithText("代码审查").performClick()
        assertEquals(1, references)
        assertEquals(0, changes)
        val input = compose.onNodeWithTag("composer-input").assertTextEquals("/rev suffix").assertIsFocused()
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    @Test fun offlineLoadingAndErrorStatesDoNotOfferStaleReferences() {
        val trigger = composerTrigger("@", 1)!!
        var state by mutableStateOf(ComposerSuggestionsState(trigger, false, data = listOf(skill)))
        content { ComposerSuggestions(trigger, state, 240.dp, {}, {}) }
        compose.onNodeWithText("连接 Codex 后可用").assertExists()
        compose.onNodeWithText("代码审查").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(online = true, loading = true) }
        compose.onNodeWithText("正在查找引用…").assertExists()
        compose.onNodeWithText("代码审查").assertDoesNotExist()
        compose.runOnIdle { state = state.copy(loading = false, error = "读取引用失败") }
        compose.onNodeWithText("读取引用失败").assertExists()
        compose.onNodeWithText("代码审查").assertDoesNotExist()
    }

    @Test fun dismissKeepsEditorFocusAndSelectionAndDoesNotRewriteTheDraft() {
        var state by mutableStateOf(ComposerSuggestionsState(online = true))
        var query: ComposerTrigger? = null
        content {
            PocketComposer("@rea suffix", "Model", "", false, ComposerAvailability(true, false, true, false),
                {}, {}, {}, {}, {}, {}, {}, true, suggestions = state,
                onQuery = { query = it; state = state.copy(trigger = it) })
        }
        compose.onNodeWithTag("composer-input").performClick().performTextInputSelection(TextRange(4))
        compose.waitForIdle()
        assertEquals("rea", query?.query)
        compose.onNodeWithContentDescription("关闭建议").performClick()
        compose.onNodeWithTag("composer-suggestions").assertDoesNotExist()
        val input = compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals("@rea suffix")
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertNull(query)
    }

    @Test fun paletteIsBoundedAndRowsHaveATouchTarget() {
        val trigger = composerTrigger("@", 1)!!
        val data = (0..30).map { buildJsonObject { put("type", "file"); put("name", "file-$it"); put("path", "src/file-$it.kt") } }
        content { ComposerSuggestions(trigger, ComposerSuggestionsState(trigger, true, data = data), 160.dp, {}, {}) }
        compose.onNodeWithTag("composer-suggestions").assertHeightIsEqualTo(160.dp)
        compose.onNodeWithTag("composer-suggestion-Files:src/file-0.kt:file-0").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("composer-suggestion-list").performScrollToIndex(20)
    }

    @Test fun replacingAComposingTokenCommitsOnlyThatToken() {
        val value = TextFieldValue("@rea suffix", TextRange(4), TextRange(1, 4))
        val reference = buildJsonObject { put("type", "file"); put("name", "README.md"); put("path", "src/README.md") }
        val result = replaceComposerEditor(value, composerTrigger(value.text, 4)!!, referenceDisplayName(reference)!!)!!
        assertEquals("README.md suffix", result.text)
        assertEquals(TextRange(9), result.selection)
        assertNull(result.composition)
        assertEquals(TextRange(1, 4), value.composition)
        val inserted = insertVerifiedReference(value.text, value.text, 0, 4, reference)!!
        val prepared = prepareReferenceInputs(result.text, inserted.spans)
        assertEquals("@src/README.md suffix", prepared.text)
        assertTrue(prepared.references.isEmpty())
    }

    @Test fun localCaretUsesTheSameReferenceEditIncludingItsTrailingSpace() {
        val value = TextFieldValue("prefix @src", TextRange(11))
        val trigger = composerTrigger(value.text, 11)!!
        val reference = buildJsonObject { put("type", "directory"); put("name", "源代码🙂"); put("path", "src/ui") }
        val expected = insertVerifiedReference(value.text, value.text, 7, 11, reference)!!
        val result = replaceComposerEditor(value, trigger, referenceDisplayName(reference)!!)!!
        assertEquals(expected.text, result.text)
        assertEquals(TextRange(expected.caret), result.selection)
        assertEquals("prefix 源代码🙂 ", result.text)
        assertEquals(TextRange(13), result.selection)
        assertEquals("prefix @src/ui ", prepareReferenceInputs(result.text, expected.spans).text)
    }
    @Test fun acceptingReferencePreservesUnrelatedChineseCompositionAndRebasesIt() {
        val value = TextFieldValue("@rea 中文", TextRange(4), TextRange(5, 7))
        val result = replaceComposerEditor(value, composerTrigger(value.text, 4)!!, "代码审查技能")!!
        assertEquals("代码审查技能 中文", result.text)
        assertEquals(TextRange(6), result.selection)
        assertEquals(TextRange(7, 9), result.composition)
    }
    @Test fun selectedCopyDeletionHasAnActualEditRangeRatherThanAPrefixGuess() {
        val before = TextFieldValue("review review", TextRange(0, 7))
        val after = TextFieldValue("review", TextRange(0))
        val reference = JsonObject(skill - "displayName")
        val spans = listOf(ReferenceSpan(0, 6, "review", reference))
        val edit = composerReferenceEdit(before, after)
        assertEquals(ReferenceTextEdit(0, 7, ""), edit)
        assertTrue(rebaseReferenceSpans(before.text, after.text, spans, edit).isEmpty())
        val deletingPlain = composerReferenceEdit(before.copy(selection = TextRange(6, 13)), after.copy(selection = TextRange(6)))
        assertEquals(ReferenceTextEdit(6, 13, ""), deletingPlain)
        assertEquals(spans, rebaseReferenceSpans(before.text, after.text, spans, deletingPlain))
    }
    @Test fun composerPassesNativeSelectionRangeAndNeverDecoratesTheSurvivingPlainCopy() {
        val reference = JsonObject(skill - "displayName")
        val initial = insertVerifiedReference("/rev review", "/rev review", 0, 4, reference)!!
        var draft by mutableStateOf(initial.text)
        var spans by mutableStateOf(initial.spans)
        var edit: ReferenceTextEdit? = null
        content {
            PocketComposer(draft, "Model", "", false, ComposerAvailability(true, false, true, false),
                { error("Native edit callback must own this mutation") }, {}, {}, {}, {}, {}, {}, true,
                referenceSpans = spans, onTextEdit = { before, after, nativeEdit ->
                    assertEquals(draft, before)
                    edit = nativeEdit
                    spans = rebaseReferenceSpans(before, after, spans, nativeEdit)
                    draft = after
                })
        }
        val input = compose.onNodeWithTag("composer-input")
        input.performTextInputSelection(TextRange(0, 7))
        input.performTextInput("")
        assertEquals(ReferenceTextEdit(0, 7, ""), edit)
        assertEquals("review", draft)
        assertTrue(spans.isEmpty())
        input.assertTextEquals("review").assertIsFocused()
        input.assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("review")))
        assertTrue(prepareReferenceInputs(draft, spans).references.isEmpty())
    }
}
