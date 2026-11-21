package dev.codexpocket.app.data

import kotlinx.serialization.json.*

data class AssistantProjectIdentity(val computerId: String, val assistantRuntimeId: String,
    val sourceRuntimeId: String, val sourceConfigStoreId: String, val threadId: String)

internal class AssistantProjectReadScope(val connection: Any, val connectionGeneration: Long,
    val identity: AssistantProjectIdentity) {
    fun matches(other: AssistantProjectReadScope?) = other != null && connection === other.connection &&
        connectionGeneration == other.connectionGeneration && identity == other.identity
    fun params() = buildJsonObject {
        put("runtimeId",identity.assistantRuntimeId);put("sourceRuntimeId",identity.sourceRuntimeId)
        put("sourceConfigStoreId",identity.sourceConfigStoreId);put("threadId",identity.threadId)
    }
}

data class AssistantProjectProof(val sourceGeneration: String, val permissionRevision: String, val writable: Boolean) {
    companion object {
        internal fun from(raw: JsonObject, expected: AssistantProjectIdentity): AssistantProjectProof? = runCatching {
            require(raw.keys == setOf("sourceRuntimeId","sourceConfigStoreId","threadId","verified","writable","sourceGeneration","permissionRevision"))
            fun text(name: String) = (raw[name] as JsonPrimitive).also {require(it.isString)}.content.also {
                require(it.isNotBlank() && it.toByteArray(Charsets.UTF_8).size <= 256 && '\u0000' !in it)
            }
            fun boolean(name: String) = (raw[name] as JsonPrimitive).also {require(!it.isString)}.boolean
            require(text("sourceRuntimeId") == expected.sourceRuntimeId && text("sourceConfigStoreId") == expected.sourceConfigStoreId && text("threadId") == expected.threadId)
            require(boolean("verified"))
            AssistantProjectProof(text("sourceGeneration"),text("permissionRevision"),boolean("writable"))
        }.getOrNull()
    }
}

/** An observation for the foreground selection. Ordinary assistant snapshots cannot populate it. */
data class AssistantProjectReadState(val identity: AssistantProjectIdentity? = null, val loading: Boolean = false,
    val proof: AssistantProjectProof? = null, val error: String? = null) {
    fun canWrite(expected: AssistantProjectIdentity?) = expected != null && identity == expected && !loading && error == null && proof?.writable == true
    fun status(expected: AssistantProjectIdentity?) = when {
        expected == null || expected != identity -> "未核验，按只读处理"
        loading -> "正在核验，当前按只读处理"
        error != null -> error
        proof == null -> "未核验，按只读处理"
        proof.writable -> "已核验 · 可写"
        else -> "已核验 · 只读"
    }
}

internal fun PocketState.assistantWorkspaces(): List<AssistantWorkspaceChoice> = (chats + agentChats).filter { chat ->
    chat.key.computerId == computerId && !chat.conflict && runtimes.any {it.id == chat.runtimeId && it.configStoreId == chat.key.configStoreId && it.online}
}.map { chat ->
    val identity = assistant.snapshot?.runtimeId?.let {AssistantProjectIdentity(computerId,it,chat.runtimeId,chat.key.configStoreId,chat.key.nativeId)}
    AssistantWorkspaceChoice(chat.runtimeId,chat.key.nativeId,chat.title,
        canWrite = online && assistantProject.canWrite(identity),parentThreadId = chat.parentThreadId,isSubagent = chat.isSubagent,
        configStoreId = chat.key.configStoreId,permissionStatus = assistantProject.status(identity))
}.distinctBy {it.selectionKey}
