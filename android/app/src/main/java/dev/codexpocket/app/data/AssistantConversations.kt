package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class AssistantConversation(val id: String, val title: String, val createdAtMs: Long, val updatedAtMs: Long) {
    companion object {
        fun from(value: JsonObject): AssistantConversation? = value.string("id").takeIf(String::isNotBlank)?.let {
            AssistantConversation(it, value.string("title").ifBlank { "新对话" }, value.number("createdAtMs"), value.number("updatedAtMs"))
        }
    }
}

fun assistantConversationCreate() = AssistantAction("assistant/v2/conversation/create", buildJsonObject {})
fun assistantConversationList() = AssistantAction("assistant/v2/conversation/list", buildJsonObject {})
fun assistantConversationSelect(id: String) = AssistantAction("assistant/conversation/select", buildJsonObject { put("conversationId", id) })
fun assistantConversationInterrupt(id: String, operationId: String): AssistantAction {
    require(id.isNotBlank() && operationId.isNotBlank()) { "请先读取当前回复的运行状态" }
    return AssistantAction("assistant/v2/conversation/interrupt", buildJsonObject { put("conversationId", id); put("operationId", operationId) })
}

/** Keep the task catalog global; only the main conversation projects its own work and questions. */
fun AssistantSnapshot.selectedConversation(): AssistantSnapshot {
    val selected = conversationId ?: return this
    val selectedTasks = tasks.filter { it.conversationId == selected }
    val taskIds = selectedTasks.map { it.id }.toSet()
    val selectedOperations = operations.filter { it.conversationId == selected || it.taskId in taskIds || it.id == activeCoordinatorOperationId }
    val operationIds = selectedOperations.map { it.id }.toSet()
    return copy(tasks = selectedTasks, operations = selectedOperations, events = events.filter { it.conversationId == selected },
        groups = groups.filter { it.taskIds.any(taskIds::contains) }, deliveries = deliveries.filter { it.conversationId == selected },
        recoveries = recoveries.filter { it.operationId in operationIds }, questionHistory = questionHistory.filter { it.operationId in operationIds })
}
