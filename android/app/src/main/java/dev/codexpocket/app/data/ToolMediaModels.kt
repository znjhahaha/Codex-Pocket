package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class ToolMediaStatus { Ready, Recoverable, Unsupported, Expired, Failed, OwnerChanged, Offline }

/** Ownership accompanies opaque references; desktop paths and native inline bytes never enter this model. */
data class ToolMediaRef(
    val mediaId: String,
    val runtimeId: String,
    val threadId: String,
    val turnId: String,
    val itemId: String,
    val mimeType: String,
    val byteLength: Long,
    val status: ToolMediaStatus,
    val message: String = "",
    val connectionId: String = "",
    val contentPart: Int? = null,
) {
    val cacheKey: String get() = JsonArray(listOf(runtimeId, connectionId, threadId, turnId, itemId, contentPart?.toString().orEmpty(), mediaId, mimeType, byteLength.toString(), status.name).map(::JsonPrimitive)).toString()
    val readable: Boolean get() = status in setOf(ToolMediaStatus.Ready, ToolMediaStatus.Recoverable) && mediaId.length == 32 && mediaId.all { it in '0'..'9' || it in 'a'..'f' } &&
        listOf(runtimeId, threadId, turnId, itemId).all { it.isNotBlank() && it.length <= 512 } &&
        (if (status == ToolMediaStatus.Recoverable) byteLength in 0..MAX_TOOL_IMAGE_BYTES && (mimeType.isBlank() || mimeType in TOOL_IMAGE_MIMES)
            else byteLength in 1..MAX_TOOL_IMAGE_BYTES && mimeType in TOOL_IMAGE_MIMES)
}

sealed interface ToolOutputBlock {
    data class Text(val text: String) : ToolOutputBlock
    data class Image(val media: ToolMediaRef) : ToolOutputBlock
}

const val MAX_TOOL_IMAGE_BYTES = 24L * 1024 * 1024
const val TOOL_IMAGE_CHUNK_BYTES = 192 * 1024
val TOOL_IMAGE_MIMES = setOf("image/png", "image/jpeg", "image/gif", "image/webp", "image/bmp")

/** A single endpoint response. The UI validates size, identity, offsets and progress before decoding. */
data class ToolMediaChunk(val mediaId: String, val status: ToolMediaStatus, val mimeType: String = "", val size: Long = 0,
    val offset: Long = 0, val eof: Boolean = false, val data: String = "") {
    companion object {
        fun from(raw: JsonObject) = ToolMediaChunk(raw.string("mediaId"), toolMediaStatus(raw.string("status")),
            raw.string("mimeType", raw.string("mime")), (raw["size"] as? JsonPrimitive)?.longOrNull ?: 0,
            (raw["offset"] as? JsonPrimitive)?.longOrNull ?: -1, (raw["eof"] as? JsonPrimitive)?.booleanOrNull ?: false, raw.string("data"))
    }
}

fun toolMediaStatus(status: String): ToolMediaStatus = when (status) {
    "ready" -> ToolMediaStatus.Ready
    "recoverable" -> ToolMediaStatus.Recoverable
    "unsupported" -> ToolMediaStatus.Unsupported
    "expired" -> ToolMediaStatus.Expired
    "owner_changed", "stale_media", "media_owner_mismatch" -> ToolMediaStatus.OwnerChanged
    "offline" -> ToolMediaStatus.Offline
    else -> ToolMediaStatus.Failed
}

/** Prefer the server projection. The fallback recognizes documented native shapes without downloading URLs. */
fun toolOutputBlocks(item: TimelineItem): List<ToolOutputBlock> {
    if (!ConversationPresentation.visible(item)) return emptyList()
    val raw = item.raw
    val projected = raw["toolOutputBlocks"] as? JsonArray
    val content: JsonElement? = projected ?: when (item.type) {
        "mcpToolCall" -> raw.obj("result")["content"]
        "dynamicToolCall" -> raw["contentItems"]
        "functionCallOutput" -> raw["output"] ?: raw["content"]
        else -> return emptyList()
    }
    if (content is JsonPrimitive && content.isString) return listOf(ToolOutputBlock.Text(content.content)).filter { it.text.isNotBlank() }
    val array = content as? JsonArray ?: (content as? JsonObject)?.get("content") as? JsonArray ?: return emptyList()
    return array.take(257).mapNotNull { element ->
        val block = element as? JsonObject ?: return@mapNotNull null
        when (block.string("type")) {
            "text", "inputText", "outputText", "input_text", "output_text" -> (block["text"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf(String::isNotBlank)?.let { ToolOutputBlock.Text(it) }
            "image", "inputImage", "imageUrl", "input_image", "image_url" -> {
                val status = if (projected != null || block.string("mediaId").isNotBlank()) toolMediaStatus(block.string("status")) else ToolMediaStatus.Unsupported
                ToolOutputBlock.Image(ToolMediaRef(block.string("mediaId"), block.string("runtimeId", raw.string("runtimeId")),
                    block.string("threadId", raw.string("threadId")), block.string("turnId", item.turnId), block.string("itemId", item.itemId),
                    block.string("mimeType"), (block["byteLength"] as? JsonPrimitive)?.longOrNull ?: 0, status,
                    if (status == ToolMediaStatus.Unsupported && projected == null) "工具图片暂不可读取，请更新电脑助手" else block.string("message").take(256), block.string("connectionId")))
            }
            else -> null
        }
    }
}

/** Native user content is ordered, including image slots separated by text. */
fun userMessageMedia(item: TimelineItem): List<ToolMediaRef> {
    if (item.type != "userMessage" || !ConversationPresentation.visible(item)) return emptyList()
    return item.raw.array("content").mapNotNull { value ->
        val block = value as? JsonObject ?: return@mapNotNull null
        if (block.string("type") !in setOf("image", "inputImage", "input_image", "image_url", "imageUrl") ||
            (!block.containsKey("mediaId") && !block.containsKey("status"))) return@mapNotNull null
        ToolMediaRef(block.string("mediaId"), block.string("runtimeId", item.raw.string("runtimeId")),
            block.string("threadId", item.raw.string("threadId")), block.string("turnId", item.turnId), block.string("itemId", item.itemId),
            block.string("mimeType", block.string("mime")), block.number("byteLength", block.number("size")),
            toolMediaStatus(block.string("status")), block.string("message").take(256), block.string("connectionId"),
            (block["contentPart"] as? JsonPrimitive)?.intOrNull?.takeIf { it >= 0 })
    }
}

fun itemMedia(item: TimelineItem): List<ToolMediaRef> = userMessageMedia(item) + toolOutputBlocks(item).mapNotNull { (it as? ToolOutputBlock.Image)?.media }

internal fun toolImageMime(bytes: ByteArray): String? = when {
    bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 13, 10, 26, 10)) -> "image/png"
    bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> "image/jpeg"
    bytes.size >= 6 && String(bytes, 0, 6, Charsets.US_ASCII) in setOf("GIF87a", "GIF89a") -> "image/gif"
    bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP" -> "image/webp"
    bytes.size >= 2 && bytes[0] == 0x42.toByte() && bytes[1] == 0x4d.toByte() -> "image/bmp"
    else -> null
}
