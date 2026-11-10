package dev.codexpocket.app.data

import android.content.Context
import kotlinx.serialization.json.*

/** Local submitted-answer receipts only; no response payload or write-replay API. Backups exclude preferences. */
class QuestionRetentionCapacityException : IllegalStateException("Local question answer retention is full")

class QuestionAnswerStore(context: Context, private val maxRecords: Int = 128,
    private val maxBytes: Int = 2 * 1024 * 1024, private val terminalRecords: Int = 16) {
    private val preferences = context.getSharedPreferences("question-delivery", Context.MODE_PRIVATE)
    private class Stored(val key: String, val text: String, val receipt: QuestionReceipt) {
        // Reserve space for longer phases on future status updates.
        val bytes = key.toByteArray(Charsets.UTF_8).size + text.toByteArray(Charsets.UTF_8).size + 256
    }
    private fun records() = preferences.all.mapNotNull { (key, value) ->
        if (!key.startsWith("receipt:")) return@mapNotNull null
        val text = value as? String ?: return@mapNotNull null
        val receipt = runCatching { QuestionReceipt.from(pocketJson.parseToJsonElement(text).jsonObject) }.getOrNull() ?: return@mapNotNull null
        Stored(key, text, receipt)
    }
    private fun retained(records: List<Stored>): MutableList<Stored> {
        val cutoff = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        val pending = records.filter { !it.receipt.phase.terminal }
        val terminal = records.filter { it.receipt.phase.terminal && it.receipt.createdAt >= cutoff }
            .sortedByDescending { it.receipt.createdAt }.take(terminalRecords)
        val kept = (pending + terminal).toMutableList()
        while ((kept.size > maxRecords || kept.sumOf { it.bytes } > maxBytes) && kept.any { it.receipt.phase.terminal }) {
            kept.remove(kept.filter { it.receipt.phase.terminal }.minBy { it.receipt.createdAt })
        }
        return kept
    }
    private fun persist(records: List<Stored>) {
        val keys = records.map { it.key }.toSet()
        val edit = preferences.edit()
        preferences.all.keys.filter { it !in keys }.forEach(edit::remove)
        records.forEach { edit.putString(it.key, it.text) }
        check(edit.commit()) { "Cannot retain question delivery metadata" }
    }
    fun all(): List<QuestionReceipt> = synchronized(gate) {
        val current = records(); val kept = retained(current)
        if (kept.size != current.size || preferences.all.size != kept.size) persist(kept)
        kept.map { it.receipt }
    }
    fun command(id: String) = synchronized(gate) { records().firstOrNull { it.receipt.commandId == id }?.receipt }
    fun request(chat: ThreadKey, key: String) = all().filter { it.chat == chat && it.requestKey == key }.maxByOrNull { it.createdAt }
    fun save(receipt: QuestionReceipt) = synchronized(gate) {
        val current = records()
        val previous = current.firstOrNull { it.receipt.commandId == receipt.commandId }?.receipt
        if (previous != null) require(previous.chat == receipt.chat && previous.runtimeId == receipt.runtimeId &&
            previous.requestId == receipt.requestId && previous.turnId == receipt.turnId && previous.itemId == receipt.itemId) {
            "Question receipt identity differs"
        }
        // The submission coroutine can finish after reconciliation has already confirmed its result.
        val merged = previous?.takeIf { it.phase.terminal } ?: receipt
        val incoming = Stored("receipt:${merged.commandId}", merged.json().toString(), merged)
        val kept = retained(current.filterNot { it.receipt.commandId == merged.commandId } + incoming)
        // Pending receipts are never evicted. Capacity refusal happens before transport dispatch.
        if ((!merged.phase.terminal && incoming.bytes > 512 * 1024) || kept.size > maxRecords || kept.sumOf { it.bytes } > maxBytes) {
            throw QuestionRetentionCapacityException()
        }
        persist(kept)
        merged
    }
    fun removeRejected(id: String): QuestionReceipt? = synchronized(gate) {
        records().firstOrNull { it.receipt.commandId == id && it.receipt.phase.terminal }?.receipt
            ?: run { check(preferences.edit().remove("receipt:$id").commit()); null }
    }
    override fun toString() = "QuestionAnswerStore"
    companion object { private val gate = Any() }
}
