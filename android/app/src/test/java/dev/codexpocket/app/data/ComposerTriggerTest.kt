package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ComposerTriggerTest {
    @Test fun slashIsOnlyACommandAtTheStartOfTheDraft() {
        assertEquals(ComposerTriggerKind.Command, composerTrigger("/", 1)?.kind)
        assertEquals("pla", composerTrigger("/pla", 4)?.query)
        assertNull(composerTrigger("please /pla", 11))
        assertNull(composerTrigger(" /pla", 5))
        assertNull(composerTrigger("/tmp/file", 9))
        assertNull(composerTrigger("./src/file", 10))
        assertNull(composerTrigger("C:\\src\\file", 11))
    }

    @Test fun emailEscapesAndEmbeddedPathMentionsAreExcluded() {
        listOf("dev@example.com", "dir/@file", "C:\\dir\\@file", "\\@file", "https://host/@file", "@@file")
            .forEach { assertNull(it, composerTrigger(it, it.length)) }
        assertEquals("rea", composerTrigger("inspect @rea", 12)?.query)
        assertEquals("src/ui", composerTrigger("inspect @src/ui", 15)?.query)
        assertNotNull(composerTrigger("(@file)", 6))
    }

    @Test fun codeFencesAndInlineCodeAreExcludedUntilClosed() {
        listOf("```kotlin\n@file", "~~~\n@file", "  ```\n@file", "before `@file", "`` @file")
            .forEach { assertNull(it, composerTrigger(it, it.length)) }
        val closed = "```\n@ignored\n```\n@file"
        assertEquals("file", composerTrigger(closed, closed.length)?.query)
        val notClosed = "````\n```\n@file"
        assertNull(composerTrigger(notClosed, notClosed.length))
        val inlineClosed = "`code` @file"
        assertNotNull(composerTrigger(inlineClosed, inlineClosed.length))
    }

    @Test fun queryUsesTheCaretAndReplacementCoversOnlyItsToken() {
        val draft = "Read @readme then compare @other."
        val trigger = composerTrigger(draft, 9)!!
        assertEquals("rea", trigger.query)
        assertEquals(ComposerTriggerRange(5, 12), trigger.range)
        val replacement = replaceComposerTrigger(draft, trigger, "@README.md")!!
        assertEquals("Read @README.md then compare @other.", replacement.text)
        assertEquals(15, replacement.cursor)
        assertNull(composerTrigger(draft, 6, 10))
        assertNull(composerTrigger(draft, -1))
        assertNull(composerTrigger(draft, draft.length + 1))
    }

    @Test fun slashSelectionKeepsArgumentsAndRejectsAChangedDraft() {
        val draft = "/pla keep this suffix"
        val trigger = composerTrigger(draft, 4)!!
        assertEquals(" keep this suffix", replaceComposerTrigger(draft, trigger, "")?.text)
        assertNull(replaceComposerTrigger("/plan keep this suffix", trigger, ""))
    }

    private fun reference(type: String, name: String, path: String) = buildJsonObject {
        put("type", type); put("name", name); put("path", path)
    }

    @Test fun referencesUseTheBrokerTokenContractAndNeverInventPluginMentions() {
        val file = reference("file", "Composer.kt", "src/Composer.kt")
        val skill = reference("skill", "review", "C:/skills/review/SKILL.md")
        val plugin = reference("mention", "tools", "plugin://tools/native")
        assertEquals("@src/Composer.kt", composerReferenceToken(file))
        assertEquals("@src", composerReferenceToken(reference("directory", "src", "src")))
        assertEquals("\$review", composerReferenceToken(skill))
        assertEquals("@tools", composerReferenceToken(plugin))
        assertNull(composerReferenceToken(reference("plugin", "tools", "plugin://tools/native")))
        assertNull(composerReferenceToken(JsonObject(skill + ("supported" to JsonPrimitive(false)))))
        assertNull(composerReferenceToken(JsonObject(skill + ("enabled" to JsonPrimitive(false)))))
        assertNull(composerReferenceToken(reference("skill", "review", "")))
        assertNull(composerReferenceToken(reference("mention", "tools", "")))
        assertNull(composerReferenceToken(reference("file", "empty", "")))
        assertNull(composerReferenceToken(JsonObject(file + ("token" to JsonPrimitive("@fake")))))
    }

    @Test fun slashSkillsReplaceWithNativeDollarReferenceAndPreserveSuffix() {
        val draft = "/rev next paragraph"
        val skill = reference("skill", "review", "C:/skills/review/SKILL.md")
        val trigger = composerTrigger(draft, 4)!!
        val state = ComposerSuggestionsState(trigger = trigger, online = true, data = listOf(skill))
        val candidate = composerSuggestionGroups(trigger, state).single().candidates.single()
        assertEquals(ComposerSuggestionGroup.Skills, candidate.group)
        assertEquals("\$review next paragraph", replaceComposerTrigger(draft, trigger, candidate.token!!)?.text)
        assertEquals(skill, candidate.reference)
    }

    @Test fun candidatesMatchCaseInsensitivelyAndUseOnlyCurrentReferenceResults() {
        val trigger = composerTrigger("@COM", 4)!!
        val file = reference("file", "Composer.kt", "src/Composer.kt")
        val other = reference("skill", "review", "skills/review.md")
        val state = ComposerSuggestionsState(trigger = trigger, online = true, data = listOf(file, other))
        assertEquals(listOf(file), composerSuggestionGroups(trigger, state).flatMap { it.candidates }.map { it.reference })
        assertTrue(composerSuggestionGroups(trigger, state.copy(trigger = composerTrigger("@old", 4))).isEmpty())
        assertTrue(composerSuggestionGroups(trigger, state.copy(online = false)).isEmpty())
        assertTrue(composerSuggestionGroups(trigger, state.copy(loading = true)).isEmpty())
        assertTrue(composerSuggestionGroups(trigger, state.copy(error = "failed")).isEmpty())
    }

    @Test fun nativeCommandsAreExplicitlyOfferedAndNeverSelectedByDefault() {
        val trigger = composerTrigger("/", 1)!!
        assertTrue(composerSuggestionGroups(trigger, ComposerSuggestionsState(online = true)).isEmpty())
        val state = ComposerSuggestionsState(online = true, actions = ComposerAction.entries.toSet())
        val candidates = composerSuggestionGroups(trigger, state).flatMap { it.candidates }
        assertEquals(6, candidates.size)
        assertTrue(candidates.all { it.reference == null && it.token == null })
        assertEquals(listOf(ComposerAction.Plan), composerSuggestionGroups(composerTrigger("/PLA", 4)!!, state)
            .flatMap { it.candidates }.map { it.action })
        assertTrue(composerSuggestionGroups(composerTrigger("/memory", 7)!!, state).isEmpty())
        assertTrue(composerSuggestionGroups(trigger, state.copy(online = false)).isEmpty())
    }

    @Test fun atPaletteGroupsOnlyVerifiedReferencesAndKeepsPluginNames() {
        val trigger = composerTrigger("@", 1)!!
        val plugin = reference("mention", "native tools", "plugin://verified")
        val state = ComposerSuggestionsState(trigger, true, data = listOf(
            plugin, reference("file", "Readme", "README.md"), reference("skill", "review", "skills/review.md"),
            reference("plugin", "unverified", "guess"), plugin), actions = ComposerAction.entries.toSet())
        val groups = composerSuggestionGroups(trigger, state)
        assertEquals(listOf(ComposerSuggestionGroup.Skills, ComposerSuggestionGroup.Files, ComposerSuggestionGroup.Plugins), groups.map { it.group })
        assertEquals("native tools", groups.last().candidates.single().title)
        assertEquals(plugin, groups.last().candidates.single().reference)
    }
}
