package dev.codexpocket.app.ui

import dev.codexpocket.app.data.*
import kotlinx.serialization.json.*

internal data class QuestionDockEntry(
    val key: String,
    val batch: QuestionBatch? = null,
    val approval: PendingApproval? = null,
    val receipt: QuestionReceipt? = null,
) {
    val asynchronous get() = batch?.asynchronous == true
    val submitted get() = approval?.submitted ?: batch?.questions?.all { it.source.flag("submitted") } ?: false
}

internal fun questionDockEntries(s: PocketState, receipts: List<QuestionReceipt> = emptyList()): List<QuestionDockEntry> {
    val chat = s.selected ?: return emptyList()
    val approvals = s.approvals.filter {
        it.runtimeId == chat.runtimeId && it.threadId.isNotBlank() && it.threadId == chat.key.nativeId && !ConversationPresentation.hookType(it.method)
    }.map { approval ->
        QuestionDockEntry(approval.key,
            if (approval.method == "item/tool/requestUserInput") QuestionBatches.blocking(approval.key, approval.params) else null, approval,
            receipts.filter { it.chat == chat.key && (it.requestKey == approval.key || it.matchesNative(chat.key, approval)) }.maxByOrNull { it.createdAt })
    }
    val pending = s.advanced.asyncQuestions.ifEmpty { s.pendingQuestions[chat.key.encoded].orEmpty() }
    val enriched = pending.map { source ->
        val native = QuestionBatches.nativePosition(source)?.let { (itemId, index) ->
            s.timeline.items.values.firstOrNull { it.itemId == itemId && it.raw.string("delivery") == "async" }
                ?.raw?.array("questions")?.getOrNull(index) as? JsonObject
        }
        // Native question metadata enriches presentation; pending identity/submission flags remain authoritative.
        JsonObject(native.orEmpty() + source)
    }
    val asynchronous = QuestionBatches.async(chat.key.encoded, enriched).map { full ->
        // A prior single-question reply may have acknowledged only part of a native batch.
        // Offer its remaining questions without ever resending the guarded ones.
        val remaining = full.questions.filter { !it.source.flag("submitted") }
        val batch = if (remaining.isEmpty()) full else full.copy(questions = remaining)
        QuestionDockEntry(batch.id, batch)
    }
    val retained = receipts.filter { receipt -> receipt.chat == chat.key && receipt.runtimeId == chat.runtimeId &&
        !receipt.phase.terminal && approvals.none { it.key == receipt.requestKey } &&
        s.commands.any { it.id == receipt.commandId && CommandPolicy.blocksNewSend(it.status) } }
        .map { QuestionDockEntry(it.requestKey, receipt = it) }
    return approvals + asynchronous + retained
}

internal fun questionAvailability(s: PocketState, entry: QuestionDockEntry): QuestionPhase {
    if (entry.batch?.privateReplyRequired == true) return QuestionPhase.Unsupported
    val runtimeId = entry.approval?.runtimeId ?: entry.receipt?.runtimeId ?: s.selected?.runtimeId
    if (!s.online || s.runtimes.none { it.id == runtimeId && it.online }) return QuestionPhase.Offline
    val guarded = s.commands.any { it.method == "approvals/respond" &&
        (it.draftText == entry.key || it.id == entry.receipt?.commandId) && CommandPolicy.blocksNewSend(it.status) }
    if (entry.receipt != null && (entry.receipt.phase.terminal || guarded || entry.submitted)) return when (entry.receipt.phase) {
        QuestionDeliveryPhase.Dispatching -> QuestionPhase.Submitting
        QuestionDeliveryPhase.Forwarded -> QuestionPhase.Forwarded
        QuestionDeliveryPhase.AwaitingConfirmation -> QuestionPhase.AwaitingConfirmation
        QuestionDeliveryPhase.Confirmed -> QuestionPhase.Confirmed
        QuestionDeliveryPhase.AnsweredElsewhere -> QuestionPhase.AnsweredElsewhere
        QuestionDeliveryPhase.Cancelled -> QuestionPhase.Cancelled
    }
    if (entry.approval != null) {
        if (!s.approvalsFresh || s.approvals.none { it.key == entry.key }) return QuestionPhase.Stale
        if (entry.key in s.submittingApprovalKeys) return QuestionPhase.Submitting
        if (guarded) return QuestionPhase.AwaitingConfirmation
    } else {
        val method = if (s.timeline.activeTurn == null) "turn/start" else "turn/steer"
        if (s.conversationLoadState != ConversationLoadState.Ready || s.selected?.archived != false ||
            s.selected.conflict || s.uncertainSend || !s.supports(method)) return QuestionPhase.Stale
    }
    return if (entry.submitted) QuestionPhase.Submitted else QuestionPhase.Waiting
}

/** Kept above the collapsing/paging content. Removed requests and disposed docks release their answers. */
internal class QuestionDockMemory {
    private val sessions = mutableMapOf<String, QuestionSession>()
    private val reveals = mutableMapOf<String, QuestionRevealState>()
    private val scopes = mutableMapOf<String, Set<String>>()
    fun session(batch: QuestionBatch) = sessions.getOrPut(batch.id) { QuestionSession(batch) }.also { it.update(batch) }
    fun reveal(batch: QuestionBatch) = reveals.getOrPut(batch.id) { QuestionRevealState() }
    fun reconcile(scope: String, entries: List<QuestionDockEntry>, fresh: Boolean) {
        val keys = entries.map { it.key }.toSet()
        if (fresh) (scopes[scope].orEmpty() - keys).forEach {
            sessions.remove(it)?.clear()
            reveals.remove(it)
        }
        scopes[scope] = if (fresh) keys else scopes[scope].orEmpty() + keys
    }
    fun clear() { sessions.values.forEach { it.clear() }; sessions.clear(); reveals.clear(); scopes.clear() }
    override fun toString() = "QuestionDockMemory"
}

/** Identity-only visual memory above collapse/batch paging; never stores or mutates answers. */
internal class QuestionRevealState {
    private var lastQuestion: String? = null
    fun durationFor(questionId: String?): Int = when {
        questionId.isNullOrBlank() || questionId == lastQuestion -> 0
        lastQuestion == null -> 320
        else -> 180
    }
    fun markShown(questionId: String?) { if (!questionId.isNullOrBlank()) lastQuestion = questionId }
}
