package dev.codexpocket.app.data

import kotlinx.serialization.json.*

enum class QuestionPhase { Waiting, Submitting, Submitted, Offline, Stale, Unsupported }

data class QuestionOption(val value: String, val label: String, val description: String, val recommended: Boolean)
data class FormQuestion(
    val id: String,
    val header: String,
    val prompt: String,
    val options: List<QuestionOption>,
    val allowsOther: Boolean,
    val secret: Boolean,
    val source: JsonObject,
) {
    val valid get() = id.isNotBlank() && prompt.isNotBlank() && options.all { it.value.isNotBlank() } &&
        options.map { it.value }.distinct().size == options.size
    fun accepts(answer: String?) = !answer.isNullOrBlank() && answer.length <= 64_000 &&
        (allowsOther || options.any { it.value == answer })
    internal fun sameInput(other: FormQuestion) = id == other.id && prompt == other.prompt &&
        options == other.options && allowsOther == other.allowsOther && secret == other.secret
}

data class QuestionBatch(val id: String, val questions: List<FormQuestion>, val asynchronous: Boolean = false) {
    val privateReplyRequired get() = asynchronous && questions.any { it.secret }
    val valid get() = id.isNotBlank() && questions.isNotEmpty() && questions.all { it.valid } &&
        questions.map { it.id }.distinct().size == questions.size
}

/** Presentation adapters only. Native identity and advertised answer values are never rewritten. */
object QuestionBatches {
    private fun JsonObject.text(key: String) = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
    private fun JsonObject.boolean(key: String, fallback: Boolean = false) = (this[key] as? JsonPrimitive)?.booleanOrNull ?: fallback
    private val recommendation = Regex("\\s*[（(](?:Recommended|推荐)[）)]\\s*$", RegexOption.IGNORE_CASE)

    private fun question(source: JsonObject, asynchronous: Boolean): FormQuestion {
        val options = (source["options"] as? JsonArray).orEmpty().map { option ->
            val value = (option as? JsonPrimitive)?.takeIf { it.isString }?.content ?: (option as? JsonObject)?.text("label").orEmpty()
            val objectValue = option as? JsonObject
            QuestionOption(value, value.replace(recommendation, "").trim(), objectValue?.text("description").orEmpty(),
                objectValue?.boolean("recommended") == true || objectValue?.boolean("isRecommended") == true || recommendation.containsMatchIn(value))
        }
        return FormQuestion(
            id = source.text(if (asynchronous) "questionItemId" else "id").ifBlank { source.text("id") },
            header = source.text("header"),
            prompt = source.text(if (asynchronous) "title" else "question"),
            options = options,
            allowsOther = options.isEmpty() || source.boolean("isOther", asynchronous),
            secret = source.boolean("isSecret"),
            source = source,
        )
    }

    fun blocking(requestKey: String, params: JsonObject) = QuestionBatch(requestKey,
        (params["questions"] as? JsonArray).orEmpty().map { question(it as? JsonObject ?: JsonObject(emptyMap()), false) })

    /** Older pending snapshots encode the batch solely in [tool name, native item ID, question index]. */
    private fun identity(source: JsonObject): Pair<String, Int>? {
        val id = source.text("questionItemId").ifBlank { source.text("id") }
        return runCatching {
            val parts = Json.parseToJsonElement(id) as? JsonArray ?: return null
            if (parts.size != 3 || (parts[0] as? JsonPrimitive)?.content != "request_user_input_async") return null
            val item = (parts[1] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            val index = (parts[2] as? JsonPrimitive)?.intOrNull ?: return null
            if (item.isBlank() || index < 0) null else item to index
        }.getOrNull()
    }

    fun nativePosition(source: JsonObject): Pair<String, Int>? = identity(source)

    fun async(scope: String, questions: List<JsonObject>): List<QuestionBatch> = questions.groupBy {
        identity(it)?.first ?: it.text("questionItemId").ifBlank { it.text("id") }
    }.map { (itemId, values) ->
        val key = buildJsonArray { add("async"); add(scope); add(itemId) }.toString()
        QuestionBatch(key, values.sortedBy { identity(it)?.second ?: 0 }.map { question(it, true) }, asynchronous = true)
    }

    private fun complete(batch: QuestionBatch, answers: List<Pair<String, String>>): Map<String, String>? {
        if (!batch.valid || answers.size != batch.questions.size || answers.map { it.first }.distinct().size != answers.size) return null
        val byId = answers.toMap()
        if (byId.keys != batch.questions.map { it.id }.toSet() || batch.questions.any { !it.accepts(byId[it.id]) }) return null
        return byId
    }

    fun blockingResponse(batch: QuestionBatch, answers: List<Pair<String, String>>): JsonObject? {
        if (batch.asynchronous) return null
        val byId = complete(batch, answers) ?: return null
        return buildJsonObject { put("answers", buildJsonObject {
            batch.questions.forEach { q -> put(q.id, buildJsonObject { put("answers", buildJsonArray { add(byId.getValue(q.id)) }) }) }
        }) }
    }

    fun asyncAnswers(batch: QuestionBatch, answers: List<Pair<String, String>>): List<Pair<JsonObject, String>>? {
        if (!batch.asynchronous || batch.privateReplyRequired) return null
        val byId = complete(batch, answers) ?: return null
        return batch.questions.map { it.source to byId.getValue(it.id) }
    }
}

/** No Bundle/Saver, serialization, persistence or answer-bearing toString. Owned by the dock's composition. */
class QuestionSession(initial: QuestionBatch) {
    private class Answer {
        var option: String? = null
        var other = false
        var text = ""
    }
    private val answers = mutableMapOf<String, Answer>()
    var batch: QuestionBatch = initial
        private set
    var page: Int = 0
        private set
    var phase: QuestionPhase = QuestionPhase.Waiting
        private set
    private var sawSubmission = false
    val currentQuestion get() = batch.questions.getOrNull(page)
    val complete get() = batch.valid && !batch.privateReplyRequired && batch.questions.all { it.accepts(answer(it.id)) }
    fun presentationPhase(availability: QuestionPhase): QuestionPhase = when {
        batch.privateReplyRequired || availability == QuestionPhase.Unsupported -> QuestionPhase.Unsupported
        !batch.valid -> QuestionPhase.Stale
        availability == QuestionPhase.Offline || availability == QuestionPhase.Stale -> availability
        availability == QuestionPhase.Submitting || phase == QuestionPhase.Submitting -> QuestionPhase.Submitting
        availability == QuestionPhase.Submitted || phase == QuestionPhase.Submitted -> QuestionPhase.Submitted
        else -> QuestionPhase.Waiting
    }
    fun answer(id: String): String? = answers[id]?.let { if (it.other) it.text else it.option }
    fun otherText(id: String): String = answers[id]?.text.orEmpty()
    fun otherSelected(id: String): Boolean = answers[id]?.other == true

    fun update(next: QuestionBatch) {
        require(next.id == batch.id)
        val currentId = currentQuestion?.id
        answers.keys.toList().forEach { id ->
            val before = batch.questions.firstOrNull { it.id == id }
            val after = next.questions.firstOrNull { it.id == id }
            if (before == null || after == null || !before.sameInput(after)) answers.remove(id)
        }
        batch = next
        if (batch.privateReplyRequired) answers.clear()
        page = next.questions.indexOfFirst { it.id == currentId }.takeIf { it >= 0 } ?: page.coerceIn(0, (next.questions.size - 1).coerceAtLeast(0))
    }

    fun selectOption(id: String, value: String): Boolean {
        if (batch.privateReplyRequired || phase != QuestionPhase.Waiting || batch.questions.firstOrNull { it.id == id }?.options?.none { it.value == value } != false) return false
        answers.getOrPut(id, ::Answer).apply { option = value; other = false }
        return true
    }

    fun selectOther(id: String): Boolean {
        if (batch.privateReplyRequired || phase != QuestionPhase.Waiting || batch.questions.firstOrNull { it.id == id }?.allowsOther != true) return false
        answers.getOrPut(id, ::Answer).other = true
        return true
    }

    fun editOther(id: String, value: String): Boolean {
        if (!selectOther(id)) return false
        answers.getValue(id).text = value
        return true
    }

    fun move(delta: Int) { page = (page + delta).coerceIn(0, (batch.questions.size - 1).coerceAtLeast(0)) }

    fun beginSubmission(availability: QuestionPhase): List<Pair<String, String>>? {
        if (availability != QuestionPhase.Waiting || phase != QuestionPhase.Waiting || !complete || page != batch.questions.lastIndex) return null
        val result = batch.questions.map { it.id to answer(it.id)!! }
        phase = QuestionPhase.Submitting
        sawSubmission = false
        return result
    }

    /** A callback dispatch is not an acknowledgement. Reopen only after observed processing ends in refusal. */
    fun observe(submitted: Boolean, submitting: Boolean, pendingConfirmed: Boolean = true) {
        if (submitting) {
            sawSubmission = true
            phase = QuestionPhase.Submitting
        } else if (submitted) {
            phase = QuestionPhase.Submitted
            batch.questions.filter { it.secret }.forEach { answers.remove(it.id) }
        } else if (pendingConfirmed && ((phase == QuestionPhase.Submitting && sawSubmission) || phase == QuestionPhase.Submitted)) {
            phase = QuestionPhase.Waiting
            sawSubmission = false
        }
    }

    fun clear() { answers.clear() }
    override fun toString() = "QuestionSession(page=$page, phase=$phase)"
}
