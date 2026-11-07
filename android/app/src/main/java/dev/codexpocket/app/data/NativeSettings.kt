package dev.codexpocket.app.data

import kotlinx.serialization.json.*

/** Unknown (absent) and a known native default (JSON null) are different states. */
data class NativeSetting(val known: Boolean = false, val value: String? = null)

enum class NativeSettingsField { Model, Effort, Mode, ServiceTier }

/** Only safe selection fields; never retains raw config or developer instructions. */
data class NativeSettings(
    val model: NativeSetting = NativeSetting(),
    val effort: NativeSetting = NativeSetting(),
    val mode: NativeSetting = NativeSetting(),
    val serviceTier: NativeSetting = NativeSetting(),
) {
    operator fun get(field: NativeSettingsField): NativeSetting = when (field) {
        NativeSettingsField.Model -> model
        NativeSettingsField.Effort -> effort
        NativeSettingsField.Mode -> mode
        NativeSettingsField.ServiceTier -> serviceTier
    }
    val fields get() = NativeSettingsField.entries.filterTo(mutableSetOf()) { get(it).known }
    val anyKnown get() = fields.isNotEmpty()
    fun only(fields: Set<NativeSettingsField>) = NativeSettings(
        model.takeIf { NativeSettingsField.Model in fields } ?: NativeSetting(),
        effort.takeIf { NativeSettingsField.Effort in fields } ?: NativeSetting(),
        mode.takeIf { NativeSettingsField.Mode in fields } ?: NativeSetting(),
        serviceTier.takeIf { NativeSettingsField.ServiceTier in fields } ?: NativeSetting(),
    )
    fun merge(patch: NativeSettings) = NativeSettings(
        patch.model.takeIf { it.known } ?: model,
        patch.effort.takeIf { it.known } ?: effort,
        patch.mode.takeIf { it.known } ?: mode,
        patch.serviceTier.takeIf { it.known } ?: serviceTier,
    )

    /** Selection fields for turn/start. Catalog defaults are not current native settings. */
    fun turnSelection(model: String?, effort: String?, mode: String?, pending: Set<NativeSettingsField>) = buildJsonObject {
        model?.let { put("model", it) }
        if (effort != null || this@NativeSettings.effort.known) put("effort", effort?.let(::JsonPrimitive) ?: JsonNull)
        // A phone choice to leave Plan is explicit Default; an absent native mode stays absent.
        val selectedMode = mode ?: "default".takeIf { NativeSettingsField.Mode in pending }
        if (selectedMode != null) {
            require(selectedMode in setOf("plan", "default")) { "Unknown native collaboration mode" }
            require(!model.isNullOrBlank()) { "Select a model for this mode" }
            put("collaborationMode", buildJsonObject {
                put("mode", selectedMode)
                put("settings", buildJsonObject {
                    put("model", model)
                    if (effort != null || this@NativeSettings.effort.known) put("reasoning_effort", effort?.let(::JsonPrimitive) ?: JsonNull)
                    put("developer_instructions", JsonNull)
                })
            })
        }
    }

    /** Flat safe cache, with nulls retained and absent fields omitted. */
    private fun safeJson() = buildJsonObject {
        fun field(name: String, value: NativeSetting) {
            if (value.known) put(name, value.value?.let(::JsonPrimitive) ?: JsonNull)
        }
        field("model", model); field("effort", effort); field("mode", mode); field("serviceTier", serviceTier)
    }

    companion object {
        private fun value(o: JsonObject, name: String): NativeSetting = when (val v = o[name]) {
            JsonNull -> NativeSetting(true, null)
            is JsonPrimitive -> if (v.isString && v.content.isNotBlank()) NativeSetting(true, v.content) else NativeSetting()
            else -> NativeSetting()
        }

        /** Native ThreadSettings uses effort; collaborationMode.settings uses reasoning_effort. */
        fun parse(o: JsonObject): NativeSettings {
            val collaboration = o["collaborationMode"] as? JsonObject
            val settings = collaboration?.get("settings") as? JsonObject
            val mode = if (o["collaborationMode"] == JsonNull) NativeSetting(true, null)
                else collaboration?.let { value(it, "mode").takeIf { v -> v.value in setOf("plan", "default") } } ?: NativeSetting()
            val nestedModel = settings?.let { value(it, "model") } ?: NativeSetting()
            val nestedEffort = settings?.let { value(it, "reasoning_effort") } ?: NativeSetting()
            return NativeSettings(
                nestedModel.takeIf { it.known } ?: value(o, "model"),
                nestedEffort.takeIf { it.known } ?: value(o, "effort"),
                mode, value(o, "serviceTier"),
            )
        }

        fun notification(params: JsonObject) = parse(params["threadSettings"] as? JsonObject ?: buildJsonObject {})

        /** Top-level start/resume response fields. A Thread or its historical turns is not settings. */
        fun snapshot(response: JsonObject): NativeSettings = parse(JsonObject(response +
            if (response.containsKey("reasoningEffort")) mapOf("effort" to response.getValue("reasoningEffort")) else emptyMap()))

        fun readResponse(response: JsonObject, runtimeId: String, threadId: String): NativeSettings? {
            if (response.string("runtimeId") != runtimeId || response.string("threadId") != threadId || !response.flag("known")) return null
            return (response["settings"] as? JsonObject)?.let(::parse)
        }

        internal fun restored(o: JsonObject) = NativeSettings(value(o, "model"), value(o, "effort"),
            value(o, "mode").let { if (it.value == null || it.value in setOf("plan", "default")) it else NativeSetting() }, value(o, "serviceTier"))
    }

    internal fun persisted() = safeJson()
}

/** Per-field revisions fence late reads, even if a later read has identical values. */
data class NativeSettingsRecord(
    val settings: NativeSettings = NativeSettings(),
    val pending: Set<NativeSettingsField> = emptySet(),
    val revisions: Map<NativeSettingsField, Long> = emptyMap(),
) {
    private fun apply(patch: NativeSettings, pending: Set<NativeSettingsField>): NativeSettingsRecord = copy(
        settings = settings.merge(patch), pending = pending,
        revisions = revisions + patch.fields.associateWith { (revisions[it] ?: 0L) + 1 },
    )
    fun edit(patch: NativeSettings) = apply(patch, pending + patch.fields)
    fun event(patch: NativeSettings): NativeSettingsRecord {
        // Helper snapshot events share the native method. Keep unsent local choices until a
        // native event acknowledges that value; unrelated desktop fields still update at once.
        val accepted = patch.fields.filterTo(mutableSetOf()) { field ->
            field !in pending || settings[field] == patch[field] ||
                (field == NativeSettingsField.Mode && settings.mode.value == null && patch.mode.value == "default")
        }
        return apply(patch.only(accepted), pending - accepted)
    }
    fun snapshot(patch: NativeSettings, ticket: Map<NativeSettingsField, Long>): NativeSettingsRecord = apply(
        patch.only(patch.fields.filterTo(mutableSetOf()) { it !in pending && (revisions[it] ?: 0L) == (ticket[it] ?: 0L) }), pending,
    )
    /** A successful explicit submission may supersede its captured pending value, never a newer edit. */
    fun submitted(patch: NativeSettings, ticket: Map<NativeSettingsField, Long>): NativeSettingsRecord {
        val accepted = patch.fields.filterTo(mutableSetOf()) { (revisions[it] ?: 0L) == (ticket[it] ?: 0L) }
        return apply(patch.only(accepted), pending - accepted)
    }
    fun persisted(runtimeId: String) = buildJsonObject {
        put("runtimeId", runtimeId); put("settings", settings.persisted())
        put("pending", JsonArray(pending.map { JsonPrimitive(it.name) }))
        put("revisions", buildJsonObject { revisions.forEach { (field, revision) -> put(field.name, revision) } })
    }
    companion object {
        fun restore(o: JsonObject, runtimeId: String): NativeSettingsRecord? {
            if (o.string("runtimeId") != runtimeId) return null
            val settings = NativeSettings.restored(o.obj("settings"))
            val pending = o.array("pending").mapNotNull { value -> NativeSettingsField.entries.firstOrNull { it.name == (value as? JsonPrimitive)?.contentOrNull } }.toSet()
            val revisions = NativeSettingsField.entries.mapNotNull { field ->
                (o.obj("revisions")[field.name] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 0 }?.let { field to it }
            }.toMap()
            return NativeSettingsRecord(settings, pending.intersect(settings.fields), revisions)
        }
    }
}
