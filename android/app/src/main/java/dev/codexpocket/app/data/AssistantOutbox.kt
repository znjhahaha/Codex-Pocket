package dev.codexpocket.app.data

import android.content.SharedPreferences
import kotlinx.serialization.json.*
import java.util.UUID

/** One durable user message. Transport uncertainty never creates a new identity or an automatic retry. */
data class AssistantOutgoingMessage(
    val commandId: String, val binding: AssistantComposerBinding, val params: JsonObject,
    val attachments: List<LocalAttachment>, val atMs: Long, val status: String = "submitted", val reason: String? = null, val retryAfterMs: Long = 0,
    val wireParams: JsonObject? = null, val retryAllowed: Boolean = true,
    val messageId: String = commandId, val previousCommandIds: List<String> = emptyList(),
    val observed: Boolean = false,
) {
    val key get() = binding.receiptKey(commandId)
    val text get() = params.string("message")
    val canRetry get() = status == "rejected" && retryAllowed
    fun matchesMessage(id: String) = (listOf(messageId, commandId) + previousCommandIds).any { id == it || id == "user:$it" }
    fun action(retry: Boolean = false) = AssistantAction("assistant/send", params, commandId, binding, attachments, retry)
    fun json() = buildJsonObject {
        put("commandId", commandId); put("computerId", binding.computerId); put("runtimeId", binding.runtimeId)
        binding.conversationId?.let { put("conversationId", it) }; put("params", params)
        put("attachments", JsonArray(attachments.map { it.json() })); put("atMs", atMs); put("status", status)
        reason?.let { put("reason", it) }
        put("retryAfterMs", retryAfterMs)
        wireParams?.let { put("wireParams", it) }
        put("retryAllowed", retryAllowed)
        put("messageId", messageId); put("previousCommandIds", JsonArray(previousCommandIds.map(::JsonPrimitive)))
        put("observed", observed)
    }
    companion object {
        fun from(value: JsonObject) = AssistantOutgoingMessage(value.string("commandId"),
            AssistantComposerBinding(value.string("computerId"), value.string("runtimeId"), value.string("conversationId").ifBlank { null }),
            value.obj("params"), value.array("attachments").map { LocalAttachment.from(it.jsonObject) }, value.number("atMs"),
            value.string("status").ifBlank { "unknown" }, value.string("reason").ifBlank { null }, value.number("retryAfterMs"), value["wireParams"] as? JsonObject,
            value["retryAllowed"] != JsonPrimitive(false), value.string("messageId").ifBlank { value.string("commandId") },
            value.array("previousCommandIds").mapNotNull { (it as? JsonPrimitive)?.contentOrNull }, value.flag("observed"))
    }
}

/** Uses the same private preferences as drafts so retaining a message and releasing its draft are one disk write. */
class AssistantOutbox(private val preferences: SharedPreferences) {
    fun all(): List<AssistantOutgoingMessage> = synchronized(gate) {
        pocketJson.parseToJsonElement(preferences.getString(STORAGE, "[]")!!).jsonArray.map { AssistantOutgoingMessage.from(it.jsonObject) }
    }
    fun messages(binding: AssistantComposerBinding) = all().filter {
        it.binding == binding || (binding.conversationId?.startsWith("main:") == true && it.binding.computerId == binding.computerId &&
            it.binding.runtimeId == binding.runtimeId && it.binding.conversationId in setOf(null, "assistant:${binding.conversationId.removePrefix("main:")}"))
    }
    fun observe(binding: AssistantComposerBinding, ids: Set<String>) = synchronized(gate) {
        val entries = all()
        val visibleKeys = messages(binding).filter { entry -> ids.any(entry::matchesMessage) }.map { it.key }.toSet()
        val updated = entries.map { if (it.key in visibleKeys) it.copy(status = "accepted", reason = null, observed = true) else it }
        if (updated != entries) save(updated)
    }
    fun dispatched(key: String, params: JsonObject) = synchronized(gate) {
        val entries=all()
        if (entries.any {it.key==key}) save(entries.map {if(it.key==key) it.copy(wireParams=params) else it})
    }
    fun receipt(receipt: AssistantSendReceipt) = synchronized(gate) {
        val entry = all().firstOrNull { it.key == receipt.key } ?: return@synchronized
        if (receipt.status != "rejected" || receipt.submittedAtMs >= entry.retryAfterMs) settle(receipt.key, receipt.status, entry.reason)
    }
    /** Older installs retained identity/text/files but not the complete target params. Reconcile, never reconstruct a replay. */
    fun adoptLegacy(binding: AssistantComposerBinding, id: String, text: String, files: List<LocalAttachment>, draftKey: String, attachmentKey: String) = synchronized(gate) {
        val entries=all()
        val entry=AssistantOutgoingMessage(id,binding,buildJsonObject {put("message",text)},files,System.currentTimeMillis(),"unknown",retryAllowed=false)
        val editor=preferences.edit().putString(STORAGE,JsonArray((if(entries.any {it.key==entry.key}) entries else entries+entry).map {it.json()}).toString())
            .remove("attachment-pending:$attachmentKey").remove("attachment-text:$attachmentKey").remove("attachment-sent:$attachmentKey")
        if(preferences.getString(draftKey,null)==text) editor.putString(draftKey,"")
        val remaining=pocketJson.parseToJsonElement(preferences.getString("attachment-files:$attachmentKey","[]")!!).jsonArray
            .map {LocalAttachment.from(it.jsonObject)}.filterNot {it.id in files.map(LocalAttachment::id)}
        check(editor.putString("attachment-files:$attachmentKey",JsonArray(remaining.map {it.json()}).toString()).commit()) {"消息状态未能保存"}
    }
    fun enqueue(action: AssistantAction, draftKey: String, attachmentKey: String) = synchronized(gate) {
        val entry = AssistantOutgoingMessage(requireNotNull(action.commandId), requireNotNull(action.composerBinding), action.params,
            action.attachments, System.currentTimeMillis())
        val entries = all()
        require(entries.none { it.key == entry.key }) { "这条消息已保存" }
        check(preferences.edit().putString(STORAGE, JsonArray((entries + entry).map { it.json() }).toString())
            .putString(draftKey, "").putString("attachment-files:$attachmentKey", "[]")
            .remove("target:$draftKey")
            .remove("attachment-pending:$attachmentKey").remove("attachment-text:$attachmentKey").remove("attachment-sent:$attachmentKey").commit()) {
            "消息未能保存，请重试"
        }
    }
    fun settle(key: String, status: String, reason: String? = null) = synchronized(gate) {
        val entries = all()
        val previous = entries.firstOrNull { it.key == key } ?: return@synchronized
        if (previous.status == "accepted" || status !in setOf("accepted", "rejected", "unknown", "submitted", "not_found")) return@synchronized
        val next = previous.copy(status = if (status == "not_found") "unknown" else status, reason = reason)
        if (previous != next) save(entries.map { if (it.key == key) next else it })
    }
    fun retry(key: String): AssistantAction? = synchronized(gate) {
        val entries = all()
        val entry = entries.firstOrNull { it.key == key && it.canRetry } ?: return@synchronized null
        // The original rejected receipt stays terminal on the server. An explicit
        // retry is a new admission attempt for the same local message, never a replay
        // of an uncertain command. Keep both IDs so late receipts cannot affect it.
        val next = entry.copy(commandId = UUID.randomUUID().toString(), status = "submitted", reason = null,
            retryAfterMs = System.currentTimeMillis(), previousCommandIds = entry.previousCommandIds + entry.commandId)
        save(entries.map { if (it.key == key) next else it })
        next.action(retry = true)
    }
    private fun save(entries: List<AssistantOutgoingMessage>) {
        check(preferences.edit().putString(STORAGE, JsonArray(entries.map { it.json() }).toString()).commit()) { "消息状态未能保存" }
    }
    companion object {
        const val STORAGE = "assistant-outbox-v1"
        private val gate = Any()
    }
}
