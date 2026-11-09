package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Display-only projection. Original native errors stay intact for inspection and copying. */
data class ConversationErrorPresentation(val title: String, val summary: String, val detail: String,
    val retrying: Boolean = false) {
    companion object {
        private val markup = Regex("(?is)<!doctype\\b|<!--|<html\\b|<head\\b|<body\\b|<script\\b|<style\\b")
        private val status = Regex("(?i)(?:HTTP(?:/\\d(?:\\.\\d)?)?\\s*[:=]?\\s*|upstream_status\\s*[:=]\\s*|status(?:\\s+code)?\\s*[:=]?\\s*)([45]\\d{2})\\b")
        private val reconnect = Regex("(?i)(?:reconnecting|retrying|正在重新连接|正在重连)\\s*(?:[.。…:：-]\\s*)*(\\d{1,3})\\s*/\\s*(\\d{1,3})")

        fun from(item: TimelineItem, timeline: NativeTimeline?): ConversationErrorPresentation {
            val params = item.raw.obj("params").ifEmpty { item.raw }
            val error = params.obj("error")
            val message = ConversationPresentation.detail(item)
            val info = error["codexErrorInfo"] ?: item.raw["codexErrorInfo"]
            val code = (info as? JsonObject)?.values?.asSequence()?.mapNotNull {
                ((it as? JsonObject)?.get("httpStatusCode") as? JsonPrimitive)?.intOrNull
            }?.firstOrNull { it in 400..599 }
            val followUp = timeline?.items?.values?.dropWhile { it.key != item.key }?.drop(1)
                ?.any { it.turnId == item.turnId && it.type != "userMessage" } == true
            val retrying = (params["willRetry"] as? JsonPrimitive)?.booleanOrNull == true && timeline != null &&
                timeline.activeTurn == item.turnId && item.turnId !in timeline.completedTurns && !followUp &&
                timeline.turns[item.turnId]?.status !in setOf("failed", "completed", "interrupted")
            return fromText(message, code, retrying, error.string("additionalDetails"))
        }

        fun fromText(message: String, code: Int? = null, retrying: Boolean = false,
            additionalDetails: String = ""): ConversationErrorPresentation {
            val raw = message.ifBlank { "未提供错误详情" }
            // Bound inspection work. Never parse or execute an upstream HTML response.
            val head = raw.take(8_192)
            val htmlStart = markup.find(head)?.range?.first
            val beforeHtml = (htmlStart?.let { head.take(it) } ?: head).trim()
            val http = code ?: status.find(beforeHtml)?.groupValues?.get(1)?.toIntOrNull()
            val summary = when (http) {
                522, 524, 504, 408 -> "上游连接超时 · HTTP $http"
                429 -> "请求受到限流 · HTTP 429"
                401 -> "身份验证失败 · HTTP 401"
                403 -> "上游拒绝请求 · HTTP 403"
                500, 502, 503 -> "上游服务暂不可用 · HTTP $http"
                else -> when {
                    htmlStart != null -> if (http != null) "上游返回错误页面 · HTTP $http" else "上游返回了错误页面"
                    beforeHtml.isBlank() -> "未提供错误详情"
                    else -> beforeHtml.replace(Regex("\\s+"), " ").let { if (it.length > 240) it.take(239) + "…" else it }
                }
            }
            // Counts are shown only when the native error explicitly supplies a retry label.
            val count = if (retrying) reconnect.find(beforeHtml)?.groupValues?.let { values ->
                val attempt = values[1].toInt(); val limit = values[2].toInt()
                "$attempt/$limit".takeIf { attempt in 1..limit && limit <= 999 }
            } else null
            val title = if (retrying) "正在重新连接" + (count?.let { " $it" } ?: "") else "请求未完成"
            val detail = listOf(raw, additionalDetails.takeIf { it != raw }.orEmpty()).filter(String::isNotBlank).joinToString("\n\n")
            return ConversationErrorPresentation(title, summary, detail, retrying)
        }
    }
}
