package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Replace only the authenticated image slot, retaining live prose, status and all sibling content. */
internal fun renewedMediaItem(current: TimelineItem, fresh: TimelineItem, expected: ToolMediaRef): TimelineItem? {
    if (current.turnId != fresh.turnId || current.itemId != fresh.itemId || current.type != fresh.type) return null
    val before = itemMedia(current)
    val index = before.indexOf(expected)
    if (index < 0 || before.lastIndexOf(expected) != index) return null
    val after = itemMedia(fresh)
    val nextIndex = if (expected.contentPart != null) after.indexOfFirst { it.contentPart == expected.contentPart }
        else index.takeIf { before.size == after.size } ?: return null
    val renewed = after.getOrNull(nextIndex) ?: return null
    if (!renewed.readable || renewed == expected || renewed.runtimeId != expected.runtimeId ||
        renewed.threadId != expected.threadId || renewed.turnId != expected.turnId || renewed.itemId != expected.itemId) return null
    val field = if (current.type == "userMessage") "content" else "toolOutputBlocks"
    val parts = current.raw[field] as? JsonArray ?: return null
    val freshParts = fresh.raw[field] as? JsonArray ?: return null
    fun imageSlots(values: JsonArray) = values.indices.filter { slot ->
        val block = values[slot].asObject()
        block.string("type") in setOf("image", "inputImage", "input_image", "image_url", "imageUrl") &&
            (block.containsKey("mediaId") || block.containsKey("status"))
    }
    val slots = imageSlots(parts); val freshSlots = imageSlots(freshParts)
    if (slots.size != before.size || freshSlots.size != after.size) return null
    return current.copy(raw = JsonObject(current.raw + (field to JsonArray(parts.toMutableList().apply {
        this[slots[index]] = freshParts[freshSlots[nextIndex]]
    }))))
}
