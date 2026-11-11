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
    data class Task(val task:AssistantTask,override val atMs:Long?):AssistantTimeline {override val key="task:${task.id}"}
    data class Question(val operation:AssistantOperation,override val atMs:Long?):AssistantTimeline {override val key="question:${operation.id}"}
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
        state.messages.none {it.role=="Assistant" && it.operationId==reply.operationId && it.text==reply.text}
    }).distinctBy {it.id}
    val events=snapshot.events.groupBy {it.operationId}
    fun at(operation:String?,last:Boolean=false):Long? = operation?.let {id ->
        val times=events[id].orEmpty().mapNotNull {it.atMs}
        if(last) times.maxOrNull() else times.minOrNull()
    }
    val items=buildList {
        messages.forEach {add(AssistantTimeline.Message(it))}
        snapshot.tasks.distinctBy {it.id}.forEach {add(AssistantTimeline.Task(it,at(it.activeRunId)))}
        snapshot.operations.filter {it.question!=null}.distinctBy {it.id}.forEach {add(AssistantTimeline.Question(it,at(it.id,true)))}
        snapshot.operations.filter {it.role=="Execution" && it.phase in setOf(AssistantOperationPhase.Completed,AssistantOperationPhase.Failed) && !it.result.isNullOrBlank()}
            .distinctBy {it.id}.forEach {add(AssistantTimeline.Result(it,at(it.id,true)))}
    }
    // Unknown timestamps keep their source order at the end; never fabricate a send time.
    return items.sortedBy {it.atMs ?: Long.MAX_VALUE}
}
