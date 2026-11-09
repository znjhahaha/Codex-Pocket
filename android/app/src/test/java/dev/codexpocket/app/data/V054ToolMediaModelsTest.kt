package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class V054ToolMediaModelsTest {
    private fun item(type: String, content: JsonElement): TimelineItem = TimelineItem.from("turn", buildJsonObject {
        put("id", "tool"); put("type", type)
        when (type) {
            "mcpToolCall" -> put("result", buildJsonObject { put("content", content) })
            "dynamicToolCall" -> put("contentItems", content)
            else -> put("output", content)
        }
    })
    private val image = buildJsonObject {
        put("type", "image"); put("mediaId", "a".repeat(32)); put("runtimeId", "runtime"); put("threadId", "thread")
        put("turnId", "turn"); put("itemId", "tool"); put("mimeType", "image/png"); put("byteLength", 100); put("status", "ready")
    }
    @Test fun normalizedMixedContentKeepsOrderAndDoesNotDuplicateLegacyText() {
        val raw = buildJsonObject {
            put("id", "tool"); put("type", "dynamicToolCall"); put("text", "before\nafter")
            put("toolOutputBlocks", buildJsonArray {
                add(buildJsonObject { put("type", "text"); put("text", "before") }); add(image)
                add(buildJsonObject { put("type", "text"); put("text", "after") })
            })
        }
        val value = NativeActivityPresentation.describe(TimelineItem.from("turn", raw))
        assertEquals(listOf("before", "after"), value.outputBlocks.filterIsInstance<ToolOutputBlock.Text>().map { it.text })
        assertTrue(value.outputBlocks[1] is ToolOutputBlock.Image)
        assertEquals("before\nafter", value.output.text)
        assertTrue(value.outputBlocks.filterIsInstance<ToolOutputBlock.Image>().single().media.readable)
        assertEquals(raw, TimelineItem.from("turn", raw).raw)
    }
    @Test fun pureImagesFromAllNativeToolTypesHaveMediaAndNoTextFallback() {
        for (type in listOf("mcpToolCall", "dynamicToolCall", "functionCallOutput")) {
            val value = NativeActivityPresentation.describe(item(type, JsonArray(listOf(image))))
            assertEquals(1, value.outputBlocks.size)
            assertEquals("", value.output.text)
        }
    }
    @Test fun rawInlineAndExternalSourcesAreRecognizedWithoutCopyingTheirBytesOrPaths() {
        for (part in listOf(
            buildJsonObject { put("type", "inputImage"); put("imageUrl", "data:image/png;base64,PRIVATE") },
            buildJsonObject { put("type", "image"); put("mimeType", "image/png"); put("data", "PRIVATE") },
            buildJsonObject { put("type", "imageUrl"); put("imageUrl", "https://example.invalid/private") },
        )) {
            val block = toolOutputBlocks(item("dynamicToolCall", JsonArray(listOf(part)))).single() as ToolOutputBlock.Image
            assertEquals(ToolMediaStatus.Unsupported, block.media.status)
            assertFalse(block.media.readable)
            assertFalse(block.media.toString().contains("PRIVATE"))
            assertFalse(block.media.toString().contains("https://"))
        }
    }
    @Test fun invalidReferencesAndUnknownStatusesAreNotReadable() {
        val good = (toolOutputBlocks(item("mcpToolCall", JsonArray(listOf(image)))).single() as ToolOutputBlock.Image).media
        assertFalse(good.copy(mediaId = "../secret").readable)
        assertFalse(good.copy(byteLength = MAX_TOOL_IMAGE_BYTES + 1).readable)
        assertFalse(good.copy(byteLength = 0).readable)
        assertFalse(good.copy(mimeType = "image/svg+xml").readable)
        assertFalse(good.copy(threadId = "").readable)
        assertFalse(good.copy(status = ToolMediaStatus.Expired).readable)
        assertEquals(ToolMediaStatus.Failed, toolMediaStatus("future"))
        assertNotEquals(good.cacheKey, good.copy(runtimeId = "other").cacheKey)
    }
    @Test fun textOnlyAndStructuredSummariesRemainCompatible() {
        val plain = NativeActivityPresentation.describe(item("functionCallOutput", JsonPrimitive("plain output")))
        assertEquals("plain output", plain.output.text)
        val raw = buildJsonObject { put("id", "tool"); put("type", "mcpToolCall"); put("toolOutputBlocks", JsonArray(emptyList()))
            put("result", buildJsonObject { put("structuredContent", buildJsonObject { put("summary", "Useful summary"); put("secret", "PRIVATE") }) }) }
        assertEquals("Useful summary", NativeActivityPresentation.describe(TimelineItem.from("turn", raw)).output.text)
        val native = item("functionCallOutput", buildJsonArray { add(buildJsonObject { put("type", "input_text"); put("text", "Found") }) })
        assertEquals("Found", NativeActivityPresentation.describe(native).output.text)
    }
}
