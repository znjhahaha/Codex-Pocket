package dev.codexpocket.app.ui

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.room.Room
import dev.codexpocket.app.MainActivity
import dev.codexpocket.app.data.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real repository + Room + Composer; only catalog/initial draft state is seeded, no transport or inference. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w412dp-h915dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerRepositoryIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val reference = buildJsonObject {
        put("type", "skill"); put("name", "review"); put("displayName", "代码审查")
        put("path", "skills/review/SKILL.md"); put("description", "检查实现和测试"); put("source", "工作区")
    }

    @Suppress("UNCHECKED_CAST")
    private fun state(repo: PocketRepository) = PocketRepository::class.java.getDeclaredField("_state")
        .apply { isAccessible = true }.get(repo) as MutableStateFlow<PocketState>

    private fun fixture(test: (PocketRepository, PocketDatabase) -> Unit) {
        val context = compose.activity.application
        val db = Room.inMemoryDatabaseBuilder(context, PocketDatabase::class.java).build()
        val storage = object : PairingStorage {
            override fun load(): PairedComputer? = null
            override fun save(pair: PairedComputer) = Unit
            override fun forget() = Unit
        }
        val repo = PocketRepository(context, { error("Fixture must never connect") }, storage, db, emptyFlow())
        try {
            compose.waitUntil(5_000) { repo.state.value.initialized }
            state(repo).update { it.copy(draft = DraftEntity(ThreadKey("pc", "store", "chat"), "/rev suffix"),
                advanced = it.advanced.copy(references = listOf(reference))) }
            compose.activity.runOnUiThread {
                compose.activity.setContent {
                    PocketTheme("light") { PocketBackdrop {
                        val pocket by repo.state.collectAsState()
                        var trigger by remember { mutableStateOf<ComposerTrigger?>(null) }
                        PocketComposer(pocket.draft!!.text, "Model", "", false, ComposerAvailability(true, false, true, false),
                            repo::editDraft, {}, {}, {}, { error("Selecting suggestions must never send") }, {}, {}, true,
                            suggestions = ComposerSuggestionsState(trigger, true, data = listOf(reference)),
                            onQuery = { trigger = it }, onReference = repo::insertReferenceAt,
                            referenceSpans = remember(pocket.draft!!.text, pocket.draft!!.references) {
                                restoreReferenceDraft(pocket.draft!!.text, pocket.draft!!.references).spans
                            })
                    } }
                }
            }
            compose.waitForIdle()
            compose.onNodeWithTag("composer-input").performClick().performTextInputSelection(TextRange(4))
            test(repo, db)
        } finally {
            val scope = PocketRepository::class.java.getDeclaredField("scope").apply { isAccessible = true }.get(repo) as CoroutineScope
            runBlocking { scope.coroutineContext[Job]!!.cancelAndJoin() }
            db.close()
        }
    }

    private fun assertPersisted(draft: DraftEntity, db: PocketDatabase) {
        compose.waitUntil(5_000) { runBlocking { db.dao().draft(draft.chatKey) } == draft }
    }

    @Test fun acceptedReferenceUpdatesAtomicDraftMetadataAndTheSameEditorCaret() = fixture { repo, db ->
        compose.onNodeWithText("代码审查").performClick()
        val draft = repo.state.value.draft!!
        assertEquals("代码审查 suffix", draft.text)
        val saved = pocketJson.parseToJsonElement(draft.references).jsonArray.single().jsonObject
        assertEquals(1, saved["version"]!!.jsonPrimitive.int)
        assertEquals(0, saved["start"]!!.jsonPrimitive.int)
        assertEquals(4, saved["end"]!!.jsonPrimitive.int)
        assertEquals("代码审查", saved.string("displayName"))
        assertEquals(reference, saved["reference"])
        assertFalse(saved.containsKey("type"))
        val restored = restoreReferenceDraft(draft.text, draft.references)
        assertEquals(listOf(ReferenceSpan(0, 4, "代码审查", reference)), restored.spans)
        val prepared = prepareReferenceInputs(restored.text, restored.spans)
        assertEquals("\$review suffix", prepared.text)
        assertEquals(listOf(buildJsonObject {
            put("type", "skill"); put("name", "review"); put("path", "skills/review/SKILL.md")
        }), prepared.references)
        val input = compose.onNodeWithTag("composer-input").assertIsFocused()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.InputText, AnnotatedString("代码审查 suffix")))
            .assertTextEquals("\u2003代码审查 suffix")
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertPersisted(draft, db)
    }

    @Test fun withdrawnCatalogReferenceIsRejectedWithoutChangingDraftOrCaret() = fixture { repo, _ ->
        compose.runOnIdle { state(repo).update { it.copy(advanced = it.advanced.copy(references = emptyList())) } }
        compose.onNodeWithText("代码审查").performClick()
        assertEquals("/rev suffix", repo.state.value.draft!!.text)
        assertEquals("[]", repo.state.value.draft!!.references)
        val input = compose.onNodeWithTag("composer-input").assertIsFocused().assertTextEquals("/rev suffix")
        assertEquals(TextRange(4), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }
    @Test fun editorChangesRebasePersistedReferenceAndKeepItsWireIdentity() = fixture { repo, db ->
        compose.onNodeWithText("代码审查").performClick()
        val input = compose.onNodeWithTag("composer-input")
        input.performTextInputSelection(TextRange(0))
        input.performTextInput("中文🙂 ")
        val draft = repo.state.value.draft!!
        assertEquals("中文🙂 代码审查 suffix", draft.text)
        val restored = restoreReferenceDraft(draft.text, draft.references)
        assertEquals(listOf(ReferenceSpan(5, 9, "代码审查", reference)), restored.spans)
        assertEquals("中文🙂 \$review suffix", prepareReferenceInputs(restored.text, restored.spans).text)
        assertEquals(TextRange(5), input.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertPersisted(draft, db)
    }
    @Test fun replacingVerifiedNameRevokesMetadataAndTypedWireTokenStaysPlain() = fixture { repo, db ->
        compose.onNodeWithText("代码审查").performClick()
        val input = compose.onNodeWithTag("composer-input")
        input.performTextInputSelection(TextRange(0, 4))
        input.performTextInput("手动内容")
        input.performTextInputSelection(TextRange(repo.state.value.draft!!.text.length))
        input.performTextInput(" \$review")
        val draft = repo.state.value.draft!!
        assertEquals("手动内容 suffix \$review", draft.text)
        assertEquals("[]", draft.references)
        val restored = restoreReferenceDraft(draft.text, draft.references)
        val prepared = prepareReferenceInputs(restored.text, restored.spans)
        assertEquals(draft.text, prepared.text)
        assertTrue(prepared.references.isEmpty())
        assertPersisted(draft, db)
    }
    @Test fun genericRepositoryEditNeverTransfersIdentityBetweenEqualDisplayNames() = fixture { repo, db ->
        compose.onNodeWithText("代码审查").performClick()
        compose.runOnIdle { repo.editDraft("代码审查 代码审查") }
        assertEquals(1, restoreReferenceDraft(repo.state.value.draft!!.text, repo.state.value.draft!!.references).spans.size)
        compose.runOnIdle { repo.editDraft("代码审查") }
        val draft = repo.state.value.draft!!
        assertEquals("代码审查", draft.text)
        assertEquals("[]", draft.references)
        assertTrue(prepareReferenceInputs(draft.text, restoreReferenceDraft(draft.text, draft.references).spans).references.isEmpty())
        assertPersisted(draft, db)
    }
}
