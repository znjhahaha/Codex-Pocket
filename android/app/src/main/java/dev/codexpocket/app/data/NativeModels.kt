package dev.codexpocket.app.data

import kotlinx.serialization.json.*

val pocketJson = Json { ignoreUnknownKeys = true; prettyPrint = false }
fun JsonObject.string(key: String, fallback: String = "") = (get(key) as? JsonPrimitive)?.contentOrNull ?: fallback
fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: buildJsonObject {}
fun JsonObject.array(key: String) = get(key) as? JsonArray ?: JsonArray(emptyList())
fun JsonObject.flag(key: String, fallback: Boolean = false) = (get(key) as? JsonPrimitive)?.booleanOrNull ?: fallback
fun JsonObject.number(key: String, fallback: Long = 0) = (get(key) as? JsonPrimitive)?.longOrNull ?: fallback
fun JsonElement.asObject() = this as? JsonObject ?: buildJsonObject {}
fun jsonObject(vararg pairs: Pair<String, JsonElement>) = JsonObject(mapOf(*pairs))

data class ThreadKey(val computerId: String, val configStoreId: String, val nativeId: String) {
    val encoded: String get() = JsonArray(listOf(computerId, configStoreId, nativeId).map(::JsonPrimitive)).toString()
    companion object {
        fun decode(value: String): ThreadKey {
            val a = pocketJson.parseToJsonElement(value).jsonArray
            require(a.size == 3)
            return ThreadKey(a[0].jsonPrimitive.content, a[1].jsonPrimitive.content, a[2].jsonPrimitive.content)
        }
    }
}

data class RuntimeInfo(val id: String, val source: String, val version: String, val configStoreId: String, val online: Boolean, val loadedThreadIds: Set<String>, val capabilities: Set<String>?) {
    companion object {
        fun from(o: JsonObject) = RuntimeInfo(o.string("id"), o.string("source"), o.string("version"), o.string("configStoreId"), o.flag("online"), o.array("loadedThreadIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet(), (o["capabilities"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.toSet())
    }
}

data class NativeModel(val id: String, val model: String, val name: String, val description: String, val efforts: List<String>, val defaultEffort: String, val images: Boolean, val isDefault: Boolean) {
    companion object {
        fun from(o: JsonObject) = NativeModel(o.string("id"), o.string("model"), o.string("displayName"), o.string("description"), o.array("supportedReasoningEfforts").mapNotNull { it.asObject().string("reasoningEffort").takeIf(String::isNotBlank) }, o.string("defaultReasoningEffort"), o.array("inputModalities").any { it == JsonPrimitive("image") }, o.flag("isDefault"))
    }
}

data class NativeMode(val name: String, val mode: String, val model: String?, val effort: String?) {
    companion object {
        fun from(o: JsonObject): NativeMode? {
            val mode = o.string("mode").takeIf { it in setOf("plan", "default") } ?: return null
            return NativeMode(o.string("name", mode), mode, o.string("model").takeIf(String::isNotBlank), o.string("reasoning_effort").takeIf(String::isNotBlank))
        }
    }
    fun payload(model: String, effort: String?) = buildJsonObject {
        put("mode", mode)
        put("settings", buildJsonObject { put("model", model); put("reasoning_effort", effort?.let(::JsonPrimitive) ?: JsonNull); put("developer_instructions", JsonNull) })
    }
}

data class Cursor(val epoch: String, val sequence: Long) {
    fun compare(next: Cursor) = when {
        next.epoch != epoch -> CursorDecision.SNAPSHOT
        next.sequence <= sequence -> CursorDecision.DUPLICATE
        next.sequence == sequence + 1 -> CursorDecision.NEXT
        else -> CursorDecision.SNAPSHOT
    }
    fun json() = buildJsonObject { put("epoch", epoch); put("sequence", sequence) }
}
enum class CursorDecision { NEXT, DUPLICATE, SNAPSHOT }
fun Cursor.covers(event: Cursor) = epoch == event.epoch && event.sequence <= sequence

data class TimelineItem(val turnId: String, val itemId: String, val type: String, val text: String = "", val status: String = "", val raw: JsonObject = buildJsonObject {}, val complete: Boolean = false,
    // Only a live lifecycle event establishes activity; snapshots/cache default to unknown.
    val liveStarted: Boolean = false) {
    val key get() = JsonArray(listOf(JsonPrimitive(turnId), JsonPrimitive(itemId))).toString()
    val diff get() = if (type == "fileChange") raw.array("changes").joinToString("\n\n") { val c = it.asObject(); "--- ${c.string("path")}\n${c.string("diff")}" } else if (type == "turn/diff/updated") raw.obj("params").string("diff") else ""
    companion object {
        fun from(turn: String, item: JsonObject, complete: Boolean = true): TimelineItem {
            val type = item.string("type", "unknown")
            val text = when (type) {
                "agentMessage" -> item.string("text")
                "userMessage" -> item.array("content").mapNotNull { val c = it.asObject(); c.string("text").takeIf { c.string("type") == "text" } }.joinToString("\n")
                "commandExecution" -> listOf(item.string("command"), item.string("aggregatedOutput")).filter(String::isNotBlank).joinToString("\n\n")
                "reasoning" -> item.array("summary").mapNotNull { (it as? JsonPrimitive)?.contentOrNull ?: (it as? JsonObject)?.string("text") }.joinToString("\n")
                "plan" -> item.string("text")
                else -> item.string("text", item.string("output"))
            }
            return TimelineItem(turn, item.string("id"), type, text, item.string("status"), item, complete)
        }
    }
}

data class NativeTimeline(val items: Map<String, TimelineItem> = linkedMapOf(), val activeTurn: String? = null, val status: String = "", val completedTurns: Set<String> = emptySet())

object NativeReducer {
    fun reduce(old: NativeTimeline, event: JsonObject, eventKey: String = "unknown"): NativeTimeline {
        val method = event.string("method")
        val p = event.obj("params")
        if (ConversationPresentation.hookType(method) || ConversationPresentation.hasHookMetadata(event)) return old
        val turn = p.string("turnId", p.obj("turn").string("id"))
        if (method == "turn/started") return old.copy(activeTurn = turn, status = "inProgress")
        if (method == "turn/completed") {
            val result = old.copy(activeTurn = if (old.activeTurn == turn) null else old.activeTurn, status = p.obj("turn").string("status", "completed"), completedTurns = old.completedTurns + turn)
            val message = p.obj("turn").obj("error").string("message")
            if (message.isBlank() && result.status != "failed") return result
            val failure = TimelineItem(turn, "turn-error:$turn", "error", message, "failed", complete = true)
            return result.copy(items = LinkedHashMap(result.items).apply { put(failure.key, failure) })
        }
        if (method == "thread/status/changed") return old.copy(status = p.obj("status").string("type"))
        val next: TimelineItem = when (method) {
            "item/started", "item/completed" -> TimelineItem.from(turn, p.obj("item"), method == "item/completed").copy(liveStarted = method == "item/started")
            "item/agentMessage/delta", "item/commandExecution/outputDelta", "item/reasoning/summaryTextDelta", "item/plan/delta" -> {
                val type = when (method) {
                    "item/agentMessage/delta" -> "agentMessage"
                    "item/commandExecution/outputDelta" -> "commandExecution"
                    "item/plan/delta" -> "plan"
                    else -> "reasoning"
                }
                val seed = TimelineItem(turn, p.string("itemId"), type)
                val existing = old.items[seed.key] ?: seed
                if (existing.complete) return old
                existing.copy(text = existing.text + p.string("delta"), liveStarted = true)
            }
            else -> {
                if (!ConversationPresentation.eventVisible(method)) return old
                TimelineItem(turn, p.string("itemId", "event:$eventKey"), method, p.string("message", p.obj("error").string("message")), raw = event, complete = true)
            }
        }
        if (next.itemId.isBlank() || !ConversationPresentation.visible(next)) return old
        return old.copy(items = LinkedHashMap(old.items).apply { put(next.key, next) })
    }

    fun snapshot(thread: JsonObject): NativeTimeline {
        val items = linkedMapOf<String, TimelineItem>()
        var active: String? = null
        val completed = mutableSetOf<String>()
        for (turnValue in thread.array("turns")) {
            val turn = turnValue.asObject()
            if (turn.string("status") == "inProgress") active = turn.string("id")
            else if (turn.string("status") in setOf("completed", "interrupted", "failed")) completed += turn.string("id")
            for (item in turn.array("items")) {
                val value = TimelineItem.from(turn.string("id"), item.asObject(), turn.string("status") != "inProgress")
                if (ConversationPresentation.visible(value)) items[value.key] = value
            }
            if (turn.string("status") == "failed" || turn.obj("error").string("message").isNotBlank()) {
                val failure = TimelineItem(turn.string("id"), "turn-error:${turn.string("id")}", "error", turn.obj("error").string("message"), "failed", complete = true)
                items[failure.key] = failure
            }
        }
        return NativeTimeline(items, active, thread.obj("status").string("type"), completed)
    }
}

data class PendingApproval(val runtimeId: String, val requestId: JsonElement, val method: String, val params: JsonObject, val submitted: Boolean = false) {
    val key get() = JsonArray(listOf(JsonPrimitive(runtimeId), requestId)).toString()
    val threadId get() = params.string("threadId")
    fun matches(runtime: String, id: JsonElement) = runtimeId == runtime && requestId == id
}

object CommandPolicy {
    fun canAutomaticallyReplay(@Suppress("UNUSED_PARAMETER") status: String) = false
    fun blocksNewSend(status: String) = status in setOf("submitted", "unknown", "not_found")
}
