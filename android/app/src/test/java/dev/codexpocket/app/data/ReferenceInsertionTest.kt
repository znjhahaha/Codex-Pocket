package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class ReferenceInsertionTest {
    @Test fun selectionReplacesOnlyTriggerAndRetainsSuffix() {
        val text = "检查 @rea 后面的内容"
        val edit = replaceReferenceAt(text, text, 3, 7, "@D:/项目/README.md")!!
        assertEquals("检查 @D:/项目/README.md 后面的内容", edit.text)
        assertEquals("检查 @D:/项目/README.md".length, edit.caret)
    }
    @Test fun staleOrInvalidRangeNeverModifiesDraft() {
        assertNull(replaceReferenceAt("@new", "@old", 0, 4, "@file"))
        assertNull(replaceReferenceAt("abc", "abc", 0, 2, "@file"))
        assertNull(replaceReferenceAt("/", "/", 0, 2, "\$skill"))
    }
    @Test fun nativeSkillAndMentionUseOriginalIdentity() {
        fun ref(type: String) = buildJsonObject { put("type", type); put("name", "my-skill"); put("path", "D:/skills/a/SKILL.md") }
        assertEquals("\$my-skill", referenceToken(ref("skill")))
        assertEquals("@my-skill", referenceToken(ref("mention")))
        assertNull(referenceToken(ref("unknown")))
    }
}
