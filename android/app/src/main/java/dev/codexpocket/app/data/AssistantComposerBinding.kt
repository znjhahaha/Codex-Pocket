package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Local identity captured by the composer. Never part of the broker/native request. */
data class AssistantComposerBinding(val computerId: String, val runtimeId: String, val conversationId: String?) {
    val scopeKey get() = JsonArray(listOf(computerId,runtimeId,conversationId.orEmpty()).map(::JsonPrimitive)).toString()
    fun receiptKey(commandId: String) = AssistantSendReceipt.key(computerId,runtimeId,conversationId,commandId)
    fun draft(text: String) = AssistantSendReceipt.draft(runtimeId,conversationId,text)
}

fun AssistantSnapshot.composerBinding(computerId: String) = AssistantComposerBinding(computerId,scopeId ?: runtimeId.orEmpty(),
    conversationId ?: if(scopeId != null) null else
    assistantId?.takeIf(String::isNotBlank)?.let { "assistant:$it" } ?: coordinatorThreadId)

/** A successful transport response alone does not establish product acceptance. */
internal fun assistantSendAckStatus(result: JsonElement, commandId: String): String {
    val reply = result as? JsonObject ?: return "unknown"
    val echoed = reply["commandId"] as? JsonPrimitive ?: return "unknown"
    if(!echoed.isString || echoed.content != commandId) return "unknown"
    val accepted = reply["accepted"] as? JsonPrimitive ?: return "unknown"
    if(accepted.isString) return "unknown"
    val status = if(reply.containsKey("status")) {
        val value = reply["status"] as? JsonPrimitive ?: return "unknown"
        if(!value.isString) return "unknown"
        value.content
    } else null
    return when {
        accepted.booleanOrNull == true && status in setOf(null,"accepted") &&
            (reply["error"] == null || reply["error"] == JsonNull) -> "accepted"
        accepted.booleanOrNull == false && status == "rejected" -> "rejected"
        else -> "unknown"
    }
}
