package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** A separate, revision-scoped editor. Never contains ordinary references or attachments. */
data class PlanFeedbackDraft(
    val chatKey: ThreadKey,
    val runtimeId: String,
    val revision: String,
    val text: String = "",
    val previousMode: NativeSetting = NativeSetting(),
    val previousModePending: Boolean = false,
    val modeRevision: Long = 0,
) {
    fun matches(key: ThreadKey?, runtime: String?, plan: ProposedPlan) =
        chatKey == key && runtimeId == runtime && revision == plan.revision

    fun persisted() = buildJsonObject {
        put("chatKey", chatKey.encoded); put("runtimeId", runtimeId); put("revision", revision); put("text", text)
        put("modeKnown", previousMode.known); put("mode", previousMode.value?.let(::JsonPrimitive) ?: JsonNull)
        put("modePending", previousModePending); put("modeRevision", modeRevision)
    }

    companion object {
        fun restore(o: JsonObject, key: ThreadKey, runtime: String): PlanFeedbackDraft? {
            if (o.string("chatKey") != key.encoded || o.string("runtimeId") != runtime || o.string("revision").isBlank()) return null
            return PlanFeedbackDraft(key, runtime, o.string("revision"), o.string("text"),
                NativeSetting(o.flag("modeKnown"), (o["mode"] as? JsonPrimitive)?.contentOrNull),
                o.flag("modePending"), (o["modeRevision"] as? JsonPrimitive)?.longOrNull ?: 0L)
        }
    }
}
