package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CommandStepsTest {
    private fun raw(json: String) = pocketJson.parseToJsonElement(json).jsonObject
    private val p = NativeActivityPresentation

    @Test fun parsedReadsSearchesAndSkillsAreExplorationNotCommands() {
        val item = raw("""{"commandActions":[
            {"type":"read","command":"Get-Content a.kt","name":"a.kt","path":"D:/x/a.kt"},
            {"type":"read","command":"Get-Content SKILL.md","name":"SKILL.md","path":"C:/Users/me/.codex/plugins/cache/pdf/skills/pdf/SKILL.md"},
            {"type":"search","command":"rg foo src","query":"foo","path":"src"},
            {"type":"listFiles","command":"ls src","path":"src"}]}""")
        val steps = p.commandSteps(item)
        assertEquals(listOf(StepKind.Read, StepKind.Skill, StepKind.Search, StepKind.ListFiles), steps.map { it.kind })
        assertEquals("pdf", steps[1].name)
        assertEquals("PDF", skillLabel(steps[1].name))
        assertEquals("foo", steps[2].query)
        assertTrue(ActivityCommand("x", "", 0, steps = steps).explored)
    }

    @Test fun anyUnknownActionKeepsTheShellCommand() {
        val steps = p.commandSteps(raw("""{"commandActions":[{"type":"read","command":"cat a","name":"a","path":"a"},{"type":"unknown","command":"npm test"}]}"""))
        assertFalse(ActivityCommand("x", "", 0, steps = steps).explored)
        assertFalse(ActivityCommand("x", "", 0).explored)
    }

    @Test fun summaryCountsExplorationSeparatelyFromCommands() {
        fun cmd(id: String, actions: String) = TimelineItem("t", id, "commandExecution", raw = raw("""{"command":"c","commandActions":$actions}"""), complete = true)
        val summary = p.summary(listOf(
            cmd("1", """[{"type":"read","name":"a.md","path":"a.md"},{"type":"read","name":"b.md","path":"b.md"}]"""),
            cmd("2", """[{"type":"search","query":"x"}]"""),
            cmd("3", """[{"type":"unknown","command":"cargo test"}]"""),
            cmd("4", "[]"),
        ))
        assertEquals(2, summary.reads)
        assertEquals(1, summary.searches)
        assertEquals(2, summary.commands)
    }

    @Test fun skillNeedsSkillsFolderAndSkillFile() {
        assertEquals("review", p.skillName("C:\\u\\.mirasim\\skills\\review\\SKILL.md", "SKILL.md"))
        assertNull(p.skillName("D:/docs/SKILL.md", "SKILL.md"))
        assertNull(p.skillName("D:/skills/review/README.md", "README.md"))
    }
}
