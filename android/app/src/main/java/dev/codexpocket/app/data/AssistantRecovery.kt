package dev.codexpocket.app.data

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.booleanOrNull

/** An explicit offer for the same persisted message, before any native dispatch. */
data class AssistantHandoffRecovery(val generation: String, val messageIds: List<String>) {
    companion object {
        fun from(operation: JsonObject): AssistantHandoffRecovery? {
            fun JsonObject.id(name: String) = (this[name] as? JsonPrimitive)?.takeIf {it.isString}?.content
                ?.takeIf {it.isNotBlank() && '\u0000' !in it && it.toByteArray(Charsets.UTF_8).size<=256}
            val offer=operation["handoffRecovery"] as? JsonObject ?: return null
            if((offer["action"] as? JsonPrimitive)?.content!="resume_original_handoff" ||
                (operation["role"] as? JsonPrimitive)?.content!="Coordinator" ||
                (operation["phase"] as? JsonPrimitive)?.content!="Paused" ||
                (operation["suspended"] as? JsonPrimitive)?.takeUnless {it.isString}?.booleanOrNull!=false ||
                (operation["mapping"]!=null && operation["mapping"]!=JsonNull)) return null
            val id=operation.id("id") ?: return null
            val generation=operation.id("expected_generation") ?: return null
            if(offer.id("operationId")!=id || offer.id("generation")!=generation) return null
            fun JsonObject.messages(name: String): List<String>? {
                val values=this[name] as? JsonArray ?: return null
                if(values.isEmpty() || values.size>256) return null
                val ids=values.map {value -> (value as? JsonPrimitive)?.takeIf {it.isString}?.content
                    ?.takeIf {it.isNotBlank() && '\u0000' !in it && it.toByteArray(Charsets.UTF_8).size<=256} ?: return null}
                return ids.takeIf {it.distinct().size==it.size}
            }
            val messages=operation.messages("user_message_ids") ?: return null
            if(offer.messages("messageIds")!=messages) return null
            return AssistantHandoffRecovery(generation,messages)
        }
    }
}

/** Only the backend's explicit original-rejection recovery offer is actionable. */
data class AssistantRecoveryRequest(val operationId: String, val requestId: String) {
    companion object {
        fun from(value: JsonObject): AssistantRecoveryRequest? {
            fun text(name: String)=(value[name] as? JsonPrimitive)?.takeIf {it.isString}?.content
            if(text("method")!="item/tool/call" || text("recoveryAction")!="reject_original_tool_answer") return null
            fun id(name: String)=text(name)?.takeIf {it.isNotBlank() && it.length<=256 && '\u0000' !in it}
            return AssistantRecoveryRequest(id("operationId") ?: return null,id("requestId") ?: return null)
        }
    }
}

internal val AssistantUiState.unconfirmedFeedback: String? get() =
    if(unconfirmedActions.isNotEmpty()) "原操作结果尚未确认，正在核对；不会重复提交" else null

/** Room's uncertain commands survive refreshes and app restarts; snapshots are not command receipts. */
internal fun AssistantUiState.withCommandFeedback(commands: List<CommandEntity>): AssistantUiState {
    val methods=commands.filter {it.method.startsWith("assistant/") && CommandPolicy.blocksNewSend(it.status) &&
        (it.status!="submitted" || it.method !in pendingActions)}.map {it.method}.toSet()
    val next=copy(unconfirmedActions=methods)
    return next.copy(error=next.unconfirmedFeedback ?: if(error == unconfirmedFeedback && unconfirmedFeedback != null) snapshot?.preparationIssue else error)
}
