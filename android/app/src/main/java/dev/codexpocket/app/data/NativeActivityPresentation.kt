package dev.codexpocket.app.data

import kotlinx.serialization.json.*
import java.util.Locale

enum class ActivityKind { Command, Files, Tool, Web, Agents, Reasoning, Image, Commentary, Unknown }
enum class ActivityStatus { Running, Completed, Failed, Waiting, Interrupted, Unknown }
enum class WebAction { Search, Open, Find, Other }
data class ActivityOutput(val text: String = "", val truncated: Boolean = false)
/** Codex's own parse of a shell call (`commandActions`): what the command did, not how it was spelled. */
enum class StepKind { Read, ListFiles, Search, Skill, Unknown }
data class CommandStep(val kind: StepKind, val name: String = "", val path: String = "", val query: String = "", val command: String = "")
/** [display] is the command a reader recognises: native parsed actions, or the script without its shell launcher. */
data class ActivityCommand(val command: String, val cwd: String, val exitCode: Int?, val display: String = command, val durationMs: Long? = null,
    val steps: List<CommandStep> = emptyList()) {
    /** Every step is a known read/search/list: Codex renders these as exploration lines, never as a command. */
    val explored get() = steps.isNotEmpty() && steps.none { it.kind == StepKind.Unknown }
}
data class ActivityAgent(val threadId: String, val name: String, val status: ActivityStatus, val nativeStatus: String, val message: ActivityOutput)
data class PresentedActivity(
    val key: String,
    val kind: ActivityKind,
    val status: ActivityStatus,
    val title: String = "",
    val subtitle: String = "",
    val output: ActivityOutput = ActivityOutput(),
    val command: ActivityCommand? = null,
    val files: List<ActivityFile> = emptyList(),
    val agents: List<ActivityAgent> = emptyList(),
    val webAction: WebAction = WebAction.Other,
)
data class ActivitySummary(val commands: Int = 0, val files: Int = 0, val tools: Int = 0, val agents: Int = 0, val commentary: Int = 0, val reasoning: Int = 0, val failed: Int = 0,
    val web: Int = 0, val images: Int = 0, val reads: Int = 0, val searches: Int = 0, val lists: Int = 0, val skills: Int = 0)

/** Presentation only: native identities are retained; arbitrary arguments and JSON are never UI text. */
object NativeActivityPresentation {
    const val OUTPUT_LIMIT = 16_000
    private const val LABEL_LIMIT = 600

    fun attentionBoundary(item: TimelineItem): Boolean {
        val raw = item.raw
        val interaction = setOf("request_user_input", "requestUserInput", "send_user_message")
        return state(item) in setOf(ActivityStatus.Failed, ActivityStatus.Waiting, ActivityStatus.Interrupted) ||
            raw.array("questions").isNotEmpty() || raw.obj("params").array("questions").isNotEmpty() ||
            listOf(raw.string("tool"), raw.string("name")).any { it.substringAfterLast('.') in interaction } ||
            listOf(item.type, raw.string("method")).any {
                it.contains("approval", ignoreCase = true) || it.contains("requestUserInput", ignoreCase = true)
            }
    }

    fun foldedByDefault(row: TranscriptRow): Boolean = row.items.none(::attentionBoundary) &&
        (row.completedTurn || row.items.all { state(it) == ActivityStatus.Completed || (it.complete && state(it) !in setOf(ActivityStatus.Running, ActivityStatus.Waiting)) })

    fun groupExpanded(row: TranscriptRow, persisted: Boolean, manual: Boolean?, expandedItems: List<String>): Boolean =
        !foldedByDefault(row) || (manual ?: (persisted || row.items.any { it.key in expandedItems }))

    fun status(value: String): ActivityStatus = when (value.lowercase(Locale.ROOT)) {
        "inprogress", "in_progress", "running", "started" -> ActivityStatus.Running
        "completed", "success", "succeeded" -> ActivityStatus.Completed
        "failed", "errored", "error", "declined", "rejected" -> ActivityStatus.Failed
        "pending", "pendinginit", "waiting", "waitingonapproval", "waitingonuserinput" -> ActivityStatus.Waiting
        "interrupted", "cancelled", "canceled", "shutdown" -> ActivityStatus.Interrupted
        else -> ActivityStatus.Unknown
    }

    fun state(item: TimelineItem, completedTurn: Boolean = false): ActivityStatus {
        val raw = item.raw
        val exit = (raw["exitCode"] as? JsonPrimitive)?.intOrNull
        if ((item.type == "commandExecution" && exit != null && exit != 0) ||
            (item.type == "dynamicToolCall" && (raw["success"] as? JsonPrimitive)?.booleanOrNull == false) ||
            raw.obj("result").flag("isError") || raw.obj("error").string("message").isNotBlank() ||
            (item.type == "imageGeneration" && raw["failure"] is JsonObject)) return ActivityStatus.Failed
        val native = item.status.ifBlank { raw.string("status") }
        if (native.isNotBlank()) {
            val state = status(native)
            return if (completedTurn && state in setOf(ActivityStatus.Running, ActivityStatus.Waiting)) ActivityStatus.Unknown else state
        }
        return when {
            item.type == "subAgentActivity" -> status(raw.string("kind"))
            item.type == "functionCallOutput" && ((raw["output"] as? JsonPrimitive)?.isString == true || raw["output"] is JsonArray) -> ActivityStatus.Completed
            item.complete -> ActivityStatus.Completed
            completedTurn -> ActivityStatus.Unknown
            item.liveStarted -> ActivityStatus.Running
            else -> ActivityStatus.Unknown
        }
    }

    fun describe(item: TimelineItem, completedTurn: Boolean = false): PresentedActivity {
        if (!ConversationPresentation.visible(item)) return PresentedActivity(item.key, ActivityKind.Unknown, ActivityStatus.Unknown)
        val raw = item.raw
        val state = state(item, completedTurn)
        return when (item.type) {
            "commandExecution" -> {
                // Live outputDelta appends to the model's command + initial output text.
                val command = raw.string("command").ifBlank { if (raw.isEmpty()) item.text.substringBefore('\n') else "" }
                val output = when {
                    command.isNotBlank() && item.text.startsWith(command) -> {
                        val start = command.length + if (item.text.startsWith("\n\n", command.length)) 2 else 0
                        item.text.substring(start, minOf(item.text.length, start + OUTPUT_LIMIT + 1))
                    }
                    item.text.isNotBlank() -> item.text
                    else -> raw.string("aggregatedOutput")
                }.ifBlank { raw.string("aggregatedOutput") }
                PresentedActivity(item.key, ActivityKind.Command, state, command.take(LABEL_LIMIT), raw.string("cwd").take(LABEL_LIMIT),
                    bounded(sequenceOf(output)).let { if (raw.flag("pocketOutputTruncated")) it.copy(truncated = true) else it }, ActivityCommand(command, raw.string("cwd"), (raw["exitCode"] as? JsonPrimitive)?.intOrNull,
                        displayCommand(raw, command).take(LABEL_LIMIT), (raw["durationMs"] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }, commandSteps(raw)))
            }
            "fileChange", "turn/diff/updated" -> PresentedActivity(item.key, ActivityKind.Files, state, files = activityFiles(item))
            "mcpToolCall", "dynamicToolCall", "functionCallOutput" -> {
                val text = sequence {
                    yield(raw.obj("error").string("message"))
                    when (item.type) {
                        "mcpToolCall" -> {
                            val content = textContent(raw.obj("result")["content"])
                            if (content.any(String::isNotBlank)) yieldAll(content)
                            else {
                                val structured = raw.obj("result").obj("structuredContent")
                                // No generic tree walk: keys, argument blobs and metadata are not prose.
                                yieldAll(sequenceOf("title", "summary", "message", "text").map { structured.string(it) })
                            }
                        }
                        "dynamicToolCall" -> yieldAll(textContent(raw["contentItems"]))
                        else -> {
                            val output = raw["output"]
                            if (output is JsonPrimitive && output.isString) yield(output.content)
                            else yieldAll(textContent(output))
                        }
                    }
                    // Legacy cache and incremental plain text remain human text, never raw.toString().
                    if (item.text.isNotBlank() && item.type != "functionCallOutput") yield(item.text)
                }
                PresentedActivity(item.key, ActivityKind.Tool, state,
                    raw.obj("appContext").string("actionName").ifBlank { raw.string("tool", raw.string("name")) }.take(LABEL_LIMIT),
                    raw.obj("appContext").string("appName").ifBlank { raw.string("server", raw.string("namespace")) }.take(LABEL_LIMIT), bounded(text))
            }
            "webSearch" -> {
                val action = raw.obj("action")
                val kind = when (action.string("type")) { "search" -> WebAction.Search; "openPage" -> WebAction.Open; "findInPage" -> WebAction.Find; else -> WebAction.Other }
                val queries = action.array("queries").asSequence().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.take(8).map { it.take(LABEL_LIMIT) }.joinToString(" · ")
                val subtitle = when (kind) {
                    WebAction.Open -> action.string("url")
                    WebAction.Find -> listOf(action.string("pattern"), action.string("url")).filter(String::isNotBlank).joinToString(" · ")
                    else -> queries.ifBlank { action.string("query").ifBlank { raw.string("query") } }
                }
                // Search results are opaque upstream. Only documented human fields are displayed.
                val results = raw.array("results").asSequence().flatMap { result ->
                    val record = result.asObject()
                    sequenceOf(record.string("title"), record.string("url"), record.string("snippet"))
                }
                PresentedActivity(item.key, ActivityKind.Web, state, subtitle = subtitle.take(LABEL_LIMIT), output = bounded(results), webAction = kind)
            }
            "collabAgentToolCall", "subAgentActivity" -> PresentedActivity(item.key, ActivityKind.Agents, state,
                title = raw.string("tool", raw.string("kind")).take(LABEL_LIMIT),
                output = bounded(sequenceOf(raw.string("prompt"))), agents = agents(item))
            "reasoning", "agentMessage" -> PresentedActivity(item.key, if (item.type == "reasoning") ActivityKind.Reasoning else ActivityKind.Commentary, state, output = bounded(sequenceOf(item.text)))
            "imageView", "imageGeneration" -> PresentedActivity(item.key, ActivityKind.Image, state,
                subtitle = raw.string("path", raw.string("savedPath")).take(LABEL_LIMIT), output = bounded(sequenceOf(raw.string("revisedPrompt"), raw.obj("failure").string("message"))))
            else -> PresentedActivity(item.key, ActivityKind.Unknown, state)
        }
    }

    fun summary(items: List<TimelineItem>): ActivitySummary {
        var result = ActivitySummary()
        val nativeFiles = mutableMapOf<String, Int>()
        val latestDiff = mutableMapOf<String, TimelineItem>()
        items.filter(ConversationPresentation::visible).forEach { item ->
            result = when (item.type) {
                "commandExecution" -> {
                    val steps = commandSteps(item.raw)
                    if (steps.isNotEmpty() && steps.none { it.kind == StepKind.Unknown }) result.copy(
                        reads = result.reads + steps.count { it.kind == StepKind.Read },
                        searches = result.searches + steps.count { it.kind == StepKind.Search },
                        lists = result.lists + steps.count { it.kind == StepKind.ListFiles },
                        skills = result.skills + steps.count { it.kind == StepKind.Skill })
                    else result.copy(commands = result.commands + 1)
                }
                "fileChange" -> {
                    nativeFiles[item.turnId] = nativeFiles.getOrDefault(item.turnId, 0) + item.raw.array("changes").count { it is JsonObject }
                    result
                }
                "turn/diff/updated" -> { latestDiff[item.turnId] = item; result }
                "collabAgentToolCall", "subAgentActivity" -> result.copy(agents = result.agents + 1)
                "agentMessage" -> result.copy(commentary = result.commentary + 1)
                "reasoning" -> result.copy(reasoning = result.reasoning + 1)
                "webSearch" -> result.copy(web = result.web + 1)
                "imageView", "imageGeneration" -> result.copy(images = result.images + 1)
                else -> result.copy(tools = result.tools + 1)
            }
            if (state(item) == ActivityStatus.Failed) result = result.copy(failed = result.failed + 1)
        }
        // A turn diff is a cumulative snapshot, not another edit. Preserve every event,
        // but count only native changes or the latest aggregate when those are unavailable.
        return result.copy(files = nativeFiles.values.sum() + latestDiff.entries.sumOf { (turn, diff) ->
            if (turn in nativeFiles) 0 else activityFiles(diff).size
        })
    }

    fun commandSteps(raw: JsonObject): List<CommandStep> = raw.array("commandActions").mapNotNull { element ->
        val action = element as? JsonObject ?: return@mapNotNull null
        val command = action.string("command", action.string("cmd")).take(LABEL_LIMIT)
        val path = action.string("path").take(LABEL_LIMIT)
        when (action.string("type")) {
            "read" -> {
                val name = action.string("name").ifBlank { path.substringAfterLast('/').substringAfterLast('\\') }.take(LABEL_LIMIT)
                skillName(path, name)?.let { CommandStep(StepKind.Skill, it, path, command = command) } ?: CommandStep(StepKind.Read, name, path, command = command)
            }
            "listFiles", "list_files" -> CommandStep(StepKind.ListFiles, path = path, command = command)
            "search" -> CommandStep(StepKind.Search, path = path, query = action.string("query").take(LABEL_LIMIT), command = command)
            else -> CommandStep(StepKind.Unknown, command = command)
        }
    }

    /** `…/skills/<name>/SKILL.md` is a skill load, which Codex names by the skill rather than the file. */
    fun skillName(path: String, name: String): String? {
        if (!name.equals("SKILL.md", ignoreCase = true)) return null
        val parts = path.replace('\\', '/').split('/').filter(String::isNotBlank)
        if (parts.size < 3 || !parts[parts.size - 3].equals("skills", ignoreCase = true)) return null
        return parts[parts.size - 2]
    }

    // Codex launches every command through a shell; the launcher and its flags carry no meaning for a reader.
    private val shells = setOf("pwsh", "powershell", "cmd", "bash", "zsh", "sh")
    private val scriptFlags = setOf("-command", "-c", "/c", "-lc")

    /** Native `commandActions` first (what Codex itself shows), then the script without its shell launcher. */
    fun displayCommand(raw: JsonObject, command: String): String {
        val parsed = raw.array("commandActions").mapNotNull { (it as? JsonObject)?.string("command")?.trim()?.takeIf(String::isNotBlank) }.distinct()
        if (parsed.isNotEmpty()) return parsed.joinToString(" && ")
        val text = command.trim()
        val quoted = text.startsWith('"')
        val end = if (quoted) text.indexOf('"', 1) else text.indexOf(' ').let { if (it < 0) text.length else it }
        if (end < 0) return text
        val executable = text.substring(if (quoted) 1 else 0, end)
        if (executable.substringAfterLast('\\').substringAfterLast('/').lowercase(Locale.ROOT).removeSuffix(".exe") !in shells) return text
        var rest = text.substring(minOf(text.length, end + if (quoted) 1 else 0)).trimStart()
        var previousWasFlag = false
        repeat(10) {
            val token = rest.substringBefore(' ')
            val after = rest.substringAfter(' ', "").trimStart()
            val flag = token.startsWith('-') || token.startsWith('/')
            when {
                token.lowercase(Locale.ROOT) in scriptFlags -> {
                    val script = after.trim()
                    val unquoted = when {
                        script.length >= 2 && script.first() == '"' && script.last() == '"' ->
                            // Launcher argv is JSON/C-style escaped inside double quotes.
                            script.substring(1, script.length - 1).replace("\\\\", "\u0000").replace("\\\"", "\"").replace("\u0000", "\\")
                        script.length >= 2 && script.first() == '\'' && script.last() == '\'' -> script.substring(1, script.length - 1)
                        else -> script
                    }
                    return unquoted.trim().ifBlank { text }
                }
                flag -> previousWasFlag = true
                previousWasFlag -> previousWasFlag = false // value of an option such as -ExecutionPolicy Bypass
                else -> return text
            }
            if (after.isEmpty()) return text
            rest = after
        }
        return text
    }

    /**
     * The one line a reader recognises a script by. Here-string and quote openers such as `@'` or a lone
     * `"` carry no meaning, so the first line with a letter or digit is used instead.
     */
    fun headline(script: String): String {
        val lines = script.lineSequence().map(String::trim).filter(String::isNotBlank).toList()
        return (lines.firstOrNull { line -> line.any(Char::isLetterOrDigit) } ?: lines.firstOrNull()).orEmpty()
    }

    /** Compact elapsed time in the Codex desktop style: 840ms, 3s, 1m 5s, 2h 4m. */
    fun duration(ms: Long): String = when {
        ms < 1_000 -> "${ms}ms"
        ms < 60_000 -> "${ms / 1_000}s"
        ms < 3_600_000 -> "${ms / 60_000}m ${(ms / 1_000) % 60}s".removeSuffix(" 0s")
        else -> "${ms / 3_600_000}h ${(ms / 60_000) % 60}m".removeSuffix(" 0m")
    }

    private fun agents(item: TimelineItem): List<ActivityAgent> {
        val raw = item.raw
        if (item.type == "subAgentActivity") {
            val id = raw.string("agentThreadId").takeIf(String::isNotBlank) ?: return emptyList()
            return listOf(ActivityAgent(id, raw.string("agentPath"), status(raw.string("kind")), raw.string("kind"), ActivityOutput()))
        }
        val states = raw.obj("agentsStates")
        val ids = (raw.array("receiverThreadIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull } + states.keys).filter(String::isNotBlank).distinct()
        return ids.map { id ->
            val agent = states.obj(id)
            ActivityAgent(id, agent.string("name", agent.string("nickname")), status(agent.string("status")), agent.string("status"), bounded(sequenceOf(agent.string("message"))))
        }
    }

    private fun textContent(value: JsonElement?): Sequence<String> = (value as? JsonArray).orEmpty().asSequence().mapNotNull {
        val part = it as? JsonObject ?: return@mapNotNull null
        part.string("text").takeIf { _ -> part.string("type") in setOf("text", "inputText", "input_text") }
    }

    private fun bounded(parts: Sequence<String>): ActivityOutput {
        val text = StringBuilder()
        for (part in parts.filter(String::isNotBlank)) {
            val separator = if (text.isEmpty()) "" else "\n"
            if (text.length + separator.length + part.length > OUTPUT_LIMIT) {
                val remaining = (OUTPUT_LIMIT - text.length).coerceAtLeast(0)
                text.append(separator.take(remaining))
                text.append(part, 0, minOf(part.length, (remaining - separator.length).coerceAtLeast(0)))
                return ActivityOutput(text.toString(), true)
            }
            text.append(separator).append(part)
        }
        return ActivityOutput(text.toString())
    }
}
