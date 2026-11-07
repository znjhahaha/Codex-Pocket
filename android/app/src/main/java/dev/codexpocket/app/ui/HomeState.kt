package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import java.util.Locale

/** Exact native paths: a similar-looking path on another computer is another project. */
internal data class HomeProject(val computerId: String, val cwd: String) {
    val id: String get() = homeScopeKey(computerId, cwd)
    val title: String get() = projectName(cwd).ifBlank { "无项目" }
}

internal data class HomeChatGroup(val id: String, val title: String, val chats: List<ChatEntity>, val subtitle: String? = null)

internal fun homeScopeKey(computerId: String, runtimeOrPath: String?): String =
    "${computerId.length}:$computerId" + (runtimeOrPath?.let { "${it.length}:$it" } ?: "-")

private fun homeOrderedChats(chats: List<ChatEntity>): List<ChatEntity> = chats
    .sortedWith(compareByDescending<ChatEntity> { homeUpdatedAtMillis(it.updatedAt) }.thenBy { it.key.encoded }.thenBy { it.runtimeId }.thenBy { it.title })
    .distinctBy { it.key.encoded }

internal fun homeProjects(chats: List<ChatEntity>, computerId: String, runtimeFilter: String?, selected: HomeProject?): List<HomeProject> {
    val projects = homeOrderedChats(chats.filter {
        (computerId.isBlank() || it.key.computerId == computerId) && (runtimeFilter == null || it.runtimeId == runtimeFilter)
    }).map { HomeProject(it.key.computerId, it.cwd) }.distinct()
    return if (selected != null && selected !in projects) listOf(selected) + projects else projects
}

internal fun homeFilteredChats(chats: List<ChatEntity>, computerId: String, runtimeFilter: String?, project: HomeProject?, search: String, archived: Boolean): List<ChatEntity> {
    val query = search.trim()
    return homeOrderedChats(chats.filter {
        (computerId.isBlank() || it.key.computerId == computerId) &&
            (runtimeFilter == null || it.runtimeId == runtimeFilter) && it.archived == archived &&
            (project == null || (it.key.computerId == project.computerId && it.cwd == project.cwd)) &&
            (query.isEmpty() || it.title.contains(query, true) || it.cwd.contains(query, true) || homeSafePreview(it.preview).contains(query, true))
    })
}

internal fun homeProjectGroups(chats: List<ChatEntity>): List<HomeChatGroup> = homeOrderedChats(chats)
    .groupBy { HomeProject(it.key.computerId, it.cwd) }
    .map { (project, rows) -> HomeChatGroup("project:${project.id}", project.title, rows, project.cwd.takeIf(String::isNotBlank)) }

/** Selected projects partition into mutually exclusive sections; special views stay flat. */
internal fun homeChatGroups(chats: List<ChatEntity>, statuses: Map<String, String>, search: String, archived: Boolean): List<HomeChatGroup> {
    val ordered = homeOrderedChats(chats)
    if (search.isNotBlank() || archived) return listOf(HomeChatGroup(if (archived) "archive" else "search", if (archived) "已归档" else "搜索结果", ordered)).filter { it.chats.isNotEmpty() }
    val partitions = ordered.groupBy { chat ->
        when { statuses[chat.key.encoded] == "inProgress" -> "running"; chat.pinned -> "pinned"; else -> "recent" }
    }
    return listOf("running" to "运行中", "pinned" to "置顶", "recent" to "最近").mapNotNull { (id, title) ->
        partitions[id]?.let { HomeChatGroup(id, title, it) }
    }
}

// Cached previews have no trusted item type. Reject structured/control text rather than
// trying to extract a possibly private protocol field as a user-visible summary.
private val homeProtocolPreview = Regex(
    "(?im)(```|[<{]\\s*[/!]?\\s*(?:hook|system|developer|tool|function|environment_context|instructions|send_user_message_question_reply)|" +
        "(?:^|\\s)(?:hooks?/|hook(?:Prompt|Started|Completed|_prompt|_started|_completed)\\b|" +
        "(?:item|turn|thread)/(?:started|completed|updated|delta|error|failed)\\b)|" +
        "^\\s*(?:(?:SessionStart|UserPromptSubmit|PreToolUse|PostToolUse|SubagentStop)\\s*:|hooks?\\s*[:/]|hook\\s+(?:started|completed|prompt)\\b)|" +
        "[\"'](?:jsonrpc|method|params|tool_calls|hook_event_name)[\"']\\s*:)"
)

internal fun homeSafePreview(raw: String): String {
    val text = homeUnwrapPreview(raw.trim())
    if (text.isEmpty() || text.length > 16_384 || text.startsWith('{') || text.startsWith('[') || homeProtocolPreview.containsMatchIn(text)) return ""
    return text.filter { !it.isISOControl() || it.isWhitespace() }.replace(Regex("\\s+"), " ").take(160)
}

/** Show the typed request, not Codex's attachment wrapper; a cut-off preview falls back to file names. */
internal fun homeUnwrapPreview(text: String): String {
    if (!text.startsWith("# Files mentioned by the user:")) return text
    val parsed = dev.codexpocket.app.data.UserMessageParsing.parse(text)
    if (parsed.request != text) return parsed.request
    // Collapsed single-line previews lose the newline before the request header.
    text.lastIndexOf("## My request:").takeIf { it >= 0 }?.let { return text.substring(it + 14).trim() }
    val names = Regex("(?:^|\\s)## ([^:\\n]+):").findAll(text).map { it.groupValues[1].trim() }.filter { it != "My request" }.toList()
    return names.joinToString("、")
}

/** Keep actionable signals visible offline; an acknowledged approval is no longer pending. */
internal fun homeChatStatus(s: PocketState, chat: ChatEntity): String {
    val questions = s.pendingQuestions[chat.key.encoded]
        ?: if (s.selected?.key == chat.key) s.advanced.asyncQuestions else emptyList()
    if (questions.any { !it.flag("submitted") } || s.approvals.any {
            !it.submitted && it.runtimeId == chat.runtimeId && it.threadId == chat.key.nativeId && !ConversationPresentation.hookType(it.method)
        }) return "pending"
    if (s.commands.any { it.chatKey == chat.key && CommandPolicy.blocksNewSend(it.status) }) return "unknown"
    val online = s.online && s.runtimes.any { it.id == chat.runtimeId && it.online }
    val nativeStatus = if (online && s.selected?.key == chat.key) {
        if (s.timeline.activeTurn != null) "inProgress" else s.timeline.status.ifBlank { chat.status }
    } else chat.status
    val normalized = when (nativeStatus.lowercase(Locale.ROOT)) {
        "inprogress", "in_progress", "running", "active" -> "inProgress"
        "pending", "waiting", "waitingonapproval", "waitingonuserinput" -> "pending"
        "failed", "systemerror" -> "failed"
        "completed", "interrupted", "unknown", "idle", "notloaded" -> nativeStatus.lowercase(Locale.ROOT)
        else -> "idle"
    }
    return if (online || normalized in setOf("failed", "pending", "unknown")) normalized else "offline"
}

internal fun homeUpdatedAtMillis(value: Long): Long = when {
    value <= 0 -> 0
    value < 100_000_000_000L -> value * 1000
    else -> value
}
