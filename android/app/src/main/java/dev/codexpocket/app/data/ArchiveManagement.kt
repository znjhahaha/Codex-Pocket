package dev.codexpocket.app.data

/** Selects a transport for native archive management, never an execution owner. The broker
 * still proves the exact thread in this store and rechecks all owners before the mutation. */
fun PocketState.archiveManagementRuntime(chat: ChatEntity): RuntimeInfo? {
    if (!online || computerId != chat.key.computerId || chat.conflict || chat.isSubagent) return null
    val method = if (chat.archived) "thread/unarchive" else "thread/archive"
    val channels = runtimes.filter { it.online && it.configStoreId == chat.key.configStoreId &&
        it.role != "managedHost" && it.source in setOf("cli", "desktop") && supports(method, it) }
    val owners = channels.filter { chat.key.nativeId in it.loadedThreadIds }
    if (owners.size > 1) return null
    channels.firstOrNull { it.id == chat.runtimeId }?.let { return it }
    // A live row cannot silently switch its execution instance. History/retired rows may
    // use an existing management channel; choosing it does not update the cached runtimeId.
    if (runtimes.any { it.id == chat.runtimeId && it.online }) return null
    return owners.singleOrNull() ?: channels.sortedWith(compareBy<RuntimeInfo> { it.source != chat.source }.thenBy { it.id }).firstOrNull()
}

fun PocketState.archiveUnavailableReason(chat: ChatEntity? = selected): String? = when {
    !online -> "连接电脑后可用"
    chat == null || chat.key.computerId != computerId -> "请重新打开此电脑的会话"
    chat.conflict -> "先确认会话归属"
    chat.isSubagent -> "子会话为只读"
    commands.any { it.chatKey == chat.key && it.method in setOf("thread/archive", "thread/unarchive") && CommandPolicy.blocksNewSend(it.status) } -> "归档操作结果待确认，不会重复发送"
    busy || sendBusy -> "当前操作完成后可用"
    archiveManagementRuntime(chat) == null -> "等待同一配置档案的 Codex 就绪"
    else -> null
}
