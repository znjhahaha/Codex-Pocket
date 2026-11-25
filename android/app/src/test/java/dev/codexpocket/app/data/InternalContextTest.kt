package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class InternalContextTest {
    @Test fun environmentTagsAloneNeverHideUserText() {
        val raw = buildJsonObject {
            put("id", "m"); put("type", "userMessage")
            putJsonArray("content") { add(buildJsonObject { put("type", "text"); put("text", "<environment_context>user text</environment_context>") }) }
        }
        val item = TimelineItem.from("t", raw)
        assertTrue(ConversationPresentation.visible(item))
        val internal = TimelineItem.from("t", JsonObject(raw + ("pocketContextSource" to JsonPrimitive("runtime-environment"))))
        assertFalse(ConversationPresentation.visible(internal))
        assertEquals(item.text, internal.text)
    }
}
