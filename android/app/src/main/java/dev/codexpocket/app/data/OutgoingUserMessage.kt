package dev.codexpocket.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.json.*

/** Local send intent. Native items and their (turnId,itemId) keys remain in the items table. */
@Entity(tableName = "outgoing_user_messages", indices = [Index("chatKey"), Index("commandId")])
data class OutgoingUserMessage(
    @PrimaryKey val clientId: String,
    val chatKey: ThreadKey,
    val commandId: String,
    val createdAt: Long,
    val text: String,
    val attachments: String,
    val references: String,
    val draftRevision: Long,
    val phase: String = "preparing",
    val showPending: Boolean = true,
    val nativeKey: String? = null,
    val blockingCommandId: String? = null,
) {
    val displayKey: String get() = "pocket-user:$clientId"
    val visible: Boolean get() = phase != "reconciled"
    fun localAttachments(): List<LocalAttachment> = pocketJson.parseToJsonElement(attachments).jsonArray
        .map { LocalAttachment.from(it.asObject()) }

    fun draftReceipt() = buildJsonObject {
        put("pocketDraft", 1); put("pocketUserMessageId", clientId); put("text", text)
        put("draftRevision", draftRevision); put("attachments", pocketJson.parseToJsonElement(attachments))
    }.toString()

    fun pendingItem(): TimelineItem {
        val files = localAttachments()
        val body = text.ifBlank { files.joinToString("\n") { "附件：${it.name}" } }
        return TimelineItem("pocket-pending:$clientId", clientId, "userMessage", body, phase,
            raw = buildJsonObject {
                put("_pocketOptimistic", true)
                put("content", buildJsonArray {
                    if (text.isNotBlank()) add(buildJsonObject { put("type", "text"); put("text", text) })
                })
            }, presentationKey = displayKey)
    }
}

/**
 * Request clientUserMessageId is echoed as userMessage.clientId, NOT userMessage.id.
 * Protocol: openai/codex, codex-rs/app-server-protocol/src/protocol/v2/item.rs,
 * ThreadItemPayload::UserMessage { id, client_id, content } (serde camelCase).
 * Verified by codex-rs/app-server/tests/suite/v2/turn_start.rs,
 * turn_start_emits_user_message_item_with_text_elements (clientUserMessageId -> emitted client_id).
 * An older cache may lack clientId: only a previously persisted native key can associate it.
 * Message text is never identity evidence.
 */
internal fun OutgoingUserMessage.associate(timeline: NativeTimeline): OutgoingUserMessage {
    if (nativeKey != null) return this
    val item = timeline.items.values.firstOrNull { it.type == "userMessage" && it.raw.string("clientId") == clientId }
        ?: return this
    return copy(nativeKey = item.key)
}

/** Presentation-only projection; the repository/cache continues to contain native items alone. */
internal fun NativeTimeline.withOutgoingPresentation(): NativeTimeline {
    if (outgoingMessages.isEmpty()) return this
    val associated = outgoingMessages.map { it.associate(this) }
    val displayKeys = associated.mapNotNull { message -> message.nativeKey?.let { it to message.displayKey } }.toMap()
    val projected = items.mapValuesTo(linkedMapOf()) { (key, item) ->
        displayKeys[key]?.let { item.copy(presentationKey = it) } ?: item
    }
    // Queued and steered messages are still the user's messages. Older persisted intents
    // may have showPending=false; that legacy display preference must not hide them now.
    associated.filter { it.visible && it.nativeKey == null }.forEach { message ->
        val pending = message.pendingItem()
        projected[pending.key] = pending
    }
    // taskRows recursively calls rows for each task. Project the local intent exactly once.
    return copy(items = projected, outgoingMessages = emptyList())
}

/** turn/start and commands/status.result use the same native TurnStartResponse shape. */
internal fun applyOutgoingReceiptItems(timeline: NativeTimeline, result: JsonObject): NativeTimeline {
    val turn = result.obj("turn")
    val id = turn.string("id")
    if (id.isBlank()) return timeline
    return turn.array("items").map(JsonElement::asObject).filter { it.string("type") == "userMessage" }
        .fold(timeline) { current, item ->
            NativeReducer.reduce(current, buildJsonObject {
                put("method", "item/completed")
                put("params", buildJsonObject { put("turnId", id); put("item", item) })
            })
        }
}
