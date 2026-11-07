package dev.codexpocket.app.data

import kotlinx.serialization.json.*

internal enum class ComposerTriggerKind { Command, Reference }
internal data class ComposerTriggerRange(val start: Int, val end: Int)

/** UTF-16 offsets, like TextFieldValue. The snapshot also lets the parent reject stale queries. */
internal data class ComposerTrigger(
    val kind: ComposerTriggerKind,
    val query: String,
    val range: ComposerTriggerRange,
    val cursor: Int,
    val draft: String,
)

internal data class ComposerReplacement(val text: String, val cursor: Int)

/** Only the collapsed caret's token is active; the suffix of that token is replaced as well. */
internal fun composerTrigger(text: String, selectionStart: Int, selectionEnd: Int = selectionStart): ComposerTrigger? {
    if (selectionStart != selectionEnd || selectionStart !in 1..text.length) return null
    val cursor = selectionStart
    var start = cursor - 1
    while (start > 0 && !composerTokenBoundary(text[start - 1])) start--
    val kind = when (text[start]) {
        '/' -> if (start == 0) ComposerTriggerKind.Command else return null
        '@' -> if (start == 0 || text[start - 1].isWhitespace() || text[start - 1] in "([{"
        ) ComposerTriggerKind.Reference else return null
        else -> return null
    }
    var end = cursor
    while (end < text.length && !composerTokenBoundary(text[end])) end++
    val token = text.substring(start + 1, end)
    if ('@' in token || '$' in token || (kind == ComposerTriggerKind.Command &&
            token.any { !it.isLetterOrDigit() && it !in "-_" })) return null
    if (composerInCode(text, start)) return null
    return ComposerTrigger(kind, text.substring(start + 1, cursor), ComposerTriggerRange(start, end), cursor, text)
}

private fun composerTokenBoundary(char: Char) = char.isWhitespace() || char in "([{)]},;!?<>\"'`"

/** Markdown fences accept up to three leading spaces and require a matching closing fence. */
private fun composerInCode(text: String, offset: Int): Boolean {
    var fence: Char? = null
    var fenceLength = 0
    var inlineTicks = 0
    var lineStart = 0
    while (lineStart <= offset) {
        val lineEnd = text.indexOf('\n', lineStart).let { if (it < 0) text.length else it }
        var content = lineStart
        while (content < lineEnd && text[content] == ' ') content++
        var ticksEnd = content
        if (content < lineEnd && text[content] in "`~") {
            while (ticksEnd < lineEnd && text[ticksEnd] == text[content]) ticksEnd++
        }
        val count = ticksEnd - content
        val marker = text.getOrNull(content)
        val closing = fence != null && marker == fence && count >= fenceLength &&
            text.substring(ticksEnd, lineEnd).isBlank()
        val opening = fence == null && count >= 3 && content - lineStart <= 3 &&
            (marker != '`' || '`' !in text.substring(ticksEnd, lineEnd))
        if (closing && content - lineStart <= 3) {
            fence = null; inlineTicks = 0
        } else if (opening) {
            fence = marker; fenceLength = count; inlineTicks = 0
        } else if (fence == null) {
            var index = lineStart
            while (index < minOf(lineEnd, offset)) {
                if (text[index] == '\\') { index += 2; continue }
                if (text[index] != '`') { index++; continue }
                val tickStart = index
                while (index < lineEnd && text[index] == '`') index++
                val run = index - tickStart
                if (inlineTicks == 0) inlineTicks = run else if (inlineTicks == run) inlineTicks = 0
            }
        }
        if (lineEnd >= offset) return fence != null || inlineTicks != 0
        lineStart = lineEnd + 1
    }
    return false
}

/** No send or action execution; surrounding text and punctuation survive verbatim. */
internal fun replaceComposerTrigger(text: String, trigger: ComposerTrigger, token: String): ComposerReplacement? {
    if (text != trigger.draft || composerTrigger(text, trigger.cursor) != trigger) return null
    val range = trigger.range
    return ComposerReplacement(text.replaceRange(range.start, range.end, token), range.start + token.length)
}

/** This is an allowlist of actions the Android parent can actually handle. None is offered by default. */
internal enum class ComposerAction(val command: String, val title: String, val detail: String, val aliases: String = "") {
    Plan("plan", "Plan", "切换规划模式"),
    Models("model", "模型", "选择模型和推理强度", "models"),
    Goal("goal", "目标", "设置当前对话目标"),
    Rename("rename", "重命名", "修改当前对话名称"),
    Compact("compact", "压缩上下文", "压缩当前对话上下文"),
    Files("mention", "文件引用", "选择工作区文件或原生引用", "files reference"),
}

internal enum class ComposerSuggestionGroup(val label: String) {
    Commands("命令"), Skills("技能"), Files("文件"), Plugins("插件"),
}

/** [trigger] identifies the returned data, not just its query string. Parent owns runtime/generation guards. */
internal data class ComposerSuggestionsState(
    val trigger: ComposerTrigger? = null,
    val online: Boolean = false,
    val loading: Boolean = false,
    val data: List<JsonObject> = emptyList(),
    val error: String? = null,
    val unsupported: Set<String> = emptySet(),
    val actions: Set<ComposerAction> = emptySet(),
)

internal data class ComposerSuggestion(
    val key: String,
    val group: ComposerSuggestionGroup,
    val title: String,
    val detail: String,
    val action: ComposerAction? = null,
    val reference: JsonObject? = null,
    val token: String? = null,
)
internal data class ComposerSuggestionSection(val group: ComposerSuggestionGroup, val candidates: List<ComposerSuggestion>)

private fun JsonObject.referenceString(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()

/** Use the repository's token rule. A raw plugin catalog entry has no mention proof. */
internal fun composerReferenceToken(value: JsonObject): String? {
    if (listOf("supported", "enabled", "installed").any { key ->
            key in value && (value[key] as? JsonPrimitive)?.booleanOrNull != true }) return null
    val name = value.referenceString("name")
    val path = value.referenceString("path")
    if (path.isBlank() || path.any { it.isISOControl() } || name.any { it.isISOControl() }) return null
    val token = referenceToken(value) ?: return null
    if ("token" in value && value.referenceString("token") != token) return null
    return token
}

/** Queries never select a candidate or run commands. Only current, verified results become rows. */
internal fun composerSuggestionGroups(trigger: ComposerTrigger, state: ComposerSuggestionsState): List<ComposerSuggestionSection> {
    if (!state.online) return emptyList()
    val candidates = mutableListOf<ComposerSuggestion>()
    val query = trigger.query
    if (trigger.kind == ComposerTriggerKind.Command) {
        ComposerAction.entries.filter { it in state.actions &&
            listOf(it.command, it.title, it.aliases).any { text -> text.contains(query, ignoreCase = true) } }
            .forEach { candidates += ComposerSuggestion("command:${it.command}", ComposerSuggestionGroup.Commands,
                "/${it.command}", "${it.title} · ${it.detail}", action = it) }
    }
    if (state.trigger == trigger && !state.loading && state.error == null) {
        state.data.forEach { value ->
            val token = composerReferenceToken(value) ?: return@forEach
            val group = when (value.referenceString("type")) {
                "skill" -> ComposerSuggestionGroup.Skills
                "file", "directory" -> ComposerSuggestionGroup.Files
                "mention" -> ComposerSuggestionGroup.Plugins
                else -> return@forEach
            }
            if (trigger.kind == ComposerTriggerKind.Command && group != ComposerSuggestionGroup.Skills) return@forEach
            val name = value.referenceString("name").ifBlank { value.referenceString("path") }
            val path = value.referenceString("path")
            if (!name.contains(query, ignoreCase = true) && !path.contains(query, ignoreCase = true)) return@forEach
            val detail = value.referenceString("description").takeIf(String::isNotBlank) ?: path
            candidates += ComposerSuggestion("${group.name}:$path:$name", group, name, detail, reference = value, token = token)
        }
    }
    return ComposerSuggestionGroup.entries.mapNotNull { group ->
        candidates.filter { it.group == group }.distinctBy { it.key }.takeIf { it.isNotEmpty() }
            ?.let { ComposerSuggestionSection(group, it) }
    }
}
