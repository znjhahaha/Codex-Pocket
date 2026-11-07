package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Native asynchronous questions are assistant items, never approval RPCs. */
object AsyncQuestions {
    private const val OPEN = "<send_user_message_question_reply>"
    private const val CLOSE = "</send_user_message_question_reply>"

    fun replies(text: String): List<JsonObject> {
        val value = text.trim()
        if (!value.startsWith(OPEN) || !value.endsWith(CLOSE)) return emptyList()
        return runCatching {
            val body = pocketJson.parseToJsonElement(value.substring(OPEN.length, value.length - CLOSE.length).trim())
            val values = if (body is JsonArray) body else JsonArray(listOf(body))
            values.mapNotNull { it as? JsonObject }.filter {
                val answer = it["answer"] as? JsonPrimitive
                it.string("questionItemId").isNotBlank() && answer?.isString == true && answer.content.isNotBlank()
            }
        }.getOrDefault(emptyList())
    }

    fun pending(timeline: NativeTimeline, submitted: Set<String> = emptySet()): List<JsonObject> {
        val answered = timeline.items.values.filter { it.type == "userMessage" }
            .flatMap { replies(it.text) }.map { it.string("questionItemId") }.toSet()
        return timeline.items.values.flatMap { item ->
            if (item.type != "agentMessage" || item.raw.string("delivery") != "async") emptyList()
            else item.raw.array("questions").mapIndexedNotNull { index, value ->
                val question = value.asObject()
                val title = question.string("title")
                val id = buildJsonArray { add("request_user_input_async"); add(item.itemId); add(index) }.toString()
                if (title.isBlank() || id in answered) null else buildJsonObject {
                    put("id", id); put("questionItemId", id); put("title", title)
                    put("options", question["options"] as? JsonArray ?: JsonArray(emptyList()))
                    put("isSecret", question.flag("isSecret"))
                    put("submitted", id in submitted)
                }
            }
        }
    }

    fun reply(question: JsonObject, answer: String): String {
        require(answer.isNotBlank() && answer.length <= 64_000)
        val payload = buildJsonArray { add(buildJsonObject {
            put("questionItemId", question.string("questionItemId")); put("question", question.string("title")); put("answer", answer.trim())
        }) }
        return "$OPEN\n$payload\n$CLOSE"
    }
}
