package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*

/** Stable identities and real event times only; a refresh must not duplicate coordinator replies. */
internal sealed interface AssistantTimeline {
    val key:String
    val atMs:Long?
    data class Message(val message:AssistantChatMessage):AssistantTimeline {
        override val key="message:${message.id}"
        override val atMs=message.atMs
    }
    data class Group(val group:AssistantGroup,val tasks:List<AssistantTask>,override val atMs:Long?):AssistantTimeline {override val key="group:${group.id}"}
    data class Task(val task:AssistantTask,override val atMs:Long?):AssistantTimeline {override val key="task:${task.id}"}
    data class Question(val operation:AssistantOperation,override val atMs:Long?):AssistantTimeline {override val key="question:${operation.id}"}
    data class Delivery(val delivery:AssistantDelivery):AssistantTimeline {override val key="delivery:${delivery.id}";override val atMs=delivery.atMs}
    data class History(val history:AssistantQuestionHistory):AssistantTimeline {override val key="question-history:${history.id}";override val atMs=history.atMs}
    data class Result(val operation:AssistantOperation,override val atMs:Long?):AssistantTimeline {override val key="result:${operation.id}"}
}

internal fun assistantTimeline(state:AssistantUiState):List<AssistantTimeline> {
    val snapshot=state.snapshot ?: return emptyList()
    val replies=snapshot.coordinatorReplies
    val nativeMessages=state.messages.filter {it.role=="User" || it.role=="Assistant"}.map {message ->
        if(message.role=="Assistant" && message.atMs==null && message.operationId!=null) {
            message.copy(atMs=replies.firstOrNull {it.operationId==message.operationId && it.text==message.text && it.atMs!=null}?.atMs)
        } else message
    }
    val messages=(nativeMessages + replies.filter {reply ->
        state.messages.none {it.role=="Assistant" && ((it.eventId!=null && it.eventId==reply.eventId) ||
            (it.operationId!=null && it.operationId==reply.operationId && it.text==reply.text && (it.memberId==null || reply.memberId==null || it.memberId==reply.memberId)))}
    }).distinctBy {it.id}
    val events=snapshot.events.groupBy {it.operationId}
    fun at(operation:String?,last:Boolean=false):Long? = operation?.let {id ->
        val times=events[id].orEmpty().mapNotNull {it.atMs}
        if(last) times.maxOrNull() else times.minOrNull()
    }
    val items=buildList {
        messages.forEach {add(AssistantTimeline.Message(it))}
        val grouped=mutableSetOf<String>()
        snapshot.groups.distinctBy {it.id}.forEach {group ->
            val tasks=snapshot.tasks.distinctBy {it.id}.filter {it.id !in grouped && (it.id in group.taskIds || snapshot.assignmentFor(it.id)?.groupId==group.id)}
            if(tasks.isNotEmpty()) {
                grouped+=tasks.map {it.id}
                val taskIds=tasks.map {it.id}.toSet()
                val time=(snapshot.events.filter {it.groupId==group.id || it.taskId in taskIds}.mapNotNull {it.atMs}+tasks.mapNotNull {at(it.activeRunId)}).minOrNull()
                add(AssistantTimeline.Group(group,tasks,time))
            }
        }
        snapshot.tasks.distinctBy {it.id}.filter {it.id !in grouped}.forEach {add(AssistantTimeline.Task(it,at(it.activeRunId)))}
        snapshot.deliveries.filter {it.status != "sent"}.distinctBy {it.id}.forEach {add(AssistantTimeline.Delivery(it))}
        snapshot.questionHistory.distinctBy {it.id}.forEach {add(AssistantTimeline.History(it))}
        snapshot.operations.filter {it.question!=null && snapshot.questionHistory.none {h -> h.operationId==it.id && h.requestId==it.question.requestId}}.distinctBy {it.id}.forEach {add(AssistantTimeline.Question(it,at(it.id,true)))}
        snapshot.operations.filter {it.role=="Execution" && it.phase in setOf(AssistantOperationPhase.Completed,AssistantOperationPhase.Failed) && !it.result.isNullOrBlank() && snapshot.groups.none {group -> group.requiresSummary &&
                (it.taskId in group.taskIds || snapshot.assignmentFor(it.taskId.orEmpty())?.groupId == group.id)} && messages.none {message -> message.role=="Assistant" && message.operationId==it.id && message.text==it.result}}
            .distinctBy {it.id}.forEach {add(AssistantTimeline.Result(it,at(it.id,true)))}
    }
    // Unknown timestamps keep their source order at the end; never fabricate a send time.
    return items.sortedBy {it.atMs ?: Long.MAX_VALUE}
}
