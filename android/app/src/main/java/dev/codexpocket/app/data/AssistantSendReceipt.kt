package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Local Room evidence for one composer attempt; never inferred from a model message. */
data class AssistantSendReceipt(val commandId: String, val computerId: String, val runtimeId: String,
    val conversationId: String?, val status: String, val submittedAtMs: Long = 0) {
    val key get() = key(computerId,runtimeId,conversationId,commandId)
    companion object {
        fun key(computer: String, runtime: String, conversation: String?, command: String) =
            JsonArray(listOf(computer,runtime,conversation.orEmpty(),command).map(::JsonPrimitive)).toString()
        fun draft(runtime: String, conversation: String?, text: String) = buildJsonObject {
            put("pocketAssistantDraft",1);put("runtimeId",runtime);conversation?.let {put("conversationId",it)};put("text",text)
        }.toString()
        fun from(command: CommandEntity): AssistantSendReceipt? {
            if(command.method!="assistant/send" || command.status !in setOf("accepted","rejected")) return null
            val draft=runCatching {pocketJson.parseToJsonElement(command.draftText).jsonObject}.getOrNull() ?: return null
            if(draft.number("pocketAssistantDraft")!=1L || draft.string("runtimeId").isBlank()) return null
            return AssistantSendReceipt(command.id,command.computerId,draft.string("runtimeId"),draft.string("conversationId").ifBlank {null},command.status,command.createdAt)
        }
    }
}
