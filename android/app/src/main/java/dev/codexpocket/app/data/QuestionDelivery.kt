package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class QuestionDeliveryPhase(val wire: String, val label: String, val terminal: Boolean = false) {
    Dispatching("dispatching", "正在发送"), Forwarded("forwarded", "已转交电脑"),
    AwaitingConfirmation("awaiting_confirmation", "等待原生答案确认"),
    Confirmed("confirmed", "原生已确认答案", true), AnsweredElsewhere("answered_elsewhere", "已在其他端回答", true),
    Cancelled("cancelled", "问题已取消", true);
    companion object { fun parse(value: String) = entries.firstOrNull { it.wire == value } }
}

/** Durable presentation receipt, never a replayable request. Secret values and hashes are excluded. */
class QuestionReceipt private constructor(
    val commandId: String, val chat: ThreadKey, val runtimeId: String, val requestId: JsonElement,
    val turnId: String, val itemId: String, val phase: QuestionDeliveryPhase,
    val createdAt: Long,
    private val answers: Map<String, String>, private val inputs: Map<String, String>,
) {
    val requestKey get() = JsonArray(listOf(JsonPrimitive(runtimeId), requestId)).toString()
    fun json() = buildJsonObject {
        put("commandId", commandId); put("chat", chat.encoded); put("runtimeId", runtimeId); put("requestId", requestId)
        put("turnId", turnId); put("itemId", itemId); put("phase", phase.wire)
        put("createdAt", createdAt)
        put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
        put("inputs", JsonObject(inputs.mapValues { JsonPrimitive(it.value) }))
    }
    fun matchesNative(chat: ThreadKey, approval: PendingApproval) = this.chat == chat &&
        approval.method == "item/tool/requestUserInput" && approval.threadId == chat.nativeId &&
        turnId.isNotBlank() && itemId.isNotBlank() && approval.params.string("turnId") == turnId && approval.params.string("itemId") == itemId
    fun restorableAnswers(batch: QuestionBatch, approval: PendingApproval? = null): Map<String, String> {
        if (batch.id != requestKey && (approval == null || approval.key != batch.id || !matchesNative(chat, approval))) return emptyMap()
        return batch.questions.filter { !it.secret && inputs[it.id] == signature(it) && it.accepts(answers[it.id]) }
            .associate { it.id to answers.getValue(it.id) }
    }
    fun withDelivery(value: JsonObject): QuestionReceipt? {
        val next = QuestionDeliveryPhase.parse(value.string("phase")) ?: return null
        if (value.string("commandId") != commandId || value.string("runtimeId") != runtimeId ||
            value.string("threadId") != chat.nativeId || value.string("turnId") != turnId ||
            value.string("itemId") != itemId || value["requestId"] != requestId) return null
        return QuestionReceipt(commandId, chat, runtimeId, requestId, turnId, itemId, if (phase.terminal) phase else next, createdAt, answers, inputs)
    }
    fun awaitingConfirmation() = QuestionReceipt(commandId, chat, runtimeId, requestId, turnId, itemId,
        if (phase.terminal) phase else QuestionDeliveryPhase.AwaitingConfirmation, createdAt, answers, inputs)
    fun commandStatus(status: String): String = when (phase) {
        QuestionDeliveryPhase.Confirmed -> "accepted"
        QuestionDeliveryPhase.AnsweredElsewhere -> "answered_elsewhere"
        QuestionDeliveryPhase.Cancelled -> "cancelled"
        else -> if (status == "rejected") status else if (status in setOf("unknown", "not_found")) status else "submitted"
    }
    override fun toString() = "QuestionReceipt(phase=${phase.wire})"
    companion object {
        private fun signature(q: FormQuestion) = buildJsonArray {
            add(q.prompt); add(q.allowsOther); add(buildJsonArray { q.options.forEach { add(it.value) } })
        }.toString()
        fun capture(commandId: String, chat: ThreadKey, approval: PendingApproval, response: JsonObject): QuestionReceipt {
            val batch = QuestionBatches.blocking(approval.key, approval.params)
            val safe = batch.questions.filter { !it.secret }
            val answers = safe.mapNotNull { q ->
                val values = response.obj("answers").obj(q.id).array("answers")
                val text = (values.singleOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                text?.takeIf(q::accepts)?.let { q.id to it }
            }.toMap()
            return QuestionReceipt(commandId, chat, approval.runtimeId, approval.requestId,
                approval.params.string("turnId"), approval.params.string("itemId"), QuestionDeliveryPhase.Dispatching,
                System.currentTimeMillis(),
                answers, safe.associate { it.id to signature(it) })
        }
        fun from(value: JsonObject): QuestionReceipt? = runCatching {
            val phase = QuestionDeliveryPhase.parse(value.string("phase")) ?: return null
            val id = value.string("commandId").takeIf(String::isNotBlank) ?: return null
            val chat = ThreadKey.decode(value.string("chat"))
            fun strings(key: String) = value.obj(key).mapNotNull { (id, text) ->
                val limit = if (key == "answers") MAX_QUESTION_ANSWER_BYTES else 256 * 1024
                (text as? JsonPrimitive)?.takeIf { it.isString && it.content.toByteArray(Charsets.UTF_8).size <= limit }?.let { id to it.content }
            }.toMap()
            QuestionReceipt(id, chat, value.string("runtimeId"), value["requestId"] ?: return null,
                value.string("turnId"), value.string("itemId"), phase, value.number("createdAt"), strings("answers"), strings("inputs"))
        }.getOrNull()
    }
}

/** Read-only rendering of native arguments and completed results, including authenticated broker projections. */
class NativeQuestionHistory private constructor(val batch: QuestionBatch, val answers: Map<String, String>, val cancelled: Boolean) {
    val text get() = batch.questions.joinToString("\n\n") { q ->
        listOfNotNull(q.prompt, answers[q.id]?.let { "回答：$it" }).joinToString("\n")
    } + if (cancelled) "\n问题已取消" else ""
    override fun toString() = "NativeQuestionHistory"
    companion object {
        fun isRequest(item: TimelineItem) = item.type == "dynamicToolCall" &&
            item.raw.string("tool").substringAfterLast('.') == "request_user_input"
        private fun arguments(item: TimelineItem): JsonObject = (item.raw["arguments"] as? JsonObject) ?:
            runCatching { pocketJson.parseToJsonElement(item.raw.string("arguments")) as? JsonObject }.getOrNull() ?: buildJsonObject {}
        private fun result(item: TimelineItem, ids: Set<String>): JsonObject? {
            if (item.raw.string("status") != "completed" || item.raw["success"] == JsonPrimitive(false)) return null
            val content = item.raw.array("contentItems").takeIf { it.isNotEmpty() } ?: item.raw.array("toolOutputBlocks")
            val results = content.mapNotNull { part ->
                val block = part as? JsonObject ?: return@mapNotNull null
                if (block.string("type") !in setOf("inputText", "text")) return@mapNotNull null
                // Match the native frame bound; individual answers retain their smaller byte limit below.
                val text = (block["text"] as? JsonPrimitive)?.takeIf {
                    it.isString && it.content.toByteArray(Charsets.UTF_8).size <= 16 * 1024 * 1024
                }?.content ?: return@mapNotNull null
                runCatching { pocketJson.parseToJsonElement(text).jsonObject["answers"] as? JsonObject }.getOrNull()
            }
            val answers = results.singleOrNull() ?: return null
            if (answers.isEmpty()) return answers
            if (answers.keys != ids || answers.values.any { value ->
                val list = (value as? JsonObject)?.get("answers") as? JsonArray
                list == null || list.size > 32 || list.any { it !is JsonPrimitive || !it.isString || it.content.toByteArray(Charsets.UTF_8).size > MAX_QUESTION_ANSWER_BYTES }
            }) return null
            return answers
        }
        fun from(item: TimelineItem): NativeQuestionHistory? {
            if (!isRequest(item)) return null
            val batch = QuestionBatches.blocking(item.key, arguments(item))
            val result = result(item, batch.questions.map { it.id }.toSet()).takeIf { batch.valid }
            val answers = batch.questions.mapNotNull { q ->
                val values = result?.get(q.id)?.asObject()?.array("answers") ?: return@mapNotNull null
                q.id to if (q.secret) "敏感回答已隐藏" else values.joinToString("\n") { it.jsonPrimitive.content }
            }.toMap()
            return NativeQuestionHistory(batch, answers, result?.isEmpty() == true)
        }
        /** Cached history may retain public answers, but never native secret result content. */
        fun cacheSafe(item: TimelineItem): TimelineItem {
            if (!isRequest(item)) return item
            val batch = QuestionBatches.blocking(item.key, arguments(item))
            val secretIds = batch.questions.filter { it.secret }.map { it.id }.toSet()
            if (secretIds.isEmpty() && batch.valid) return item
            fun sanitized(content: JsonArray) = content.mapNotNull { part ->
                val block = part as? JsonObject ?: return@mapNotNull null
                if (block.string("type") !in setOf("inputText", "text")) return@mapNotNull null
                val safe = runCatching {
                    val obj = pocketJson.parseToJsonElement(block.string("text")).jsonObject
                    val answers = obj["answers"] as? JsonObject ?: return@runCatching null
                    buildJsonObject { put("answers", JsonObject(answers.filterKeys { id -> batch.valid && id in batch.questions.map { it.id } }.mapValues { (id, value) ->
                        val values = (value as? JsonObject)?.get("answers") as? JsonArray
                        val safeValues = if (id in secretIds || values == null || values.size > 32) JsonArray(emptyList())
                            else JsonArray(values.filterIsInstance<JsonPrimitive>().filter { it.isString && it.content.toByteArray(Charsets.UTF_8).size <= MAX_QUESTION_ANSWER_BYTES })
                        buildJsonObject { put("answers", safeValues) }
                    })) }
                }.getOrNull() ?: return@mapNotNull null
                buildJsonObject { put("type", block.string("type")); put("text", safe.toString()) }
            }
            val raw = (item.raw - "text" - "output" - "result" - "toolOutputBlocks") +
                ("contentItems" to JsonArray(sanitized(item.raw.array("contentItems")))) +
                if (item.raw["toolOutputBlocks"] is JsonArray) mapOf("toolOutputBlocks" to JsonArray(sanitized(item.raw.array("toolOutputBlocks")))) else emptyMap()
            return item.copy(text = "", raw = JsonObject(raw))
        }
    }
}
